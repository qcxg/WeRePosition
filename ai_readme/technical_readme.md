# WeRePosition Current Simulation Pipeline

Last maintained: 2026-11-11 (per-app isolation, joystick jitter, and settings/theme release)

This document is the current source of truth for how WeRePosition simulates GNSS, Wi-Fi, cellular, and related environment signals. It is written for future coding agents and developers so the implementation stays understandable and does not drift into hidden generated telemetry.

## Project Invariants

- The app must not use Android Developer Options mock-location app assignment as the primary spoofing mechanism.
- The app must not leave Android test providers or mock providers registered after stopping, rebooting, or service death.
- Only the system framework requires LSPosed/libxposed scope for ordinary location delivery. By default all clients receive the synthetic position; optional per-app isolation filters framework delivery and GNSS listeners by caller identity and delegated `WorkSource`. Play services and Fused Location must remain outside module scope.
- While spoofing is active, direct reads of real GNSS, Wi-Fi, and cell data should be blocked or replaced only for explicitly enabled simulation channels.
- When no trusted simulated Wi-Fi or cell data exists, return empty simulated data rather than inventing APs or towers.
- A missing or stale heartbeat during an active run keeps the last safe synthetic position or blocks delivery; it must not reveal a real fix. Only an explicit stop fence or boot cleanup restores pass-through.
- Generated environment records must not be stored in the local environment database or displayed as collected/API records.

## Runtime Layers

### Host App

Main package: `com.shiraka.wereposition`

Primary components:

- `MainViewModel.kt`: coordinates UI state, API fetches, database writes, in-memory anchors/options, refresh requests, and service controls. It does not publish runtime config files.
- `EnvironmentScanner.kt`: scans the current real environment for local Wi-Fi and cell data and stores it as user-collected data. It never starts a Bluetooth scan.
- `EnvironmentCoveragePolicy.kt`: owns the world-fixed Web Mercator hex grid and its 30 m circumradius cell IDs.
- `EnvironmentRfResolver.kt`: is the single Room-to-runtime RF resolver; its DAO pre-filter is followed by an exact target-cell comparison.
- `SpoofingService.kt`: owns movement output, refreshes RF for cell/database/request/material within-cell changes, keeps active state fresh, and is the sole continuous active-runtime writer.
- `LocationJitterEngine.kt`: owns process-local bounded coordinate drift, same-cell RF constraint, and reported accuracy for one runtime configuration snapshot per fix.
- `CellPayloadReader.kt`: parses client cell JSON and derives signal values; `NmeaSentenceFactory.kt` formats GNSS NMEA sentences. `SatelliteData.kt` is their shared satellite model.
- `ConfigManager.kt`: currently writes stop and cleanup snapshots; its old active-write branch has no callers and should be removed in a separately verified stop-path refactor. It is not a second heartbeat writer.
- `RuntimeConfigWriteCoordinator.kt`: serializes lifecycle and heartbeat commits, rejects stale generations, and advances the committed generation only after a write actually succeeds.
- `RootManager.kt`: owns the service-only persistent root shell used during active simulation; one acknowledged serialized shell replaces a new `su` process per movement tick and is closed on stop, location-off, or service destruction.
- `LocationHooker.kt`: libxposed module injected into scoped processes.

Debug/test apps that directly inspect Wi-Fi, cell, GNSS status, or NMEA must also be in the LSPosed scope. System framework scope is enough for many normal location consumers, but tools such as Cellular-Z and GPSTest read several APIs inside their own app process, so their package processes must be hooked too. Never scope Play services or Fused Location.

### Config Channels

Active spoofing state is written to:

- `/data/local/tmp/wereposition_config.json`
- `/data/system/wereposition_config.json`
- app SharedPreferences named `wereposition_prefs`
- system properties under `gsm.wrp.*`

JSON config files are written through a generation-tagged `.tmp` file followed by `mv` to the final path. Full snapshots are written on static/RF change and as a 60-second fallback, not once per movement tick. `RuntimeConfigWriteCoordinator` serializes in-process commits and advances only after an acknowledged write succeeds. Every root writer also holds `/data/local/tmp/wereposition_runtime.lock`, opened as a shell-function redirection so Android toybox `flock` inherits a valid fd. Generation ordering uses decimal-string comparison because Android shell arithmetic is not reliably 64-bit. An admitted old full snapshot therefore cannot finish after a successful stop snapshot and reactivate the runtime.

`gsm.wrp.frame` is the active hot path. It is one bounded property value containing protocol version, active state, the last successful full-snapshot generation, monotonic heartbeat, E7 coordinates, bearing, and speed. The floating joystick can update this frame about every 250 ms while visible; ordinary moving modes use the regular service cadence and a stationary run refreshes it every 10 seconds. The Hook validates the frame and, when its generation matches the cached JSON snapshot, clones/overlays memory without touching disk, ContentProvider, or preferences. A frame generation change is the signal to reload the full JSON snapshot.

`LocationHooker.readConfig()` reads only the expected path for its process: UID 1000 uses `/data/system`, other hooked clients use `/data/local/tmp`. It never probes both paths, and after the first permission denial that process permanently disables disk reads and uses the exported ContentProvider instead. XSharedPreferences is not queried by the runtime path. Rich Wi-Fi/cell JSON is never carried in legacy properties. The SystemProperties change callback is global, so it performs only compact property reads and equality checks; unrelated property changes do not trigger JSON parsing or I/O.

An inactive lifecycle write first publishes the monotonic `gsm.wrp.stop_generation` under a separate short-held lock, then publishes the inactive compact frame before it waits for the full runtime-file lock. Hook consumers reject every frame/JSON/property snapshot whose generation is missing or is less than or equal to that fence. Every active full-snapshot and position-frame writer re-checks both the fence and current snapshot generation after acquiring the runtime lock; a superseded service retires without publishing another tombstone. A later legitimate start does not clear the fence; its first successful full snapshot naturally has a newer generation. This keeps stop fail-open even if an orphaned old writer outlives the normal root-command timeout, while stale/equal stop replays cannot turn off a newer run.

Important config fields:

- `active`
- `fail_closed`
- `lat`, `lng`, `altitude`
- `sim_mode`, `sim_bearing`
- `wifi_json`
- `cell_json`
- `bluetooth_json`
- `mock_wifi`, `mock_cell`, `mock_bluetooth`
- `satellite_count`
- `location_allowlist_enabled`, `location_allowlist`
- `joystick_mode`, `enable_jitter`
- `jitter_radius_meters`
- `heartbeat_at`
- `heartbeat_uptime_ms`
- `config_generation`
- `gsm.wrp.stop_generation` (property-only lifecycle fence)
- `runtime_lifecycle_token`
- `boot_id`

Routine success and failure logs in hot hook/scanner paths use fixed-cardinality keys and at least one-minute throttling; repeated ContentProvider, Wi-Fi transport, cell-construction, NMEA, and scan failures should no longer dominate logcat during stable operation.

Collected environment records remain in Room/SQLite. The active Room flow/projection loads only rows with replayable Wi-Fi or cell relations; historical Bluetooth tables and management/import compatibility remain in the schema but are not materialized by runtime simulation. Map rendering, UI capability checks, and runtime resolution all use `EnvironmentCoveragePolicy`: a world-fixed Web Mercator hex grid with a 30 m circumradius. `EnvironmentRfResolver` queries a conservative DAO bound and then requires the stored row and target to have the exact same cell ID. Sampling density cannot enlarge coverage, and no distance-based fallback may borrow RF data across an edge.

Fixed-point, automatic route, and floating joystick movement share one output path. `MainViewModel` updates fixed-point and route options, but never writes runtime files. The joystick overlay passes direction and speed to `SpoofingService`; that service alone integrates joystick motion and publishes runtime output. RF replay and random position jitter are suspended while the floating joystick is visible; they resume from the user's saved switches after closing. The full runtime snapshot, property fallback, and local preference cache all publish `enable_jitter=false` while the joystick is active. Outside joystick mode, RF is re-resolved after an exact-cell change, a Room invalidation, an explicit refresh request, or material movement within a cell. Do not restore a second active-runtime writer in `MainViewModel`.

The start-spoofing dialog uses content height up to a screen-bounded maximum, then scrolls. Collapsed sections are removed from layout instead of reserving blank space or animating platform-window remeasurement. It stays visible after confirmation as a compact progress surface. Local exact-cell RF cache hits enable immediately; API misses identify WiGLE/OpenCellID as separate sources and offer either retry or "use current state" startup. User-enabled RF channels remain enabled even when their exact-cell payload is empty, in which case diagnostics show `block only` and hooks hide real RF data.

The legacy per-app coordinate selector was removed because it did not affect any active location hook. Runtime, storage, and external RF lookup coordinates now have one WGS-84 interpretation.

The hook layer checks compact-frame monotonic heartbeat and boot ID. A missing/stale active heartbeat retains a safe synthetic point or blocks reports until an explicit stop; a stale or mismatched boot config must not revive spoofing after reboot.

`SpooferProvider` exposes only the last heartbeat actually published by the host writer; a ContentProvider query must never renew liveness. Explicit stop clears the provider heartbeat/RF snapshot and publishes the compact inactive frame plus legacy `active=false` before filesystem work, so normal platform behavior resumes even if a later chmod/file write fails.

Start/API work is guarded by both a UI operation generation and a repository/service lifecycle token. Provider publication is serialized by a lifecycle mutex; stale START/STOP commands are rejected; first active state is not reported ready until the service has committed its first full snapshot. Config commits use a separate monotonic write generation and re-check `SpooferProvider.isActive`, so an older in-flight start, rollback, or frame cannot reactivate runtime after stop.

## GNSS Simulation

### Data Source

GNSS coordinates come from the selected WGS-84 target, the separate automatic route, or the service-owned floating joystick. Standard Android location is independent of Wi-Fi/cell payload availability. Wi-Fi and cell hooks are optional RF supplements; they never gate whether a normal `LocationManager` consumer can obtain a fix.

The host/UI coordinate, saved points, RF database, runtime `lat`/`lng`, GNSS generation, and Android framework fix are all WGS-84. A future GCJ-02 or BD-09 integration must convert only at that external map or data-source boundary.

### Authoritative Framework Path

There is exactly one owner for ordinary location cadence: the module instance loaded by `onSystemServerStarting`.

```text
SpoofingService fixed / automatic route / floating joystick output point
  -> system_server 1 Hz cadence
  -> current real AbstractLocationProvider.reportLocation(LocationResult)
  -> LocationProviderManager.onReportLocation() before-hook replaces or suppresses provider input
  -> one canonical WGS-84 LocationResult enters framework validation
  -> framework last-location cache
  -> interval / minDistance filtering
  -> permission, AppOps and coarse-location handling
  -> native listener / PendingIntent / Binder transport
  -> optional system_server per-app delivery filter
  -> selected ordinary LocationManager consumers and delegated GMS requests
```

The cadence calls the current real provider abstraction for `gps`, `network`, and `fused`. It does not create, register, remove, or disguise an Android test/mock provider. Passive location is updated by the framework itself.

`LocationProviderManager.onReportLocation()` is the first standard-location rewrite point on the current device path. Each provider report is rewritten before framework caching and delivery filtering; a failed rewrite suppresses the report. The downstream transport, `Location.writeToParcel()`, and public `Location` getters remain untouched.

This placement is deliberate:

- Late transport/Parcel rewriting makes the point accepted by `minUpdateDistance` differ from the point delivered to the app.
- Getter rewriting in `system_server` can recurse through `Location.equals()` and corrupt framework logic.
- Rewriting after coarse-location handling can overwrite Android's privacy reduction.
- Per-client callback timers do not populate framework provider state and cannot provide global location.

Do not reintroduce a `Map<Location, ...>` sample cache. Android 16 `Location.equals()` reads motion/location getters, and an equality-keyed `WeakHashMap<Location, ...>` previously caused a verified recursive `StackOverflowError` in `system_server`. There is no cross-object cache in the current path: a fresh sample is created once per provider report and then carried as ordinary raw fields.

### Behavior

- `LocationManager.requestLocationUpdates()`, `requestSingleUpdate()`, `getCurrentLocation()`, and `removeUpdates()` always proceed to the real framework. No client hook fulfils or swallows them.
- When the per-app mode is enabled, `LocationRegistration.acceptLocationChange` blocks continuous delivery to unselected packages; one-shot registration and `getLastLocation` are filtered separately. `LocationRequest.WorkSource` identifies an app behind Play services' delegated requests. Unknown attribution is denied. The provider cache remains synthetic for every caller, so the filter cannot turn a blocked request into a real fix.
- Provider name stays equal to its manager (`gps`, `network`, or `fused`); RF payload availability does not rename a provider.
- A complete fix includes paired latitude/longitude, horizontal accuracy, optional altitude plus vertical accuracy, wall time, elapsed realtime, and motion fields. MSL altitude from an old real fix is removed. Speed/bearing and their accuracies are either set together for moving simulation or removed together for stationary simulation.
- Wall time is current; elapsed realtime is non-decreasing and never manufactured in the future. Drift cadence uses `SystemClock.elapsedRealtime()`, not wall clock, so network time corrections cannot freeze jitter.
- The location master switch remains authoritative for delivery, not lifecycle admission. Simulation may be armed while the switch is off; the foreground service stays asleep with no active runtime frame/RF output, then writes its first full snapshot automatically when the switch is enabled. Turning it off during a run pauses output and turning it back on resumes with a fresh full snapshot.
- While fixed spoofing is active, dragging the home map and leaving it idle updates the shared anchor. While the floating joystick is visible, home-map dragging never changes the runtime anchor or the selected home-map point. Closing the joystick restores its starting anchor.
- The foreground service is the single moving-output and active-runtime writer for fixed, route, and joystick modes. The floating joystick keeps moving coordinates fresh through the compact frame at up to about 250 ms intervals; the system provider fallback checks every 500 ms while this mode is visible. A stationary run refreshes its frame every 10 seconds, and full runtime JSON is rewritten only on content/RF changes or the 60-second safety fallback. It does not apply final random jitter before publication.
- Jitter is sampled only on the authoritative framework cadence. `jitter_radius_meters` is the requested roaming envelope, not only a distant clamp. Slow/medium/fast use different OU time scales and hold a coordinate sample for about 2.8 / 1.5 / 0.8 seconds, producing approximately 3 / 2 / 1-fix advancement at 1 Hz while timestamps remain fresh. When any RF switch is enabled, the final WGS-84 OU sample is bounded to the anchor's exact `EnvironmentCoveragePolicy` cell; this prevents a delivered coordinate from crossing into a cell whose RF payload was not resolved. With all RF switches off, the full configured drift envelope remains available. The location fields are written together, so paired latitude/longitude can never belong to different samples.
- `onSystemServerStarting` installs the framework hook groups once per PID. Later `onPackageReady` callbacks in that PID are ignored by the process installation guard.
- GMS receives ordinary framework reports through its normal registrations and is not injected by this module.

### Satellite and NMEA Supplements

- `GnssStatus`, legacy `GpsStatus`, and NMEA remain supplemental client-facing simulations; they are not the source or gate for ordinary LocationManager positions.
- GNSS status satellite count follows `satellite_count`; satellite azimuth/elevation/CN0 are generated from the active WGS-84 target coordinate, current time, and simplified GPS/GLONASS/BDS orbit geometry, then lightly jittered for stable realism.
- The generated GNSS status marks a realistic subset of the configured satellites as `usedInFix` so tools do not see only visible-but-unused satellites.
- GNSS status is exposed through public `GnssStatus` getter hooks (`getSatelliteCount`, `getSvid`, `getCn0DbHz`, elevation/azimuth, carrier/baseband CN0, and fix flags). Do not mutate `GnssStatus` private parallel arrays in callbacks; Android version field drift can make count and array lengths inconsistent and crash consumers such as Google Maps.
- NMEA `RMC`, `GGA`, `GLL`, `VTG`, `GSA`, and `GSV` sentences are rewritten and checksums recalculated. `GSA`/`GSV` use the same cached simulated satellite set as `GnssStatus`.
- When simulation is inactive or the Android location master switch is off, NMEA listener registration is passed through byte-for-byte and no Proxy or injector timer is created. A listener registered while inactive remains a real listener until its client registers again after simulation starts.
- If a listener Proxy was created during an active run and remains registered after stop, its normal NMEA callback path becomes a direct real-sentence pass-through: it does not poll config, rewrite the sentence, copy callback arguments, or use reflection.
- In non-infrastructure scoped client processes, NMEA listeners also receive an active injected burst shortly after registration and then once per second, so satellite views do not need to wait for slow hardware NMEA sentences before showing the simulated constellation. The system framework does not receive extra active NMEA timer events.
- `usedInFix` satellite selection is weighted by signal quality but not strictly the first sorted satellites; a rotating subset of lower-list satellites can participate in the fix.
- Hardware satellite visibility is suppressed or replaced through GNSS status and NMEA hooks. A system-server GNSS listener operation is blocked for unselected packages; client-side status and NMEA injectors also honor the location allowlist.
- AMap, Baidu, and Tencent SDK-specific deep hooks are disabled by default. The stable path is Android framework output; GMS and Fused Location consume it without module injection. Re-enabling per-SDK branches requires an explicit compatibility decision because those branches can conflict with the global provider chain and reintroduce app-specific instability.

### Restart and Reboot Semantics

- Installing a new APK does not reload already-injected LSPosed/libxposed code inside running target processes. A target process restart or device reboot is required before the newly installed hook code is authoritative.
- A release `MY_PACKAGE_REPLACED` sets `moduleRestartRequired`; repository starts are rejected until the next `BOOT_COMPLETED`. Debug replacement remains startable for host/service-only iteration, but a tester must still reboot explicitly whenever hook bytecode changed. This prevents production host writers from running against old resident hook code without blocking the normal debug loop.
- Active runtime configs include `boot_id`; after reboot, previous-boot active configs are intentionally disabled instead of resurrected. Spoofing must be started again so the foreground service writes a fresh same-boot heartbeat/config.
- `RuntimeCleanupReceiver` marks runtime config inactive on `MY_PACKAGE_REPLACED` and `BOOT_COMPLETED`. This prevents an old `active=true` file with no foreground service heartbeat from leaving system/client hooks stuck in fail-closed before the user opens the app again.

### Current Risk Notes

- GNSS object compatibility depends on Android version. Prefer public getter hooks and avoid private field mutation.
- The provider cadence and `onReportLocation` hook have been checked on the connected API 37 device. Other framework versions need their provider-manager method order verified before deployment.
- The current stable path does not rewrite magnetometer data or force `Location` bearing APIs in fixed spoofing mode. Compass calibration, magnetic-field accuracy, and app-side compass behavior remain handled by the system/physical sensor stack. Route, joystick, and moving simulation modes can still expose course-over-ground through `Location` speed/bearing.
- The unused manual route flag and the unimplemented road-routing toggle were removed. Automatic out-and-back routes remain separate from the floating joystick. Device validation is still required after installing new hook bytecode in already running target processes.

## Wi-Fi Simulation

### Trusted Wi-Fi Sources

Replayable real Wi-Fi data comes from:

- Real local scans from `EnvironmentScanner.scanWifi()`.
- Previously stored local environment records from Room.
- WiGLE API records returned by `WigleClient.fetchWifiData()`.

`WigleClient.kt` now returns an empty JSON array when:

- the token is blank,
- the request fails,
- the body is missing,
- the API returns no results,
- a result lacks a BSSID/netid.

It no longer generates random SSIDs or vendor-looking BSSIDs.

### Local Wi-Fi Storage

`saveEnvironmentData()` stores:

- connected Wi-Fi as `LocationConnectedWifi`,
- nearby scan results as `WifiDevice` plus `LocationWifi`,
- RSSI and frequency/channel/capability metadata when available.

`EnvironmentRfResolver` builds the payload only from stored records in the target's exact `EnvironmentCoveragePolicy` cell. `locationToJson()` may interpolate RSSI among those same-cell records but may not include a record from another cell.

### API Wi-Fi Import

`fetchWifiFromWigleSync()` queries WiGLE with the app's WGS-84 coordinate directly. It stores WiGLE APs as environment data when returned.

On start spoofing, WiGLE is queried only when the exact target cell has no trusted local Wi-Fi payload; once imported, later runs resolve the Room/SQLite row through the same cell policy.

Important distinction:

- WiGLE supplies BSSID/SSID identity.
- The app still normalizes Android object fields such as RSSI, frequency, capabilities, network id, and Wi-Fi standard so hooked Android APIs can return structurally valid `WifiInfo` and `ScanResult` objects.
- This normalization is not treated as collected RF truth.

### Hook Path

`LocationHooker.kt` hooks Wi-Fi APIs including:

- `WifiManager.getConnectionInfo`
- `WifiManager.getScanResults`
- scan listener/callback result paths
- related Wi-Fi scan result structures where available

When active and `mock_wifi=true`:

- connection info is built from `connectedWifi` when available,
- `wifi_connection_mode=FIXED` keeps the first/connected AP, while `RANDOM` chooses deterministically when a stable session timestamp is available,
- the environment-data dialog keeps one de-duplicated Wi-Fi list; short press expands details and long press switches `connectedWifi`,
- otherwise another AP from the same exact-cell payload may be used as the visible connection anchor for compatibility,
- scan results are built from `nearbyWifi`,
- if no simulated APs exist, hooks return empty Wi-Fi data rather than exposing the real scan list,
- the hook must not generate coordinate-derived Wi-Fi APs such as `WIFI_Nearby_*`.

### App-Side Diagnostics Standard

The app exposes RF chain diagnostics in the environment-data dialog. The state labels mean:

- `ready`: spoofing is enabled and trusted payload exists. Wi-Fi hooks should return simulated `WifiInfo`, scan results, and scan callbacks.
- `block only`: spoofing is enabled but trusted payload is empty. Real Wi-Fi is still hidden; hooks should return empty simulated scan data.
- `off`: Wi-Fi spoofing is disabled for this run.
- `loading`: the app is fetching or rebuilding Wi-Fi payload.

This is an app-side readiness check. Full confirmation that a target process consumed the hook still comes from logcat hook logs.

## Cellular Simulation

### Trusted Cell Sources

Replayable real cell data comes from:

- Real local scans from `EnvironmentScanner.scanCell()`.
- Previously stored local environment records from Room.
- OpenCellID API records returned by `OpenCellIdClient.fetchCellData()`.

`OpenCellIdClient.kt` already returns an empty JSON array when:

- the token is blank,
- no cells are found,
- returned cells cannot be normalized,
- the request fails.

### Explicit Synthetic RF And Removed Hidden Fallbacks

The following paths are intentionally removed or disabled:

- coordinate-derived deterministic LTE fallback towers,
- synthetic LTE primary cell prepended to non-LTE OpenCellID results,
- generated LAC/CID fallback for `getCellLocation()` when `cell_json` is empty.

If `cell_json` is empty while active and `mock_cell=true`, the hook returns empty or null cell structures. This keeps real base stations blocked without creating hidden fake towers.

An opt-in setting may generate fictional country-matched Wi-Fi and cell JSON through `SyntheticRfGenerator` when no local RF exists. The generated snapshot is in memory for the simulation cycle, is labelled as simulation in the UI, and is never saved to the Room collection. This is separate from the removed hidden coordinate-derived fallbacks above.

### Local Cell Storage

`saveEnvironmentData()` stores normalized cell identity and signal fields:

- radio type,
- MCC/MNC,
- TAC/CI for LTE/NR,
- LAC/CID/PSC for GSM/WCDMA,
- NR NCI when available,
- CDMA fields when available,
- dBm and registered state.

`locationToJson()` interpolates signal strength only among the resolved exact-cell records and emits `cell_json`.

OpenCellID follows the same one-time supplement rule as Wi-Fi: the API is queried only when the exact target cell has no trusted local cell payload, then the service resolves the cached row through that same cell before publishing `cell_json` to Telephony hooks.

### Hook Path

`LocationHooker.kt` hooks:

- `TelephonyManager.getAllCellInfo`
- `TelephonyManager.getCellLocation`
- `TelephonyManager.getNeighboringCellInfo`
- `TelephonyManager.listen`
- `TelephonyManager.requestCellInfoUpdate`
- `TelephonyManager.registerTelephonyCallback`
- service state and signal strength paths

When active and `mock_cell=true`:

- `getAllCellInfo` returns `CellInfo*` objects built only from `cell_json`.
- RSRP/RSRQ/SINR are normalized into realistic ranges and can apply slow bounded signal jitter through `signal_jitter_enabled` and `signal_jitter_level`.
- `getCellLocation` returns `null` if no valid first cell exists.
- listeners/callbacks receive simulated cell events only when valid data exists.
- `listen()` and callback registration always proceed with the caller's original flags. Delivery-time hooks re-check active/location/channel state, so registration-before-start works and inactive/location-off/channel-off never changes normal telephony behavior.
- `requestCellInfoUpdate()` avoids a real modem refresh only while Cell simulation is active and always completes the accepted callback contract, including a stop race.
- Registration-failed, barring-info, physical-channel, voice-network-type, CellInfo, CellLocation, ServiceState, and SignalStrength paths are covered. Empty or incomplete payloads return neutral/empty results rather than leaking a real identity or inventing LTE/`-70 dBm` defaults.

### App-Side Diagnostics Standard

The app uses the same RF chain diagnostic state labels for cellular:

- `ready`: spoofing is enabled and `cell_json` contains trusted tower records. Telephony APIs and callbacks should return simulated cells.
- `block only`: spoofing is enabled but `cell_json` is empty. Real towers are still hidden; hooks should return empty or null cell structures.
- `off`: cellular spoofing is disabled for this run.

As with Wi-Fi, this is a local readiness check; hook delivery is verified through logcat.

## Bluetooth Removal And Historical Compatibility

- The app requests no Bluetooth permissions, starts no BLE scan, exposes no start-time Bluetooth switch, and installs no Bluetooth Xposed hook.
- Runtime payloads keep only compatibility tombstones: `bluetooth_json=[]` and `mock_bluetooth=false`. No runtime consumer reads or simulates them.
- Existing Bluetooth Room entities and import/export fields are still present, but no runtime Bluetooth data is collected or spoofed. Older database versions are intentionally rebuilt rather than migrated.
- Runtime coverage SQL and `EnvironmentRfResolver` projections exclude Bluetooth-only rows and do not materialize Bluetooth relations.

## Connectivity Layer

The unused generic `ConnectivityManager`, `NetworkCapabilities`, `NetworkInfo`, and `NetworkInterface` hook helper has been removed. Wi-Fi and cellular simulation own only their explicit API surfaces; inactive mode and disabled RF channels do not rewrite generic connectivity state.

## Compass And Magnetic Field

The previous experimental magnetic-field hook is removed from the stable hook chain. Compass calibration and magnetic-field behavior are left to the physical sensor stack. Fixed spoofing does not force `Location.getBearing()` because that makes stationary targets appear to have an artificial near-zero course; moving modes expose course-over-ground from the simulated trajectory.

## Data Quality Rules

- Do not silently generate AP BSSIDs or cell identities. Fictional records are allowed only when the user explicitly enables synthetic RF, and must remain marked as fictional and separate from collected/API records.
- Do not silently convert “no data” into plausible-looking data when synthetic RF is disabled.
- Do not store fallback object-construction defaults as collected/API data.
- Prefer local collected data first because it is auditable by the user.
- Remote API imports must be labeled as `由 WiGLE 導入` / `由 OpenCellID 導入`, not reused as place names.
- The app UI groups environment records with the same coordinate into one visible location and shows RF source labels beside the Wi-Fi/cell counts.
- Nearby place names resolve through Google Places Nearby Search first, then Google Geocoding and Android `Geocoder`, with `WeRePosition_Debug` logs for success and failure.
- Recent locations are separate from saved favorites: the home screen shows a user-configurable number of used locations, while the top bookmark button opens saved favorites.
- Recent locations must be written only after the current coordinate's Wi-Fi/cell payload has been resolved; loading a saved/recent point re-evaluates local RF data for that coordinate.
- If Android object construction needs missing optional fields, use harmless structural defaults and keep identity fields from trusted data.

## Known Gaps

- Exact-cell RF coverage uses Web Mercator and is defined only for latitude `-85.05112878°..85.05112878°`. The coordinate UI still accepts the wider Android range; polar coordinates outside the grid do not guarantee exact-cell RF isolation or same-cell jitter constraint and should not be used with RF simulation.
- Phone/network-stack processes are deliberately not given client RF hooks. System UID and client hooks use their one known-readable JSON path; minimal properties are exceptional fallback only.
- If no local/API Wi-Fi or cell data exists for a spoofed coordinate, Maps may still report weak location signal because the module will correctly hide real RF data and return empty simulated RF data.
- OpenCellID coverage varies by country and coordinate. Empty results should be expected in some places.
- WiGLE data may contain APs without enough Android-specific fields; the app fills object-shape fields for API compatibility.
- GNSS status and NMEA coverage depends on Android version internals.

## Debugging Checklist

Local build/install paths are recorded in `ai_readme/README.md`; use them directly after context compaction.

Use logcat filters:

- `WeRePosition_Xposed`
- `OpenCellID`
- `WeRePosition_Debug`

Questions to answer from logs:

- Did `readConfig()` load JSON or only property fallback?
- Is the target package itself being hooked? For RF/GNSS test tools, logcat should include `Hooking package: <target package>` after the target app starts.
- Is `active=true` and heartbeat fresh?
- How many APs are present in `wifi_json.nearbyWifi`?
- How many cells are present in `cell_json`?
- Does `getAllCellInfo` log a non-zero fake cell count?
- Does `getScanResults returning fake AP count=N` log a non-zero fake AP count?
- Did an unexpected writer death leave a safe synthetic point or an empty result? It must not restore a real fix until an explicit stop. A normal stop should publish the inactive frame immediately.

## File Map

- `app/src/main/java/com/shiraka/wereposition/xposed/LocationHooker.kt`
- `app/src/main/java/com/shiraka/wereposition/provider/SpooferProvider.kt`
- `app/src/main/java/com/shiraka/wereposition/service/SpoofingService.kt`
- `app/src/main/java/com/shiraka/wereposition/data/repository/LocationRepository.kt`
- `app/src/main/java/com/shiraka/wereposition/receiver/RuntimeCleanupReceiver.kt`
- `app/src/main/java/com/shiraka/wereposition/utils/ConfigManager.kt`
- `app/src/main/java/com/shiraka/wereposition/utils/RuntimeConfigWriteCoordinator.kt`
- `app/src/main/java/com/shiraka/wereposition/utils/EnvironmentCoveragePolicy.kt`
- `app/src/main/java/com/shiraka/wereposition/utils/EnvironmentRfResolver.kt`
- `app/src/main/java/com/shiraka/wereposition/utils/MapCoverageHelper.kt`
- `app/src/main/java/com/shiraka/wereposition/data/db/EnvironmentDao.kt`
- `app/src/main/java/com/shiraka/wereposition/utils/EnvironmentScanner.kt`
- `app/src/main/java/com/shiraka/wereposition/utils/WigleClient.kt`
- `app/src/main/java/com/shiraka/wereposition/utils/OpenCellIdClient.kt`
- `app/src/main/java/com/shiraka/wereposition/viewmodel/MainViewModel.kt`
