# WeRePosition

System-level Android location and radio-environment simulation for testing apps on an authorized rooted device.

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-14%2B-green.svg)](https://developer.android.com)
[![Root](https://img.shields.io/badge/Root-KernelSU%20%7C%20Magisk%20%7C%20APatch-orange.svg)](https://kernelsu.org)
[![Xposed](https://img.shields.io/badge/Framework-LSPosed-purple.svg)](https://github.com/LSPosed/LSPosed)

[繁體中文](README.md) | [Changelog](CHANGELOG.md) | [Code audit](docs/CODE_AUDIT.md)

> This project uses only code obtained from an early public version of [HuangZhuoRui/LocationSpoofer](https://github.com/HuangZhuoRui/LocationSpoofer) as its starting point. The maintainer reviewed that source. WeRePosition is now independently maintained by qcxg and has no affiliation, collaboration, or service relationship with the original project or its current maintainers. The source and license attribution remain to accurately describe the code's history. The Android package is `com.shiraka.wereposition`.

## Design

Android developer-options mock locations can be identified through mock flags, AppOps, test providers, or inconsistent Wi-Fi/cell fingerprints. WeRePosition establishes an ordinary global location source inside the Android framework. Target-app hooks are optional supplements only when an app directly reads Wi-Fi, cellular, GNSS status, or NMEA APIs.

The project follows two rules:

- Android's developer-options mock-location assignment is not the runtime mechanism.
- Fictional Wi-Fi and cell data requires explicit opt-in and is labelled as simulated. Otherwise, enabled RF channels with no payload fail closed.

## Features

### Global framework location

- One `system_server` cadence reports through the current real provider abstraction.
- A complete canonical WGS-84 fix is created after provider validation and before framework cache, filtering, privacy, and native delivery.
- Ordinary `LocationManager` requests remain untouched. The module does not swallow requests, rewrite `Location` getters or parcels, or run per-client location timers.
- By default, GMS and ordinary apps receive the synthetic framework location through normal registrations. Optional per-app isolation lets selected apps receive new locations while unselected apps receive no new location or satellite reports. Play services stays outside module scope; Wi-Fi/cell uses a separate LSPosed scope.
- Fixed point, the independent floating joystick, and automatic out-and-back routes share the service runtime path. The joystick needs no route points, leaves the home map at its selected starting point, and restores that point when closed. See [joystick design and verification](ai_readme/joystick_refactor_plan.md).
- The joystick starts separately after location simulation is active. If location mode is off, the first tap explains the prerequisite and a second tap within five seconds starts both. Wi-Fi/cell simulation and location jitter pause while the joystick is visible and resume afterward. The overlay can lock its position or exit the entire simulation.
- Settings has one level of detail pages. Location simulation and App isolation and scope both open directly from Settings home. Custom tones color the Material 3 surfaces used by settings cards and floating navigation.

### Bounded natural drift

- Drift is sampled once by the authoritative framework cadence, while the foreground service keeps a stable base coordinate.
- The configurable radius is 1–80 m. Slow, medium, and fast use distinct time scales and advance the drift sample roughly every 3, 2, or 1 framework fixes respectively. When any RF switch is enabled, the final drift sample stays inside the resolved hex; with all RF switches off, the full configured envelope is available.
- Latitude, longitude, accuracy, altitude, motion fields, wall time, and elapsed realtime belong to one internally consistent sample.

### Coordinates

- Google Maps selection, manual input, routes, the collected database, WiGLE/OpenCellID queries, and Android framework `Location` all use WGS-84 without a second conversion during injection.
- A future GCJ-02 or BD-09 map or data source must convert only at its input or display boundary. The legacy per-app coordinate setting had no effect and has been removed; existing stored points are not shifted by guesswork.

### Trusted RF replay

Replayable real sources include:

- local Wi-Fi/cell collection;
- saved Room database records;
- live WiGLE Wi-Fi results;
- live OpenCellID cellular results.

An explicit setting can generate fictional Wi-Fi and cell data matched to the target country when replay data is missing. These records are not stored as collected data and are labelled as simulation in the UI.

When enabled, the module can replace Wi-Fi connection/scan callbacks and cellular info/location/callbacks. An enabled channel with no valid payload returns an empty or null simulated result instead of leaking real nearby data.

### GNSS and NMEA supplements

- Public `GnssStatus` values can expose a coherent GPS/GLONASS/BeiDou constellation and used-in-fix subset.
- RMC, GGA, GLL, VTG, GSA, and GSV sentences are rewritten with matching coordinates and checksums.
- These are client-facing supplements; they are not the source or gate for ordinary framework locations.
- The stable chain does not alter magnetometer data. Compass calibration remains the responsibility of the physical sensor and Android sensor stack.

### Local data tools

- Collect, inspect, edit, import, and export local environment records.
- Only records with replayable Wi-Fi or cellular relations appear live in world-fixed Web Mercator hexagons with a 30 m circumradius. Historical Bluetooth relations remain only for database and import/export compatibility.
- Map rendering, pre-start capability checks, and runtime RF resolution use the exact same `EnvironmentCoveragePolicy` cell ID. `EnvironmentRfResolver` and the DAO read only the target cell, so dense samples do not inflate coverage and neighbouring cells are never borrowed.
- Exact-cell RF hexagons are defined only within Web Mercator latitude `±85.05112878°`. Polar coordinates outside that range do not currently guarantee RF isolation or same-cell drift constraints and should not be used with RF simulation.
- Reuse exact-cell local RF data first, then optionally supplement it through WiGLE/OpenCellID. If an enabled channel still has no trusted payload in that cell, it remains enabled in `block only` mode instead of falling back to real RF.
- API settings include connection tests so an entered credential can be checked before relying on data acquisition.

## Runtime architecture

```text
WeRePosition app
  -> MainViewModel: update the anchor, control the service, present capability state
  -> Room RF-bearing flow -> world-fixed 30 m Web Mercator cells
  -> EnvironmentRfResolver / DAO: resolve the exact target cell
  -> SpoofingService: sole continuous active-runtime writer
  -> RuntimeConfigWriteCoordinator: serialize writes and reject stale revisions
  -> atomic runtime config + heartbeat + boot ID
  -> system_server 1 Hz cadence
  -> real provider abstraction
  -> framework validation
  -> one canonical WGS-84 LocationResult
  -> Android cache / filters / permissions / native transport
  -> per-app delivery filter (optional) -> selected apps and delegated GMS requests

Optional scoped app hooks
  -> Wi-Fi / Cell / GNSS status / NMEA supplements
```

The service owns runtime publication for fixed point, automatic route, and floating joystick movement. During joystick movement it publishes the compact position frame at up to about 250 ms intervals; ordinary fixed and route modes retain their existing cadence. `RuntimeConfigWriteCoordinator` serializes lifecycle and service writes so an older generation cannot overwrite newer state.

> Automatic routes remain a separate feature. The unused manual route flag and unimplemented road-routing toggle were removed. The floating joystick adds no second runtime writer or location hook.

Runtime configuration is written atomically through generation-tagged temporary files and rename. Hooked processes verify the heartbeat and boot ID; stale active state fails closed and a previous-boot config cannot silently reactivate spoofing.

## Requirements

- Android 14 (API 34) or later.
- KernelSU, Magisk, or APatch root.
- LSPosed/libxposed with the module enabled for the Android framework. Keep Google Play services and Fused Location outside the module scope.
- Add an individual app to scope only when its direct RF, GNSS-status, or NMEA reads must also be simulated.
- A Google Maps API key for map/search features; WiGLE and OpenCellID credentials are optional.

## Build

The app uses Kotlin, Jetpack Compose, Material 3, Room, Koin, Google Maps/Places, and LSPosed APIs.

Windows PowerShell:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

The APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install with:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

After changing module code or its scope, reboot so `system_server` and target processes load the same build.

## Usage

1. Install the APK and grant root access.
2. Enable the module for the Android framework in LSPosed; leave Google Play services and Fused Location out of scope.
3. Add only apps that need direct RF/GNSS/NMEA supplements, then reboot.
4. Select a target point in WeRePosition, then use fixed point, the floating joystick, or an automatic out-and-back route as needed.
5. Enable only the Wi-Fi, cellular, and drift options needed for the test.
6. Start simulation and verify the location/environment in the tested app.

## Safety boundary

- Use this tool only on devices and apps you own or are authorized to test.
- It does not guarantee bypass of any platform policy or risk-control system.
- Missing RF data is blocked by default. Fictional data is generated and labelled only after explicit opt-in.
- You are responsible for applicable law and service terms.

## License

[GNU General Public License v3.0](LICENSE)
