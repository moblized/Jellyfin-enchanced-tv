package org.jellyfin.androidtv.enhanced

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.getActivity
import java.util.Locale

/** Coroutine scope bound to the activity behind [this] context, so work stops when the screen goes away. */
fun Context.enhancedScope(): CoroutineScope =
	(getActivity() as? LifecycleOwner)?.lifecycleScope ?: MainScope()

fun Context.isActivityAlive(): Boolean {
	val activity = getActivity() ?: return true
	return !activity.isFinishing && !activity.isDestroyed
}

fun Context.enhancedToast(message: String) {
	Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** Turn a failed Jellyfin Enhanced call into a short, human message. */
fun Throwable.toEnhancedMessage(context: Context): String {
	val err = this as? JellyfinEnhancedException ?: return context.getString(R.string.je_error_generic, javaClass.simpleName)
	return when {
		err.code == "unlinked" || err.code == "blocked" -> context.getString(R.string.je_error_unlinked)
		err.code == "unreachable" || err.status == 502 -> context.getString(R.string.je_error_unreachable)
		err.code == "parental_block" -> context.getString(R.string.je_error_parental)
		err.code?.startsWith("no_") == true || err.status == 403 -> context.getString(R.string.je_error_permission)
		err.status == 409 -> context.getString(R.string.je_already_requested)
		err.status == 503 -> context.getString(R.string.je_error_not_configured)
		err.status == 0 -> context.getString(R.string.je_error_network)
		!err.message.isNullOrBlank() -> err.message!!
		else -> context.getString(R.string.je_error_generic, "HTTP ${err.status}")
	}
}

/** Format a playback position as h:mm:ss or m:ss. */
fun formatPosition(positionMs: Long): String {
	val totalSeconds = (positionMs / 1000).coerceAtLeast(0)
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	val seconds = totalSeconds % 60
	return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
	else String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/** Star rating such as "★★★½" for a 0.5 – 5 value. */
fun formatStars(rating: Double): String {
	val clamped = rating.coerceIn(0.0, 5.0)
	val full = clamped.toInt()
	val half = clamped - full >= 0.5
	return buildString {
		repeat(full) { append('★') }
		if (half) append('½')
		append(' ')
		append(if (clamped % 1.0 == 0.0) clamped.toInt().toString() else clamped.toString())
	}
}
