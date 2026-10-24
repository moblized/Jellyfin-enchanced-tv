package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/**
 * Everything from one studio hub (JMS Fusion style): its movies and series, newest first.
 * A hub can cover several library studios, passed as [EXTRA_STUDIO_IDS].
 */
class StudioFragment : EnhancedBrowseFragment() {
	companion object {
		const val EXTRA_STUDIO_IDS = "studio_ids"
	}

	override fun setupQueries(rowLoader: RowLoader) {
		val studioIds = arguments?.getString(EXTRA_STUDIO_IDS)
			?.split(',')
			?.mapNotNull { it.trim().toUUIDOrNull() }
			?.takeIf { it.isNotEmpty() }
			?: listOf(mFolder.id)

		fun query(type: BaseItemKind) = GetItemsRequest(
			fields = ItemRepository.browseFields,
			studioIds = studioIds,
			includeItemTypes = setOf(type),
			recursive = true,
			sortBy = setOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME),
			sortOrder = setOf(SortOrder.DESCENDING),
			imageTypeLimit = 1,
		)

		mRows.add(BrowseRowDef(getString(R.string.lbl_movies), query(BaseItemKind.MOVIE), 60))
		mRows.add(BrowseRowDef(getString(R.string.lbl_tv_series), query(BaseItemKind.SERIES), 60))

		rowLoader.loadRows(mRows)
	}
}
