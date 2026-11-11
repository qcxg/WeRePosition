# Independent classic Bluetooth device fixture

This debug-only APK is a separate Android app/UID, not WeRePosition itself. It reads bonded devices and A2DP/Headset APIs; it never scans, pairs, connects or changes Bluetooth settings. It is excluded from normal builds and has no release variant.

Build explicitly from the repository root:

```powershell
.\gradlew.bat -PbluetoothProbe=true :bluetooth-probe:assembleDebug :app:assembleDebugAndroidTest
```

Install the host debug APK, instrumentation APK, and `tools/bluetooth-probe/build/outputs/apk/debug/bluetooth-probe-debug.apk` on a rooted test device. Enable Bluetooth and stop any existing simulation. In LSPosed, manually add **WRP Bluetooth Test** (`com.shiraka.wereposition.btprobe`) to WeRePosition's scope. The host app does not need to be scoped. The test never requests or changes module scope. Restart the test app after a scope change; a framework-hook change still requires rebooting.

Run explicitly with ADB:

```powershell
adb shell am instrument -w -r -e bluetoothProbe true -e class com.shiraka.wereposition.ClassicBluetoothRuntimeTest com.shiraka.wereposition.test/androidx.test.runner.AndroidJUnitRunner
```

The scenario captures an inactive baseline, starts a temporary fixed simulation with three fictional devices (A2DP-only, Headset-only, paired-only), and checks names, alias, class, UUIDs, bond/type, real-device preservation, profile lists and state filtering. It then checks disabling, re-enabling and stopping. Settings and the original location-master state are restored and simulation is stopped in `finally`. Reports stay in the host's private `files/bluetooth-*.json`; they contain real paired-device metadata, so do not commit them. Avoid connecting/disconnecting real devices during the test because the baseline must remain comparable.

After testing, manually remove the fixture from scope and uninstall its APK. No fixture is installed as part of a normal app build.

## Device evidence — 2026-11-11, WeRePosition 2.0.3

Passed on Android 17 using an independent client selected manually in LSPosed (the host app was never scoped):

| Phase | Bonded devices | A2DP connected | Headset connected |
| --- | ---: | ---: | ---: |
| Baseline | 5 | 0 | 0 |
| Active | 8 | 1 | 1 |
| Disabled | 5 | 0 | 0 |
| Re-enabled | 8 | 1 | 1 |
| Stopped | 5 | 0 | 0 |

Metadata, bonded/classic type, real-device preservation, connected-state filtering, per-device states and adapter profile states all passed. Full reports live in the host’s private files. The probe also emits phase/count summaries under `WRP_BT_PROBE`; inspect with `adb logcat -d -s WRP_BT_PROBE`.

The fixture temporarily enables the Android location master switch and restores its original state in `finally`. An initial run with that switch off returned the baseline five devices, as expected for paused simulation; the successful run verified active delivery. The fixture does not require the user to re-complete onboarding or change saved simulation settings permanently. Temporary scope and APK are removed after the test.
