package org.jellyfin.androidtv.enhanced

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jellyfin.preference.Preference
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.migration.MigrationContext
import org.jellyfin.preference.store.AsyncPreferenceStore
import timber.log.Timber

/**
 * The user's personal Jellyfin Enhanced settings document (`settings.json` on the server), exposed as a preference
 * store so it can be edited from the settings screens. Only booleans are supported. Unknown properties are kept
 * untouched: the full document is read, changed keys are merged in and the whole document is written back.
 */
class EnhancedServerSettingsStore(
	private val repository: JellyfinEnhancedRepository,
) : AsyncPreferenceStore<Unit, Unit>() {
	companion object {
		// Native poster tags (drawn into poster images by the server for apps like this one)
		val useNativePosterTags = booleanPreference("UseNativePosterTags", true)
		val qualityTagsEnabled = booleanPreference("QualityTagsEnabled", false)
		val genreTagsEnabled = booleanPreference("GenreTagsEnabled", false)
		val languageTagsEnabled = booleanPreference("LanguageTagsEnabled", false)
		val ratingTagsEnabled = booleanPreference("RatingTagsEnabled", false)
		val ageRatingTagsEnabled = booleanPreference("AgeRatingTagsEnabled", false)
	}

	private var document: JsonObject? = null
	private val changes = mutableMapOf<String, JsonElement>()

	var status: JellyfinEnhancedStatus? = null
		private set

	val isLoaded get() = document != null

	override val shouldUpdate: Boolean
		get() = document == null

	override suspend fun update(): Boolean = try {
		val currentStatus = repository.getStatus(forceRefresh = true)
		status = currentStatus
		if (currentStatus.available) {
			document = repository.userSettings()
			changes.clear()
			true
		} else false
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Unable to load Jellyfin Enhanced user settings")
		false
	}

	override suspend fun commit(): Boolean {
		val current = document ?: return false
		if (changes.isEmpty()) return true

		return try {
			val merged = JsonObject(current + changes)
			repository.saveUserSettings(merged)
			document = merged
			changes.clear()
			true
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to save Jellyfin Enhanced user settings")
			false
		}
	}

	/** Use the casing the server already uses for a property, to never write the same property twice. */
	private fun resolveKey(key: String): String {
		val current = document ?: return key
		return current.keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: key
	}

	private fun value(key: String): JsonElement? {
		val resolved = resolveKey(key)
		return changes[resolved] ?: document?.get(resolved)
	}

	override fun getBool(key: String, defaultValue: Boolean): Boolean =
		(value(key) as? JsonPrimitive)?.booleanOrNull ?: defaultValue

	override fun setBool(key: String, value: Boolean) {
		changes[resolveKey(key)] = JsonPrimitive(value)
	}

	// Only booleans are edited from the app

	override fun getInt(key: String, defaultValue: Int) = defaultValue
	override fun getLong(key: String, defaultValue: Long) = defaultValue
	override fun getFloat(key: String, defaultValue: Float) = defaultValue
	override fun getString(key: String, defaultValue: String) = defaultValue
	override fun setInt(key: String, value: Int) = Unit
	override fun setLong(key: String, value: Long) = Unit
	override fun setFloat(key: String, value: Float) = Unit
	override fun setString(key: String, value: String) = Unit

	override fun <T : Any> delete(preference: Preference<T>) {
		changes.remove(resolveKey(preference.key))
	}

	override fun <T : Enum<T>> getEnum(preference: Preference<T>): T = preference.defaultValue

	override fun <V : Enum<V>> setEnum(preference: Preference<*>, value: Enum<V>) = Unit

	override fun runMigrations(body: MigrationContext<Unit, Unit>.() -> Unit) = Unit
}
