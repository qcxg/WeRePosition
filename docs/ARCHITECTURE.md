# WeRePosition architecture

## Runtime path

1. `MainActivity` owns the four main destinations. Map and scanner screens are temporary full-screen flows.
2. `MainViewModel` coordinates user actions and exposes `AppState`. It does not fabricate RF payloads.
3. `EnvironmentRfResolver` reads only captured or imported Room records in the exact target coverage cell.
4. `SyntheticRfGenerator` is an optional fallback for missing fixed-position RF data. It reverse-geocodes the WGS-84 target, selects a supported E.212 country profile, and returns in-memory JSON. Unknown countries and failed lookups return no generated RF. Generated data is never inserted into Room.
5. `SpoofingService` resolves the current coordinate and RF snapshot, then publishes it through `SpooferProvider` and runtime files. The provider accepts reads only from the app, Android system, and packages in the LSPosed scope.
6. `LocationHooker` loads the runtime snapshot in selected processes. Framework position reports are rewritten in `system_server`; client Wi-Fi and cell APIs are hooked only in selected apps. `SystemGnssStatusHook` rewrites `GnssStatusProvider` reports before listener delivery and provides periodic status while the real GNSS stream is quiet. The older client GNSS path remains as a compatibility fallback pending device validation. `SatelliteSkyGenerator` owns sky geometry, `GnssStatusFactory` constructs valid status objects, and `HookCompatibility` contains the libxposed adapter.

## Data boundaries

- Room is the source for user-collected and imported RF data. Its coverage map never includes generated signals.
- `OpenCellNormalizer` validates imported cell identity and operator codes before database insertion. It never substitutes a default country's MCC.
- Synthetic RF is opt-in, marked as fictional in Settings and the active-status card, and used only to fill missing data. Real local RF data takes precedence. Recent location history stores only local RF, even when a generated snapshot is active.
- Location infrastructure processes remain outside client Wi-Fi/cell hooks. The LSPosed scope and provider check are separate controls: ordinary apps do not read the provider to receive framework location updates; only selected hook processes need its runtime configuration.

## Remaining decomposition

`LocationHooker` still owns several hook families and runtime configuration loading. Move one family at a time into dedicated collaborators with process-state ownership and regression coverage. `MainViewModel` still combines map, collection, and runtime start flows; extract those coordinators after their inputs and cancellation rules are made explicit.
