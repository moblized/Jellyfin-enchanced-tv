package org.jellyfin.androidtv.enhanced.seerr

import android.app.AlertDialog
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.JellyfinEnhancedRepository
import org.jellyfin.androidtv.enhanced.SeerrItem
import org.jellyfin.androidtv.enhanced.SeerrMediaStatus
import org.jellyfin.androidtv.enhanced.SeerrSeason
import org.jellyfin.androidtv.enhanced.enhancedScope
import org.jellyfin.androidtv.enhanced.enhancedToast
import org.jellyfin.androidtv.enhanced.isActivityAlive
import org.jellyfin.androidtv.enhanced.toEnhancedMessage

/**
 * Request flow for a Seerr search result, built from plain AlertDialogs so it is fully remote/D-pad friendly.
 */
class SeerrRequestFlow(
	private val context: Context,
	private val repository: JellyfinEnhancedRepository,
	private val onRequested: (SeerrItem) -> Unit = {},
) {
	private val scope = context.enhancedScope()

	private val busyStatuses = setOf<Int?>(
		SeerrMediaStatus.AVAILABLE,
		SeerrMediaStatus.PENDING,
		SeerrMediaStatus.PROCESSING,
		SeerrMediaStatus.BLOCKLISTED,
	)

	fun start(item: SeerrItem) {
		val status = repository.currentStatus
		val allow4k = status != null && if (item.isMovie) status.seerr4kMovies else status.seerr4kTv

		if (item.isMovie) showMovieDialog(item, allow4k)
		else loadSeasons(item, allow4k)
	}

	private fun title(item: SeerrItem) = if (item.year != null) "${item.title} (${item.year})" else item.title

	private fun showMovieDialog(item: SeerrItem, allow4k: Boolean) {
		val requestable = item.status !in busyStatuses
		val can4k = allow4k && item.status4k !in busyStatuses

		val message = buildString {
			item.statusLabel(context)?.let { append(it).append("\n\n") }
			append(item.overview?.take(600).orEmpty())
		}

		AlertDialog.Builder(context).apply {
			setTitle(title(item))
			if (message.isNotBlank()) setMessage(message)
			if (requestable) setPositiveButton(R.string.je_request) { _, _ -> submit(item, null, false) }
			if (can4k) setNeutralButton(R.string.je_request_4k) { _, _ -> submit(item, null, true) }
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	private fun loadSeasons(item: SeerrItem, allow4k: Boolean) {
		scope.launch {
			val seasons = try {
				repository.seerrSeasons(item.tmdbId)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				if (context.isActivityAlive()) context.enhancedToast(err.toEnhancedMessage(context))
				return@launch
			}
			val includeSpecials = repository.seerrIncludeSpecials()
			if (!context.isActivityAlive()) return@launch

			showSeasonDialog(item, seasons.filter { includeSpecials || it.seasonNumber > 0 }, allow4k)
		}
	}

	private fun showSeasonDialog(item: SeerrItem, seasons: List<SeerrSeason>, allow4k: Boolean) {
		val open = seasons.filter { it.status !in busyStatuses }
		if (open.isEmpty() && !allow4k) {
			AlertDialog.Builder(context)
				.setTitle(title(item))
				.setMessage(
					if (item.status == SeerrMediaStatus.AVAILABLE) R.string.je_already_available
					else R.string.je_request_nothing_left
				)
				.setPositiveButton(android.R.string.ok, null)
				.show()
			return
		}

		// When everything is already requested in HD, still offer the full list for 4K
		val choices = open.ifEmpty { seasons }
		val labels = choices.map { season ->
			if (season.episodeCount != null) context.getString(R.string.je_season_episodes, season.name, season.episodeCount)
			else season.name
		}.toTypedArray()
		val checked = BooleanArray(choices.size) { true }

		AlertDialog.Builder(context).apply {
			setTitle(context.getString(R.string.je_request_seasons, title(item)))
			setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
			if (open.isNotEmpty()) setPositiveButton(R.string.je_request) { _, _ ->
				submit(item, choices.filterIndexed { i, _ -> checked[i] }.map { it.seasonNumber }, false)
			}
			if (allow4k) setNeutralButton(R.string.je_request_4k) { _, _ ->
				submit(item, choices.filterIndexed { i, _ -> checked[i] }.map { it.seasonNumber }, true)
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	private fun submit(item: SeerrItem, seasons: List<Int>?, is4k: Boolean) {
		if (!item.isMovie && seasons.isNullOrEmpty()) {
			context.enhancedToast(context.getString(R.string.je_request_pick_season))
			return
		}

		scope.launch {
			try {
				repository.seerrRequest(item, seasons, is4k)
				if (!context.isActivityAlive()) return@launch
				context.enhancedToast(context.getString(R.string.je_request_sent, item.title))
				onRequested(if (is4k) item.copy(status4k = SeerrMediaStatus.PENDING) else item.copy(status = SeerrMediaStatus.PENDING))
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				if (context.isActivityAlive()) context.enhancedToast(err.toEnhancedMessage(context))
			}
		}
	}
}
