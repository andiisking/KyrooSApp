# Changelog

All notable changes to KyrooS will be documented in this file.

## [2.3.0] - 2026-03-12

### Added
- **Fps Monitor**
- **File Manager** (Beta)
- **Device Info**

## [2.1.0] - 2026-02-28

### Added
- **Game Mode Tweaks:** Added precise controls for Game Mode, allowing users to force custom FPS limits and downscale resolution per app using native Android commands.
- **Smart Cache Cleaner:** Added a tool to scan and automatically clean up large junk cache files exceeding a custom limit.
- **Action Notifications:** Added Toast notifications to all Tweak buttons so users know when a script has successfully run.

### Fixed
- **App List Lag:** Fixed severe freezing and stuttering when scrolling through the App List. Icons now load smoothly in the background.
- **UI & Animation Polish:** Fixed various UI component errors and added bouncy, organic animations throughout the app.

---

## [2.0.0] - 2026-02-25

### Added
- **Complete App Rewrite (Native UI):** Migrated completely from a WebView-based architecture to a 100% Native Android application using Kotlin and Jetpack Compose for superior performance and fluid UX.
- **Native Kotlin Engine:** System tuning logic is now handled entirely within the app (`Profile.kt`), generating precise shell scripts on the fly.
- **On-Demand System Profiles:** Introduced dynamically applied profiles (Balanced, Powersave, Gaming, Extreme) adjusting `activity_manager`, `app_standby`, `device_idle`, and `job_scheduler`.
- **Deep Telemetry Cleaner:** Added a one-time initial setup script that aggressively disables system loggers, media metrics, binder stats, and window manager tracing.

### Changed
- Config file (`kyroos.conf`) repurposed purely as a UI state memory rather than an active background engine trigger.
- Developer UI Profile updated with "KyrooS Developer" badge and modern UI enhancements.
- Notification alerts now show dynamically based on the applied profile.

### Removed
- Deprecated persistent background binary/engine (Sigma engine is completely removed).
- Removed `SigmaConfigScreen` and all associated background tracking dependencies.

---

## [1.0.1] - 2026-02-21

### Added
- Package name sanitization to remove duplicate entries
- Anti-duplication protection for ANGLE, Game, and Developer driver lists
- Per-line config file writing to prevent newline characters corruption
- refreshConfig() function to reload config on app resume
- visibilitychange event listener for automatic config refresh
- Config reload when app returns to foreground

### Fixed
- Need to turn off Play protect. 
- Duplicate package entries in angle_gl_driver_selection_pkgs
- Unwanted \n characters appearing in config file
- Invalid fa-apps icon replaced with fa-th-large
- Console.log statements removed for production
- Cannot kill sigma binary. 

### Changed
- Config file (`kyroos.conf`) repurposed purely as a UI state memory rather than an active background engine trigger.
- Developer UI Profile updated with "KyrooS Developer" badge and modern UI enhancements.
- Notification alerts now show dynamically based on the applied profile.

### Removed
- Deprecated persistent background binary/engine (Sigma engine is completely removed).
- Removed `SigmaConfigScreen` and all associated background tracking dependencies.

---

## [1.0.1] - 2026-02-21

### Added
- Package name sanitization to remove duplicate entries
- Anti-duplication protection for ANGLE, Game, and Developer driver lists
- Per-line config file writing to prevent newline characters corruption
- refreshConfig() function to reload config on app resume
- visibilitychange event listener for automatic config refresh
- Config reload when app returns to foreground

### Fixed
- Need to turn off Play protect. 
- Duplicate package entries in angle_gl_driver_selection_pkgs
- Unwanted \n characters appearing in config file
- Invalid fa-apps icon replaced with fa-th-large
- Console.log statements removed for production
- Cannot kill sigma binary. 
