package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.Bookmark
import org.jellyfin.androidtv.enhanced.JellyfinEnhancedRepository
import org.jellyfin.androidtv.enhanced.enhancedScope
import org.jellyfin.androidtv.enhanced.enhancedToast
import org.jellyfin.androidtv.enhanced.formatPosition
import org.jellyfin.androidtv.enhanced.isActivityAlive
import org.jellyfin.androidtv.enhanced.toEnhancedMessage
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.androidtv.util.popupMenu
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Jellyfin Enhanced bookmarks in the player: save the current position, or jump to a saved one.
 */
class BookmarkAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue), KoinComponent {
	private val repository by inject<JellyfinEnhancedRepository>()
	private var popup: PopupMenu? = null

	init {
		initializeWithIcon(R.drawable.ic_bookmark)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		val playingItem = playbackController.currentlyPlayingItem ?: return
		val position = playbackController.currentPosition

		val status = repository.currentStatus
		if (status != null && !status.available) {
			context.enhancedToast(context.getString(R.string.je_not_available))
			return
		}

		dismissPopup()
		videoPlayerAdapter.leanbackOverlayFragment.setFading(false)

		context.enhancedScope().launch {
			val bookmarks = try {
				repository.bookmarksFor(playingItem)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load bookmarks")
				emptyList<Bookmark>()
			}

			if (!context.isActivityAlive()) return@launch

			popup = popupMenu(context, view, Gravity.END) {
				item(context.getString(R.string.je_bookmark_add, formatPosition(position))) {
					addBookmark(context, playbackController, position)
				}

				for (bookmark in bookmarks) {
					val label = buildString {
						append(context.getString(R.string.je_bookmark_jump, formatPosition(bookmark.positionMs)))
						if (bookmark.label.isNotBlank()) append(" · ").append(bookmark.label)
					}
					item(label) { playbackController.seek(bookmark.positionMs) }
				}
			}
			popup?.setOnDismissListener {
				videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
				popup = null
			}
			popup?.show()
		}
	}

	private fun addBookmark(context: Context, playbackController: PlaybackController, position: Long) {
		val playingItem = playbackController.currentlyPlayingItem ?: return

		context.enhancedScope().launch {
			try {
				repository.addBookmark(playingItem, position, "")
				if (context.isActivityAlive()) {
					context.enhancedToast(context.getString(R.string.je_bookmark_added, formatPosition(position)))
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to add bookmark")
				if (context.isActivityAlive()) context.enhancedToast(err.toEnhancedMessage(context))
			}
		}
	}

	fun dismissPopup() {
		popup?.dismiss()
	}
}
