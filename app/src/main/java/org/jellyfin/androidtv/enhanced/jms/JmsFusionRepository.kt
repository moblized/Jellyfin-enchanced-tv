package org.jellyfin.androidtv.enhanced.jms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jellyfin.androidtv.enhanced.JellyfinEnhancedApi
import org.jellyfin.androidtv.enhanced.JellyfinEnhancedException
import org.jellyfin.androidtv.enhanced.array
import org.jellyfin.androidtv.enhanced.string
import timber.log.Timber

/**
 * What the connected server's JMS Fusion plugin (Jellyfin MonWUI) offers. JMS Fusion keeps its settings in the
 * browser; an admin can publish them to the server, which is the copy this app reads. When nothing is published
 * the JMS Fusion defaults are used.
 */
data class JmsFusionStatus(
	val installed: Boolean,
	/** Published settings snapshot ("desktop" profile), as JMS Fusion stores them: string values. */
	val settings: Map<String, String> = emptyMap(),
) {
	companion object {
		val NOT_INSTALLED = JmsFusionStatus(installed = false)
	}
}

/** Home slider settings, with JMS Fusion's own defaults. */
data class JmsSliderSettings(
	val limit: Int = 15,
	val durationMs: Long = 15_000,
	val customQuery: String = DEFAULT_QUERY,
	val useManualList: Boolean = false,
	val manualListIds: List<String> = emptyList(),
	val onlyUnwatched: Boolean = false,
	val balanceItemTypes: Boolean = true,
	val playingLimit: Int = 0,
	val excludeEpisodesFromPlaying: Boolean = true,
) {
	companion object {
		const val DEFAULT_QUERY =
			"IncludeItemTypes=Movie,Series&Recursive=true&hasOverview=true&imageTypes=Logo,Backdrop&sortBy=DateCreated&sortOrder=Descending"
	}
}

/** Pause screen settings, with JMS Fusion's own defaults. */
data class JmsPauseSettings(
	val showPlot: Boolean = true,
	val showMetadata: Boolean = true,
	val showLogo: Boolean = true,
	val showBackdrop: Boolean = true,
	val minVideoMinutes: Int = 5,
)

data class JmsStudioManualEntry(
	val studioId: String,
	val name: String,
	val logoFileName: String?,
)

data class JmsStudioVisibility(
	val hiddenNames: List<String> = emptyList(),
	val orderNames: List<String> = emptyList(),
)

class JmsFusionRepository(
	private val jeApi: JellyfinEnhancedApi,
) {
	companion object {
		private const val STATUS_TTL_MS = 10 * 60 * 1000L
		private const val MISSING_TTL_MS = 2 * 60 * 1000L
		private const val PROFILE = "desktop"
	}

	private val mutex = Mutex()
	private var cacheKey: String? = null
	private var status: JmsFusionStatus? = null
	private var expiry = 0L

	val currentStatus: JmsFusionStatus?
		get() = status?.takeIf { cacheKey == jeApi.sessionKey }

	suspend fun getStatus(forceRefresh: Boolean = false): JmsFusionStatus = mutex.withLock {
		val key = jeApi.sessionKey
		val now = System.currentTimeMillis()
		val cached = status
		if (!forceRefresh && cached != null && cacheKey == key && now < expiry) return@withLock cached

		val loaded = load()
		status = loaded
		cacheKey = key
		expiry = now + if (loaded.installed) STATUS_TTL_MS else MISSING_TTL_MS
		loaded
	}

	private suspend fun load(): JmsFusionStatus {
		if (!jeApi.hasSession) return JmsFusionStatus.NOT_INSTALLED

		try {
			jeApi.serverText("GET", listOf("JMSFusion", "ping"))
		} catch (err: CancellationException) {
			throw err
		} catch (err: JellyfinEnhancedException) {
			Timber.i("JMS Fusion not detected (status ${err.status})")
			return JmsFusionStatus.NOT_INSTALLED
		}

		val settings = try {
			val response = jeApi.serverJson(
				"GET", listOf("Plugins", "JMSFusion", "UserSettings"),
				query = mapOf("profile" to PROFILE),
			) as? JsonObject
			(response?.get("global") as? JsonObject)?.mapValues { (_, value) -> value.asSettingString() }.orEmpty()
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to read JMS Fusion settings")
			emptyMap()
		}

		return JmsFusionStatus(installed = true, settings = settings)
	}

	private fun JsonElement.asSettingString(): String = when (this) {
		is JsonPrimitive -> contentOrNull.orEmpty()
		is JsonNull -> ""
		else -> toString()
	}

	suspend fun sliderSettings(): JmsSliderSettings {
		val settings = getStatus().settings
		val defaults = JmsSliderSettings()
		return JmsSliderSettings(
			limit = settings.int("limit")?.coerceIn(1, 50) ?: defaults.limit,
			durationMs = settings.int("sliderDuration")?.toLong()?.coerceIn(4_000, 120_000) ?: defaults.durationMs,
			customQuery = settings["customQueryString"]?.takeIf { it.isNotBlank() } ?: defaults.customQuery,
			useManualList = settings.bool("useManualList") ?: defaults.useManualList,
			manualListIds = settings["manualListIds"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
			onlyUnwatched = settings.bool("onlyUnwatchedRandom") ?: defaults.onlyUnwatched,
			balanceItemTypes = settings.bool("balanceItemTypes") ?: defaults.balanceItemTypes,
			playingLimit = settings.int("playingLimit")?.coerceIn(0, 20) ?: defaults.playingLimit,
			excludeEpisodesFromPlaying = settings.bool("excludeEpisodesFromPlaying") ?: defaults.excludeEpisodesFromPlaying,
		)
	}

	suspend fun pauseSettings(): JmsPauseSettings {
		val settings = getStatus().settings
		val defaults = JmsPauseSettings()

		// Newer JMS Fusion versions store one JSON object, older ones separate keys
		val json = settings["pauseOverlay"]?.takeIf { it.trim().startsWith("{") }?.let {
			runCatching { JellyfinEnhancedApi.json.parseToJsonElement(it) as? JsonObject }.getOrNull()
		}
		fun flag(jsonKey: String, legacyKey: String, default: Boolean): Boolean =
			json?.get(jsonKey)?.let { (it as? JsonPrimitive)?.contentOrNull != "false" }
				?: settings.bool(legacyKey)
				?: default

		val minutes = (json?.get("minVideoMinutes") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
			?: settings.int("pauseOverlayMinVideoMinutes")

		return JmsPauseSettings(
			showPlot = flag("showPlot", "pauseOverlayShowPlot", defaults.showPlot),
			showMetadata = flag("showMetadata", "pauseOverlayShowMetadata", defaults.showMetadata),
			showLogo = flag("showLogo", "pauseOverlayShowLogo", defaults.showLogo),
			showBackdrop = flag("showBackdrop", "pauseOverlayShowBackdrop", defaults.showBackdrop),
			minVideoMinutes = minutes?.coerceIn(0, 240) ?: defaults.minVideoMinutes,
		)
	}

	// Studio hubs

	suspend fun studioManualEntries(): List<JmsStudioManualEntry> {
		if (!getStatus().installed) return emptyList()
		return try {
			val response = jeApi.serverJson("GET", listOf("JMSFusion", "studio-hubs", "collection")) as? JsonObject
			response?.array("entries").orEmpty().mapNotNull { element ->
				val entry = element as? JsonObject ?: return@mapNotNull null
				val name = entry.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				JmsStudioManualEntry(
					studioId = entry.string("studioId").orEmpty(),
					name = name,
					logoFileName = entry.string("logoFileName")?.takeIf { it.isNotBlank() },
				)
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to read JMS Fusion studio hubs")
			emptyList()
		}
	}

	suspend fun studioVisibility(): JmsStudioVisibility {
		if (!getStatus().installed) return JmsStudioVisibility()
		return try {
			val response = jeApi.serverJson(
				"GET", listOf("JMSFusion", "studio-hubs", "visibility"),
				query = mapOf("profile" to PROFILE),
			) as? JsonObject
			JmsStudioVisibility(
				hiddenNames = response?.stringList("hiddenNames").orEmpty(),
				orderNames = response?.stringList("orderNames").orEmpty(),
			)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to read JMS Fusion studio visibility")
			JmsStudioVisibility()
		}
	}

	/** Logo of a manually added studio hub, uploaded by the admin. */
	fun manualLogoUrl(fileName: String): String? = jeApi.serverUrl("JMSFusion", "studio-hubs", "logo", fileName)

	/** Logo bundled with JMS Fusion for its default studios, served by the plugin. */
	fun bundledLogoUrl(slug: String): String? = jeApi.serverUrl("slider", "src", "images", "studios", "$slug.webp")

	private fun JsonObject.stringList(key: String): List<String> =
		(this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
}

private fun Map<String, String>.int(key: String): Int? = this[key]?.trim()?.toDoubleOrNull()?.toInt()

private fun Map<String, String>.bool(key: String): Boolean? = when (this[key]?.trim()?.lowercase()) {
	"true" -> true
	"false" -> false
	else -> null
}
