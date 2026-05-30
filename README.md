# DashCast DevTools

Android companion app to **DashCast**, dedicated to diagnostics, reverse
engineering and validation of the BYD projection stack (DiLink 3 / 4 / 5).

## Why a separate app?

DashCast must stay lean for end users. The system-level tooling (continuous
logcat sniffer, display / surface / service recon, cluster projection
validation) has its own logic, **does not depend on DashCast's internal
state**, and gets a real benefit from running independently:

- **Sniffer**: can keep capturing **even when DashCast crashes or is
  killed** — exactly when it's most interesting to observe.
- **Recon**: read-only probes of the BYD environment (displays, services,
  permissions, compat framework). No dependency on DashCast.
- **Fission test**: validates the projection stack (`auto_container` +
  `cmd display create-virtual-display` + MirrorDaemon) **in isolation**
  from DashCast — helps distinguish a DashCast bug from a ROM bug.

## Current status

| Module | Status |
|---|---|
| Gradle skeleton + `bydPlatform` signing | ✅ |
| `AdbClient` (slim, no Beta proxy) | ✅ |
| `AppLogger` | ✅ |
| **Sniffer** | ✅ full transplant from DashCast |
| Recon | ⏳ placeholder — TODO: transplant `Dl5ClusterReconRunner` |
| Fission | ⏳ placeholder — TODO: transplant `Dl5VdTestRunner` + `MirrorDaemon` |
| i18n × 12 locales | ✅ (FR + EN proper, others seeded from EN) |

## Build

```bash
cd DashCastDevTools
./gradlew assembleDebug
# → app/build/outputs/apk/debug/DashCastDevTools-v0.1.0-alpha-debug.apk
```

Signed with the same `platform.keystore` as DashCast (BYD signature
permissions granted).

## Transplant plan (next iterations)

1. **Recon**: copy `Platform.java` (DL3/DL5 detection) +
   `DiLink5TestRunner.java` (PASS/FAIL/WARN data model) +
   `Dl5ClusterReconRunner.java`. Adapt the UI: rows + Run All + Export.
2. **Fission**: copy `MirrorDaemon.java` + `Dl5VdTestRunner.java`. Adapt
   the UI: V01..V07 battery with interactive Yes/No dialogs.
3. Once validated in the car, **remove** the corresponding modules from
   DashCast (DiagActivity slimming pass).

## Conventions

- Root package: `com.dashcast.devtools`
- `applicationId`: `com.dashcast.devtools` (no collision with DashCast)
- Signing: `bydPlatform` (same keystore — BYD signature permissions)
- Min / target SDK: 28 / 29 (DL3 + DL5 compatible)
- Strings: 12 mandatory locales (FR + EN + 10 placeholders to refine)
