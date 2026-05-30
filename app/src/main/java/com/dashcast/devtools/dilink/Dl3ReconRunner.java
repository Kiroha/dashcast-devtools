package com.dashcast.devtools.dilink;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dl3ReconRunner — 25 tests de reconnaissance pour la plateforme DiLink 3.
 *
 * Chaque test est labellisé [App] (Java API dans le contexte applicatif) ou
 * [ADB] (commande shell via AdbClient, qui s'exécute avec l'UID de l'appli)
 * afin de mettre en évidence les différences de visibilité entre les deux
 * contextes — information cruciale pour le développement de DashCast.
 *
 * Règle absolue : AUCUNE commande ne modifie le display 0 (écran 15.6").
 * Le seul test d'écriture (E01 VirtualDisplay) est entièrement reversible.
 *
 * Catégories :
 *   A — Enumération des displays (App vs ADB)
 *   B — Gestion des processus et activités (App vs ADB)
 *   C — Capacités API 29 (commandes disponibles)
 *   D — Taille/DPI/fenêtrage (lecture seule)
 *   E — Virtual Display (créé 1×1 et relâché immédiatement)
 *   F — Sondes spécifiques DashCast
 */
public final class Dl3ReconRunner {

    // ── Model ─────────────────────────────────────────────────────────────────

    public static final class TestDef {
        public final String id;
        public final String title;
        public final String description;
        TestDef(String id, String title, String description) {
            this.id = id; this.title = title; this.description = description;
        }
    }

    public enum Status { PENDING, RUNNING, PASS, FAIL, WARN, SKIPPED }

    public static final class TestResult {
        public final TestDef def;
        public volatile Status status;
        public volatile String message;
        public volatile long elapsedMs;
        TestResult(TestDef def) {
            this.def = def; this.status = Status.PENDING; this.message = ""; this.elapsedMs = 0;
        }
    }

    public interface Listener {
        void onSuiteStarted(List<TestResult> results);
        void onTestUpdated(int index, TestResult result);
        void onSuiteFinished(List<TestResult> results);
    }

    // ── Catalog ───────────────────────────────────────────────────────────────

    public static List<TestDef> catalog() {
        return Arrays.asList(
            // ── A — Display enumeration ────────────────────────────────────
            new TestDef("A01", "[App] Displays via DisplayManager",
                    "DisplayManager.getDisplays() — tous types. Montre ce que l'appli voit. "
                    + "WARN si un seul display (cluster absent ou non visible depuis l'UID appli)."),
            new TestDef("A02", "[ADB] Displays via dumpsys display",
                    "dumpsys display | grep mDisplayId/isPresentation/DisplayInfo — "
                    + "référentiel système. Comparer avec A01 pour détecter des displays "
                    + "cachés au contexte applicatif."),
            new TestDef("A03", "[App] Displays Presentation uniquement",
                    "DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION) — seuls les "
                    + "displays 'cluster' / HDMI. PASS si ≥1 (cluster branché), WARN sinon."),
            new TestDef("A04", "[ADB] SurfaceFlinger display list",
                    "dumpsys SurfaceFlinger | grep 'Display #\\|type=' — couche bas niveau "
                    + "(HDMI, VIRTUAL, etc.). Peut révéler des displays non remontés au Java."),

            // ── B — Process & activity management ─────────────────────────
            new TestDef("B01", "[App] Processus via ActivityManager",
                    "ActivityManager.getRunningAppProcesses() — liste limitée à ce que l'UID "
                    + "de l'appli peut voir. Permet de comparer avec B02."),
            new TestDef("B02", "[ADB] Activités via dumpsys activity",
                    "dumpsys activity | grep ActivityRecord — vue système complète. "
                    + "Compare la visibilité avec B01 pour mesurer la restriction."),
            new TestDef("B03", "[App] ActivityOptions.setLaunchDisplayId (réflexion)",
                    "Vérifie si la méthode est présente via Class.forName. Doit être PASS "
                    + "sur API ≥ 26 (DL3 = API 29). Indispensable pour lancer sur le cluster."),
            new TestDef("B04", "[ADB] am start --display flag",
                    "am start --help | grep -iE '--display|--stack' — confirme que la "
                    + "ROM DL3 expose bien le flag multi-display dans la commande am."),

            // ── C — API 29 command discovery ───────────────────────────────
            new TestDef("C01", "[App] PackageManager features display",
                    "hasSystemFeature : freeform_window_management, picture_in_picture, "
                    + "activities_on_secondary_displays. Détermine les capacités déclarées."),
            new TestDef("C02", "[ADB] wm — arbre de sous-commandes",
                    "wm (sans args) | head -25 — liste toutes les sous-commandes WindowManager "
                    + "shell disponibles sur ce build. Lecture seule."),
            new TestDef("C03", "[ADB] am display + am task",
                    "am display (sans args) puis am task (sans args) — documente les "
                    + "sous-commandes de gestion multi-display et de tâches disponibles."),
            new TestDef("C04", "[ADB] cmd services display/window",
                    "cmd -l | grep -iE 'display|window|surface|hardware' — services cmd "
                    + "liés à l'affichage accessibles via l'UID appli."),
            new TestDef("C05", "[ADB] Binder services display/surface",
                    "service list | grep -iE 'display|window|surface|SurfaceFlinger' — "
                    + "services Binder exposés. Révèle AutoContainer, etc."),

            // ── D — Resize / DPI / windowing (lecture seule) ───────────────
            new TestDef("D01", "[App] Taille + DPI courants (appli)",
                    "WindowManager.getDefaultDisplay().getRealSize/getRealMetrics — "
                    + "résolution et densité vus depuis le contexte appli (API Java)."),
            new TestDef("D02", "[ADB] wm size (lecture, tous displays)",
                    "wm size — lit la taille logique déclarée. JAMAIS wm size WxH "
                    + "(écriture interdite). Comparer avec D01."),
            new TestDef("D03", "[ADB] wm scaling (mode de mise à l'échelle)",
                    "wm scaling — mode actuel (auto/scale/stretchToFill/none). "
                    + "Utile pour comprendre comment les applis sont redimensionnées."),
            new TestDef("D04", "[ADB] am stack (gestion des piles, API 29)",
                    "am stack (sans args) — sous-commandes disponibles. "
                    + "Déprécié mais potentiellement présent sur certains builds DL3."),

            // ── E — Virtual Display ────────────────────────────────────────
            new TestDef("E01", "[App] Create VirtualDisplay 1×1 → release (réversible)",
                    "DisplayManager.createVirtualDisplay('dl3-recon-probe', 1, 1, 72, null, "
                    + "PRESENTATION|OWN_CONTENT_ONLY) immédiatement suivi de release(). "
                    + "Teste si l'UID appli peut créer un VD sans MediaProjection. "
                    + "100%% réversible — durée < 50 ms."),
            new TestDef("E02", "[ADB] Virtual displays existants",
                    "dumpsys display | grep -i 'virtual\\|VirtualDisplay' — "
                    + "liste les VirtualDisplays actifs au moment du test."),
            new TestDef("E03", "[ADB] wm create / wm dismiss (disponibilité)",
                    "wm create (sans args) et wm dismiss (sans args) — "
                    + "vérifie si ces sous-commandes existent dans ce build. "
                    + "Lecture seule : sans arguments la commande affiche l'usage."),

            // ── F — DashCast probes ────────────────────────────────────────
            new TestDef("F01", "[ADB] AutoContainer / auto_container service",
                    "service call AutoContainer 1 et service call auto_container 1 — "
                    + "sonde de présence du pont cluster BYD. Code 1 = INTERFACE_TRANSACTION "
                    + "(lit le descripteur, n'exécute aucune action)."),
            new TestDef("F02", "[ADB] Focus fenêtre courant (lecture)",
                    "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' — "
                    + "identifie l'activité au premier plan. Utile pour déboguer les "
                    + "conflits de focus avec le cluster DashCast."),
            new TestDef("F03", "[ADB] Infos hardware DL3",
                    "getprop | grep -E 'ro.product.model|ro.build.version.sdk|"
                    + "ro.hardware|ro.board.platform' — empreinte matérielle du véhicule."),
            new TestDef("F04", "[App] Packages launchables (candidats multi-display)",
                    "PackageManager.queryIntentActivities(ACTION_MAIN/LAUNCHER) — "
                    + "nombre de packages lançables via CATEGORY_LAUNCHER. "
                    + "Alimente la liste de sélection pour les tests sur display cluster."),
            new TestDef("F05", "[ADB] État d'alimentation des displays",
                    "dumpsys power | grep -E 'mWakefulness|Display Power|mDisplayPowerRequest' "
                    + "— lecture seule. Vérifie si le display cluster est ON/OFF/DOZE.")
        );
    }

    // ── Run ───────────────────────────────────────────────────────────────────

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dl3-recon-runner");
        t.setDaemon(true);
        return t;
    });

    public static void runAll(Context ctx, Listener listener) {
        List<TestDef> defs = catalog();
        List<TestResult> results = new ArrayList<>();
        for (TestDef d : defs) results.add(new TestResult(d));

        Handler ui = new Handler(Looper.getMainLooper());
        ui.post(() -> listener.onSuiteStarted(Collections.unmodifiableList(results)));

        EXEC.execute(() -> {
            for (int i = 0; i < results.size(); i++) {
                final int idx = i;
                TestResult r = results.get(i);
                r.status = Status.RUNNING;
                ui.post(() -> listener.onTestUpdated(idx, r));
                long t0 = System.currentTimeMillis();
                try {
                    runTest(ctx, r);
                } catch (Exception e) {
                    r.status = Status.FAIL;
                    r.message = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                r.elapsedMs = System.currentTimeMillis() - t0;
                ui.post(() -> listener.onTestUpdated(idx, r));
            }
            ui.post(() -> listener.onSuiteFinished(Collections.unmodifiableList(results)));
        });
    }

    private static void runTest(Context ctx, TestResult r) {
        switch (r.def.id) {
            // App context tests
            case "A01": runA01(ctx, r); break;
            case "A03": runA03(ctx, r); break;
            case "B01": runB01(ctx, r); break;
            case "B03": runB03(ctx, r); break;
            case "C01": runC01(ctx, r); break;
            case "D01": runD01(ctx, r); break;
            case "E01": runE01(ctx, r); break;
            case "F04": runF04(ctx, r); break;
            // ADB shell tests
            default:    runShell(ctx, r); break;
        }
    }

    // ── A — Display enumeration (App) ──────────────────────────────────────────

    private static void runA01(Context ctx, TestResult r) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] all = dm.getDisplays();
        StringBuilder sb = new StringBuilder();
        sb.append(all.length).append(" display(s):\n");
        for (Display d : all) {
            android.graphics.Point sz = new android.graphics.Point();
            d.getRealSize(sz);
            sb.append("  id=").append(d.getDisplayId())
              .append(" \"").append(d.getName()).append("\"")
              .append(" ").append(sz.x).append("×").append(sz.y).append("\n");
        }
        r.message = sb.toString().trim();
        r.status = all.length >= 2 ? Status.PASS : Status.WARN;
    }

    private static void runA03(Context ctx, TestResult r) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (pres.length == 0) {
            r.status = Status.WARN;
            r.message = "0 display PRESENTATION — cluster non visible depuis l'UID appli";
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(pres.length).append(" display(s) PRESENTATION:\n");
        for (Display d : pres) {
            android.graphics.Point sz = new android.graphics.Point();
            d.getRealSize(sz);
            sb.append("  id=").append(d.getDisplayId())
              .append(" \"").append(d.getName()).append("\"")
              .append(" ").append(sz.x).append("×").append(sz.y).append("\n");
        }
        r.message = sb.toString().trim();
        r.status = Status.PASS;
    }

    // ── B — Process & activity (App) ──────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static void runB01(Context ctx, TestResult r) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) {
            r.status = Status.WARN;
            r.message = "getRunningAppProcesses() null (visibilité restreinte depuis cet UID)";
            return;
        }
        int fg = 0, vis = 0, svc = 0;
        for (ActivityManager.RunningAppProcessInfo p : procs) {
            if      (p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) fg++;
            else if (p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)    vis++;
            else if (p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE)    svc++;
        }
        r.message = String.format(Locale.US,
                "total=%d  fg=%d  visible=%d  service=%d  (autres=%d)",
                procs.size(), fg, vis, svc, procs.size() - fg - vis - svc);
        r.status = Status.PASS;
    }

    private static void runB03(Context ctx, TestResult r) {
        try {
            Class.forName("android.app.ActivityOptions")
                 .getMethod("setLaunchDisplayId", int.class);
            r.status = Status.PASS;
            r.message = "ActivityOptions.setLaunchDisplayId(int) présente (API 26+) ✓";
        } catch (NoSuchMethodException e) {
            r.status = Status.WARN;
            r.message = "setLaunchDisplayId absente — lancement sur display secondaire impossible";
        } catch (ClassNotFoundException e) {
            r.status = Status.FAIL;
            r.message = "ActivityOptions introuvable via réflexion";
        }
    }

    // ── C — API 29 features (App) ─────────────────────────────────────────────

    private static void runC01(Context ctx, TestResult r) {
        PackageManager pm = ctx.getPackageManager();
        boolean freeform  = pm.hasSystemFeature("android.software.freeform_window_management");
        boolean pip       = pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        boolean secondary = pm.hasSystemFeature("android.software.activities_on_secondary_displays");
        boolean embedded  = pm.hasSystemFeature("android.software.embedded");

        r.message = String.format(
                "freeform=%b  PiP=%b  secondary_displays=%b  embedded=%b",
                freeform, pip, secondary, embedded);
        r.status = secondary ? Status.PASS : Status.WARN;
    }

    // ── D — Resize / DPI (App) ────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static void runD01(Context ctx, TestResult r) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        android.graphics.Point sz = new android.graphics.Point();
        wm.getDefaultDisplay().getRealSize(sz);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        r.message = String.format(Locale.US,
                "size=%d×%d  densityDpi=%d  xdpi=%.1f  ydpi=%.1f  displayId=%d",
                sz.x, sz.y, dm.densityDpi, dm.xdpi, dm.ydpi,
                wm.getDefaultDisplay().getDisplayId());
        r.status = Status.PASS;
    }

    // ── E — Virtual Display (App, réversible) ─────────────────────────────────

    private static void runE01(Context ctx, TestResult r) {
        // SAFE: PRESENTATION|OWN_CONTENT_ONLY ne nécessite pas CAPTURE_VIDEO_OUTPUT.
        // Le display est relâché immédiatement après la vérification.
        // N'affecte PAS le display 0.
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        VirtualDisplay vd = null;
        try {
            vd = dm.createVirtualDisplay(
                    "dl3-recon-probe",
                    1, 1, 72,   // 1×1 @72dpi — footprint minimal
                    null,       // pas de Surface — display purement logique
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (vd != null) {
                int id = vd.getDisplay().getDisplayId();
                r.status = Status.PASS;
                r.message = "Created displayId=" + id + " (1×1@72dpi) → released immédiatement";
            } else {
                r.status = Status.WARN;
                r.message = "createVirtualDisplay() null (permission silencieusement refusée ?)";
            }
        } catch (SecurityException e) {
            r.status = Status.WARN;
            r.message = "SecurityException: " + e.getMessage()
                    + " → besoin CAPTURE_VIDEO_OUTPUT ou MediaProjection";
        } finally {
            if (vd != null) vd.release();
        }
    }

    // ── F — DashCast probes (App) ─────────────────────────────────────────────

    private static void runF04(Context ctx, TestResult r) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = ctx.getPackageManager()
                .queryIntentActivities(intent, PackageManager.GET_META_DATA);
        r.message = apps.size() + " packages launchables via CATEGORY_LAUNCHER";
        r.status = Status.PASS;
    }

    // ── ADB shell tests ───────────────────────────────────────────────────────

    private static void runShell(Context ctx, TestResult r) {
        String cmd = getShellCmd(r.def.id);
        if (cmd == null) {
            r.status = Status.SKIPPED;
            r.message = "Commande non définie pour " + r.def.id;
            return;
        }
        String out = execShell(ctx, cmd);
        if (out.startsWith("__SHELL_ERR__:")) {
            r.status = Status.FAIL;
            r.message = out.substring("__SHELL_ERR__:".length());
            return;
        }
        r.message = out.trim();
        evalShell(r, out.trim());
    }

    private static String getShellCmd(String id) {
        switch (id) {
            // A — displays (ADB)
            case "A02": return
                "dumpsys display 2>&1 | grep -E 'mDisplayId|isPresentation|DisplayInfo|PhysicalDisplayInfo' | head -30";
            case "A04": return
                "dumpsys SurfaceFlinger 2>&1 | grep -E 'Display #|type=' | head -20";

            // B — processes (ADB)
            case "B02": return
                "dumpsys activity 2>&1 | grep -E 'ActivityRecord|TaskRecord|mFocused' | head -20";
            case "B04": return
                "am start --help 2>&1 | grep -iE -- '--display|--stack|-display' | head -8";

            // C — command discovery (ADB)
            // READ-ONLY: wm without args prints usage only, never modifies display 0
            case "C02": return "wm 2>&1 | grep -v '^$' | head -25";
            case "C03": return
                "echo '--- am display ---' && am display 2>&1 | head -10 && "
                + "echo '--- am task ---' && am task 2>&1 | head -10";
            case "C04": return "cmd -l 2>&1 | grep -iE 'display|window|surface|hardware' | head -20";
            case "C05": return
                "service list 2>&1 | grep -iE 'display|window|surface|SurfaceFlinger' | head -20";

            // D — resize/dpi (ADB, READ-ONLY — no wm size WxH, no wm density X)
            case "D02": return "wm size 2>&1";
            case "D03": return "wm scaling 2>&1";
            // am stack without args prints usage only (no modification)
            case "D04": return "am stack 2>&1 | head -15";

            // E — virtual display (ADB)
            case "E02": return
                "dumpsys display 2>&1 | grep -iE 'virtual|VirtualDisplay' | head -15";
            case "E03": return
                "echo '--- wm create ---' && wm create 2>&1 | head -6 && "
                + "echo '--- wm dismiss ---' && wm dismiss 2>&1 | head -4";

            // F — DashCast probes (ADB)
            case "F01": return
                "echo '--- AutoContainer ---' && service call AutoContainer 1 2>&1 && "
                + "echo '--- auto_container ---' && service call auto_container 1 2>&1";
            case "F02": return
                "dumpsys window 2>&1 | grep -E 'mCurrentFocus|mFocusedApp' | head -5";
            case "F03": return
                "getprop 2>&1 | grep -E 'ro.product.model|ro.build.version.sdk|"
                + "ro.hardware|ro.board.platform|ro.product.name' | head -10";
            case "F05": return
                "dumpsys power 2>&1 | grep -E 'mWakefulness|Display Power|mDisplayPowerRequest' | head -8";

            default: return null;
        }
    }

    private static void evalShell(TestResult r, String out) {
        switch (r.def.id) {
            case "A02":
            case "A04":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            case "B02":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            case "B04":
                r.status = (out.contains("display") || out.contains("stack"))
                        ? Status.PASS : Status.WARN;
                break;
            case "C02":
                r.status = out.contains("size") ? Status.PASS : Status.WARN;
                break;
            case "C03":
                r.status = (out.contains("am display") || out.contains("am task") || !out.isEmpty())
                        ? Status.PASS : Status.WARN;
                break;
            case "C04":
            case "C05":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            case "D02":
                r.status = out.contains("Physical") || out.contains("Override")
                        ? Status.PASS : Status.WARN;
                break;
            case "D03":
                // "Unknown command" = feature absent on this build
                r.status = out.toLowerCase().contains("unknown") ? Status.WARN : Status.PASS;
                break;
            case "D04":
                // am stack deprecated → WARN if error, PASS if it shows usage
                r.status = out.toLowerCase().contains("error") ? Status.WARN : Status.PASS;
                break;
            case "E02":
                r.status = Status.PASS; // informational
                break;
            case "E03":
                r.status = (out.contains("wm create") || out.contains("displayId"))
                        ? Status.PASS : Status.WARN;
                break;
            case "F01":
                r.status = out.contains("does not exist") ? Status.WARN
                        : (out.contains("Parcel") || out.contains("i32") ? Status.PASS : Status.WARN);
                break;
            case "F02":
            case "F03":
            case "F05":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            default:
                r.status = Status.PASS;
        }
    }

    // ── Shell exec (blocking, appel depuis worker thread) ─────────────────────

    private static String execShell(Context ctx, String cmd) {
        final AtomicReference<String> out = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            com.dashcast.devtools.common.AdbClient.executeShellWithResult(ctx, cmd,
                new com.dashcast.devtools.common.AdbClient.Callback() {
                    @Override public void onSuccess(String s) {
                        out.set(s == null ? "" : s);
                        latch.countDown();
                    }
                    @Override public void onError(String e) {
                        out.set("__SHELL_ERR__:" + (e == null ? "(null)" : e));
                        latch.countDown();
                    }
                });
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "__SHELL_ERR__:interrupted";
        }
        String result = out.get();
        return result != null ? result : "__SHELL_ERR__:timeout";
    }

    // ── Report ────────────────────────────────────────────────────────────────

    public static String buildReport(List<TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DL3 · Recon Report ===\n");
        sb.append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date())).append("\n\n");
        for (TestResult r : results) {
            sb.append(String.format(Locale.US, "[%-8s] %-4s  %-42s  %s\n",
                    r.status.name(), r.def.id, r.def.title, r.message));
        }
        return sb.toString();
    }
}
