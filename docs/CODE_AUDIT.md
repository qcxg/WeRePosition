# Code audit — 2026-11-11

This is a maintenance snapshot of the tracked Android production source. Counts include physical lines, including blanks and comments, in `app/src/main/**/*.kt` and `*.java`; generated code, resources, and Gradle files are excluded.

| Area | Files | Lines |
| --- | ---: | ---: |
| UI | 14 | 6,292 |
| Xposed hooks | 7 | 5,757 |
| Utilities | 19 | 3,338 |
| ViewModel | 1 | 2,266 |
| Service | 2 | 1,589 |
| Data | 13 | 1,193 |
| Other | 5 | 827 |
| **Total** | **61** | **21,262** |

The four largest files are `LocationHooker.kt` (4,862 physical lines at this snapshot), `SpoofingScreen.kt`, `MainViewModel.kt`, and `SpoofingService.kt`; together they contain about half the production source. The codebase is moderate in size for an Android app, but its platform-hook and runtime-lifecycle complexity is high. File size alone is not a defect; the main maintenance risk is that unrelated responsibilities share mutable state and release-critical paths.

## Verified in this pass

- The jitter dialog stores radius and speed through `MainViewModel` / `SettingsRepository` and passes them to `LocationRepository`; `SpoofingService` publishes the full runtime JSON. The service had omitted the radius and speed from its system-property fallback, so that reduced source could silently use 30 m / medium after a refresh. The active writer now mirrors both properties, and one runtime snapshot drives coordinate drift and accuracy for each fix. Joystick mode still disables jitter.
- Extracted `LocationJitterEngine` from `LocationHooker`; it owns bounded drift, the RF-cell constraint, and horizontal accuracy. Its sample method serializes the mutable drift state across provider callbacks.
- The active runtime writer is `SpoofingService`. All three current `ConfigManager.saveConfig` call sites pass `active=false`, so its older active-write branch is unreachable in the current app. Its stop-generation fence remains safety critical; the branch should be removed in a focused stop-path refactor with reboot, replacement, and concurrent-writer verification.
- Missing or hidden SSIDs previously became patterned `WIFI_...` or `HOME_WIFI` names in client hooks. They now remain unknown. The opt-in country-matched synthetic RF generator still creates its own varied, simulation-cycle-stable names.
- Removed unused package-install permission, unused picture-in-picture activity declaration, an obsolete signing comment, redundant condition, and old module-name log prefixes.
- `testDebugUnitTest`, `lintRelease`, and `assembleDebug` passed locally. Lint reports 0 errors, 150 warnings, and 2 hints; many warnings concern locale-dependent coordinate formatting and intentionally reflected Android internals. The first Windows lint attempt hit an internal PSI exception, but a fresh stable run completed successfully.
- On Android 17 (API 37), the 2.0.1 Debug build published a 76 m / slow / enabled jitter setting to the runtime properties. Repeated `dumpsys location` GPS samples moved around the selected synthetic Hong Kong anchor while reporting horizontal accuracy; the original 30 m / slow setting was restored and simulation stopped afterward.

## Follow-up risks

1. `AppDatabase` registers explicit migrations only for 3→4, 6→7, and 7→8, then calls `fallbackToDestructiveMigration()`. Opening a database whose older schema lacks a migration path can erase collected local data. Reconstruct the missing historical schemas and add tested migrations before removing the fallback; do not change it blindly in a patch release.
2. `LocationHooker` still combines framework location, client Wi-Fi/cell, GNSS/NMEA, and runtime configuration. `MainViewModel` combines map state, API calls, collection, and simulation startup. Extract one area at a time while keeping process-local state and the active writer boundary explicit.
3. System GNSS rewriting and selected-app compatibility hooks coexist. This is intentional for current Android versions, but no automated device regression suite covers their overlap or per-app blocking. Preserve the tested behavior while separating them.
4. The new `LocationJitterEngineTest` covers radius, disabled-mode reset, and same-cell RF drift. No device instrumentation suite yet covers framework ordering, LSPosed scope, Pure mode, or live GNSS behavior. Add focused service-serialization tests and instrumentation checks where possible.
5. Several coordinate strings use `String.format` without an explicit locale. Lint flags this because decimal separators can differ by device language; use `Locale.US` or a numeric formatter at serialization/input boundaries before widening locale support.

Historical Bluetooth Room relations and JSON fields remain for stored-data/import compatibility. Their current `false`/empty runtime values are deliberate and should not be removed without a schema and format migration.
