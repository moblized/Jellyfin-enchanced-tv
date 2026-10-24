package org.jellyfin.androidtv.ui.preference.screen

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.EnhancedServerSettingsStore
import org.jellyfin.androidtv.enhanced.JellyfinEnhancedRepository
import org.jellyfin.androidtv.enhanced.jms.JmsFusionRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.preference.dsl.OptionsFragment
import org.jellyfin.androidtv.ui.preference.dsl.checkbox
import org.jellyfin.androidtv.ui.preference.dsl.info
import org.jellyfin.androidtv.ui.preference.dsl.optionsScreen
import org.jellyfin.preference.store.PreferenceStore
import org.koin.android.ext.android.inject

class EnhancedPreferencesScreen : OptionsFragment() {
	private val userPreferences: UserPreferences by inject()
	private val repository: JellyfinEnhancedRepository by inject()
	private val serverSettings by lazy { EnhancedServerSettingsStore(repository) }
	private val jmsFusionRepository: JmsFusionRepository by inject()
	// Usually cached already (home screen); otherwise a quick check with a short limit
	private val jmsStatus by lazy {
		jmsFusionRepository.currentStatus ?: runBlocking {
			withTimeoutOrNull(3.seconds) { runCatching { jmsFusionRepository.getStatus() }.getOrNull() }
		}
	}

	override val stores: Array<PreferenceStore<*, *>>
		get() = arrayOf(serverSettings)

	override val screen by optionsScreen {
		setTitle(R.string.je_name)

		val status = serverSettings.status

		category {
			setTitle(R.string.je_pref_status)

			info {
				title = getString(R.string.je_name)
				content = if (status?.available == true) {
					getString(R.string.je_pref_status_found, status?.version.orEmpty())
				} else {
					getString(R.string.je_not_available)
				}
			}
		}

		category {
			setTitle(R.string.je_pref_category_app)

			checkbox {
				setTitle(R.string.je_pref_seerr_search)
				setContent(R.string.je_pref_seerr_search_description)
				bind(userPreferences, UserPreferences.enhancedSeerrSearch)
			}

			checkbox {
				setTitle(R.string.je_pref_player_bookmarks)
				setContent(R.string.je_pref_player_bookmarks_description)
				bind(userPreferences, UserPreferences.enhancedPlayerBookmarks)
			}

			checkbox {
				setTitle(R.string.je_pref_details_button)
				setContent(R.string.je_pref_details_button_description)
				bind(userPreferences, UserPreferences.enhancedDetailsButton)
			}
		}

		category {
			setTitle(R.string.jms_pref_category)

			info {
				content = getString(
					if (jmsStatus?.installed == true) R.string.jms_pref_status_found
					else R.string.jms_pref_status_missing
				)
			}

			checkbox {
				setTitle(R.string.jms_pref_slider)
				setContent(R.string.jms_pref_slider_description)
				bind(userPreferences, UserPreferences.jmsHomeSlider)
			}

			checkbox {
				setTitle(R.string.jms_pref_studio_hubs)
				setContent(R.string.jms_pref_studio_hubs_description)
				bind(userPreferences, UserPreferences.jmsStudioHubs)
			}

			checkbox {
				setTitle(R.string.jms_pref_pause_screen)
				setContent(R.string.jms_pref_pause_screen_description)
				bind(userPreferences, UserPreferences.jmsPauseScreen)
			}
		}

		if (status?.available == true) {
			category {
				setTitle(R.string.je_pref_category_tags)

				if (status?.nativePosterTagsEnabled != true) {
					info {
						content = getString(R.string.je_pref_tags_master_off)
					}
				}

				checkbox {
					setTitle(R.string.je_pref_tags_native)
					bind(serverSettings, EnhancedServerSettingsStore.useNativePosterTags)
					depends { serverSettings.isLoaded }
				}

				checkbox {
					setTitle(R.string.je_pref_tags_quality)
					bind(serverSettings, EnhancedServerSettingsStore.qualityTagsEnabled)
					depends { serverSettings.isLoaded }
				}

				checkbox {
					setTitle(R.string.je_pref_tags_genre)
					bind(serverSettings, EnhancedServerSettingsStore.genreTagsEnabled)
					depends { serverSettings.isLoaded }
				}

				checkbox {
					setTitle(R.string.je_pref_tags_language)
					bind(serverSettings, EnhancedServerSettingsStore.languageTagsEnabled)
					depends { serverSettings.isLoaded }
				}

				checkbox {
					setTitle(R.string.je_pref_tags_rating)
					bind(serverSettings, EnhancedServerSettingsStore.ratingTagsEnabled)
					depends { serverSettings.isLoaded }
				}

				checkbox {
					setTitle(R.string.je_pref_tags_age)
					bind(serverSettings, EnhancedServerSettingsStore.ageRatingTagsEnabled)
					depends { serverSettings.isLoaded }
				}

				info {
					content = getString(R.string.je_pref_tags_note)
				}
			}
		}
	}
}
