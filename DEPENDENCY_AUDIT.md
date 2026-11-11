# Dependency audit (2026-11-05)

This is a dated dependency snapshot. Releases through 2.0.2 did not update dependency versions; verify Maven metadata again before making version decisions. The "latest stable found" column remains the finding from 2026-11-05, not a current live version check.

Stable versions were checked against the published Maven metadata in [Google Maven](https://dl.google.com/dl/android/maven2/) and [Maven Central](https://repo.maven.apache.org/maven2/). A newer version is an upgrade candidate, not a compatibility guarantee. The current versions below reflect this rebrand and API 37 build migration.

| Dependency | Current | Latest stable found | Status |
| --- | ---: | ---: | --- |
| Android Gradle Plugin | 9.1.1 | 9.5.0 | Updated to the minimum version supporting API 37 |
| Kotlin / Compose compiler / serialization plugin | 2.2.10 | 2.4.21 | Updated with AGP 9.1.1 |
| KSP | 2.3.10 | 2.3.13 | Updated for Kotlin 2.2 and Room |
| Room | 2.8.5 | 2.8.5 | Updated to resolve the KSP2 `unexpected jvm signature V` error |
| AndroidX Core KTX | 1.13.1 | 1.19.1 | Candidate |
| AndroidX Lifecycle | 2.7.0 | 2.11.0 | Candidate |
| AndroidX Activity Compose | 1.9.0 | 1.13.0 | Candidate |
| AndroidX Compose BOM | 2024.05.00 | 2026.09.00 | Candidate |
| AndroidX AppCompat | 1.6.1 | 1.8.0 | Candidate |
| Koin Compose | 3.5.3 | 4.2.2 | Major upgrade; review API changes |
| libxposed API | 101.0.1 | 102.0.0 | Major upgrade; validate hooks on a device |
| libxposed service | 101.0.0 | 102.0.0 | Major upgrade; update with API artifact |
| OkHttp | 4.12.0 | 5.5.1 | Major upgrade; review API changes |
| Google Maps SDK | 20.0.0 | 20.1.0 | Candidate |
| Play Services Location | 21.2.0 | 21.4.0 | Candidate |
| Google Places SDK | 3.4.0 | 6.0.2 | Major upgrade; review API changes |
| Kotlinx Serialization JSON | 1.6.3 | 1.12.0 | Candidate |

The remaining candidates should be upgraded in small groups with a build and device check after each group. The Google Maps API key restrictions must be updated for the new package `com.shiraka.wereposition` and signing certificate before maps and Places can be verified on a device.
