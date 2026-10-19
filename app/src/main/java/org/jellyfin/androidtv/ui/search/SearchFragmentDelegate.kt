package org.jellyfin.androidtv.ui.search

import android.content.Context
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.enhanced.JellyfinEnhancedRepository
import org.jellyfin.androidtv.enhanced.SeerrItem
import org.jellyfin.androidtv.enhanced.seerr.SeerrCardPresenter
import org.jellyfin.androidtv.enhanced.seerr.SeerrRequestFlow
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter

class SearchFragmentDelegate(
	private val context: Context,
	private val backgroundService: BackgroundService,
	private val itemLauncher: ItemLauncher,
	enhancedRepository: JellyfinEnhancedRepository,
) {
	val rowsAdapter = MutableObjectAdapter<Row>(CustomListRowPresenter())

	// Jellyfin Enhanced: Seerr results are kept in their own row which is always placed after the library rows
	private val seerrAdapter = ArrayObjectAdapter(SeerrCardPresenter())
	private val seerrRow = ListRow(HeaderItem(context.getString(R.string.je_seerr_row)), seerrAdapter)
	private val seerrRequestFlow = SeerrRequestFlow(context, enhancedRepository) { updated ->
		val index = (0 until seerrAdapter.size()).firstOrNull { i ->
			val existing = seerrAdapter.get(i) as? SeerrItem
			existing != null && existing.tmdbId == updated.tmdbId && existing.mediaType == updated.mediaType
		}
		if (index != null) seerrAdapter.replace(index, updated)
	}

	fun showResults(searchResultGroups: Collection<SearchResultGroup>) {
		rowsAdapter.clear()
		val adapters = mutableListOf<ItemRowAdapter>()
		for ((labelRes, baseItems) in searchResultGroups) {
			val adapter = ItemRowAdapter(
				context,
				baseItems.toList(),
				CardPresenter(),
				rowsAdapter,
				QueryType.Search
			).apply {
				setRow(ListRow(HeaderItem(context.getString(labelRes)), this))
			}
			adapters.add(adapter)
		}
		for (adapter in adapters) adapter.Retrieve()

		if (seerrAdapter.size() > 0) rowsAdapter.add(seerrRow)
	}

	fun showSeerrResults(items: List<SeerrItem>) {
		seerrAdapter.clear()
		if (items.isNotEmpty()) seerrAdapter.addAll(0, items)

		val index = rowsAdapter.indexOf(seerrRow)
		if (items.isEmpty() && index >= 0) rowsAdapter.removeAt(index)
		else if (items.isNotEmpty() && index < 0) rowsAdapter.add(seerrRow)
	}

	val onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
		if (item is SeerrItem) {
			seerrRequestFlow.start(item)
			return@OnItemViewClickedListener
		}

		if (item !is BaseRowItem) return@OnItemViewClickedListener
		row as ListRow
		val adapter = row.adapter as ItemRowAdapter
		itemLauncher.launch(item as BaseRowItem?, adapter, context)
	}

	val onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
		when (item) {
			is SeerrItem -> backgroundService.setBackgroundUrl(item.backdropUrl)
			is BaseRowItem -> {
				val baseItem = item.baseItem
				if (baseItem != null) backgroundService.setBackground(baseItem)
				else backgroundService.clearBackgrounds()
			}

			else -> backgroundService.clearBackgrounds()
		}
	}
}
