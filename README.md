# Jellyfin Enhanced for Android TV

A fork of the official [Jellyfin for Android TV](https://github.com/jellyfin/jellyfin-androidtv) app (v0.19.10) with
the features of the [Jellyfin Enhanced](https://github.com/n00bcodr/Jellyfin-Enhanced) server plugin built in natively.

Jellyfin Enhanced normally works by injecting JavaScript into Jellyfin's web interface. The Android TV app has no web
interface, so the plugin's features can't run there. Instead of turning the app into a slow web view, this fork
rebuilds the features as native Android TV screens that talk to the plugin's own server API. Playback, browsing and
scrolling are untouched stock code.

It installs next to the official app (package `org.jellyfin.androidtv.enhanced`, shown as **Jellyfin Enhanced**).

## Features

| Feature | Where | How it works |
| --- | --- | --- |
| **Seerr search & request** | Search screen | A "Request with Seerr" row appears below your library results. Pick a movie or show to request it, in 4K too when your server allows it, choosing seasons for series. Requests go through the plugin, so the TV never needs to reach Seerr directly. |
| **Poster tags** | Every poster | Uses the plugin's *Native Poster Tags*: the server draws quality, genre, language, rating and age tags into the poster images, so the TV does no extra work while scrolling. Toggle tag groups in Settings → Jellyfin Enhanced. |
| **Bookmarks** | Player and details | A bookmark button in the player saves the current time or jumps to saved ones. The Enhanced button on a details screen lists bookmarks to play from or delete. Synced with the web UI. |
| **Reviews & rating** | Details | The Enhanced button shows TMDB reviews and reviews from people on your server, and lets you rate (½ to 5 stars) and write your own. |

### JMS Fusion style features

Native versions of three [JMS Fusion](https://github.com/G-grbz/Jellyfin-MonWUI-Plugin) features. They work without
the plugin (using its defaults); with JMS Fusion installed they follow the settings an admin published from its
settings page, plus each user's studio hub order and hidden hubs.

| Feature | Where | How it works |
| --- | --- | --- |
| **Home slider** | Top of home | Big rotating banner with backdrop, logo, year, rating, runtime, genres and plot. ◀ ▶ browse, OK opens details, hold OK or press play to start. Uses JMS Fusion's item query, item count, slide duration, manual list, "only unwatched" and "balance movies and series". It only rotates while on screen and preloads the next slide, so it costs nothing while you browse rows below. |
| **Studio hubs** | Home, below the slider | Studio logo tiles (Marvel, Pixar, Disney+, Netflix…) matched to your library's studios by name and alias, plus any hubs an admin added in JMS Fusion. Logos come from your server's JMS Fusion. Opens the studio's movies and series. |
| **Pause screen** | Player | After a video has been paused for 5 seconds without input, the controls give way to the title (logo), episode, metadata, plot and progress. Any key brings the controls back; play resumes. Honours JMS Fusion's show plot / metadata / logo / backdrop and minimum video length settings. |

JMS Fusion's JavaScript and the JavaScript Injector plugin can't run here: they modify Jellyfin's web page, and this
app is native. The JMS Fusion project is archived by its author.

Every feature can be switched off in **Settings → Jellyfin Enhanced**.

## Server setup

1. Install the [Jellyfin Enhanced](https://github.com/n00bcodr/Jellyfin-Enhanced) plugin on your Jellyfin server.
2. For Seerr: enable the Seerr integration in the plugin and link your Jellyfin user to Seerr.
3. For poster tags: in the plugin's **Display → Media Tags**, turn on **Enable Native Poster Tags (Experimental)**,
   then pick tag groups in the app's settings (or the web UI).
4. For reviews: turn on **Show TMDB Reviews** (needs a TMDB API key) and/or **Enable User Written Reviews**.

## Install

Every push to `main` builds a signed APK on GitHub Actions and publishes it as the
[latest release](../../releases/latest).

On the TV, the easiest route is the *Downloader* app: open
`https://github.com/moblized/jellyfin-enchanced-tv/releases/latest/download/jellyfin-enhanced-tv.apk`.

New builds install over the previous one. The signing key is generated on the first build and kept in this repository's
private Actions cache (never committed); a scheduled job refreshes it every few days so GitHub doesn't expire it. If
that cache is ever cleared, Android will refuse the update ("App not installed"): uninstall once, then install the new
build. Builds from before October 24, 2026 used a different key, so uninstall those once too.
To use your own key instead, add `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` repository secrets (key alias `enhanced`).

## Credits

- [Jellyfin for Android TV](https://github.com/jellyfin/jellyfin-androidtv), GPL-2.0
- [Jellyfin Enhanced](https://github.com/n00bcodr/Jellyfin-Enhanced) by n00bcodr
- [JMS Fusion / Jellyfin MonWUI](https://github.com/G-grbz/Jellyfin-MonWUI-Plugin) by G-grbz, slider concept by BobHasNoSoul

Not affiliated with the Jellyfin project.
