# WeRePosition architecture

## Runtime path

1. `MainActivity` owns the four main destinations and their horizontal transitions. Settings owns categorized subpages; the main navigation bar yields to a subpage until Back returns to Settings home. Map and scanner screens are temporary full-screen flows.
2. `MainViewModel` coordinates user actions and exposes `AppState`. It does not fabricate RF payloads.
3. `EnvironmentRfResolver` reads only captured or imported Room records in the exact target coverage cell.
4. `SyntheticRfGenerator` is an optional fallback for missing fixed-position RF data. It reverse-geocodes the WGS-84 target, selects a supported E.212 country profile, and returns in-memory JSON. Unknown countries and failed lookups return no generated RF. Generated data is never inserted into Room.
5. `SpoofingService` resolves the current coordinate and RF snapshot, then publishes it through `SpooferProvider` and runtime files. The provider accepts reads only from the app, Android system, and packages in the LSPosed scope.
   The floating joystick has a separate input overlay and moves a service-owned runtime anchor. Its 100 ms input loop, 250 ms service frame, and 500 ms system provider fallback do not update the selected point shown on the home map. Closing the joystick restores its starting anchor. Wi-Fi/cell simulation is paused for that mode and resumes from saved switches afterward.
   If its writer stalls, `system_server` keeps reporting the most recent trusted joystick coordinate at zero speed. A missing provider cache is also answered with a fresh synthetic fix. Only a valid stop generation or a new boot returns location handling to Android; an active run without any valid synthetic coordinate still blocks real reports.
6. `LocationHooker` loads the runtime snapshot in selected processes. Framework position reports are rewritten in `system_server`; client Wi-Fi and cell APIs are hooked only in selected apps. Runtime property callbacks may not wake another process, so `system_server` polls the compact frame every 250 ms during joystick mode and every second during other active modes. `SystemGnssStatusHook` rewrites `GnssStatusProvider` reports before listener delivery and provides periodic status while the real GNSS stream is quiet. The older client GNSS path remains as a compatibility fallback pending device validation. `SatelliteSkyGenerator` owns sky geometry, `GnssStatusFactory` constructs valid status objects, and `HookCompatibility` contains the libxposed adapter.

The per-app location allowlist is suspended. Returning null to an unselected app let Google Maps reuse a cached real coordinate, so every framework location client now receives the synthetic coordinate while simulation is active. The saved location selections are retained for a future safe design; they do not filter location today. Wi-Fi/cell selection through LSPosed remains separate and active. When Android's location master switch is off, the service still publishes the synthetic target and setting changes; framework delivery stays gated by the switch, and the target is ready before the switch is enabled again.

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
