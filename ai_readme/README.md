# WeRePosition AI Notes

This folder is the maintained AI-facing technical memory for the project.

## Handoff For The Next Conversation

- Current source version is `2.0.3` (`versionCode=20003`). Onboarding/startup access, shared horizontal page transitions, and specified-connection testing are documented in `technical_readme.md`.
- Onboarding language/information/permissions/completion and Settings > Permissions now use the existing Settings slide/fade timing, with reverse back navigation. Android 17 transition screenshots confirmed intermediate horizontal positions. User settings and animation scale were restored after verification; do not reset onboarding for another user handoff unless requested.
- The independent classic-Bluetooth fixture passed on Android 17: bonded 5 -> 8 -> 5 -> 8 -> 5 across baseline/active/disabled/re-enabled/stopped, with A2DP and Headset connected lists 0 -> 1 -> 0 -> 1 -> 0. Metadata, state filtering and real-device preservation assertions passed. The first run correctly returned baseline data with the location master switch off; the fixture now temporarily enables that switch and restores it in `finally`.
- Scope preparation remains manual in LSPosed. The user authorized resuming Bluetooth verification; only the independent test client was temporarily selected, never the host app. The temporary scope and APK were removed after testing; the final LSPosed scope matches the original scope. See `tools/bluetooth-probe/README.md` for commands and evidence. The fixture emits count-only `WRP_BT_PROBE` logs and preserves full private JSON reports on-device.
- The most important invariant is that specified Wi-Fi must never expose real scan results; `ClientWifiHook` returns simulated nearby APs or an empty scan. The classic Bluetooth addition must leave other real devices visible.

## Current Source Of Truth

- `technical_readme.md` is the current source of truth for the GNSS, Wi-Fi, cellular, Bluetooth, config, lifecycle, and hook paths.

## Active Planning Documents

- `joystick_refactor_plan.md` records the joystick design, the completed separation from route mode, and remaining device checks.

Superseded investigation snapshots are intentionally removed after their durable constraints are merged into `technical_readme.md`. Resolve every implementation question against that document and the current code.

## Maintenance Rules

- Keep `technical_readme.md` updated whenever spoofing data flow or lifecycle behavior changes.
- Ordinary location has one authoritative system path: real provider abstraction -> framework pre-filter canonical fix -> native delivery. Do not add `Location` getter/Parcel/transport resampling or client callback timers.
- Never swallow `LocationManager` requests in client processes. The optional location allowlist is enforced on framework delivery and last-location reads in `system_server`; an unselected app must receive no new location or GNSS data, while the provider cache stays synthetic. The LSPosed Wi-Fi/cell scope is independent.
- Synthetic Wi-Fi and cell data is allowed only through the explicit opt-in setting and must be marked as fictional. Missing data otherwise remains blocked.
- Specified connected Wi-Fi changes only the selected AP; scans must still return simulated nearby APs or empty results. Specified classic Bluetooth is additive in selected apps and must leave other real devices visible. The two switches are independent of environment API credentials.
- If a fallback is needed for Android object construction, document it as object-shape normalization, not as collected/API data.
- Remove transient status notes once their durable content is merged into the current technical document.
- After a successful debug build, install the APK when a test device is connected and available. A build alone does not verify LSPosed hooks already loaded in other processes.

## Local Build And Deploy Environment

Use these paths directly after context compaction. Do not rediscover them unless they stop working.

- Java 21 runtime for Gradle:
  `C:\Program Files\Android\Android Studio\jbr`
- ADB executable:
  `C:\Users\shira\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Debug APK output:
  `C:\Users\shira\Documents\shirosproj\WeRePosition\app\build\outputs\apk\debug\app-debug.apk`

Clean debug build command:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat clean :app:assembleDebug --console=plain
```

Install command:

```powershell
& 'C:\Users\shira\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r 'C:\Users\shira\Documents\shirosproj\WeRePosition\app\build\outputs\apk\debug\app-debug.apk'
```

Default test device:

- `47050DLAQ001LE`
