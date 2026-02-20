# Changelog

All notable changes to KyrooS will be documented in this file.

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
