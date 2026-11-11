# WeRePosition AI Notes

This folder is the maintained AI-facing technical memory for the project.

## Handoff For The Next Conversation

- Current source version is `2.0.2`. The specified-connection feature and the Wi-Fi hook extraction are documented in `technical_readme.md`, `docs/ARCHITECTURE.md`, and `docs/CODE_AUDIT.md`.
- The new settings page was smoke-tested on the connected Android 17 device, including collapsed/expanded forms and saved Wi-Fi/paired Bluetooth import lists. A scoped third-party app read of the new classic Bluetooth hook is still pending.
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
