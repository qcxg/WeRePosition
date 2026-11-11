# Code audit — 2026-11-11

This is a maintenance snapshot of the tracked Android production source. Counts include physical lines, including blanks and comments, in `app/src/main/**/*.kt` and `*.java`; generated code, resources, and Gradle files are excluded.

| Area | Files | Lines |
| --- | ---: | ---: |
| UI | 16 | 6,792 |
| Xposed hooks | 12 | 6,055 |
| Utilities | 20 | 3,449 |
| ViewModel | 1 | 2,326 |
| Service | 2 | 1,608 |
| Data | 14 | 1,314 |
| Other | 5 | 822 |
| **Total** | **70** | **22,366** |

The four largest files are `LocationHooker.kt` (3,927 physical lines at this snapshot), `SpoofingScreen.kt`, `MainViewModel.kt`, and `SpoofingService.kt`; together they contain about half the production source. The codebase is moderate in size for an Android app, but its platform-hook and runtime-lifecycle complexity is high. File size alone is not a defect; the main maintenance risk is that unrelated responsibilities share mutable state and release-critical paths.

## Verified in this pass

- The jitter dialog stores radius and speed through `MainViewModel` / `SettingsRepository` and passes them to `LocationRepository`; `SpoofingService` publishes the full runtime JSON. The service had omitted the radius and speed from its system-property fallback, so that reduced source could silently use 30 m / medium after a refresh. The active writer now mirrors both properties, and one runtime snapshot drives coordinate drift and accuracy for each fix. Joystick mode still disables jitter.
- Extracted `LocationJitterEngine` from `LocationHooker`; it owns bounded drift, the RF-cell constraint, and horizontal accuracy. Its sample method serializes the mutable drift state across provider callbacks.
- Extracted `NmeaSentenceFactory` and `CellPayloadReader`; `SatelliteData` is now a shared top-level type. Hook installation and caller authorization remain in `LocationHooker`.
- Extracted the complete client Wi-Fi hook family into `ClientWifiHook`. Runtime configuration and scope policy remain supplied by `LocationHooker`; the existing empty-result block path is unchanged.
- Added independent specified connected Wi-Fi and classic Bluetooth switches. `SavedConnectionImporter` reads saved Wi-Fi network metadata through root `cmd wifi list-networks` and paired classic Bluetooth devices through the Android API. The active service publishes both selections in the runtime snapshot. `ClientWifiHook` replaces only the connected AP selection while retaining the existing simulated nearby list; `ClientBluetoothHook` adds selected devices to bonded/A2DP/Headset results without removing real devices.
- The dedicated settings page collapses each detailed form when disabled and opens an editable Bluetooth row when enabled. Device class and UUID fields keep in-progress text until save validation. The connected Android 17 device showed both collapsed and expanded states and returned saved Wi-Fi and paired-device import lists; the independent Bluetooth client later verified the hook output as detailed below.
- The app-access list places enabled apps first when the installed list arrives. Its location and RF selections now use compact toggle icons and keep their row order during taps.
- Removed explicit Room migrations. The current schema is opened directly; any older schema is dropped and recreated as requested because there is no user data to preserve. The database file remains named `environment_database`, and the current schema version remains 8.
- The active runtime writer is `SpoofingService`. All three current `ConfigManager.saveConfig` call sites pass `active=false`, so its older active-write branch is unreachable in the current app. Its stop-generation fence remains safety critical; the branch should be removed in a focused stop-path refactor with reboot, replacement, and concurrent-writer verification.
- Missing or hidden SSIDs previously became patterned `WIFI_...` or `HOME_WIFI` names in client hooks. They now remain unknown. The opt-in country-matched synthetic RF generator still creates its own varied, simulation-cycle-stable names.
- Removed unused package-install permission, unused picture-in-picture activity declaration, an obsolete signing comment, redundant condition, and old module-name log prefixes.
- `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed locally after the specified-connection changes. Existing lint warnings concern locale-dependent coordinate formatting, deprecated compatibility APIs, and intentionally reflected Android internals.
- On Android 17 (API 37), the 2.0.1 Debug build published a 76 m / slow / enabled jitter setting to the runtime properties. Repeated `dumpsys location` GPS samples moved around the selected synthetic Hong Kong anchor while reporting horizontal accuracy; the original 30 m / slow setting was restored and simulation stopped afterward.

## Startup follow-up — 2026-11-11

- Added versioned first-run information/consent and per-feature permissions, including optional overlay access. Removed launch-time permission requests and the obsolete blocking screens. Returning users render the main UI while access is checked; only verified missing access opens a themed recovery dialog.
- Android 17 checks passed for the consent gate, access setup, completion/cold relaunch without a module warning, and a recovery dialog after revoking precise location. Original permission grants were restored. New source compiles with existing deprecation warnings.
- Added an opt-in independent Bluetooth API probe and instrumentation scenario. The previous attempt stopped at an unapproved scope request. After the user authorized resuming, the independent client was selected manually. An initial assertion returned baseline devices while the system location master was off; temporarily enabling/restoring the switch fixed the fixture precondition. The scenario passed: bonded 5/8/5/8/5 and A2DP/Headset 0/1/0/1/0 through baseline/active/disabled/re-enabled/stopped, including metadata and real-device preservation checks. The host app was never scoped.
- Version 2.0.3 adds shared directional Settings slide/fade transitions to onboarding, completion/recovery and the settings permission page. The permission page previously returned before the settings animation container. Device screenshots confirmed horizontal intermediate states in both directions; original preferences and system animation scale were restored afterward.


## Follow-up risks

1. `LocationHooker` still combines framework location, client Wi-Fi/cell hook installation, GNSS callbacks, and runtime configuration. `MainViewModel` combines map state, API calls, collection, and simulation startup. Extract one area at a time while keeping process-local state and the active writer boundary explicit.
2. System GNSS rewriting and selected-app compatibility hooks coexist. This is intentional for current Android versions, but no automated device regression suite covers their overlap or per-app blocking. The classic Bluetooth hook now has a passing independent scoped-client API scenario on Android 17. Preserve the tested behavior while separating them.
3. The new `LocationJitterEngineTest` covers radius, disabled-mode reset, and same-cell RF drift. No device instrumentation suite yet covers framework ordering, LSPosed scope, Pure mode, or live GNSS behavior. Add focused service-serialization tests and instrumentation checks where possible.
4. Several coordinate strings use `String.format` without an explicit locale. Lint flags this because decimal separators can differ by device language; use `Locale.US` or a numeric formatter at serialization/input boundaries before widening locale support.

Historical Bluetooth Room relations and legacy `bluetooth_json` remain unused. The new additive connection feature has a separate `specified_bluetooth_json` payload and never turns on the legacy global Bluetooth-blocking switch. Old fields can be removed in a focused data-format cleanup without preserving schema upgrades.
