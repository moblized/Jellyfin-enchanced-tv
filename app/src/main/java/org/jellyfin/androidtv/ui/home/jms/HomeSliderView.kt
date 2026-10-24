package org.jellyfin.androidtv.ui.home.jms

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
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Size
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.getActivity
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Cinematic home slider (JMS Fusion style), built from plain views so scrolling the home screen stays as fast as stock.
 *
 * Remote: left/right browse slides, OK opens details, holding OK or the play key starts playback.
 * Slides advance on their own, but only while the slider is on screen.
 */
class HomeSliderView(context: Context) : FrameLayout(context), KoinComponent {
	companion object {
		const val HEIGHT_DP = 260
		private const val CONTENT_START_DP = 56
		private const val BACKDROP_FADE_MS = 450L
		private const val CONTENT_FADE_OUT_MS = 110L
		private const val CONTENT_FADE_IN_MS = 220L
		private const val LONG_PRESS_MS = 600L
		private const val SCRIM = 0x101010
	}

	private val imageHelper by inject<ImageHelper>()
	private val imageLoader by inject<ImageLoader>()
	private val navigationRepository by inject<NavigationRepository>()
	private val keyProcessor by inject<KeyProcessor>()

	private val heightPx = dp(HEIGHT_DP)
	private val backdropWidthPx = resources.displayMetrics.widthPixels.coerceAtMost(1920)

	private var backBackdrop = createBackdropView()
	private var frontBackdrop = createBackdropView()
	private val content = LinearLayout(context)
	private val logo = ImageView(context)
	private val title = TextView(context)
	private val meta = TextView(context)
	private val overview = TextView(context)
	private val hint = TextView(context)
	private val dots = LinearLayout(context)
	private val chevronStart = chevron("‹", Gravity.START or Gravity.CENTER_VERTICAL)
	private val chevronEnd = chevron("›", Gravity.END or Gravity.CENTER_VERTICAL)

	private var items: List<BaseItemDto> = emptyList()
	private var index = 0
	private var durationMs = 15_000L
	private var lastInteraction = 0L

	private var backdropRequest: Disposable? = null
	private var logoRequest: Disposable? = null
	private var prefetchRequests = mutableListOf<Disposable>()

	private var okDownAt = 0L
	private var longPressFired = false

	/** Called when the visible slide changes while the slider has focus. */
	var onSlideChanged: ((BaseItemDto) -> Unit)? = null

	private val autoAdvance = object : Runnable {
		override fun run() {
			val sinceInteraction = SystemClock.uptimeMillis() - lastInteraction
			if (sinceInteraction >= durationMs && items.size > 1 && isOnScreen()) {
				showSlide(index + 1)
			}
			scheduleAutoAdvance()
		}
	}

	init {
		layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, heightPx)
		isFocusable = true
		isFocusableInTouchMode = true
		clipChildren = true
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS

		addView(backBackdrop)
		addView(frontBackdrop)

		// Fade the backdrop into the screen: left for the text, bottom towards the rows
		addView(View(context).apply {
			background = linearGradient(
				horizontal = true,
				colors = intArrayOf(alpha(SCRIM, 0.96f), alpha(SCRIM, 0.82f), alpha(SCRIM, 0.35f), alpha(SCRIM, 0f)),
				positions = floatArrayOf(0f, 0.32f, 0.58f, 0.85f),
			)
		}, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
		addView(View(context).apply {
			background = linearGradient(
				horizontal = false,
				colors = intArrayOf(alpha(SCRIM, 0f), alpha(SCRIM, 0f), alpha(SCRIM, 0.95f)),
				positions = floatArrayOf(0f, 0.55f, 1f),
			)
		}, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

		buildContent()
		addView(content, LayoutParams(
			(resources.displayMetrics.widthPixels * 0.5f).roundToInt(),
			LayoutParams.WRAP_CONTENT,
			Gravity.START or Gravity.BOTTOM,
		).apply {
			marginStart = dp(CONTENT_START_DP)
			bottomMargin = dp(30)
		})

		dots.orientation = LinearLayout.HORIZONTAL
		dots.gravity = Gravity.CENTER_VERTICAL
		addView(dots, LayoutParams(LayoutParams.WRAP_CONTENT, dp(10), Gravity.START or Gravity.BOTTOM).apply {
			marginStart = dp(CONTENT_START_DP)
			bottomMargin = dp(12)
		})

		addView(chevronStart)
		addView(chevronEnd)
		updateFocusDecorations(false, animate = false)

		setOnKeyListener { _, keyCode, event -> handleKey(keyCode, event) }
		setOnClickListener { currentItem()?.let(::openDetails) }
	}

	fun bind(newItems: List<BaseItemDto>, slideDurationMs: Long) {
		val sameItems = newItems.map { it.id } == items.map { it.id }
		items = newItems
		durationMs = slideDurationMs
		if (!sameItems) index = 0

		buildDots()
		showSlide(index, animate = false)
		scheduleAutoAdvance()
	}

	fun unbind() {
		removeCallbacks(autoAdvance)
		backdropRequest?.dispose()
		logoRequest?.dispose()
		prefetchRequests.forEach { it.dispose() }
		prefetchRequests.clear()
	}

	fun currentItem(): BaseItemDto? = items.getOrNull(index)

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		scheduleAutoAdvance()
	}

	override fun onDetachedFromWindow() {
		removeCallbacks(autoAdvance)
		super.onDetachedFromWindow()
	}

	override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
		super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
		updateFocusDecorations(gainFocus, animate = true)
		if (gainFocus) lastInteraction = SystemClock.uptimeMillis()
	}

	// Slides

	private fun showSlide(newIndex: Int, animate: Boolean = true) {
		if (items.isEmpty()) return
		index = Math.floorMod(newIndex, items.size)
		val item = items[index]

		loadBackdrop(item, animate)
		updateDots()

		if (animate) {
			content.animate().cancel()
			content.animate().alpha(0f).setDuration(CONTENT_FADE_OUT_MS).withEndAction {
				bindText(item)
				content.animate().alpha(1f).setDuration(CONTENT_FADE_IN_MS).start()
			}.start()
		} else {
			content.alpha = 1f
			bindText(item)
		}

		prefetch(items.getOrNull(Math.floorMod(index + 1, items.size)))
		if (isFocused) onSlideChanged?.invoke(item)
	}

	private fun bindText(item: BaseItemDto) {
		title.text = item.name.orEmpty()
		meta.text = metadataLine(item)
		meta.visibility = if (meta.text.isNullOrBlank()) GONE else VISIBLE
		val text = item.overview?.trim().orEmpty()
		overview.text = text
		overview.visibility = if (text.isEmpty()) GONE else VISIBLE

		logoRequest?.dispose()
		val logoUrl = imageHelper.getLogoImageUrl(item, 600)
		logo.setImageDrawable(null)
		if (logoUrl == null) {
			logo.visibility = GONE
			title.visibility = VISIBLE
		} else {
			// Show the title until the logo is ready, so a slow logo never leaves a gap
			logo.visibility = GONE
			title.visibility = VISIBLE
			logoRequest = imageLoader.enqueue(
				ImageRequest.Builder(context)
					.data(logoUrl)
					.target(
						onSuccess = { image ->
							if (currentItem()?.id == item.id) {
								logo.setImageDrawable(image.asDrawable(resources))
								logo.visibility = VISIBLE
								title.visibility = GONE
							}
						},
					)
					.build()
			)
		}
	}

	private fun metadataLine(item: BaseItemDto): String = buildList {
		item.productionYear?.let { add(it.toString()) }
		item.officialRating?.takeIf { it.isNotBlank() }?.let { add(it) }
		if (item.type == BaseItemKind.SERIES) {
			item.childCount?.takeIf { it > 0 }?.let { add(resources.getQuantityString(R.plurals.jms_seasons, it, it)) }
		} else {
			item.runTimeTicks?.let { formatRuntime(it) }?.let { add(it) }
		}
		item.communityRating?.takeIf { it > 0 }?.let { add(String.format(Locale.getDefault(), "★ %.1f", it)) }
		item.criticRating?.takeIf { it > 0 }?.let { add("🍅 ${it.roundToInt()}%") }
		item.genres?.take(3)?.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
	}.joinToString("  ·  ")

	private fun formatRuntime(ticks: Long): String? {
		val minutes = (ticks / 600_000_000L).toInt()
		if (minutes <= 0) return null
		val hours = minutes / 60
		return if (hours > 0) context.getString(R.string.jms_duration_hours_minutes, hours, minutes % 60)
		else context.getString(R.string.jms_duration_minutes, minutes)
	}

	// Images

	private fun backdropUrl(item: BaseItemDto): String? {
		val image = item.itemBackdropImages.firstOrNull() ?: item.parentBackdropImages.firstOrNull() ?: return null
		return imageHelper.getImageUrl(image, backdropWidthPx, heightPx)
	}

	private fun backdropRequestBuilder(url: String) = ImageRequest.Builder(context)
		.data(url)
		.size(Size(Dimension.Pixels(backdropWidthPx), Dimension.Pixels(heightPx)))

	private fun loadBackdrop(item: BaseItemDto, animate: Boolean) {
		backdropRequest?.dispose()
		val url = backdropUrl(item) ?: return

		backdropRequest = imageLoader.enqueue(
			backdropRequestBuilder(url)
				.target(onSuccess = { image -> swapBackdrop(image.asDrawable(resources), animate) })
				.build()
		)
	}

	private fun swapBackdrop(drawable: Drawable, animate: Boolean) {
		val incoming = backBackdrop
		val outgoing = frontBackdrop

		incoming.animate().cancel()
		incoming.setImageDrawable(drawable)
		// Backdrops live at index 0 and 1, below the scrims and text: put the incoming one on top of the other
		if (indexOfChild(incoming) == 0) {
			removeView(incoming)
			addView(incoming, 1)
		}

		if (animate) {
			incoming.alpha = 0f
			incoming.animate().alpha(1f).setDuration(BACKDROP_FADE_MS).start()
		} else {
			incoming.alpha = 1f
		}

		frontBackdrop = incoming
		backBackdrop = outgoing
	}

	private fun prefetch(item: BaseItemDto?) {
		if (item == null) return
		prefetchRequests.removeAll { it.isDisposed }
		backdropUrl(item)?.let { url -> prefetchRequests += imageLoader.enqueue(backdropRequestBuilder(url).build()) }
		imageHelper.getLogoImageUrl(item, 600)?.let { url ->
			prefetchRequests += imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
		}
	}

	// Input

	private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
		val item = currentItem() ?: return false

		when (keyCode) {
			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
				if (items.size <= 1) return false
				if (event.action == KeyEvent.ACTION_DOWN) {
					lastInteraction = SystemClock.uptimeMillis()
					showSlide(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) index - 1 else index + 1)
				}
				return true
			}

			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
				when (event.action) {
					KeyEvent.ACTION_DOWN -> {
						if (event.repeatCount == 0) {
							okDownAt = event.downTime
							longPressFired = false
						} else if (!longPressFired && event.eventTime - okDownAt >= LONG_PRESS_MS) {
							longPressFired = true
							play(item)
						}
					}

					KeyEvent.ACTION_UP -> if (!longPressFired) openDetails(item)
				}
				return true
			}

			KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
				if (event.action == KeyEvent.ACTION_UP) play(item)
				return true
			}
		}

		return false
	}

	private fun openDetails(item: BaseItemDto) {
		lastInteraction = SystemClock.uptimeMillis()
		navigationRepository.navigate(Destinations.itemDetails(item.id))
	}

	private fun play(item: BaseItemDto) {
		lastInteraction = SystemClock.uptimeMillis()
		val activity = context.getActivity() as? FragmentActivity ?: return
		keyProcessor.handleKey(KeyEvent.KEYCODE_MEDIA_PLAY, BaseItemDtoBaseRowItem(item), activity)
	}

	// Helpers

	private fun scheduleAutoAdvance() {
		removeCallbacks(autoAdvance)
		if (items.size > 1 && isAttachedToWindow) postDelayed(autoAdvance, durationMs.coerceAtLeast(1_000) / 3)
	}

	private fun isOnScreen(): Boolean {
		if (!isShown || !hasWindowFocus()) return false
		val rect = android.graphics.Rect()
		return getGlobalVisibleRect(rect) && rect.height() > heightPx / 3
	}

	private fun updateFocusDecorations(focused: Boolean, animate: Boolean) {
		val hasMany = items.size > 1
		val targets = listOf(hint to (if (focused) 1f else 0f), chevronStart to (if (focused && hasMany) 1f else 0f), chevronEnd to (if (focused && hasMany) 1f else 0f))
		targets.forEach { (view, alpha) ->
			view.animate().cancel()
			if (animate) view.animate().alpha(alpha).setDuration(180).start() else view.alpha = alpha
		}
		updateDots()
	}

	private fun buildDots() {
		dots.removeAllViews()
		if (items.size <= 1) return
		repeat(items.size) {
			dots.addView(View(context), LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) })
		}
		updateDots()
	}

	private fun updateDots() {
		for (i in 0 until dots.childCount) {
			val dot = dots.getChildAt(i)
			val active = i == index
			dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).apply { width = dp(if (active) 18 else 6) }
			dot.background = GradientDrawable().apply {
				cornerRadius = dp(3).toFloat()
				setColor(if (active) Color.WHITE else Color.argb(if (isFocused) 140 else 90, 255, 255, 255))
			}
		}
	}

	private fun buildContent() {
		content.orientation = LinearLayout.VERTICAL

		logo.adjustViewBounds = true
		logo.scaleType = ImageView.ScaleType.FIT_START
		logo.maxHeight = dp(72)
		logo.maxWidth = dp(320)
		content.addView(logo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(72)))

		title.setTextColor(Color.WHITE)
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
		title.setTypeface(title.typeface, Typeface.BOLD)
		title.maxLines = 2
		title.ellipsize = TextUtils.TruncateAt.END
		title.setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
		content.addView(title)

		meta.setTextColor(Color.argb(220, 255, 255, 255))
		meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
		meta.isSingleLine = true
		meta.ellipsize = TextUtils.TruncateAt.END
		content.addView(meta, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
			topMargin = dp(10)
		})

		overview.setTextColor(Color.argb(230, 255, 255, 255))
		overview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
		overview.maxLines = 3
		overview.ellipsize = TextUtils.TruncateAt.END
		overview.setLineSpacing(0f, 1.1f)
		content.addView(overview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
			topMargin = dp(8)
		})

		hint.text = context.getString(R.string.jms_slider_hint)
		hint.setTextColor(Color.argb(170, 255, 255, 255))
		hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
		content.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
			topMargin = dp(12)
		})
	}

	private fun createBackdropView() = ImageView(context).apply {
		scaleType = ImageView.ScaleType.CENTER_CROP
		layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
	}

	private fun chevron(text: String, gravity: Int) = TextView(context).apply {
		this.text = text
		setTextColor(Color.argb(210, 255, 255, 255))
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
		setShadowLayer(10f, 0f, 0f, Color.BLACK)
		layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity).apply {
			marginStart = dp(14)
			marginEnd = dp(14)
		}
	}

	private fun linearGradient(horizontal: Boolean, colors: IntArray, positions: FloatArray): Drawable =
		PaintDrawable().apply {
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

	private fun alpha(rgb: Int, alpha: Float) = Color.argb((alpha * 255).roundToInt(), Color.red(rgb), Color.green(rgb), Color.blue(rgb))

	private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()
}
