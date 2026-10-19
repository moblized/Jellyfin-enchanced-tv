package org.jellyfin.androidtv.ui.itemdetail

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.Bookmark
import org.jellyfin.androidtv.enhanced.JellyfinEnhancedRepository
import org.jellyfin.androidtv.enhanced.Review
import org.jellyfin.androidtv.enhanced.enhancedToast
import org.jellyfin.androidtv.enhanced.formatPosition
import org.jellyfin.androidtv.enhanced.formatStars
import org.jellyfin.androidtv.enhanced.reviewKey
import org.jellyfin.androidtv.enhanced.toEnhancedMessage
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.util.popupMenu
import org.jellyfin.androidtv.util.showIfNotEmpty
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import kotlin.math.roundToInt

private val enhancedRepository: JellyfinEnhancedRepository
	get() = KoinJavaComponent.get(JellyfinEnhancedRepository::class.java)

private val playableTypes = setOf(
	BaseItemKind.MOVIE,
	BaseItemKind.EPISODE,
	BaseItemKind.VIDEO,
	BaseItemKind.MUSIC_VIDEO,
)

private val BaseItemDto.supportsBookmarks get() = type in playableTypes

/**
 * Whether the Jellyfin Enhanced button belongs on this details screen. Called from [FullDetailsFragment.addButtons].
 */
fun FullDetailsFragment.shouldShowEnhancedButton(): Boolean {
	val userPreferences = KoinJavaComponent.get<UserPreferences>(UserPreferences::class.java)
	if (!userPreferences[UserPreferences.enhancedDetailsButton]) return false

	val baseItem: BaseItemDto = mBaseItem ?: return false
	if (!baseItem.supportsBookmarks && baseItem.reviewKey == null) return false

	val status = enhancedRepository.currentStatus
	if (status == null) {
		// Not loaded yet: load it now, the menu waits for it when opened
		enhancedRepository.prefetch()
		return true
	}

	return (status.bookmarksEnabled && baseItem.supportsBookmarks) || (status.reviewsEnabled && baseItem.reviewKey != null)
}

/**
 * Popup with the Jellyfin Enhanced actions for the current item.
 */
fun FullDetailsFragment.showEnhancedMenu(anchor: View) {
	val baseItem: BaseItemDto = mBaseItem ?: return
	val context = requireContext()

	lifecycleScope.launch {
		try {
			val status = enhancedRepository.getStatus()
			if (!status.available) {
				context.enhancedToast(context.getString(R.string.je_not_available))
				return@launch
			}

			val key = baseItem.reviewKey
			val bookmarksJob = async {
				if (status.bookmarksEnabled && baseItem.supportsBookmarks) enhancedRepository.bookmarksFor(baseItem, forceRefresh = true)
				else null
			}
			val myReviewJob = async {
				if (status.userReviewsEnabled && key != null) enhancedRepository.myReview(key.first, key.second)
				else null
			}
			val bookmarks = runCatching { bookmarksJob.await() }.getOrNull()
			val myReview = runCatching { myReviewJob.await() }.getOrNull()
			if (!isAdded) return@launch

			popupMenu(context, anchor) {
				if (bookmarks != null) {
					item("${context.getString(R.string.je_bookmarks)} (${bookmarks.size})") {
						showBookmarksDialog(baseItem, bookmarks)
					}
				}

				if (status.reviewsEnabled && key != null) {
					item(context.getString(R.string.je_reviews)) {
						showReviewsDialog(baseItem, key.first, key.second)
					}
				}

				if (status.userReviewsEnabled && key != null) {
					val rating = myReview?.rating
					val label = if (rating != null) context.getString(R.string.je_rate_current, formatStars(rating))
					else context.getString(R.string.je_rate)
					item(label) { showRatingDialog(key.first, key.second, myReview) }
				}
			}.also { menu ->
				if (!menu.showIfNotEmpty()) context.enhancedToast(context.getString(R.string.je_review_unsupported))
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to open Jellyfin Enhanced menu")
			if (isAdded) context.enhancedToast(err.toEnhancedMessage(context))
		}
	}
}

// Bookmarks

private fun FullDetailsFragment.showBookmarksDialog(item: BaseItemDto, bookmarks: List<Bookmark>) {
	val context = requireContext()

	if (bookmarks.isEmpty()) {
		AlertDialog.Builder(context)
			.setTitle(R.string.je_bookmarks)
			.setMessage(R.string.je_bookmarks_none)
			.setPositiveButton(android.R.string.ok, null)
			.show()
		return
	}

	val labels = bookmarks.map { bookmark ->
		buildString {
			append(formatPosition(bookmark.positionMs))
			if (bookmark.label.isNotBlank()) append("  ·  ").append(bookmark.label)
		}
	}.toTypedArray()

	AlertDialog.Builder(context)
		.setTitle(R.string.je_bookmarks)
		.setItems(labels) { _, which -> showBookmarkActions(item, bookmarks[which]) }
		.setNegativeButton(android.R.string.cancel, null)
		.show()
}

private fun FullDetailsFragment.showBookmarkActions(item: BaseItemDto, bookmark: Bookmark) {
	val context = requireContext()
	val actions = arrayOf(
		context.getString(R.string.je_bookmark_play),
		context.getString(R.string.je_bookmark_delete),
	)

	AlertDialog.Builder(context)
		.setTitle(formatPosition(bookmark.positionMs))
		.setItems(actions) { _, which ->
			when (which) {
				0 -> play(item, bookmark.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), false)
				1 -> lifecycleScope.launch {
					try {
						enhancedRepository.deleteBookmark(bookmark)
						if (isAdded) context.enhancedToast(context.getString(R.string.je_bookmark_deleted))
					} catch (err: CancellationException) {
						throw err
					} catch (err: Exception) {
						if (isAdded) context.enhancedToast(err.toEnhancedMessage(context))
					}
				}
			}
		}
		.setNegativeButton(android.R.string.cancel, null)
		.show()
}

// Reviews

private fun FullDetailsFragment.showReviewsDialog(item: BaseItemDto, mediaType: String, tmdbId: String) {
	val context = requireContext()

	lifecycleScope.launch {
		val status = enhancedRepository.getStatus()
		val tmdbJob = async {
			if (status.tmdbReviewsEnabled) runCatching { enhancedRepository.tmdbReviews(mediaType, tmdbId) }.getOrDefault(emptyList())
			else emptyList()
		}
		val userJob = async {
			if (status.userReviewsEnabled) runCatching { enhancedRepository.userReviews(mediaType, tmdbId) }.getOrDefault(emptyList())
			else emptyList()
		}
		val userReviews = userJob.await()
		val tmdbReviews = tmdbJob.await()
		if (!isAdded) return@launch

		val content = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			val padding = context.dp(24)
			setPadding(padding, context.dp(8), padding, context.dp(8))
		}

		if (userReviews.isEmpty() && tmdbReviews.isEmpty()) {
			content.addView(reviewText(context, context.getString(R.string.je_reviews_none), focusable = true))
		}
		if (userReviews.isNotEmpty()) {
			content.addView(headerText(context, context.getString(R.string.je_reviews_server)))
			userReviews.forEach { content.addView(reviewText(context, it.format(), focusable = true)) }
		}
		if (tmdbReviews.isNotEmpty()) {
			content.addView(headerText(context, context.getString(R.string.je_reviews_tmdb)))
			tmdbReviews.forEach { content.addView(reviewText(context, it.format(), focusable = true)) }
		}

		val scrollView = ScrollView(context).apply {
			addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		}

		AlertDialog.Builder(context)
			.setTitle("${context.getString(R.string.je_reviews)} · ${item.name.orEmpty()}")
			.setView(scrollView)
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}
}

private fun Review.format(): String = buildString {
	append(author)
	rating?.let {
		append("  ·  ")
		// TMDB uses a 10 point scale, the server reviews 5 stars
		append(formatStars(if (ratingScale == 10) it / 2 else it))
	}
	date?.take(10)?.let { append("  ·  ").append(it) }
	if (content.isNotBlank()) {
		append("\n")
		val trimmed = content.trim()
		append(if (trimmed.length > 900) trimmed.take(900).trimEnd() + "…" else trimmed)
	}
}

private fun Context.dp(value: Int) = TypedValue.applyDimension(
	TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
).roundToInt()

private fun headerText(context: Context, text: String) = TextView(context).apply {
	this.text = text
	setTypeface(typeface, Typeface.BOLD)
	setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
	setPadding(0, context.dp(12), 0, context.dp(4))
}

private fun reviewText(context: Context, text: String, focusable: Boolean) = TextView(context).apply {
	this.text = text
	setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
	setLineSpacing(0f, 1.15f)
	val padding = context.dp(8)
	setPadding(padding, padding, padding, padding)
	// Focusable rows let the D-pad move through (and scroll) long review lists
	isFocusable = focusable
	isFocusableInTouchMode = false
	setOnFocusChangeListener { view, hasFocus ->
		view.setBackgroundColor(if (hasFocus) Color.argb(40, 255, 255, 255) else Color.TRANSPARENT)
	}
}

// Rating

private val ratingSteps = (2..10).map { it / 2.0 }

private fun FullDetailsFragment.showRatingDialog(mediaType: String, tmdbKey: String, current: Review?) {
	val context = requireContext()

	val options = buildList {
		ratingSteps.forEach { rating ->
			val label = formatStars(rating)
			add(if (current?.rating == rating) "✓ $label" else label)
		}
		add(context.getString(R.string.je_review_write))
		if (current != null) add(context.getString(R.string.je_rate_remove))
	}.toTypedArray()

	AlertDialog.Builder(context)
		.setTitle(R.string.je_rate)
		.setItems(options) { _, which ->
			when {
				which < ratingSteps.size -> saveReview(mediaType, tmdbKey, ratingSteps[which], current?.content.orEmpty())
				which == ratingSteps.size -> showWriteReviewDialog(mediaType, tmdbKey, current)
				else -> deleteReview(mediaType, tmdbKey)
			}
		}
		.setNegativeButton(android.R.string.cancel, null)
		.show()
}

private fun FullDetailsFragment.showWriteReviewDialog(mediaType: String, tmdbKey: String, current: Review?) {
	val context = requireContext()
	val input = EditText(context).apply {
		inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
		setHint(R.string.je_review_hint)
		setText(current?.content.orEmpty())
		minLines = 3
	}
	val container = LinearLayout(context).apply {
		val padding = context.dp(24)
		setPadding(padding, context.dp(8), padding, 0)
		addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
	}

	AlertDialog.Builder(context)
		.setTitle(R.string.je_review_write)
		.setView(container)
		.setPositiveButton(R.string.lbl_save) { _, _ ->
			saveReview(mediaType, tmdbKey, current?.rating, input.text.toString().trim().take(2000))
		}
		.setNegativeButton(android.R.string.cancel, null)
		.show()
}

private fun FullDetailsFragment.saveReview(mediaType: String, tmdbKey: String, rating: Double?, content: String) {
	val context = requireContext()
	if (rating == null && content.isBlank()) return deleteReview(mediaType, tmdbKey)

	lifecycleScope.launch {
		try {
			enhancedRepository.saveReview(mediaType, tmdbKey, rating, content)
			if (isAdded) context.enhancedToast(context.getString(R.string.je_review_saved))
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			if (isAdded) context.enhancedToast(err.toEnhancedMessage(context))
		}
	}
}

private fun FullDetailsFragment.deleteReview(mediaType: String, tmdbKey: String) {
	val context = requireContext()

	lifecycleScope.launch {
		try {
			enhancedRepository.deleteReview(mediaType, tmdbKey)
			if (isAdded) context.enhancedToast(context.getString(R.string.je_review_removed))
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			if (isAdded) context.enhancedToast(err.toEnhancedMessage(context))
		}
	}
}
