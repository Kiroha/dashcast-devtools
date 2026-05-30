# DashCast Dev Tools — Handoff document

> **Purpose.** Pick up the codebase in a fresh VSCode window from a brand‑new
> folder (no DashCast / MyBYDApp / app_byd workspace) and continue development
> without re‑discovery. Read this file once end‑to‑end before touching code.

Current shipped version : **v0.3.1‑alpha** (commit `3103c6d` on `main`).
Repo : <https://github.com/Kiroha/dashcast-devtools>

---

## 1. What this project is (and isn't)

**DashCast Dev Tools** is a standalone Android app extracted from
[DashCast](https://github.com/Kiroha/dashcast) (a BYD infotainment companion
app). It bundles three reverse‑engineering tools that were previously hidden
inside DashCast's *Diagnostics* surface:

| Module    | Purpose                                                                 | Status      |
|-----------|-------------------------------------------------------------------------|-------------|
| Sniffer   | Continuous `logcat` + `dumpsys` capture into a single text file         | ✅ ported   |
| Recon     | Catalog of read‑only diagnostic shell probes (Platform / cluster / …)   | ⏳ placeholder |
| Fission   | Interactive battery of VirtualDisplay / mirror tests (V01..V07)         | ⏳ placeholder |

It is **completely independent** of MyBYDApp/DashCast :
- Its own Gradle project, own `applicationId` (`com.dashcast.devtools`)
- Own RSA ADB key (separate sandbox, see §6)
- Own OTA channel (GitHub releases on `Kiroha/dashcast-devtools`)
- Same BYD platform keystore (signature perms) — see §3

It is **not** a fork of DashCast. Do not copy DashCast source files in bulk:
only transplant the specific classes listed in §10 and adapt the package.

---

## 2. Cloning into a fresh workspace

```bash
# 1) Clone the public repo
git clone https://github.com/Kiroha/dashcast-devtools.git
cd dashcast-devtools

# 2) Restore the BYD platform keystore (NOT in the public repo — see §11)
mkdir -p app/keystore
cp /path/to/your/secure/backup/platform.keystore app/keystore/

# 3) Drop a local.properties pointing to your Android SDK
cat > local.properties <<EOF
sdk.dir=/home/<user>/Android/Sdk
EOF

# 4) Configure git push credentials (PAT scoped to repo:write)
git remote set-url origin https://Kiroha:<PAT>@github.com/Kiroha/dashcast-devtools.git
# or use a ~/.git-credentials line:
#   https://Kiroha:<PAT>@github.com

# 5) Build
./gradlew assembleDebug
# → app/build/outputs/apk/debug/DashCastDevTools-v<X.Y.Z>-debug.apk
```

If you only have a GitHub fine‑grained PAT, store it once in
`~/.git-credentials` so `git push` is non‑interactive. The release scripts in
§7 read it from there.

---

## 3. Build system facts

These are the load‑bearing settings — do not regress them without re‑reading
the rationale below.

| Setting                          | Value                       | Why                                                                                          |
|----------------------------------|-----------------------------|----------------------------------------------------------------------------------------------|
| Android Gradle Plugin            | **7.4.2**                   | Matches DashCast; AGP 8 requires JDK 17 and a Material 1.10+ migration we don't want yet.    |
| `compileSdkVersion`              | **33**                      | Needed for Material 3 attributes.                                                            |
| `minSdkVersion`                  | **28**                      | DiLink 3 (BYD Seal EU) is API 28.                                                            |
| `targetSdkVersion`               | **29**                      | DiLink 5 is API 32; targeting 29 keeps legacy storage + foreground service semantics simple. |
| `sourceCompatibility`            | **1.8**                     | dadb 1.2.7 + AndroidX appcompat 1.1.0.                                                       |
| `signingConfigs.bydPlatform`     | `platform.keystore`         | BYD ROM signature perms (INJECT_EVENTS, ACCESS_SURFACE_FLINGER, etc.). See §11.              |
| `applicationId`                  | `com.dashcast.devtools`     | Must NOT clash with DashCast (`com.byd.dashcast`).                                           |
| FileProvider authority           | `com.dashcast.devtools.fileprovider` | Used by the Sniffer "Share" intent.                                                  |
| Output filename                  | `DashCastDevTools-v${versionName}-${buildType}.apk` | Naming convention used by the OTA channel (regex `-(release\|debug)\.apk$`).    |

### 3.1 Kotlin stdlib pin (DO NOT REMOVE)

dadb 1.2.7 transitively pulls `kotlin-stdlib-jdk7/jdk8 1.6.0`, while AndroidX
appcompat pulls `kotlin-stdlib 1.8.x`. Without forcing a single lineage the
build fails with *"Duplicate class kotlin.collections.jdk8.CollectionsJDK8Kt"*.

```gradle
configurations.all {
    resolutionStrategy {
        force 'org.jetbrains.kotlin:kotlin-stdlib:1.8.10'
        force 'org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.10'
        force 'org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.10'
        force 'org.jetbrains.kotlin:kotlin-stdlib-common:1.8.10'
    }
}
```

### 3.2 `packagingOptions` excludes (DO NOT REMOVE)

Required because dadb's transitive junit jars duplicate `META-INF/LICENSE.md`
etc., which AGP 7.4 treats as a fatal merge error.

```gradle
packagingOptions {
    exclude 'META-INF/LICENSE.md'
    exclude 'META-INF/LICENSE.txt'
    exclude 'META-INF/LICENSE-notice.md'
    exclude 'META-INF/NOTICE.md'
    exclude 'META-INF/NOTICE.txt'
    exclude 'META-INF/DEPENDENCIES'
    exclude 'META-INF/*.kotlin_module'
}
```

---

## 4. Project layout

```
DashCastDevTools/
├── build.gradle              # AGP 7.4.2, repos
├── settings.gradle           # includes :app
├── gradle/wrapper/           # gradle 7.5
├── README.md                 # public README (EN)
├── HANDOFF.md                # this file
└── app/
    ├── build.gradle          # all the load-bearing settings (§3)
    ├── keystore/
    │   └── platform.keystore # NOT in public repo — see §11
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/dashcast/devtools/
        │   ├── MainActivity.java
        │   ├── common/
        │   │   ├── AdbClient.java            # dadb wrapper + retry + warm-up
        │   │   ├── AppLogger.java            # 3000-entry ring buffer
        │   │   ├── InstallResultReceiver.java# OTA PackageInstaller callback
        │   │   ├── OtaUi.java                # OTA dialog (Markdown render)
        │   │   └── UpdateChecker.java        # GitHub releases polling + DL
        │   ├── sniffer/SnifferActivity.java  # logcat + dumpsys capture
        │   ├── recon/ReconActivity.java      # placeholder
        │   └── fission/FissionActivity.java  # placeholder
        └── res/
            ├── layout/                       # activity_main, activity_sniffer, activity_placeholder
            ├── drawable/                     # ic_sniffer/recon/fission/more_vert/arrow_back, bg_nav_pill_active, app icon
            ├── values/                       # FR strings + colors (light) + themes
            ├── values-night/                 # colors (dark)
            ├── values-en/                    # EN strings
            ├── values-{de,es,it,ru,uk,be,kk,uz,tr,ar}/  # 10 locales seeded from EN
            └── xml/file_paths.xml            # FileProvider scope
```

---

## 5. Architecture in 1 minute

### 5.1 Activities

```
MainActivity  ──▶ SnifferActivity   (logcat/dumpsys)
              ──▶ ReconActivity     (placeholder)
              ──▶ FissionActivity   (placeholder)
```

`MainActivity.onCreate` does 3 things on a fresh launch (`savedInstanceState == null`) :

1. Render the **Material 3 NavRail** + 3 module cards (`activity_main.xml`).
2. Call `OtaUi.checkNow(this, notifyIfUpToDate=false)` → silently checks for
   a newer GitHub release.
3. Call `AdbClient.warmUp(this)` → opens a background `Dadb.create()` so the
   "Allow USB debugging?" popup appears **at app launch**, not when the user
   opens Sniffer. See §6.

### 5.2 Shell access

Everything goes through `common/AdbClient.java`. Local ADB on
`localhost:5555` via the [dadb](https://github.com/mobile-dev-inc/dadb)
library — same approach as DashCast.

Public API :

```java
AdbClient.warmUp(context);                     // background, surfaces popup
AdbClient.executeShell(context, "id -u");      // fire-and-forget
AdbClient.executeShellWithResult(context, cmd, new Callback() { … });
```

The `connect()` private method holds the retry loop (15 × 2 s) that gives
the user up to 30 s to tap **Always allow from this computer**.

### 5.3 OTA

```
UpdateChecker ─ HTTPS ─▶ api.github.com/repos/Kiroha/dashcast-devtools/releases
              ─ HTTPS ─▶ asset URL (redirect chain ≤ 5)
              ─ file ──▶ getExternalFilesDir()/devtools-update.apk
              ─ Intent ▶ PackageInstaller.Session ── commit() ──▶ InstallResultReceiver
```

`UpdateChecker.INCLUDE_PRERELEASES = true`. Version compare strips any
`-buildN` suffix from **both** sides before lexical comparison (avoids the
loop bug we hit in DashCast v1.2.38).

`InstallResultReceiver` uses `PendingIntent.FLAG_MUTABLE` (CRITICAL — with
`FLAG_IMMUTABLE` the system drops `EXTRA_STATUS` and the callback never
fires with the real status).

---

## 6. The ADB popup — common pitfall

A fresh install must show the system popup *"Allow USB debugging?"* once.
Three things had to align before it worked reliably (the v0.3.1‑alpha hotfix) :

1. **Warm‑up at app launch.** `AdbClient.warmUp()` is called from
   `MainActivity.onCreate` so the popup appears *immediately*, not when the
   user opens Sniffer minutes later.
2. **Retry loop.** Without the 15 × 2 s loop in `connect()`, the first
   `Dadb.create()` throws while the popup is still on screen. The user has
   no time to tap **Always allow**, so `adbd` never persists the key, the
   popup re‑appears next launch, and the actual feature (Sniffer) silently
   does nothing.
3. **Per‑app sandbox.** Each app has its **own** RSA key pair :
   - DashCast    → `/data/data/com.byd.dashcast/files/adb.key`
   - DevTools    → `/data/data/com.dashcast.devtools/files/adb.key`

   `adbd` appends each accepted pubkey to `/data/misc/adb/adb_keys`
   (append‑only). Having DashCast already authorised does **not** skip the
   popup for DevTools — but accepting once with *Always allow* is enough
   for all future launches until the user revokes from the car's dev
   settings.

### 6.1 Manual reset (useful for testing the popup flow again)

```bash
adb shell pm clear com.dashcast.devtools
# and as root (rooted ROM only):
adb shell "echo > /data/misc/adb/adb_keys"
adb shell stop adbd && adb shell start adbd
```

---

## 7. Release / publish workflow

Every shipped build follows this exact pattern.

### 7.1 Before building

Bump **both** `versionCode` (integer, monotonic) and `versionName` (semver
with `-alpha` / `-beta`) in `app/build.gradle`. Android will refuse to
install an APK whose `versionCode` ≤ the installed one (the OTA flow would
loop forever).

```gradle
defaultConfig {
    versionCode 5         // ⬅ +1
    versionName "0.4.0-alpha"
}
```

### 7.2 Build

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/DashCastDevTools-v0.4.0-alpha-debug.apk
```

For a stable `1.x.y` release use `assembleRelease` instead, and the OTA
channel will surface the `-release.apk` asset.

### 7.3 Commit + push + GitHub release (one‑shot)

The repo is on `Kiroha`. `gh` CLI is authenticated as `ccarrebdl` in this
workspace and returns HTTP 404 on Kiroha repos — **do not** use `gh release
create`. Use raw curl + PAT instead.

```bash
TOKEN=$(grep "Kiroha:" ~/.git-credentials | sed 's|https://Kiroha:||' | sed 's|@github.com||')

git add -A
git -c user.email="you@local" -c user.name="you" commit -m "<subject>

<body>"
git push "https://${TOKEN}@github.com/Kiroha/dashcast-devtools.git" main

# Create the release
RESP=$(curl -s -X POST \
  -H "Authorization: token ${TOKEN}" \
  -H "Accept: application/vnd.github.v3+json" \
  https://api.github.com/repos/Kiroha/dashcast-devtools/releases \
  -d '{
    "tag_name": "v0.4.0-alpha",
    "name": "v0.4.0-alpha — <title>",
    "body": "## Highlights\n- …",
    "prerelease": true,
    "target_commitish": "main"
  }')

# Upload the APK
UPLOAD_URL=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['upload_url'].split('{')[0])")
curl -s -X POST \
  -H "Authorization: token ${TOKEN}" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @app/build/outputs/apk/debug/DashCastDevTools-v0.4.0-alpha-debug.apk \
  "${UPLOAD_URL}?name=DashCastDevTools-v0.4.0-alpha-debug.apk"
```

The `UpdateChecker` will pick this release up on the next app launch.

---

## 8. UI conventions (Material 3)

The whole app uses Material 3 tokens; never hardcode colours in layouts.

| Token                          | Light                                   | Dark        |
|--------------------------------|-----------------------------------------|-------------|
| `md_primary`                   | `#1565C0`                               | adjusted    |
| `md_surface_container_low`     | NavRail / status bar background         | …           |
| `md_secondary_container`       | Active NavRail pill background          | …           |
| `md_on_surface_variant`        | Inactive NavRail icon/text              | …           |

Theme : `Theme.Material3.DayNight.NoActionBar` — every Activity must set its
own `MaterialToolbar` (id `@+id/toolbar`) with `app:navigationIcon="@drawable/ic_arrow_back"`
and a `setNavigationOnClickListener(v -> finish())`.

The NavRail in `activity_main.xml` is 96 dp wide. The active item gets a
20 dp‑radius pill via `bg_nav_pill_active.xml`. The cards on the right are
`MaterialCardView` (the actual feature gating) — both the nav item and the
card on the same row open the **same** Activity.

---

## 9. Internationalisation rules (12 locales)

| Folder            | Language | Source                          |
|-------------------|----------|---------------------------------|
| `values/`         | French   | Authoritative (project base)    |
| `values-en/`      | English  | Translated properly             |
| `values-de/`      | German   | Seeded from EN, untranslated    |
| `values-es/`      | Spanish  | Seeded from EN, untranslated    |
| `values-it/`      | Italian  | Seeded from EN, untranslated    |
| `values-ru/`      | Russian  | Seeded from EN, untranslated    |
| `values-uk/`      | Ukrainian| Seeded from EN, untranslated    |
| `values-be/`      | Belarusian| Seeded from EN, untranslated   |
| `values-kk/`      | Kazakh   | Seeded from EN, untranslated    |
| `values-uz/`      | Uzbek    | Seeded from EN, untranslated    |
| `values-tr/`      | Turkish  | Seeded from EN, untranslated    |
| `values-ar/`      | Arabic   | Seeded from EN, untranslated    |

**Rule (carried over from DashCast).** Every new user‑facing string MUST be
added to all 12 files in the same commit, using format args (`%1$s`,
`%2$d`) instead of concatenation. Sanity check :

```bash
for d in values values-{en,de,es,it,ru,uk,be,kk,uz,tr,ar}; do
  grep -oP 'name="\K[^"]+' app/src/main/res/$d/strings.xml | sort -u > /tmp/k_$d
done
for l in en de es it ru uk be kk uz tr ar; do
  echo "=== $l ==="
  comm -23 /tmp/k_values /tmp/k_values-$l
done
```

The output should be empty for every locale.

---

## 10. Roadmap — Recon & Fission transplant

Both modules are placeholders today. The source‑of‑truth code lives in
DashCast (`MyBYDApp/app/src/main/java/com/byd/dashcast/`) and needs a
package rename + a UI rewrite (DashCast's UI is tightly coupled to its
`DiagActivity`).

### 10.1 Recon

| Source (DashCast)                                              | Target                                                   |
|---------------------------------------------------------------|----------------------------------------------------------|
| `com.byd.dashcast.platform.Platform`                          | `com.dashcast.devtools.recon.Platform`                   |
| `com.byd.dashcast.dilink5.DiLink5TestRunner` (1340 LoC)       | `com.dashcast.devtools.recon.DiLink5TestRunner`          |
| `com.byd.dashcast.dilink5.Dl5ClusterReconRunner` (1357 LoC)   | `com.dashcast.devtools.recon.Dl5ClusterReconRunner`      |

UI to build : `MaterialToolbar` + `RecyclerView` of test rows
(PASS / FAIL / WARN / SKIPPED with coloured pills) + a "Run all" FAB and a
share‑to‑Telegram intent for the export.

### 10.2 Fission

| Source (DashCast)                                              | Target                                                   |
|---------------------------------------------------------------|----------------------------------------------------------|
| `com.byd.dashcast.daemon.MirrorDaemon` (474 LoC)              | `com.dashcast.devtools.fission.MirrorDaemon`             |
| `com.byd.dashcast.dilink5.Dl5VdTestRunner` (554 LoC)          | `com.dashcast.devtools.fission.Dl5VdTestRunner`          |

V01..V07 battery with interactive Yes/No `AlertDialog` prompts using a
`CountDownLatch.await(180, SECONDS)` pattern.

> ⚠️ **Gotcha (already burnt us in DashCast).**
> `CountDownLatch.await()` inside `synchronized(LOCK)` does **not** release
> the monitor (unlike `Object.wait()`). If the callback that calls
> `countDown()` tries to take `LOCK`, you deadlock until the 180 s timeout
> fires. Always split the `synchronized` block around the await.
> Signature in the logs : a *"received"* event at exactly 0 ms after the
> *"timeout"* line.

### 10.3 Sanity check patterns to grep before any sub‑release

Carry these over verbatim from DashCast :

- `MotionEvent.obtain(` → `recycle()` in `finally` ?
- `Parcel.obtain(` → `recycle()` in `finally` ?
- `Surface(` / `new Surface` → `release()` ?
- `InputStream` / `Cursor` / `Socket` → `try-with-resources` ?
- `registerReceiver` → `unregisterReceiver` in `onDestroy` ?
- `Handler.post*` → `removeCallbacksAndMessages` in `onDestroy` ?
- `runOnUiThread` after `onDestroy` → use a `mDestroyed` flag ;
  the `safeRunOnUi` helper in `SnifferActivity` is the reference.
- `PendingIntent` → `FLAG_IMMUTABLE` unless update is required.

---

## 11. Security note — the keystore

`app/keystore/platform.keystore` is currently **tracked in the public
repository**. It grants any APK signed with it the BYD signature
permissions (`INTERNAL_SYSTEM_WINDOW`, `INJECT_EVENTS`,
`ACCESS_SURFACE_FLINGER`, `INSTALL_PACKAGES` …). The key is leaked by
BYD's own debug ROMs and not a secret — this is acceptable for a research
tool, but worth knowing if the repo ever turns into something installed at
scale.

If you ever decide to remove it :

```bash
git rm app/keystore/platform.keystore
echo "app/keystore/" >> .gitignore
git commit -m "security: remove platform keystore from public repo"
# Note: the file remains in git history; rewriting history with
# git-filter-repo + force-push would be required for a real scrub.
```

---

## 12. Quick reference — known good commands

```bash
# Build debug APK
./gradlew assembleDebug

# Install on a connected device
adb install -r app/build/outputs/apk/debug/DashCastDevTools-v*-debug.apk

# Launch
adb shell am start -n com.dashcast.devtools/.MainActivity

# Tail app logs
adb logcat -v time AdbClient:V AppLogger:V Sniffer:V "*:S"

# Reset ADB popup state (test a fresh install)
adb shell pm clear com.dashcast.devtools

# Compare strings coverage across the 12 locales (see §9)

# Find the GitHub PAT for Kiroha
grep "Kiroha:" ~/.git-credentials | sed 's|https://Kiroha:||' | sed 's|@github.com||'
```

---

## 13. Open questions / things to confirm next session

1. Decide whether to remove `platform.keystore` from the public repo
   (see §11). Pending user direction.
2. Recon transplant scope — port the full DL5 catalogue, or only the
   read‑only probes (Platform / cluster bounds) ?
3. Fission transplant — keep `MirrorDaemon` as a hidden binary launched
   from `setsid` (same as DashCast) or rewrite as a regular bound Service ?
4. After porting Recon/Fission, decide on a `release` channel (drop the
   `-alpha` and ship a stable `1.0.0`).

---

## 14. Glossary

| Term       | Meaning                                                                     |
|------------|-----------------------------------------------------------------------------|
| DL2 / DL3 / DL5 | BYD DiLink versions (Android 8 / 9 / 12 ROM forks)                     |
| dadb       | Pure‑JVM ADB client library (`localhost:5555`)                              |
| BYD platform key | RSA keystore matching the BYD ROM's `platform` signature                |
| Sniffer    | Continuous logcat + dumpsys capture                                         |
| Recon      | Read‑only diagnostic probes (Platform, cluster bounds, services list, …)    |
| Fission    | Interactive VirtualDisplay / mirror test battery                            |
