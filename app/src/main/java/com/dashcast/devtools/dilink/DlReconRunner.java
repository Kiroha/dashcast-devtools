package com.dashcast.devtools.dilink;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.lang.reflect.Method;
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
 * DlReconRunner — suite Recon UNIVERSELLE pour toute version DiLink.
 *
 * <p>But : cartographier les capacités de la ROM (displays, services,
 * réflexion Android, windowing) pour informer le développement des features
 * de projection cluster et mirror — indépendamment de la version DiLink.
 *
 * <p>Le Recon ne teste PAS la projection elle-même : il ne lance aucune app,
 * n'envoie aucune commande AutoContainer modificatrice, ne touche jamais au
 * display id=0 (écran HMI 15.6"). Le seul test d'écriture (E01 VirtualDisplay)
 * est entièrement réversible.
 *
 * <p>Chaque test est labellisé [App] (Java API dans le contexte applicatif)
 * ou [ADB] (commande shell via {@link com.dashcast.devtools.common.AdbClient}).
 *
 * <p>4 statuts de sortie :
 * <ul>
 *   <li>{@code PASS}  — capacité présente / valeur lue correctement</li>
 *   <li>{@code WARN}  — capacité absente mais "devrait" exister ; à examiner</li>
 *   <li>{@code FAIL}  — devrait marcher mais ne marche pas (vraie régression)</li>
 *   <li>{@code SKIPPED} (rendu {@code N/A}) — non applicable sur cette ROM
 *       (API trop ancienne, SoC MTK, display secondaire absent, etc.)</li>
 * </ul>
 *
 * <p>Sections :
 * <pre>
 *   A — Identification + énumération displays
 *   B — Processus / activités / lancement multi-display
 *   C — Découverte des sous-commandes (wm, am, cmd)
 *   D — Taille / DPI / fenêtrage (lecture seule)
 *   E — VirtualDisplay (réversible)
 *   F — Sondes services BYD / XDJA
 *   G — Réflexion Android (IActivityTaskManager, IWindowManager, SurfaceControl)
 *   H — Input / réseau ADB
 *   I — Windowing modes (tasks, freeform)
 *   J — Spécificités DL2 / MTK
 *   K — Spécificités DL5
 * </pre>
 *
 * Référence : log Recon DL3 du 30/05/2026 (display cluster id=3, name contient
 * "fission", owner com.xdja.containerservice, 1920×720@60fps, density=320dpi,
 * overscan (30,52,30,52), flag PRESENTATION|OWN_CONTENT_ONLY).
 */
public final class DlReconRunner {

    private DlReconRunner() {}

    // ── Model ─────────────────────────────────────────────────────────────────

    public static final class TestDef {
        public final String id;
        public final String title;
        public final String description;
        TestDef(String id, String title, String description) {
            this.id = id; this.title = title; this.description = description;
        }
    }

    /**
     * États possibles d'un test.
     * <p>{@code SKIPPED} est rendu {@code N/A} dans le rapport texte
     * — il signifie "non applicable sur cette ROM/SoC", pas "non exécuté".
     */
    public enum Status { PENDING, RUNNING, PASS, FAIL, WARN, SKIPPED }

    public static final class TestResult {
        public final TestDef def;
        public volatile Status status;
        public volatile String message;
        public volatile long elapsedMs;
        public TestResult(TestDef def) {
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
            // ── A — Identification + displays ──────────────────────────────
            new TestDef("A00", "[App] Identification ROM (SDK + hardware)",
                    "Build.VERSION.SDK_INT + ro.product.name + ro.hardware + ro.board.platform. "
                    + "Tous les autres tests se branchent sur ces valeurs pour les guards "
                    + "API et SoC (MTK vs Qualcomm)."),
            new TestDef("A01", "[App] Displays via DisplayManager",
                    "DisplayManager.getDisplays() — tous types. Montre ce que l'appli voit. "
                    + "WARN si un seul display sur API ≥ 29 (cluster non visible depuis l'UID appli). "
                    + "Détecte aussi l'ID du display cluster (utilisé par E04/E05/K02)."),
            new TestDef("A02", "[ADB] Displays via dumpsys display",
                    "dumpsys display | grep mDisplayId/isPresentation/DisplayInfo — "
                    + "référentiel système. Comparer avec A01 pour détecter des displays "
                    + "cachés au contexte applicatif."),
            new TestDef("A03", "[App] Displays Presentation uniquement",
                    "DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION) — seuls les "
                    + "displays 'cluster' / HDMI. PASS si ≥ 1, N/A sur DL2 (cluster = ECU séparé), "
                    + "WARN sinon."),
            new TestDef("A04", "[ADB] SurfaceFlinger display list",
                    "dumpsys SurfaceFlinger | grep 'Display #|type=' — couche bas niveau "
                    + "(HDMI, VIRTUAL, etc.). Peut révéler des displays non remontés au Java."),

            // ── B — Process & activity management ─────────────────────────
            new TestDef("B01", "[App] Processus via ActivityManager",
                    "ActivityManager.getRunningAppProcesses() — liste limitée à ce que l'UID "
                    + "de l'appli peut voir. Permet de comparer avec B02."),
            new TestDef("B02", "[ADB] Activités via dumpsys activity",
                    "dumpsys activity | grep ActivityRecord — vue système complète. "
                    + "Compare la visibilité avec B01 pour mesurer la restriction."),
            new TestDef("B03", "[App] ActivityOptions.setLaunchDisplayId (réflexion)",
                    "Vérifie via Class.forName que la méthode est présente. Doit être PASS "
                    + "sur API ≥ 26. Indispensable pour lancer une activité sur le cluster."),
            new TestDef("B04", "[ADB] am start --display flag",
                    "am start --display (sans argument après) — provoque IllegalArgumentException "
                    + "côté Android, ce qui prouve que le flag --display est supporté. "
                    + "Capture stderr via 2>&1. N/A si API < 29."),

            // ── C — Command discovery ──────────────────────────────────────
            new TestDef("C01", "[App] PackageManager features display",
                    "hasSystemFeature : freeform_window_management, picture_in_picture, "
                    + "activities_on_secondary_displays, embedded. Détermine les capacités "
                    + "déclarées par la ROM."),
            new TestDef("C02", "[ADB] wm — arbre de sous-commandes",
                    "wm (sans args) | head -25 — liste toutes les sous-commandes WindowManager "
                    + "shell disponibles sur ce build. Lecture seule (wm sans args = usage)."),
            new TestDef("C03", "[ADB] am display + am task",
                    "am display (sans args) puis am task (sans args) — documente les "
                    + "sous-commandes multi-display et de tâches. N/A si API < 29."),
            new TestDef("C04", "[ADB] cmd services display/window",
                    "cmd -l | grep -iE 'display|window|surface|hardware' — services cmd "
                    + "liés à l'affichage accessibles via l'UID appli."),
            new TestDef("C05", "[ADB] Binder services display/surface",
                    "service list | grep -iE 'display|window|surface|SurfaceFlinger' — "
                    + "services Binder exposés. Révèle SurfaceFlinger, magicwindow, etc."),

            // ── D — Resize / DPI / windowing (lecture seule) ───────────────
            new TestDef("D01", "[App] Taille + DPI courants (appli)",
                    "WindowManager.getDefaultDisplay().getRealSize/getRealMetrics — "
                    + "résolution et densité vus depuis le contexte appli."),
            new TestDef("D02", "[ADB] wm size (lecture, tous displays)",
                    "wm size — lit la taille logique déclarée. JAMAIS wm size WxH "
                    + "(écriture interdite). Comparer avec D01."),
            new TestDef("D03", "[ADB] wm scaling (mode de mise à l'échelle)",
                    "wm scaling — mode actuel (auto/scale/stretchToFill/none). "
                    + "Utile pour comprendre comment les applis sont redimensionnées."),
            new TestDef("D04", "[ADB] am stack (gestion des piles, API ≤ 32)",
                    "am stack (sans args) — sous-commandes disponibles. "
                    + "Supprimé en API 33 → N/A. Utiliser am task à la place (voir I01)."),

            // ── E — Virtual Display ────────────────────────────────────────
            new TestDef("E01", "[App] Create VirtualDisplay 1×1 PRES|OWN_CONTENT_ONLY",
                    "DisplayManager.createVirtualDisplay('dl-recon-probe', 1, 1, 72, null, "
                    + "PRESENTATION|OWN_CONTENT_ONLY) immédiatement suivi de release(). "
                    + "Teste si l'UID appli peut créer un VD sans MediaProjection. "
                    + "100%% réversible — durée < 50 ms."),
            new TestDef("E02", "[ADB] Virtual displays existants",
                    "dumpsys display | grep -i 'virtual|VirtualDisplay' — "
                    + "liste les VirtualDisplays actifs au moment du test."),
            new TestDef("E03", "[ADB] wm create / wm dismiss (disponibilité)",
                    "wm create (sans args) et wm dismiss (sans args) — "
                    + "vérifie si ces sous-commandes existent. Lecture seule."),
            new TestDef("E04", "[ADB] wm size -d <cluster_id> (lecture seule)",
                    "Taille reportée par wm pour le display cluster détecté en A01. "
                    + "N/A si pas de display secondaire."),
            new TestDef("E05", "[ADB] wm overscan -d <cluster_id> (lecture seule)",
                    "Overscan du cluster via wm. Sur DL3 attendu : (30,52,30,52). "
                    + "N/A si pas de display secondaire."),

            // ── F — DashCast probes ────────────────────────────────────────
            new TestDef("F01", "[ADB] AutoContainer / auto_container service",
                    "service call AutoContainer 1 et service call auto_container 1 — "
                    + "sonde de présence du pont cluster BYD. Code 1 = INTERFACE_TRANSACTION "
                    + "(lit le descripteur, n'exécute aucune action). Retour -4 depuis uid=2000 = OK."),
            new TestDef("F02", "[ADB] Focus fenêtre courant (lecture)",
                    "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' — "
                    + "identifie l'activité au premier plan."),
            new TestDef("F03", "[ADB] Infos hardware (getprop)",
                    "getprop | grep -E 'ro.product.model|ro.build.version.sdk|"
                    + "ro.hardware|ro.board.platform|ro.product.name|ro.hardware.info' — "
                    + "empreinte matérielle du véhicule."),
            new TestDef("F04", "[App] Packages launchables (candidats multi-display)",
                    "PackageManager.queryIntentActivities(ACTION_MAIN/LAUNCHER) — "
                    + "nombre de packages lançables via CATEGORY_LAUNCHER."),
            new TestDef("F05", "[ADB] État d'alimentation des displays",
                    "dumpsys power | grep -E 'mWakefulness|Display Power|mDisplayPowerRequest' "
                    + "— lecture seule. Vérifie si le display cluster est ON/OFF/DOZE."),
            new TestDef("F06", "[ADB] Services BYD/XDJA (magic/mirror/byd/auto/container/fission)",
                    "service list | grep -iE 'magic|mirror|byd|auto|container|display|fission' — "
                    + "cartographie de tous les services BYD/XDJA. La liste diffère entre DL3 et DL5."),
            new TestDef("F07", "[ADB] service call magicwindow 1 (descriptor)",
                    "Transaction 1 = getInterfaceDescriptor — identifie l'interface AIDL "
                    + "exposée par magicwindow. Inoffensif. WARN si le service n'existe pas."),
            new TestDef("F08", "[ADB] service check mirror/BYDMgmt/autoservice/crossservice",
                    "service check pour chacun — confirme la présence ou absence sur cette ROM. "
                    + "Sur DL5 ces services existent typiquement, sur DL3 non."),

            // ── G — Réflexion Android ──────────────────────────────────────
            new TestDef("G01", "[App] Réflexion IActivityTaskManager / IActivityManager",
                    "Liste les méthodes présentes parmi moveRootTaskToDisplay, moveTaskToRootTask, "
                    + "forceStopPackage, startActivityAsUser. Sur API 28 cherche dans "
                    + "IActivityManager, sur API ≥ 29 dans IActivityTaskManager."),
            new TestDef("G02", "[App] Réflexion IWindowManager",
                    "Méthodes présentes : freezeDisplayRotation(int,int), thawDisplayRotation(int), "
                    + "getDisplayUserRotation(int). Critique pour locker la rotation du cluster."),
            new TestDef("G03", "[App] Réflexion SurfaceControl (statique vs Transaction)",
                    "Deux patterns selon API : statique (API ≤ 29) — createDisplay, "
                    + "openTransaction, setDisplaySurface/LayerStack/Projection ; "
                    + "ou Transaction (API ≥ 30) — SurfaceControl.Transaction.setDisplay*. "
                    + "C'est la différence entre mirror qui marche ou non selon la version."),
            new TestDef("G04", "[App] VirtualDisplay PUBLIC|PRESENTATION (sans OWN_CONTENT_ONLY)",
                    "createVirtualDisplay 1×1 avec flags=9 (PUBLIC|PRESENTATION). "
                    + "Valide qu'on peut être owner d'un VD sans le flag bloquant. "
                    + "Distinct de E01 qui teste avec OWN_CONTENT_ONLY."),

            // ── H — Input / réseau ─────────────────────────────────────────
            new TestDef("H01", "[ADB] dumpsys input | head -60",
                    "Liste les périphériques d'entrée (sources touch, clavier virtuel). "
                    + "Base pour ClusterInputForwarder."),
            new TestDef("H02", "[ADB] Port ADB TCP (getprop + settings)",
                    "getprop service.adb.tcp.port + settings get global adb_wifi_port. "
                    + "On suppose 5555 mais peut différer selon la ROM."),

            // ── I — Windowing modes ────────────────────────────────────────
            new TestDef("I01", "[ADB] am task list (fallback am stack list)",
                    "Cartographie des tâches actives et leurs modes. "
                    + "API ≤ 31 → am stack list ; API ≥ 32 → am task list."),
            new TestDef("I02", "[ADB] dumpsys activity activities | grep windowing/Bounds/FREEFORM",
                    "Vérifie si des tâches FREEFORM existent en pratique même si "
                    + "freeform=false en feature déclarée (C01)."),

            // ── J — Spécificités DL2 / MTK ─────────────────────────────────
            new TestDef("J01", "[ADB] ro.hardware → SoC MTK ?",
                    "getprop ro.hardware — si contient 'mt' = MediaTek. "
                    + "Sur MTK (DL2), tous les tests display secondaire passent en N/A "
                    + "(cluster DL2 = ECU séparé)."),
            new TestDef("J02", "[ADB] Nombre de displays Android",
                    "dumpsys display | grep -c mDisplayId — attendu DL2 : 1, DL3 : 2, DL5 : 4."),

            // ── K — Spécificités DL5 ───────────────────────────────────────
            new TestDef("K01", "[ADB] cmd package query-activities LAUNCHER",
                    "cmd package query-activities -a MAIN -c LAUNCHER. "
                    + "Remplace pm query-intent-activities absent sur API 29+. "
                    + "Fallback pm si API < 29."),
            new TestDef("K02", "[ADB] wm set-fix-to-user-rotation (disponibilité)",
                    "Disponible depuis API 31, utile pour lock rotation cluster sur DL5. "
                    + "Appelé sans argument pour vérifier la disponibilité. N/A si API < 31.")
        );
    }

    // ── Suite execution ───────────────────────────────────────────────────────

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dl-recon-runner");
        t.setDaemon(true);
        return t;
    });

    /** State partagé entre tests (rempli par A00 / A01). */
    private static final class SuiteCtx {
        int     apiLevel    = Build.VERSION.SDK_INT;
        String  hardware    = "";
        String  boardPlatform = "";
        String  productName = "";
        String  hardwareInfo = "";
        boolean isMtk       = false;
        /** Display secondaire (cluster) détecté en A01, ou -1 si absent. */
        int     clusterDisplayId = -1;
        String  clusterDisplayName = "";
    }

    public static void runAll(Context ctx, Listener listener) {
        List<TestDef> defs = catalog();
        List<TestResult> results = new ArrayList<>();
        for (TestDef d : defs) results.add(new TestResult(d));

        Handler ui = new Handler(Looper.getMainLooper());
        ui.post(() -> listener.onSuiteStarted(Collections.unmodifiableList(results)));

        EXEC.execute(() -> {
            SuiteCtx sctx = new SuiteCtx();
            for (int i = 0; i < results.size(); i++) {
                final int idx = i;
                TestResult r = results.get(i);
                r.status = Status.RUNNING;
                ui.post(() -> listener.onTestUpdated(idx, r));
                long t0 = System.currentTimeMillis();
                try {
                    runTest(ctx, sctx, r);
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

    private static void runTest(Context ctx, SuiteCtx s, TestResult r) {
        switch (r.def.id) {
            // App context tests
            case "A00": runA00(s, r); break;
            case "A01": runA01(ctx, s, r); break;
            case "A03": runA03(ctx, s, r); break;
            case "B01": runB01(ctx, r); break;
            case "B03": runB03(r); break;
            case "C01": runC01(ctx, r); break;
            case "D01": runD01(ctx, r); break;
            case "E01": runE01(ctx, r); break;
            case "F04": runF04(ctx, r); break;
            case "G01": runG01(s, r); break;
            case "G02": runG02(r); break;
            case "G03": runG03(s, r); break;
            case "G04": runG04(ctx, r); break;
            // ADB shell tests
            default:    runShell(ctx, s, r); break;
        }
    }

    // ── A — Identification + displays (App) ───────────────────────────────────

    private static void runA00(SuiteCtx s, TestResult r) {
        s.apiLevel      = Build.VERSION.SDK_INT;
        s.productName   = readProp("ro.product.name");
        s.hardware      = readProp("ro.hardware");
        s.boardPlatform = readProp("ro.board.platform");
        s.hardwareInfo  = readProp("ro.hardware.info");
        s.isMtk         = s.hardware.toLowerCase(Locale.US).startsWith("mt")
                       || s.boardPlatform.toLowerCase(Locale.US).startsWith("mt")
                       || s.boardPlatform.toLowerCase(Locale.US).contains("mtk");
        r.message = String.format(Locale.US,
                "API=%d  product=%s  hardware=%s  board=%s  hw.info=%s  SoC=%s",
                s.apiLevel,
                emptyDash(s.productName),
                emptyDash(s.hardware),
                emptyDash(s.boardPlatform),
                emptyDash(s.hardwareInfo),
                s.isMtk ? "MediaTek" : "Qualcomm/Other");
        r.status = Status.PASS;
    }

    private static void runA01(Context ctx, SuiteCtx s, TestResult r) {
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
            // Détecte le cluster par nom (jamais par ID hardcodé).
            String name = d.getName() == null ? "" : d.getName().toLowerCase(Locale.US);
            int id = d.getDisplayId();
            if (id != Display.DEFAULT_DISPLAY && s.clusterDisplayId == -1
                && (name.contains("fission") || name.contains("cluster")
                    || name.contains("instrument") || name.contains("vd"))) {
                s.clusterDisplayId   = id;
                s.clusterDisplayName = d.getName();
            }
        }
        // Fallback : 1er display secondaire (non DEFAULT) si rien matché par nom.
        if (s.clusterDisplayId == -1) {
            for (Display d : all) {
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    s.clusterDisplayId   = d.getDisplayId();
                    s.clusterDisplayName = d.getName() == null ? "" : d.getName();
                    break;
                }
            }
        }
        r.message = sb.toString().trim();
        if (s.isMtk && all.length == 1) {
            r.status  = Status.PASS;
            r.message += "\n(MTK / DL2 : 1 seul display Android attendu — cluster = ECU séparé)";
        } else {
            r.status = all.length >= 2 ? Status.PASS : Status.WARN;
        }
    }

    private static void runA03(Context ctx, SuiteCtx s, TestResult r) {
        if (s.isMtk) {
            r.status = Status.SKIPPED;
            r.message = "N/A : SoC MTK (DL2) — pas de display PRESENTATION Android";
            return;
        }
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

    private static void runB03(TestResult r) {
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

    // ── C — API features (App) ────────────────────────────────────────────────

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
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        VirtualDisplay vd = null;
        try {
            vd = dm.createVirtualDisplay(
                    "dl-recon-probe",
                    1, 1, 72,
                    null,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (vd != null) {
                int id = vd.getDisplay().getDisplayId();
                r.status  = Status.PASS;
                r.message = "Created displayId=" + id + " (1×1@72dpi PRES|OWN) → released";
            } else {
                r.status  = Status.WARN;
                r.message = "createVirtualDisplay() null (permission silencieusement refusée ?)";
            }
        } catch (SecurityException e) {
            r.status  = Status.WARN;
            r.message = "SecurityException: " + e.getMessage();
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

    // ── G — Réflexion Android (App) ───────────────────────────────────────────

    private static void runG01(SuiteCtx s, TestResult r) {
        String cls;
        if (s.apiLevel < 29) {
            cls = "android.app.IActivityManager";
        } else {
            cls = "android.app.IActivityTaskManager";
        }
        String[] methods = { "moveRootTaskToDisplay", "moveTaskToRootTask",
                             "forceStopPackage", "startActivityAsUser" };
        r.message = "[" + cls + "]\n" + listMethodsByName(cls, methods);
        r.status = r.message.contains("✓") ? Status.PASS : Status.WARN;
    }

    private static void runG02(TestResult r) {
        String cls = "android.view.IWindowManager";
        String[] methods = { "freezeDisplayRotation", "thawDisplayRotation",
                             "getDisplayUserRotation" };
        r.message = "[" + cls + "]\n" + listMethodsByName(cls, methods);
        r.status = r.message.contains("✓") ? Status.PASS : Status.WARN;
    }

    private static void runG03(SuiteCtx s, TestResult r) {
        StringBuilder sb = new StringBuilder();
        Class<?> sc;
        try { sc = Class.forName("android.view.SurfaceControl"); }
        catch (ClassNotFoundException e) {
            r.status = Status.FAIL;
            r.message = "SurfaceControl introuvable via réflexion";
            return;
        }
        // Pattern statique (présent jusqu'à API 29).
        sb.append("Pattern statique (API ≤ 29) :\n");
        String[] staticM = { "createDisplay", "openTransaction", "closeTransaction",
                             "setDisplaySurface", "setDisplayLayerStack", "setDisplayProjection" };
        boolean hasStatic = appendStaticMethods(sb, sc, staticM);

        sb.append("\nPattern Transaction (API ≥ 30) :\n");
        boolean hasTx = false;
        try {
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            sb.append("  android.view.SurfaceControl$Transaction ✓\n");
            String[] txM = { "setDisplaySurface", "setDisplayLayerStack", "setDisplayProjection" };
            for (String m : txM) {
                boolean ok = anyMethodNamed(txCls, m);
                sb.append("  Transaction.").append(m).append(ok ? " ✓" : " ✗").append('\n');
                if (ok) hasTx = true;
            }
        } catch (ClassNotFoundException e) {
            sb.append("  Transaction class absente\n");
        }

        sb.append("\nAPI runtime = ").append(s.apiLevel)
          .append(" → pattern attendu : ")
          .append(s.apiLevel <= 29 ? "statique" : "Transaction");
        r.message = sb.toString();
        r.status = (hasStatic || hasTx) ? Status.PASS : Status.WARN;
    }

    private static void runG04(Context ctx, TestResult r) {
        // Flags = PUBLIC|PRESENTATION = 1|8 = 9. Pas de OWN_CONTENT_ONLY.
        final int flagsPubPres = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                               | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        VirtualDisplay vd = null;
        try {
            vd = dm.createVirtualDisplay("dl-recon-pubpres", 1, 1, 72, null, flagsPubPres);
            if (vd != null) {
                int id = vd.getDisplay().getDisplayId();
                r.status  = Status.PASS;
                r.message = "Created displayId=" + id + " (PUBLIC|PRESENTATION, flags=9) → released";
            } else {
                r.status  = Status.WARN;
                r.message = "createVirtualDisplay() null avec PUBLIC|PRESENTATION";
            }
        } catch (SecurityException e) {
            r.status  = Status.WARN;
            r.message = "SecurityException: " + e.getMessage()
                    + " → flag PUBLIC nécessite probablement CAPTURE_VIDEO_OUTPUT";
        } finally {
            if (vd != null) vd.release();
        }
    }

    // ── ADB shell tests ───────────────────────────────────────────────────────

    private static void runShell(Context ctx, SuiteCtx s, TestResult r) {
        // Guards API/SoC avant exécution shell.
        SkipReason sr = skipReasonFor(r.def.id, s);
        if (sr != null) {
            r.status  = Status.SKIPPED;
            r.message = "N/A : " + sr.reason;
            return;
        }
        String cmd = getShellCmd(r.def.id, s);
        if (cmd == null) {
            r.status  = Status.SKIPPED;
            r.message = "N/A : commande non définie pour " + r.def.id;
            return;
        }
        String out = execShell(ctx, cmd);
        if (out.startsWith("__SHELL_ERR__:")) {
            r.status  = Status.FAIL;
            r.message = out.substring("__SHELL_ERR__:".length());
            return;
        }
        r.message = out.trim();
        evalShell(r, out.trim(), s);
    }

    private static final class SkipReason {
        final String reason;
        SkipReason(String reason) { this.reason = reason; }
    }

    /** Renvoie un SkipReason si le test doit être N/A sur cette ROM, null sinon. */
    private static SkipReason skipReasonFor(String id, SuiteCtx s) {
        switch (id) {
            case "B04":
            case "C03":
            case "K01":  // pm fallback géré dans getShellCmd, donc K01 n'est jamais N/A
                if (id.equals("K01")) return null;
                if (s.apiLevel < 29)
                    return new SkipReason("API < 29 (actuel = " + s.apiLevel + ")");
                return null;
            case "D04":
                if (s.apiLevel >= 33)
                    return new SkipReason("am stack supprimé en API ≥ 33 — voir I01");
                return null;
            case "E04":
            case "E05":
                if (s.clusterDisplayId < 0)
                    return new SkipReason("pas de display secondaire détecté en A01");
                return null;
            case "K02":
                if (s.apiLevel < 31)
                    return new SkipReason("wm set-fix-to-user-rotation requiert API ≥ 31");
                return null;
            default:
                return null;
        }
    }

    private static String getShellCmd(String id, SuiteCtx s) {
        switch (id) {
            // A — displays (ADB)
            case "A02": return
                "dumpsys display 2>&1 | grep -E 'mDisplayId|isPresentation|DisplayInfo|PhysicalDisplayInfo' | head -30";
            case "A04": return
                "dumpsys SurfaceFlinger 2>&1 | grep -E 'Display #|type=' | head -20";

            // B — processes (ADB)
            case "B02": return
                "dumpsys activity 2>&1 | grep -E 'ActivityRecord|TaskRecord|mFocused' | head -20";
            case "B04":
                // Lancer am start --display SANS argument : Android lève
                // IllegalArgumentException("Argument expected after \"display\"").
                // Capture stderr via 2>&1.
                return "am start --display 2>&1";

            // C — command discovery (ADB)
            case "C02": return "wm 2>&1 | grep -v '^$' | head -25";
            case "C03": return
                "echo '--- am display ---' && am display 2>&1 | head -10 && "
                + "echo '--- am task ---' && am task 2>&1 | head -10";
            case "C04": return "cmd -l 2>&1 | grep -iE 'display|window|surface|hardware' | head -20";
            case "C05": return
                "service list 2>&1 | grep -iE 'display|window|surface|SurfaceFlinger' | head -20";

            // D — resize/dpi (ADB, READ-ONLY)
            case "D02": return "wm size 2>&1";
            case "D03": return "wm scaling 2>&1";
            case "D04": return "am stack 2>&1 | head -15";

            // E — virtual display (ADB)
            case "E02": return
                "dumpsys display 2>&1 | grep -iE 'virtual|VirtualDisplay' | head -15";
            case "E03": return
                "echo '--- wm create ---' && wm create 2>&1 | head -6 && "
                + "echo '--- wm dismiss ---' && wm dismiss 2>&1 | head -4";
            case "E04": return "wm size -d " + s.clusterDisplayId + " 2>&1";
            case "E05": return "wm overscan -d " + s.clusterDisplayId + " 2>&1";

            // F — DashCast probes (ADB)
            case "F01": return
                "echo '--- AutoContainer ---' && service call AutoContainer 1 2>&1 && "
                + "echo '--- auto_container ---' && service call auto_container 1 2>&1";
            case "F02": return
                "dumpsys window 2>&1 | grep -E 'mCurrentFocus|mFocusedApp' | head -5";
            case "F03": return
                "getprop 2>&1 | grep -E "
                + "'ro.product.model|ro.build.version.sdk|ro.hardware|ro.board.platform|"
                + "ro.product.name|ro.hardware.info' | head -12";
            case "F05": return
                "dumpsys power 2>&1 | grep -E 'mWakefulness|Display Power|mDisplayPowerRequest' | head -8";
            case "F06": return
                "service list 2>&1 | grep -iE 'magic|mirror|byd|auto|container|display|fission' | head -30";
            case "F07": return "service call magicwindow 1 2>&1";
            case "F08": return
                "for svc in mirror BYDMgmt autoservice crossservice magicwindow AutoContainer; do "
                + "  echo \"--- $svc ---\"; service check $svc 2>&1; "
                + "done";

            // G — couvert en App (G01–G04)

            // H — input / réseau
            case "H01": return "dumpsys input 2>&1 | head -60";
            case "H02": return
                "echo '--- service.adb.tcp.port ---' && getprop service.adb.tcp.port 2>&1 && "
                + "echo '--- adb_wifi_port (settings) ---' && "
                + "settings get global adb_wifi_port 2>&1";

            // I — windowing modes
            case "I01":
                if (s.apiLevel >= 32) {
                    return "echo '--- am task list (API >= 32) ---' && am task list 2>&1 | head -30";
                } else {
                    return "echo '--- am stack list (API < 32) ---' && am stack list 2>&1 | head -30";
                }
            case "I02": return
                "dumpsys activity activities 2>&1 | grep -E 'windowing|Bounds|FREEFORM' | head -30";

            // J — DL2 / MTK
            case "J01": return
                "echo 'ro.hardware = '$(getprop ro.hardware) && "
                + "echo 'ro.board.platform = '$(getprop ro.board.platform)";
            case "J02": return "echo 'mDisplayId count = '$(dumpsys display 2>&1 | grep -c mDisplayId)";

            // K — DL5
            case "K01":
                if (s.apiLevel >= 29) {
                    return "cmd package query-activities -a android.intent.action.MAIN "
                         + "-c android.intent.category.LAUNCHER 2>&1 | head -20";
                } else {
                    return "pm query-intent-activities -a android.intent.action.MAIN "
                         + "-c android.intent.category.LAUNCHER 2>&1 | head -20";
                }
            case "K02": return "wm set-fix-to-user-rotation 2>&1";

            default: return null;
        }
    }

    private static void evalShell(TestResult r, String out, SuiteCtx s) {
        String low = out.toLowerCase(Locale.US);
        switch (r.def.id) {
            case "A02":
            case "A04":
            case "B02":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            case "B04":
                // Le message d'erreur d'Android prouve que --display est supporté.
                if (low.contains("argument expected") || low.contains("illegalargumentexception")
                        || (low.contains("display") && low.contains("expected"))) {
                    r.status  = Status.PASS;
                    r.message = "PASS — flag --display supporté (erreur de validation attendue) :\n" + out;
                } else if (low.contains("unknown") || low.contains("invalid option")) {
                    r.status  = Status.WARN;
                    r.message = "Flag --display NON supporté par am sur cette ROM :\n" + out;
                } else {
                    r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                }
                break;
            case "C02":
                r.status = out.contains("size") ? Status.PASS : Status.WARN;
                break;
            case "C03":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            case "C04":
            case "C05":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            case "D02":
                r.status = (out.contains("Physical") || out.contains("Override"))
                        ? Status.PASS : Status.WARN;
                break;
            case "D03":
                r.status = low.contains("unknown") ? Status.WARN : Status.PASS;
                break;
            case "D04":
                r.status = low.contains("error") ? Status.WARN : Status.PASS;
                break;
            case "E02":
                r.status = Status.PASS; // informational
                break;
            case "E03":
                r.status = (out.contains("wm create") || out.contains("displayId"))
                        ? Status.PASS : Status.WARN;
                break;
            case "E04":
            case "E05":
                r.status = (low.contains("physical") || low.contains("override")
                            || low.contains("overscan") || low.contains("display"))
                        ? Status.PASS : Status.WARN;
                break;
            case "F01":
                r.status = low.contains("does not exist") ? Status.WARN
                        : (out.contains("Parcel") || out.contains("i32") || low.contains("result")
                           ? Status.PASS : Status.WARN);
                break;
            case "F02":
            case "F03":
            case "F05":
            case "F06":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            case "F07":
                if (low.contains("does not exist") || low.contains("not found")) {
                    r.status  = Status.WARN;
                    r.message = "Service magicwindow absent sur cette ROM";
                } else {
                    r.status = Status.PASS;
                }
                break;
            case "F08":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            case "H01":
            case "H02":
            case "I01":
            case "I02":
            case "J01":
            case "J02":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            case "K01":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            case "K02":
                // Sans args, la commande affiche l'usage si elle existe, sinon "Unknown".
                if (low.contains("unknown command") || low.contains("not found")) {
                    r.status  = Status.WARN;
                    r.message = "wm set-fix-to-user-rotation absent sur cette ROM";
                } else {
                    r.status = Status.PASS;
                }
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

    // ── Reflection helpers ────────────────────────────────────────────────────

    /** Liste les méthodes par nom (toutes signatures) — formate '  name ✓' ou '  name ✗'. */
    private static String listMethodsByName(String className, String[] names) {
        Class<?> cls;
        try { cls = Class.forName(className); }
        catch (ClassNotFoundException e) {
            return "  classe introuvable : " + e.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            boolean ok = anyMethodNamed(cls, n);
            sb.append("  ").append(n).append(ok ? " ✓" : " ✗").append('\n');
        }
        return sb.toString().trim();
    }

    private static boolean anyMethodNamed(Class<?> cls, String name) {
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (name.equals(m.getName())) return true;
            }
        } catch (Throwable ignore) {}
        try {
            for (Method m : cls.getMethods()) {
                if (name.equals(m.getName())) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    private static boolean appendStaticMethods(StringBuilder sb, Class<?> cls, String[] names) {
        boolean any = false;
        for (String n : names) {
            boolean ok = anyMethodNamed(cls, n);
            sb.append("  ").append(n).append(ok ? " ✓" : " ✗").append('\n');
            if (ok) any = true;
        }
        return any;
    }

    // ── SystemProperties via reflection ───────────────────────────────────────

    private static String readProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, key, "");
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String emptyDash(String s) { return (s == null || s.isEmpty()) ? "—" : s; }

    // ── Report ────────────────────────────────────────────────────────────────

    /**
     * Rendu texte aligné. SKIPPED est affiché N/A pour matcher la spec :
     * {@code [N/A     ] XXX  Skipped (raison : ...)}.
     */
    public static String buildReport(List<TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Recon Report ===\n");
        sb.append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.US).format(new java.util.Date())).append("\n\n");
        for (TestResult r : results) {
            String label = (r.status == Status.SKIPPED) ? "N/A" : r.status.name();
            sb.append(String.format(Locale.US, "[%-8s] %-4s  %-48s  %s\n",
                    label, r.def.id, r.def.title,
                    r.message == null ? "" : r.message.replace("\n", " | ")));
        }
        return sb.toString();
    }
}
