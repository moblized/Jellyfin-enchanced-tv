package org.jellyfin.androidtv.enhanced

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What the connected server's Jellyfin Enhanced plugin offers to this user.
 */
data class JellyfinEnhancedStatus(
	val available: Boolean,
	val version: String? = null,
	val config: JsonObject = JsonObject(emptyMap()),
	val seerrLinked: Boolean = false,
	val seerrReason: String? = null,
) {
	val seerrEnabled get() = available && config.bool("JellyseerrEnabled") == true
	val seerrSearchEnabled get() = seerrEnabled && seerrLinked && config.bool("JellyseerrShowSearchResults") != false
	val seerrExcludeLibraryItems get() = config.bool("JellyseerrSearchExcludeLibraryItems") == true
	val seerr4kMovies get() = config.bool("JellyseerrEnable4KRequests") == true
	val seerr4kTv get() = config.bool("JellyseerrEnable4KTvRequests") == true
	val bookmarksEnabled get() = available && config.bool("BookmarksEnabled") != false
	val tmdbReviewsEnabled get() = available && config.bool("ShowReviews") == true && config.bool("TmdbEnabled") != false
	val userReviewsEnabled get() = available && config.bool("ShowUserReviews") == true
	val reviewsEnabled get() = tmdbReviewsEnabled || userReviewsEnabled
	val nativePosterTagsEnabled get() = available && config.bool("NativePosterTagsEnabled") == true

	companion object {
		val UNAVAILABLE = JellyfinEnhancedStatus(available = false)
	}
}

/** Seerr media status values. */
object SeerrMediaStatus {
	const val UNKNOWN = 1
	const val PENDING = 2
	const val PROCESSING = 3
	const val PARTIALLY_AVAILABLE = 4
	const val AVAILABLE = 5
	const val BLOCKLISTED = 6
	const val DELETED = 7
}

data class SeerrItem(
	val tmdbId: Int,
	val mediaType: String,
	val title: String,
	val year: String?,
	val overview: String?,
	val posterPath: String?,
	val backdropPath: String?,
	val status: Int?,
	val status4k: Int?,
) {
	val isMovie get() = mediaType == "movie"
	val posterUrl get() = posterPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w342$it" }
	val backdropUrl get() = backdropPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w1280$it" }
}

data class SeerrSeason(
	val seasonNumber: Int,
	val name: String,
	val episodeCount: Int?,
	val status: Int?,
)

data class Bookmark(
	val id: String,
	val itemId: String,
	val timestampSeconds: Double,
	val label: String,
	val name: String,
	val tmdbId: String,
	val mediaType: String,
	val seasonNumber: Int?,
	val episodeNumber: Int?,
	val createdAt: String,
) {
	val positionMs get() = (timestampSeconds * 1000).toLong()
}

data class Review(
	val author: String,
	val rating: Double?,
	val ratingScale: Int,
	val content: String,
	val date: String?,
	val fromTmdb: Boolean,
	val userId: String? = null,
)

/**
 * Caches what the plugin supports and exposes the features this app uses.
 * Everything here is suspend + IO bound so the UI thread never waits on the network.
 */
class JellyfinEnhancedRepository(
	private val jeApi: JellyfinEnhancedApi,
	private val userRepository: UserRepository,
) {
	companion object {
		private const val STATUS_TTL_MS = 10 * 60 * 1000L
		private const val UNAVAILABLE_TTL_MS = 60 * 1000L
		private const val BOOKMARK_TTL_MS = 30 * 1000L
		private val versionRegex = Regex("""^\d+(\.\d+)+$""")
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val statusMutex = Mutex()
	private var statusKey: String? = null
	private var status: JellyfinEnhancedStatus? = null
	private var statusExpiry = 0L

	private var bookmarkCache: Pair<Long, List<Bookmark>>? = null
	private var bookmarkCacheKey: String? = null
	private val seasonCache = ConcurrentHashMap<Int, List<SeerrSeason>>()

	/** Last known status for the current session, without waiting. Null when it was never loaded. */
	val currentStatus: JellyfinEnhancedStatus?
		get() = status?.takeIf { statusKey == jeApi.sessionKey }

	/** Warm the status cache in the background. */
	fun prefetch() {
		scope.launch {
			runCatching { getStatus() }.onFailure { Timber.w(it, "Unable to load Jellyfin Enhanced status") }
		}
	}

	suspend fun getStatus(forceRefresh: Boolean = false): JellyfinEnhancedStatus = statusMutex.withLock {
		val key = jeApi.sessionKey
		val now = System.currentTimeMillis()
		val cached = status
		if (!forceRefresh && cached != null && statusKey == key && now < statusExpiry) return@withLock cached

		val loaded = loadStatus()
		status = loaded
		statusKey = key
		statusExpiry = now + if (loaded.available) STATUS_TTL_MS else UNAVAILABLE_TTL_MS
		if (key != bookmarkCacheKey) {
			bookmarkCache = null
			seasonCache.clear()
		}
		loaded
	}

	private suspend fun loadStatus(): JellyfinEnhancedStatus {
		if (!jeApi.hasSession) return JellyfinEnhancedStatus.UNAVAILABLE

		val version = try {
			jeApi.requestText("GET", listOf("version")).trim().trim('"')
		} catch (err: JellyfinEnhancedException) {
			Timber.i("Jellyfin Enhanced not detected (status ${err.status})")
			return JellyfinEnhancedStatus.UNAVAILABLE
		}
		if (version != "unknown" && !versionRegex.matches(version)) return JellyfinEnhancedStatus.UNAVAILABLE

		val loadedConfig: JsonObject? = try {
			jeApi.get("public-config") as? JsonObject
		} catch (err: JellyfinEnhancedException) {
			Timber.w(err, "Unable to read Jellyfin Enhanced config")
			null
		}
		val config = loadedConfig ?: JsonObject(emptyMap())

		var linked = false
		var reason: String? = null
		if (config.bool("JellyseerrEnabled") == true) {
			try {
				val userStatus = jeApi.get("jellyseerr", "user-status") as? JsonObject
				reason = userStatus?.string("reason")
				linked = reason == "linked" || (reason == null && userStatus?.bool("userFound") == true)
			} catch (err: JellyfinEnhancedException) {
				Timber.w(err, "Unable to read Seerr user status")
				reason = "unreachable"
			}
		}

		return JellyfinEnhancedStatus(
			available = true,
			version = version,
			config = config,
			seerrLinked = linked,
			seerrReason = reason,
		)
	}

	private fun requireUserId(): String = userRepository.currentUser.value?.id?.toN()
		?: throw JellyfinEnhancedException(0, "Not signed in")

	// Seerr

	suspend fun seerrSearch(query: String): List<SeerrItem> {
		val currentStatus = getStatus()
		if (!currentStatus.seerrSearchEnabled || query.isBlank()) return emptyList()

		val response = jeApi.get(
			"jellyseerr", "search",
			query = mapOf("query" to query.take(256), "page" to 1),
		) as? JsonObject ?: return emptyList()

		return response.array("results")
			.mapNotNull { (it as? JsonObject)?.toSeerrItem() }
			.filter { !currentStatus.seerrExcludeLibraryItems || it.status != SeerrMediaStatus.AVAILABLE }
	}

	private fun JsonObject.toSeerrItem(): SeerrItem? {
		val type = string("mediaType") ?: return null
		if (type != "movie" && type != "tv") return null
		val id = int("id") ?: return null
		val title = string("title") ?: string("name") ?: return null
		val date = string("releaseDate") ?: string("firstAirDate")
		val mediaInfo = obj("mediaInfo")

		return SeerrItem(
			tmdbId = id,
			mediaType = type,
			title = title,
			year = date?.take(4)?.takeIf { it.length == 4 },
			overview = string("overview"),
			posterPath = string("posterPath"),
			backdropPath = string("backdropPath"),
			status = mediaInfo?.int("status"),
			status4k = mediaInfo?.int("status4k"),
		)
	}

	suspend fun seerrSeasons(tmdbId: Int): List<SeerrSeason> {
		seasonCache[tmdbId]?.let { return it }

		val details = jeApi.get("jellyseerr", "tv", tmdbId.toString()) as? JsonObject ?: return emptyList()
		val statuses = details.obj("mediaInfo")?.array("seasons").orEmpty()
			.mapNotNull { it as? JsonObject }
			.mapNotNull { season -> season.int("seasonNumber")?.let { it to season.int("status") } }
			.toMap()

		val seasons = details.array("seasons")
			.mapNotNull { it as? JsonObject }
			.mapNotNull { season ->
				val number = season.int("seasonNumber") ?: return@mapNotNull null
				SeerrSeason(
					seasonNumber = number,
					name = season.string("name") ?: "Season $number",
					episodeCount = season.int("episodeCount"),
					status = statuses[number],
				)
			}
			.sortedBy { it.seasonNumber }

		seasonCache[tmdbId] = seasons
		return seasons
	}

	suspend fun seerrIncludeSpecials(): Boolean = try {
		(jeApi.get("jellyseerr", "settings", "partial-requests") as? JsonObject)?.bool("enableSpecialEpisodes") == true
	} catch (err: JellyfinEnhancedException) {
		false
	}

	suspend fun seerrRequest(item: SeerrItem, seasons: List<Int>?, is4k: Boolean) {
		val body = buildJsonObject {
			put("mediaType", item.mediaType)
			put("mediaId", item.tmdbId)
			put("is4k", is4k)
			if (!item.isMovie && seasons != null) putJsonArray("seasons") { seasons.forEach { add(it) } }
		}
		jeApi.post("jellyseerr", "request", body = body)
		seasonCache.remove(item.tmdbId)
	}

	// Bookmarks

	suspend fun bookmarks(forceRefresh: Boolean = false): List<Bookmark> {
		val key = jeApi.sessionKey
		val now = System.currentTimeMillis()
		val cached = bookmarkCache
		if (!forceRefresh && cached != null && bookmarkCacheKey == key && now - cached.first < BOOKMARK_TTL_MS) {
			return cached.second
		}

		val document = jeApi.get("user-settings", requireUserId(), "bookmark.json") as? JsonObject
		val bookmarks = document?.obj("Bookmarks")?.entries.orEmpty().mapNotNull { (id, value) ->
			val entry = value as? JsonObject ?: return@mapNotNull null
			Bookmark(
				id = id,
				itemId = entry.string("ItemId").orEmpty(),
				timestampSeconds = entry.double("Timestamp") ?: 0.0,
				label = entry.string("Label").orEmpty(),
				name = entry.string("Name").orEmpty(),
				tmdbId = entry.string("TmdbId").orEmpty(),
				mediaType = entry.string("MediaType").orEmpty(),
				seasonNumber = entry.int("SeasonNumber"),
				episodeNumber = entry.int("EpisodeNumber"),
				createdAt = entry.string("CreatedAt").orEmpty(),
			)
		}

		bookmarkCache = now to bookmarks
		bookmarkCacheKey = key
		return bookmarks
	}

	suspend fun bookmarksFor(item: BaseItemDto, forceRefresh: Boolean = false): List<Bookmark> {
		val itemId = item.id.toN()
		val tmdbId = item.providerId("Tmdb")
		return bookmarks(forceRefresh)
			.filter { bookmark ->
				bookmark.itemId.normalizeId() == itemId ||
					(item.type == BaseItemKind.MOVIE && tmdbId != null && bookmark.mediaType == "movie" && bookmark.tmdbId == tmdbId)
			}
			.sortedBy { it.timestampSeconds }
	}

	suspend fun addBookmark(item: BaseItemDto, positionMs: Long, label: String): String? {
		val isEpisode = item.type == BaseItemKind.EPISODE
		val name = if (isEpisode) {
			buildString {
				append(item.seriesName ?: item.name.orEmpty())
				if (item.parentIndexNumber != null && item.indexNumber != null) {
					append(" S${item.parentIndexNumber}E${item.indexNumber}")
				}
			}
		} else item.name.orEmpty()

		val body = buildJsonObject {
			put("ItemId", item.id.toN())
			put("TmdbId", if (isEpisode) "" else item.providerId("Tmdb").orEmpty())
			put("TvdbId", item.providerId("Tvdb").orEmpty())
			put("MediaType", if (isEpisode || item.type == BaseItemKind.SERIES) "tv" else "movie")
			put("Name", name)
			put("Timestamp", positionMs / 1000.0)
			put("Label", label)
			if (isEpisode) {
				put("SeasonNumber", item.parentIndexNumber)
				put("EpisodeNumber", item.indexNumber)
			}
		}

		val response = jeApi.post("user-settings", requireUserId(), "bookmark.json", "add", body = body) as? JsonObject
		bookmarkCache = null
		return response?.string("id")
	}

	suspend fun deleteBookmark(bookmark: Bookmark) {
		jeApi.delete("user-settings", requireUserId(), "bookmark.json", bookmark.id)
		bookmarkCache = null
	}

	// Reviews

	suspend fun userReviews(mediaType: String, tmdbKey: String): List<Review> {
		val response = jeApi.get("reviews", mediaType, tmdbKey) as? JsonObject ?: return emptyList()
		return response.array("reviews").mapNotNull { element ->
			val review = element as? JsonObject ?: return@mapNotNull null
			Review(
				author = review.string("userName") ?: review.string("userId") ?: "?",
				rating = review.double("rating")?.takeIf { it > 0 },
				ratingScale = 5,
				content = review.string("content").orEmpty(),
				date = review.string("updatedAt") ?: review.string("createdAt"),
				fromTmdb = false,
				userId = review.string("userId")?.normalizeId(),
			)
		}
	}

	suspend fun tmdbReviews(mediaType: String, tmdbId: String): List<Review> {
		val response = jeApi.get("tmdb", mediaType, tmdbId, "reviews") as? JsonObject ?: return emptyList()
		return response.array("results").mapNotNull { element ->
			val review = element as? JsonObject ?: return@mapNotNull null
			Review(
				author = review.string("author") ?: "TMDB user",
				rating = review.obj("author_details")?.double("rating")?.takeIf { it > 0 },
				ratingScale = 10,
				content = review.string("content").orEmpty(),
				date = review.string("created_at"),
				fromTmdb = true,
			)
		}
	}

	suspend fun myReview(mediaType: String, tmdbKey: String): Review? {
		val me = userRepository.currentUser.value?.id?.toN() ?: return null
		return userReviews(mediaType, tmdbKey).firstOrNull { it.userId == me }
	}

	suspend fun saveReview(mediaType: String, tmdbKey: String, rating: Double?, content: String) {
		val body = buildJsonObject {
			put("content", content)
			if (rating != null) put("rating", rating)
		}
		jeApi.post("reviews", mediaType, tmdbKey, body = body)
	}

	suspend fun deleteReview(mediaType: String, tmdbKey: String) {
		jeApi.delete("reviews", mediaType, tmdbKey)
	}

	// Personal Enhanced settings (used for native poster tag preferences)

	suspend fun userSettings(): JsonObject =
		jeApi.get("user-settings", requireUserId(), "settings.json") as? JsonObject ?: JsonObject(emptyMap())

	suspend fun saveUserSettings(settings: JsonObject) {
		val body = JsonObject(settings.filterKeys { !it.equals("IsAdmin", ignoreCase = true) })
		jeApi.post("user-settings", requireUserId(), "settings.json", body = body)
	}
}

fun UUID.toN(): String = toString().replace("-", "").lowercase()

fun String.normalizeId(): String = replace("-", "").lowercase()

fun BaseItemDto.providerId(name: String): String? =
	providerIds?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

/**
 * The key Jellyfin Enhanced uses for reviews: media type ("movie" / "tv") and TMDB id. Only movies and series have
 * their own TMDB id on the item, so other types return null.
 */
val BaseItemDto.reviewKey: Pair<String, String>?
	get() {
		val tmdbId = providerId("Tmdb") ?: return null
		return when (type) {
			BaseItemKind.MOVIE -> "movie" to tmdbId
			BaseItemKind.SERIES -> "tv" to tmdbId
			else -> null
		}
	}

fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
