package org.jellyfin.androidtv.ui.home.jms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.jms.JmsFusionRepository
import org.jellyfin.androidtv.enhanced.jms.JmsSliderSettings
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.net.URLDecoder

data class HomeSliderContent(
	val items: List<BaseItemDto>,
	val durationMs: Long,
)

/**
 * Picks the items for the home slider the way JMS Fusion does: its (custom) item query, item limit, manual list,
 * "only unwatched", "balance movies and series" and "continue watching first" settings are all honoured.
 */
class HomeSliderRepository(
	private val api: ApiClient,
	private val userRepository: UserRepository,
	private val jmsFusionRepository: JmsFusionRepository,
) {
	companion object {
		private const val CACHE_TTL_MS = 10 * 60 * 1000L
		private const val MAX_POOL = 100
		private val sliderFields = listOf(
			ItemFields.OVERVIEW,
			ItemFields.GENRES,
			ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
			ItemFields.TAGLINES,
			ItemFields.CHILD_COUNT,
		)
	}

	private val mutex = Mutex()
	private var cached: Pair<String, HomeSliderContent>? = null
	private var cachedAt = 0L

	/** Last loaded content for the current session, returned without any network call. */
	fun cachedContent(): HomeSliderContent? {
		val (key, content) = cached ?: return null
		return content.takeIf { key == sessionKey() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS }
	}

	private fun sessionKey() = "${api.baseUrl}|${userRepository.currentUser.value?.id}"

	suspend fun load(forceRefresh: Boolean = false): HomeSliderContent? = mutex.withLock {
		if (!forceRefresh) cachedContent()?.let { return@withLock it }

		val userId = userRepository.currentUser.value?.id ?: return@withLock null
		val settings = jmsFusionRepository.sliderSettings()

		val items = try {
			withContext(Dispatchers.IO) {
				if (settings.useManualList && settings.manualListIds.isNotEmpty()) loadManualList(settings)
				else loadFromQuery(userId.toString(), settings)
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to load home slider items")
			return@withLock cachedContent()
		}

		val content = HomeSliderContent(items.filter { it.hasBackdrop }, settings.durationMs)
		cached = sessionKey() to content
		cachedAt = System.currentTimeMillis()
		content
	}

	private suspend fun loadManualList(settings: JmsSliderSettings): List<BaseItemDto> {
		val ids = settings.manualListIds.mapNotNull { it.toUUIDOrNull() }
		if (ids.isEmpty()) return emptyList()

		val result = api.itemsApi.getItems(
			userId = userRepository.currentUser.value?.id,
			ids = ids,
			fields = sliderFields,
			enableUserData = true,
			imageTypeLimit = 1,
		).content.items
		// Keep the order of the manual list
		val byId = result.associateBy { it.id }
		return ids.mapNotNull { byId[it] }
	}

	private suspend fun loadFromQuery(userId: String, settings: JmsSliderSettings): List<BaseItemDto> {
		val query = parseQuery(settings.customQuery)

		if (settings.onlyUnwatched && !query.containsKey("isplayed")) query["isplayed"] = "IsPlayed" to "false"
		val hasExplicitSort = query.containsKey("sortby") || query.containsKey("sortorder")
		// Without a sort JMS Fusion shuffles the pool, let the server do that
		if (!hasExplicitSort) query["sortby"] = "SortBy" to "Random"

		val requestedFields = query.remove("fields")?.second?.split(',')?.map { it.trim() }.orEmpty()
		val fields = (requestedFields + sliderFields.map { it.serialName }).filter { it.isNotEmpty() }.distinct()

		query.remove("userid")
		query.remove("limit")
		query.remove("startindex")

		val parameters = buildMap<String, Any?> {
			query.values.forEach { (key, value) -> put(key, value) }
			put("userId", userId)
			put("Fields", fields.joinToString(","))
			put("EnableUserData", true)
			put("ImageTypeLimit", 1)
			put("EnableTotalRecordCount", false)
			put("Limit", (settings.limit * 3).coerceIn(30, MAX_POOL))
		}

		val pool = api.request(
			method = HttpMethod.GET,
			pathTemplate = "/Items",
			queryParameters = parameters,
		).createContent<BaseItemDtoQueryResult>().items.filter { it.hasBackdrop }

		val playing = if (settings.playingLimit > 0 && !settings.onlyUnwatched) loadPlaying(settings) else emptyList()
		val playingIds = playing.map { it.id }.toSet()
		val remainingPool = pool.filter { it.id !in playingIds }
		val slots = (settings.limit - playing.size).coerceAtLeast(0)

		val includeTypes = query["includeitemtypes"]?.second.orEmpty().split(',').map { it.trim().lowercase() }.toSet()
		val shouldBalance = settings.balanceItemTypes && "movie" in includeTypes && "series" in includeTypes

		return playing + if (shouldBalance) balance(remainingPool, slots) else remainingPool.take(slots)
	}

	private suspend fun loadPlaying(settings: JmsSliderSettings): List<BaseItemDto> = try {
		api.itemsApi.getResumeItems(
			userId = userRepository.currentUser.value?.id,
			limit = settings.playingLimit * 3,
			mediaTypes = listOf(MediaType.VIDEO),
			fields = sliderFields,
			enableUserData = true,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
		).content.items
			.filter { !settings.excludeEpisodesFromPlaying || it.type != BaseItemKind.EPISODE }
			.filter { it.hasBackdrop }
			.take(settings.playingLimit)
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Unable to load resumable items for the slider")
		emptyList()
	}

	/** Equal share per item type, alternating types so the slider doesn't show all movies first. */
	private fun balance(pool: List<BaseItemDto>, slots: Int): List<BaseItemDto> {
		if (slots <= 0) return emptyList()

		val byType = pool.groupBy { it.type }
		if (byType.size <= 1) return pool.take(slots)

		val perType = slots / byType.size
		val queues = byType.values.map { it.take(perType).toMutableList() }
		val picked = mutableListOf<BaseItemDto>()
		while (queues.any { it.isNotEmpty() }) {
			queues.forEach { queue -> queue.removeFirstOrNull()?.let(picked::add) }
		}

		// Fill what is left (uneven split or a type with too few items)
		if (picked.size < slots) {
			val pickedIds = picked.map { it.id }.toSet()
			picked += pool.filter { it.id !in pickedIds }.take(slots - picked.size)
		}
		return picked.take(slots)
	}

	/** Parse a query string into lower-cased key to original (key, value). */
	private fun parseQuery(raw: String): MutableMap<String, Pair<String, String>> {
		val result = linkedMapOf<String, Pair<String, String>>()
		raw.trim().trimStart('?', '&').split('&').forEach { part ->
			if (part.isBlank()) return@forEach
			val index = part.indexOf('=')
			val key = decode(if (index < 0) part else part.substring(0, index)).trim()
			val value = if (index < 0) "" else decode(part.substring(index + 1)).trim()
			if (key.isNotEmpty()) result[key.lowercase()] = key to value
		}
		return result
	}

	private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

val BaseItemDto.hasBackdrop: Boolean
	get() = !backdropImageTags.isNullOrEmpty() || !parentBackdropImageTags.isNullOrEmpty()
