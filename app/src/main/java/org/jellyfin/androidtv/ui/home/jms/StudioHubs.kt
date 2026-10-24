package org.jellyfin.androidtv.ui.home.jms

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.Disposable
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.jms.JmsFusionRepository
import org.jellyfin.androidtv.ui.home.HomeFragmentRow
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.studiosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt

/** A studio hub: one tile on the home screen that can stand for several library studios (aliases). */
data class StudioHub(
	val name: String,
	val studio: BaseItemDto,
	val studioIds: List<UUID>,
	val logoUrl: String?,
)

/**
 * Builds the studio hub tiles like JMS Fusion: its default studios plus the ones an admin added, matched to the
 * library's studios by name and alias, in the user's own JMS Fusion order and without the hubs they hid.
 */
class StudioHubRepository(
	private val api: ApiClient,
	private val userRepository: UserRepository,
	private val jmsFusionRepository: JmsFusionRepository,
) {
	companion object {
		private const val CACHE_TTL_MS = 15 * 60 * 1000L

		/** Default hubs and their aliases, the same list JMS Fusion ships with. */
		val defaultHubs: Map<String, List<String>> = linkedMapOf(
			"Marvel Studios" to listOf("marvel studios", "marvel", "marvel entertainment", "marvel studios llc"),
			"Pixar" to listOf("pixar", "pixar animation studios", "disney pixar"),
			"Walt Disney Pictures" to listOf("walt disney", "walt disney pictures"),
			"Disney+" to listOf("disney+", "disney plus", "disney+ originals", "disney plus originals", "disney+ studio"),
			"DC" to listOf("dc entertainment", "dc"),
			"Warner Bros. Pictures" to listOf("warner bros", "warner bros.", "warner bros pictures", "warner bros. pictures", "warner brothers"),
			"Lucasfilm Ltd." to listOf("lucasfilm", "lucasfilm ltd", "lucasfilm ltd."),
			"Columbia Pictures" to listOf("columbia", "columbia pictures", "columbia pictures industries"),
			"Paramount Pictures" to listOf("paramount", "paramount pictures", "paramount pictures corporation"),
			"Netflix" to listOf("netflix"),
			"DreamWorks Animation" to listOf("dreamworks", "dreamworks animation", "dreamworks pictures"),
		)

		/** Logos JMS Fusion bundles and serves for its default studios. */
		private val bundledLogoSlugs = setOf(
			"columbia-pictures", "dc", "disney", "dreamworks-animation", "lucasfilm-ltd", "marvel-studios",
			"netflix", "paramount-pictures", "pixar", "universal", "walt-disney-pictures", "warner-bros-pictures",
		)

		/** Same slug rules as JMS Fusion, so names map to its logo files. */
		fun slugify(name: String): String = name.lowercase()
			.replace(Regex("""[().,™©®'’"&+]"""), " ")
			.replace(Regex("""\s+and\s+"""), " ")
			.replace(Regex("[^a-z0-9]+"), "-")
			.trim('-')

		private fun normalize(name: String) = name.lowercase().trim().replace(Regex("\\s+"), " ")
	}

	private val mutex = Mutex()
	private var cached: Pair<String, List<StudioHub>>? = null
	private var cachedAt = 0L

	private fun sessionKey() = "${api.baseUrl}|${userRepository.currentUser.value?.id}"

	suspend fun load(): List<StudioHub> = mutex.withLock {
		cached?.let { (key, hubs) ->
			if (key == sessionKey() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) return@withLock hubs
		}

		val hubs = try {
			withContext(Dispatchers.IO) { build() }
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to load studio hubs")
			return@withLock cached?.second.orEmpty()
		}

		cached = sessionKey() to hubs
		cachedAt = System.currentTimeMillis()
		hubs
	}

	private suspend fun build(): List<StudioHub> {
		val userId = userRepository.currentUser.value?.id ?: return emptyList()
		val jmsInstalled = jmsFusionRepository.getStatus().installed

		val studios = api.studiosApi.getStudios(
			userId = userId,
			limit = 1000,
			enableTotalRecordCount = false,
		).content.items
		if (studios.isEmpty()) return emptyList()
		val studiosByName = studios.groupBy { normalize(it.name.orEmpty()) }

		val manualEntries = jmsFusionRepository.studioManualEntries()
		val visibility = jmsFusionRepository.studioVisibility()
		val hidden = visibility.hiddenNames.map(::normalize).toSet()

		val candidates = buildList {
			defaultHubs.forEach { (name, aliases) ->
				val matches = (aliases + name).flatMap { studiosByName[normalize(it)].orEmpty() }.distinctBy { it.id }
				if (matches.isNotEmpty()) add(Triple(name, matches, null as String?))
			}

			manualEntries.forEach { entry ->
				if (defaultHubs.keys.any { normalize(it) == normalize(entry.name) }) return@forEach
				val byId = entry.studioId.toUUIDOrNull()?.let { id -> studios.firstOrNull { it.id == id } }
				val matches = listOfNotNull(byId) + studiosByName[normalize(entry.name)].orEmpty()
				val logo = entry.logoFileName?.let { jmsFusionRepository.manualLogoUrl(it) }
				if (matches.isNotEmpty()) add(Triple(entry.name, matches.distinctBy { it.id }, logo))
			}
		}.filter { (name, _, _) -> normalize(name) !in hidden }

		val order = visibility.orderNames.map(::normalize)
		val sorted = if (order.isEmpty()) candidates else candidates.sortedBy { (name, _, _) ->
			order.indexOf(normalize(name)).let { if (it < 0) Int.MAX_VALUE else it }
		}

		return sorted.map { (name, matches, manualLogo) ->
			val primary = matches.first()
			val slug = slugify(name)
			val logo = manualLogo
				?: slug.takeIf { jmsInstalled && it in bundledLogoSlugs }?.let { jmsFusionRepository.bundledLogoUrl(it) }
				?: matches.firstNotNullOfOrNull { studio -> studio.itemImages[ImageType.PRIMARY]?.getUrl(api, maxHeight = 200) }
			StudioHub(name = name, studio = primary, studioIds = matches.map { it.id }, logoUrl = logo)
		}
	}
}

/** Home row with the studio hub tiles. */
class StudioHubsHomeRow(
	private val hubs: List<StudioHub>,
) : HomeFragmentRow, KoinComponent {
	private val navigationRepository by inject<NavigationRepository>()

	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) =
		insertInto(context, rowsAdapter, rowsAdapter.size())

	fun insertInto(context: Context, rowsAdapter: MutableObjectAdapter<Row>, index: Int) {
		if (hubs.isEmpty()) return
		val adapter = ArrayObjectAdapter(StudioHubPresenter())
		adapter.addAll(0, hubs)
		rowsAdapter.add(index.coerceIn(0, rowsAdapter.size()), ListRow(HeaderItem(context.getString(R.string.jms_studio_hubs)), adapter))
	}

	fun onItemClicked(itemViewHolder: Presenter.ViewHolder?, item: Any?, rowViewHolder: RowPresenter.ViewHolder?, row: Row?) {
		if (item !is StudioHub) return
		navigationRepository.navigate(Destinations.studioBrowser(item.studio, item.studioIds))
	}
}

/** Studio logo tile. */
class StudioHubPresenter : Presenter(), KoinComponent {
	private val imageLoader by inject<ImageLoader>()

	private class TileView(context: Context) : FrameLayout(context) {
		val logo = ImageView(context)
		val name = TextView(context)
		var request: Disposable? = null

		private val normalBackground = tileBackground(context, focused = false)
		private val focusedBackground = tileBackground(context, focused = true)

		init {
			isFocusable = true
			isFocusableInTouchMode = true
			layoutParams = ViewGroup.LayoutParams(dp(context, 200), dp(context, 112))
			background = normalBackground

			logo.scaleType = ImageView.ScaleType.FIT_CENTER
			val padding = dp(context, 22)
			logo.setPadding(padding, padding, padding, padding)
			addView(logo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

			name.setTextColor(Color.WHITE)
			name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
			name.setTypeface(name.typeface, Typeface.BOLD)
			name.gravity = Gravity.CENTER
			name.maxLines = 2
			name.ellipsize = TextUtils.TruncateAt.END
			val textPadding = dp(context, 12)
			name.setPadding(textPadding, 0, textPadding, 0)
			addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

			setOnFocusChangeListener { _, hasFocus -> background = if (hasFocus) focusedBackground else normalBackground }
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder = ViewHolder(TileView(parent.context))

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		val tile = viewHolder.view as TileView
		val hub = item as? StudioHub ?: return

		tile.contentDescription = hub.name
		tile.request?.dispose()
		tile.logo.setImageDrawable(null)
		tile.name.text = hub.name
		tile.name.alpha = 1f

		val url = hub.logoUrl ?: return
		tile.request = imageLoader.enqueue(
			ImageRequest.Builder(tile.context)
				.data(url)
				.target(onSuccess = { image ->
					tile.logo.setImageDrawable(image.asDrawable(tile.resources))
					tile.name.alpha = 0f
				})
				.build()
		)
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		val tile = viewHolder.view as TileView
		tile.request?.dispose()
		tile.logo.setImageDrawable(null)
	}
}

private fun dp(context: Context, value: Int) =
	TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).roundToInt()

private fun tileBackground(context: Context, focused: Boolean) = GradientDrawable(
	GradientDrawable.Orientation.TL_BR,
	if (focused) intArrayOf(Color.rgb(0x3A, 0x3F, 0x52), Color.rgb(0x22, 0x25, 0x33))
	else intArrayOf(Color.rgb(0x26, 0x29, 0x36), Color.rgb(0x15, 0x17, 0x20)),
).apply {
	cornerRadius = dp(context, 8).toFloat()
	if (focused) setStroke(dp(context, 2), Color.WHITE) else setStroke(dp(context, 1), Color.argb(40, 255, 255, 255))
}
