package org.jellyfin.androidtv.ui.home.jms

import android.view.ViewGroup
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemDto

/** Leanback row holding the home slider. Always kept as the first row on the home screen. */
class HomeSliderRow(
	val items: List<BaseItemDto>,
	val durationMs: Long,
) : Row()

class HomeSliderPresenter : RowPresenter() {
	init {
		// No row header and no dimming: the slider draws everything itself
		headerPresenter = null
		selectEffectEnabled = false
	}

	override fun isUsingDefaultSelectEffect() = false

	override fun createRowViewHolder(parent: ViewGroup): ViewHolder = ViewHolder(HomeSliderView(parent.context))

	override fun onBindRowViewHolder(vh: ViewHolder, item: Any) {
		super.onBindRowViewHolder(vh, item)
		val row = item as? HomeSliderRow ?: return
		(vh.view as HomeSliderView).bind(row.items, row.durationMs)
	}

	override fun onUnbindRowViewHolder(vh: ViewHolder) {
		(vh.view as HomeSliderView).unbind()
		super.onUnbindRowViewHolder(vh)
	}
}

/**
 * Index at which rows that like to be "on top" (notifications, now playing) should be inserted, so they go below the
 * slider instead of above it.
 */
fun MutableObjectAdapter<Row>.topInsertIndex(): Int = if (size() > 0 && get(0) is HomeSliderRow) 1 else 0
