# WeRePosition architecture

## Runtime path

1. `MainActivity` owns the four main destinations and their horizontal transitions. Settings has one level of categorized detail pages; App isolation and scope opens directly from Settings home. The main navigation bar yields to a detail page until Back returns to Settings home. Map and scanner screens are temporary full-screen flows.
2. `MainViewModel` coordinates user actions and exposes `AppState`. It does not fabricate RF payloads.
3. `EnvironmentRfResolver` reads only captured or imported Room records in the exact target coverage cell.
4. `SyntheticRfGenerator` is an optional fallback for missing fixed-position RF data. It reverse-geocodes the WGS-84 target, selects a supported E.212 country profile, and returns in-memory JSON. Unknown countries and failed lookups return no generated RF. Generated data is never inserted into Room.
5. `SpoofingService` resolves the current coordinate and RF snapshot, then publishes it through `SpooferProvider` and runtime files. The provider accepts reads only from the app, Android system, and packages in the LSPosed scope.
   The floating joystick has a separate input overlay and moves a service-owned runtime anchor. Its 100 ms input loop, 250 ms service frame, and 500 ms system provider fallback do not update the selected point shown on the home map. Closing the joystick restores its starting anchor. Wi-Fi/cell simulation and random position jitter pause for that mode and resume from saved switches afterward.
   If its writer stalls, `system_server` keeps reporting the most recent trusted joystick coordinate at zero speed. A missing provider cache is also answered with a fresh synthetic fix. Only a valid stop generation returns location handling to Android; an active run without any valid synthetic coordinate still blocks real reports. An active snapshot from a previous boot stays as a blocking fence until boot cleanup turns off the Pure mode location switch and commits the stop snapshot.
6. `LocationHooker` loads the runtime snapshot in selected processes. Framework position reports are rewritten in `system_server`; client Wi-Fi and cell APIs are hooked only in selected apps. `LocationJitterEngine` owns the process-local bounded drift and reported horizontal accuracy, using one config snapshot per fix. Runtime property callbacks may not wake another process, so `system_server` polls the compact frame every 250 ms during joystick mode and every second during other active modes. `SystemGnssStatusHook` rewrites `GnssStatusProvider` reports before listener delivery and provides periodic status while the real GNSS stream is quiet. The older client GNSS path remains as a compatibility fallback pending device validation. `SatelliteSkyGenerator` owns sky geometry, `GnssStatusFactory` constructs valid status objects, and `HookCompatibility` contains the libxposed adapter.

The optional per-app location allowlist is enforced in `system_server` after the provider cache has been rewritten with a synthetic fix. Existing continuous registrations are filtered at `LocationRegistration.acceptLocationChange`; one-shot registrations and `getLastLocation` are filtered separately. A Play services request carrying an app `WorkSource` is checked against that app, and GNSS listener operations are filtered by registration identity. When enabled, selected apps receive synthetic positions and unselected apps receive no new location or satellite reports. Play services and Fused Location remain outside module scope. Wi-Fi/cell selection through LSPosed remains separate. Device validation on Android 17 (API 37) found Google Maps received zero new locations while unselected, received synthetic Taipei fixes when selected, and stopped receiving new fixes when deselected; GPSTest showed zero locations and satellites while unselected. When Android's location master switch is off, the service still publishes the synthetic target and setting changes; framework delivery stays gated by the switch, and the target is ready before the switch is enabled again.

Pure mode writes and acknowledges the first active synthetic snapshot while the Android location master switch is still off. It enables the switch only after that snapshot is ready. Boot and package-replacement cleanup keep the active fence until the switch is off and the stop snapshot is committed. This closes two windows where a real fix could otherwise pass before synthetic delivery or after premature cleanup.

The jitter slider sets the actual bounded coordinate drift. Each generated location uses one runtime snapshot for its coordinate, speed profile, RF cell constraint, and reported accuracy. The service mirrors jitter radius and speed into system properties as well as the full JSON snapshot so the reduced property fallback retains the selected values. The simulation's horizontal `Location.accuracy` follows its typical bounded drift rather than the configured maximum jitter radius. The previously observed Google Maps camera-calibration warning no longer appeared after the device configuration was corrected; the framework accuracy and physical compass calibration remain separate signals.

## Data boundaries

- Room is the source for user-collected and imported RF data. Its coverage map never includes generated signals.
- Room location records have an optional group name. The local-data screen filters by group, moves selected records, and can rename or remove a group without changing the saved Wi-Fi/cell observations.
- `OpenCellNormalizer` validates imported cell identity and operator codes before database insertion. It never substitutes a default country's MCC.
- Synthetic RF is opt-in, marked as fictional in Settings and the active-status card, and used only to fill missing data. Real local RF data takes precedence. Recent location history stores only local RF, even when a generated snapshot is active.
- Location infrastructure processes remain outside client Wi-Fi/cell hooks. The LSPosed scope and provider check are separate controls: ordinary apps do not read the provider to receive framework location updates; only selected hook processes need its runtime configuration.

## Remaining decomposition

`LocationHooker` still owns several hook families and runtime configuration loading. Move one family at a time into dedicated collaborators with process-state ownership and regression coverage. `MainViewModel` still combines map, collection, and runtime start flows; extract those coordinators after their inputs and cancellation rules are made explicit.

## Map and overlay rendering

- The home map writes a new target only after an idle drag in fixed mode. It never writes a target while the floating joystick is visible.
- Coverage polygons are keyed by world cell and updated incrementally, so camera idle does not remove and recreate unchanged cells.
- The floating wheel and speed control are separate overlay windows. Their translucent surfaces do not request live cross-window blur, which otherwise forces repeated composition of underlying map and app surfaces. The speed control is a full-width filled capsule with no thumb.
- The automatic point-to-point route remains a separate legacy flow. A new route or fixed start closes any active floating joystick. The unused manual route flag and unimplemented road-routing toggle were removed.
