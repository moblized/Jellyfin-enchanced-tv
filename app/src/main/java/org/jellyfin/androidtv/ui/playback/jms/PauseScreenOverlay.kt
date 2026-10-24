package org.jellyfin.androidtv.ui.playback.jms

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.Disposable
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.jms.JmsFusionRepository
import org.jellyfin.androidtv.enhanced.jms.JmsPauseSettings
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.sdk.getDisplayName
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * JMS Fusion style pause screen: after the video has been paused for a few seconds without input, the controls give
 * way to a full screen card with the title, metadata, plot and progress. Any key brings the normal controls back.
 *
 * Nothing runs while the video plays; the only work is a single delayed callback after pausing.
 */
class PauseScreenOverlay(
	private val root: ViewGroup,
	private val scope: LifecycleCoroutineScope,
	private val controller: () -> PlaybackController?,
	/** False while another panel (guide, chapters) is open. */
	private val canShow: () -> Boolean,
	/** Called right before the screen appears, to hide the regular controls. */
	private val onShow: () -> Unit,
) : KoinComponent {
	companion object {
		private val IDLE_DELAY_MS = 5.seconds.inWholeMilliseconds
		private const val FADE_IN_MS = 350L
		private const val FADE_OUT_MS = 180L
	}

	private val api by inject<ApiClient>()
	private val imageHelper by inject<ImageHelper>()
	private val imageLoader by inject<ImageLoader>()
	private val userPreferences by inject<UserPreferences>()
	private val jmsFusionRepository by inject<JmsFusionRepository>()

	private val context: Context = root.context
	private val handler = Handler(Looper.getMainLooper())
	private val showRunnable = Runnable { show() }

	private var settings = JmsPauseSettings()
	private var view: PauseScreenView? = null
	private var loadJob: Job? = null
	private var paused = false
	private var dismissedAt = 0L
	private val itemCache = mutableMapOf<UUID, BaseItemDto>()

	private val enabled get() = userPreferences[UserPreferences.jmsPauseScreen]

	val isVisible get() = view?.visibility == View.VISIBLE

	init {
		if (enabled) scope.launch {
			settings = runCatching { jmsFusionRepository.pauseSettings() }.getOrDefault(JmsPauseSettings())
		}
	}

	/** Call whenever play / pause state may have changed. */
	fun onPlaybackStateChanged() {
		val isPaused = controller()?.isPaused == true
		if (isPaused == paused) return
		paused = isPaused

		if (isPaused) schedule() else {
			handler.removeCallbacks(showRunnable)
			hide()
		}
	}

	/**
	 * Call for every key press. Hides the screen when visible and restarts the idle timer.
	 * Returns true when the key should only dismiss the screen.
	 */
	fun onUserInteraction(keyCode: Int): Boolean {
		val wasVisible = isVisible
		if (paused) schedule()
		if (!wasVisible) return false

		hide()
		// Media keys still do their job (for example resume), everything else only brings the controls back
		return when (keyCode) {
			KeyEvent.KEYCODE_MEDIA_PLAY,
			KeyEvent.KEYCODE_MEDIA_PAUSE,
			KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
			KeyEvent.KEYCODE_MEDIA_STOP -> false

			else -> true
		}
	}

	/**
	 * Call from the back handler. Returns true when back should only dismiss the screen (also right after a key
	 * press already started dismissing it).
	 */
	fun consumeBack(): Boolean {
		val recentlyDismissed = android.os.SystemClock.uptimeMillis() - dismissedAt < 1_000
		if (!isVisible && !recentlyDismissed) return false
		hide()
		return true
	}

	fun release() {
		handler.removeCallbacks(showRunnable)
		loadJob?.cancel()
		view?.release()
		view?.let { root.removeView(it) }
		view = null
		paused = false
	}

	private fun schedule() {
		handler.removeCallbacks(showRunnable)
		if (enabled) handler.postDelayed(showRunnable, IDLE_DELAY_MS)
	}

	private fun show() {
		val playbackController = controller() ?: return
		if (!enabled || !paused || !playbackController.isPaused || playbackController.isLiveTv || !canShow()) return

		val item = playbackController.currentlyPlayingItem ?: return
		val durationMs = playbackController.duration
		if (settings.minVideoMinutes > 0 && durationMs in 1 until settings.minVideoMinutes * 60_000L) return
		val positionMs = playbackController.currentPosition

		loadJob?.cancel()
		loadJob = scope.launch {
			// Fresh details (overview, genres) and, for episodes, the series. Never wait long for them.
			val details = withTimeoutOrNull(2.seconds) { fetch(item.id) } ?: item
			val series = if (details.type == BaseItemKind.EPISODE) {
				details.seriesId?.let { id -> withTimeoutOrNull(2.seconds) { fetch(id) } }
			} else null

			val current = controller()
			if (!paused || current?.isPaused != true || !canShow()) return@launch

			val screen = view ?: PauseScreenView(context).also {
				root.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
				view = it
			}
			screen.bind(details, series, positionMs, durationMs, settings, imageHelper, imageLoader)
			onShow()
			screen.bringToFront()
			screen.alpha = 0f
			screen.visibility = View.VISIBLE
			screen.animate().alpha(1f).setDuration(FADE_IN_MS).start()
		}
	}

	private fun hide() {
		loadJob?.cancel()
		val screen = view ?: return
		if (screen.visibility != View.VISIBLE) return
		dismissedAt = android.os.SystemClock.uptimeMillis()
		screen.animate().cancel()
		screen.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction { screen.visibility = View.GONE }.start()
	}

	private suspend fun fetch(id: UUID): BaseItemDto? {
		itemCache[id]?.let { return it }
		return try {
			api.userLibraryApi.getItem(itemId = id).content.also { itemCache[id] = it }
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to load item for the pause screen")
			null
		}
	}
}

private class PauseScreenView(context: Context) : FrameLayout(context) {
	private val backdrop = ImageView(context)
	private val label = TextView(context)
	private val logo = ImageView(context)
	private val title = TextView(context)
	private val subtitle = TextView(context)
	private val meta = TextView(context)
	private val plot = TextView(context)
	private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
	private val progressText = TextView(context)
	private val status = TextView(context)

	private var backdropRequest: Disposable? = null
	private var logoRequest: Disposable? = null

	init {
		visibility = GONE
		isClickable = true
		isFocusable = false

		// Backdrop on the right, faded into a dark panel on the left (the paused frame shows through)
		backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
		addView(backdrop, LayoutParams((resources.displayMetrics.widthPixels * 0.62f).roundToInt(), LayoutParams.MATCH_PARENT, Gravity.END))
		addView(View(context).apply {
			background = gradient(
				horizontal = true,
				colors = intArrayOf(Color.argb(245, 12, 12, 14), Color.argb(225, 12, 12, 14), Color.argb(120, 12, 12, 14), Color.argb(60, 12, 12, 14)),
				positions = floatArrayOf(0f, 0.38f, 0.7f, 1f),
			)
		}, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
		addView(View(context).apply {
			background = gradient(
				horizontal = false,
				colors = intArrayOf(Color.argb(0, 12, 12, 14), Color.argb(0, 12, 12, 14), Color.argb(200, 12, 12, 14)),
				positions = floatArrayOf(0f, 0.6f, 1f),
			)
		}, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

		val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

		label.text = context.getString(R.string.jms_pause_watching)
		label.setTextColor(Color.argb(180, 255, 255, 255))
		label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
		column.addView(label)

		logo.adjustViewBounds = true
		logo.scaleType = ImageView.ScaleType.FIT_START
		logo.maxHeight = dp(96)
		logo.maxWidth = dp(380)
		column.addView(logo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(96)).apply { topMargin = dp(10) })

		title.setTextColor(Color.WHITE)
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
		title.setTypeface(title.typeface, Typeface.BOLD)
		title.maxLines = 2
		title.ellipsize = TextUtils.TruncateAt.END
		column.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

		subtitle.setTextColor(Color.WHITE)
		subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
		subtitle.maxLines = 2
		subtitle.ellipsize = TextUtils.TruncateAt.END
		column.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

		meta.setTextColor(Color.argb(220, 255, 255, 255))
		meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
		meta.maxLines = 2
		column.addView(meta, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

		plot.setTextColor(Color.argb(235, 255, 255, 255))
		plot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
		plot.maxLines = 5
		plot.ellipsize = TextUtils.TruncateAt.END
		plot.setLineSpacing(0f, 1.15f)
		column.addView(plot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

		addView(column, LayoutParams((resources.displayMetrics.widthPixels * 0.5f).roundToInt(), LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
			marginStart = dp(64)
		})

		// Progress at the bottom
		progress.max = 1000
		progress.progressDrawable = progressDrawable()
		addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM).apply {
			marginStart = dp(64)
			marginEnd = dp(64)
			bottomMargin = dp(40)
		})
		progressText.setTextColor(Color.argb(210, 255, 255, 255))
		progressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
		addView(progressText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
			marginStart = dp(64)
			bottomMargin = dp(54)
		})

		status.text = context.getString(R.string.jms_pause_paused)
		status.setTextColor(Color.argb(210, 255, 255, 255))
		status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
		addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
			marginEnd = dp(64)
			bottomMargin = dp(54)
		})
	}

	fun bind(
		item: BaseItemDto,
		series: BaseItemDto?,
		positionMs: Long,
		durationMs: Long,
		settings: JmsPauseSettings,
		imageHelper: ImageHelper,
		imageLoader: ImageLoader,
	) {
		val main = series ?: item
		val isEpisode = item.type == BaseItemKind.EPISODE

		title.text = (if (isEpisode) item.seriesName ?: main.name else item.name).orEmpty()
		subtitle.text = if (isEpisode) item.getDisplayName(context) else item.taglines?.firstOrNull().orEmpty()
		subtitle.visibility = if (subtitle.text.isNullOrBlank()) GONE else VISIBLE

		meta.text = if (settings.showMetadata) metadata(item, main) else ""
		meta.visibility = if (meta.text.isNullOrBlank()) GONE else VISIBLE

		val overview = (item.overview ?: main.overview)?.trim().orEmpty()
		plot.text = if (settings.showPlot) overview else ""
		plot.visibility = if (plot.text.isNullOrBlank()) GONE else VISIBLE

		// Progress
		if (durationMs > 0) {
			progress.progress = ((positionMs.toDouble() / durationMs) * 1000).roundToInt().coerceIn(0, 1000)
			val left = (durationMs - positionMs).coerceAtLeast(0)
			progressText.text = context.getString(R.string.jms_pause_progress, formatTime(positionMs), formatTime(durationMs), formatDuration(left))
			progress.visibility = VISIBLE
			progressText.visibility = VISIBLE
		} else {
			progress.visibility = GONE
			progressText.visibility = GONE
		}

		// Logo (falls back to the title text)
		logoRequest?.dispose()
		logo.setImageDrawable(null)
		logo.visibility = GONE
		title.visibility = VISIBLE
		val logoUrl = if (settings.showLogo) imageHelper.getLogoImageUrl(item, 760) ?: imageHelper.getLogoImageUrl(main, 760) else null
		if (logoUrl != null) logoRequest = imageLoader.enqueue(
			ImageRequest.Builder(context).data(logoUrl).target(onSuccess = { image ->
				logo.setImageDrawable(image.asDrawable(resources))
				logo.visibility = VISIBLE
				title.visibility = GONE
			}).build()
		)

		// Backdrop
		backdropRequest?.dispose()
		backdrop.setImageDrawable(null)
		val backdropImage = if (settings.showBackdrop) {
			main.itemBackdropImages.firstOrNull() ?: item.itemBackdropImages.firstOrNull() ?: item.parentBackdropImages.firstOrNull()
		} else null
		if (backdropImage != null) {
			val width = (resources.displayMetrics.widthPixels * 0.62f).roundToInt().coerceAtMost(1280)
			val url = imageHelper.getImageUrl(backdropImage, width, resources.displayMetrics.heightPixels.coerceAtMost(720))
			backdropRequest = imageLoader.enqueue(
				ImageRequest.Builder(context).data(url).target(onSuccess = { image ->
					backdrop.setImageDrawable(image.asDrawable(resources))
				}).build()
			)
		}
	}

	fun release() {
		backdropRequest?.dispose()
		logoRequest?.dispose()
	}

	private fun metadata(item: BaseItemDto, main: BaseItemDto): String = buildList {
		(main.productionYear ?: item.productionYear)?.let { add(it.toString()) }
		(main.officialRating ?: item.officialRating)?.takeIf { it.isNotBlank() }?.let { add(it) }
		item.runTimeTicks?.takeIf { it > 0 }?.let { ticks -> add(formatDuration(ticks / 10_000)) }
		(item.communityRating ?: main.communityRating)?.takeIf { it > 0 }?.let { add(String.format(Locale.getDefault(), "★ %.1f", it)) }
		(main.criticRating ?: item.criticRating)?.takeIf { it > 0 }?.let { add("🍅 ${it.roundToInt()}%") }
		(item.genres?.takeIf { it.isNotEmpty() } ?: main.genres)?.take(3)?.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
	}.joinToString("  ·  ")

	private fun formatTime(ms: Long): String {
		val totalSeconds = ms / 1000
		val hours = totalSeconds / 3600
		val minutes = (totalSeconds % 3600) / 60
		val seconds = totalSeconds % 60
		return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
		else String.format(Locale.US, "%d:%02d", minutes, seconds)
	}

	private fun formatDuration(ms: Long): String {
		val minutes = (ms / 60_000).toInt()
		val hours = minutes / 60
		return if (hours > 0) context.getString(R.string.jms_duration_hours_minutes, hours, minutes % 60)
		else context.getString(R.string.jms_duration_minutes, minutes)
	}

	private fun progressDrawable(): Drawable {
		val track = GradientDrawable().apply {
			cornerRadius = dp(2).toFloat()
			setColor(Color.argb(70, 255, 255, 255))
		}
		val fill = GradientDrawable().apply {
			cornerRadius = dp(2).toFloat()
			setColor(Color.WHITE)
		}
		return android.graphics.drawable.LayerDrawable(arrayOf(track, android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL))).apply {
			setId(0, android.R.id.background)
			setId(1, android.R.id.progress)
		}
	}

	private fun gradient(horizontal: Boolean, colors: IntArray, positions: FloatArray): Drawable = PaintDrawable().apply {
		shape = RectShape()
		shaderFactory = object : ShapeDrawable.ShaderFactory() {
			override fun resize(width: Int, height: Int): Shader = LinearGradient(
				0f, 0f,
				if (horizontal) width.toFloat() else 0f,
				if (horizontal) 0f else height.toFloat(),
				colors, positions, Shader.TileMode.CLAMP,
			)
		}
	}

	private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()
}
