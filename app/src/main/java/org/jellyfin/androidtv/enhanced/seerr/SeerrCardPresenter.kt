package org.jellyfin.androidtv.enhanced.seerr

import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.SeerrItem
import org.jellyfin.androidtv.enhanced.SeerrMediaStatus
import org.jellyfin.androidtv.ui.card.LegacyImageCardView

/**
 * Poster card for a Seerr search result. Uses the same card view as library items so rows look and scroll the same.
 */
class SeerrCardPresenter : Presenter() {
	companion object {
		private const val CARD_WIDTH = 100
		private const val CARD_HEIGHT = 150
	}

	inner class ViewHolder(
		val cardView: LegacyImageCardView,
	) : Presenter.ViewHolder(cardView) {
		fun bind(item: SeerrItem?) {
			cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)

			if (item == null) {
				cardView.mainImageView.setImageDrawable(null)
				return
			}

			cardView.setTitleText(item.title)
			cardView.setContentText(subtitle(cardView.context, item))
			cardView.mainImageView.load(
				url = item.posterUrl,
				placeholder = ContextCompat.getDrawable(cardView.context, R.drawable.tile_port_video),
				aspectRatio = CARD_WIDTH.toDouble() / CARD_HEIGHT,
			)
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val cardView = LegacyImageCardView(parent.context, true)
		cardView.isFocusable = true
		cardView.isFocusableInTouchMode = true
		// The library card opens an item menu on long press, Seerr results have no such menu
		cardView.setOnLongClickListener(null)
		cardView.isLongClickable = false

		val typedValue = TypedValue()
		parent.context.theme.resolveAttribute(R.attr.cardViewBackground, typedValue, true)
		@ColorInt val color = typedValue.data
		cardView.setBackgroundColor(color)

		return ViewHolder(cardView)
	}

	override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
		(viewHolder as? ViewHolder)?.bind(item as? SeerrItem)
	}

	override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
		(viewHolder as? ViewHolder)?.bind(null)
	}
}

fun SeerrItem.statusLabel(context: Context): String? = when (status) {
	SeerrMediaStatus.AVAILABLE -> context.getString(R.string.je_status_available)
	SeerrMediaStatus.PARTIALLY_AVAILABLE -> context.getString(R.string.je_status_partial)
	SeerrMediaStatus.PENDING -> context.getString(R.string.je_status_requested)
	SeerrMediaStatus.PROCESSING -> context.getString(R.string.je_status_processing)
	SeerrMediaStatus.BLOCKLISTED -> context.getString(R.string.je_status_blocklisted)
	else -> null
}

private fun subtitle(context: Context, item: SeerrItem): String = listOfNotNull(
	item.statusLabel(context) ?: context.getString(if (item.isMovie) R.string.je_type_movie else R.string.je_type_series),
	item.year,
).joinToString(" · ")
