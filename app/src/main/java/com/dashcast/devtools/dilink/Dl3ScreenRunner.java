package com.dashcast.devtools.dilink;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
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
 * Dl3ScreenRunner — 15 tests de compatibilité écran 8.8" pour la plateforme DiLink 3.
 *
 * L01-L07 : tests locaux (DisplayMetrics, DisplayManager, Configuration) — sans ADB.
 * S08-S15 : tests shell (wm, settings, dumpsys).
 */
public final class Dl3ScreenRunner {

    // ── Model ────────────────────────────────────────────────────────────────

    public static final class TestDef {
        public final String id;
        public final String title;
        public final String description;

        TestDef(String id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
        }
    }

    public enum Status { PENDING, RUNNING, PASS, FAIL, WARN, SKIPPED }

    public static final class TestResult {
        public final TestDef def;
        public volatile Status status;
        public volatile String message;
        public volatile long elapsedMs;

        TestResult(TestDef def) {
            this.def = def;
            this.status = Status.PENDING;
            this.message = "";
            this.elapsedMs = 0;
        }
    }

    public interface Listener {
        void onSuiteStarted(List<TestResult> results);
        void onTestUpdated(int index, TestResult result);
        void onSuiteFinished(List<TestResult> results);
    }

    // ── Catalog ──────────────────────────────────────────────────────────────

    public static List<TestDef> catalog() {
        return Arrays.asList(
            // ── Local (no ADB) ────────────────────────────────────────────
            new TestDef("L01", "Résolution réelle",
                    "DisplayMetrics.getRealSize() — px physiques du display principal"),
            new TestDef("L02", "DPI physique",
                    "DisplayMetrics.xdpi × ydpi — densité matérielle déclarée par le driver"),
            new TestDef("L03", "Ratio d'aspect",
                    "Largeur÷Hauteur — attendu ~2.67:1 (1920×720) ou ~1.78:1 (1280×720)"),
            new TestDef("L04", "Nombre de displays",
                    "DisplayManager.getDisplays() — détecte le display cluster si présent"),
            new TestDef("L05", "Refresh rate",
                    "Display.getRefreshRate() — attendu 60 Hz"),
            new TestDef("L06", "HDR capabilities",
                    "Display.getHdrCapabilities() — formats HDR supportés (SDR only = normal)"),
            new TestDef("L07", "Font scale (config)",
                    "Configuration.fontScale — facteur d'agrandissement des polices"),
            // ── Shell ─────────────────────────────────────────────────────
            new TestDef("S08", "wm size",
                    "Résolution logique déclarée au WindowManager"),
            new TestDef("S09", "wm density",
                    "DPI logique déclaré au WindowManager"),
            new TestDef("S10", "wm overscan",
                    "Décalages bord-à-bord — idéalement 0,0,0,0"),
            new TestDef("S11", "font_scale (settings)",
                    "settings get system font_scale — cross-check avec L07"),
            new TestDef("S12", "Accessibilité",
                    "settings get secure accessibility_enabled — 0 = désactivée"),
            new TestDef("S13", "Navigation bar",
                    "dumpsys window | grep NavigationBarHeight"),
            new TestDef("S14", "Status bar",
                    "dumpsys window | grep StatusBarHeight"),
            new TestDef("S15", "Rotation système",
                    "wm user-rotation — vérifie si la rotation est verrouillée")
        );
    }

    // ── Run ──────────────────────────────────────────────────────────────────

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dl3-screen-runner");
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
            case "L01": runL01(ctx, r); break;
            case "L02": runL02(ctx, r); break;
            case "L03": runL03(ctx, r); break;
            case "L04": runL04(ctx, r); break;
            case "L05": runL05(ctx, r); break;
            case "L06": runL06(ctx, r); break;
            case "L07": runL07(ctx, r); break;
            default:    runShell(ctx, r); break;
        }
    }

    // ── Local tests ──────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static void runL01(Context ctx, TestResult r) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        Point sz = new Point();
        wm.getDefaultDisplay().getRealSize(sz);
        r.message = sz.x + "×" + sz.y + " px";
        boolean known = (sz.x == 1920 && sz.y == 720)
                     || (sz.x == 1280 && sz.y == 720)
                     || (sz.x == 720  && sz.y == 1920)
                     || (sz.x == 720  && sz.y == 1280);
        r.status = known ? Status.PASS : Status.WARN;
    }

    @SuppressWarnings("deprecation")
    private static void runL02(Context ctx, TestResult r) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        r.message = String.format(Locale.US, "xdpi=%.1f  ydpi=%.1f  densityDpi=%d",
                dm.xdpi, dm.ydpi, dm.densityDpi);
        r.status = (dm.xdpi >= 80 && dm.xdpi <= 500) ? Status.PASS : Status.WARN;
    }

    @SuppressWarnings("deprecation")
    private static void runL03(Context ctx, TestResult r) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        Point sz = new Point();
        wm.getDefaultDisplay().getRealSize(sz);
        int w = Math.max(sz.x, sz.y);
        int h = Math.min(sz.x, sz.y);
        if (h == 0) {
            r.status = Status.FAIL;
            r.message = "Hauteur = 0 !";
            return;
        }
        double ratio = (double) w / h;
        r.message = String.format(Locale.US, "%.3f:1  (%d×%d)", ratio, w, h);
        boolean is267 = ratio >= 2.617 && ratio <= 2.717; // 1920/720 = 2.667
        boolean is178 = ratio >= 1.728 && ratio <= 1.828; // 1280/720 = 1.778
        r.status = (is267 || is178) ? Status.PASS : Status.WARN;
    }

    private static void runL04(Context ctx, TestResult r) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays();
        StringBuilder sb = new StringBuilder();
        for (Display d : displays) {
            sb.append(d.getDisplayId()).append(":").append(d.getName()).append("  ");
        }
        r.message = displays.length + " display(s): " + sb.toString().trim();
        r.status = displays.length >= 1 ? Status.PASS : Status.FAIL;
    }

    @SuppressWarnings("deprecation")
    private static void runL05(Context ctx, TestResult r) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        float hz = wm.getDefaultDisplay().getRefreshRate();
        r.message = String.format(Locale.US, "%.1f Hz", hz);
        r.status = (hz >= 55.0f && hz <= 65.0f) ? Status.PASS : Status.WARN;
    }

    @SuppressWarnings("deprecation")
    private static void runL06(Context ctx, TestResult r) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        Display.HdrCapabilities hdr = wm.getDefaultDisplay().getHdrCapabilities();
        if (hdr == null || hdr.getSupportedHdrTypes().length == 0) {
            r.status = Status.PASS;
            r.message = "SDR uniquement (attendu sur DL3)";
        } else {
            r.status = Status.PASS;
            r.message = "HDR types: " + Arrays.toString(hdr.getSupportedHdrTypes());
        }
    }

    private static void runL07(Context ctx, TestResult r) {
        Configuration cfg = ctx.getResources().getConfiguration();
        r.message = String.format(Locale.US, "%.2f", cfg.fontScale);
        r.status = (Math.abs(cfg.fontScale - 1.0f) < 0.05f) ? Status.PASS : Status.WARN;
    }

    // ── Shell tests ──────────────────────────────────────────────────────────

    private static void runShell(Context ctx, TestResult r) {
        String cmd;
        switch (r.def.id) {
            case "S08": cmd = "wm size"; break;
            case "S09": cmd = "wm density"; break;
            case "S10": cmd = "wm overscan"; break;
            case "S11": cmd = "settings get system font_scale"; break;
            case "S12": cmd = "settings get secure accessibility_enabled"; break;
            case "S13": cmd = "dumpsys window | grep -i 'NavigationBarHeight\\|mNavBarHeight'"; break;
            case "S14": cmd = "dumpsys window | grep -i 'StatusBarHeight\\|mStatusBarHeight'"; break;
            case "S15": cmd = "wm user-rotation"; break;
            default:
                r.status = Status.SKIPPED;
                r.message = "Commande inconnue pour " + r.def.id;
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

    private static void evalShell(TestResult r, String out) {
        switch (r.def.id) {
            case "S08":
                r.status = (out.contains("1920x720") || out.contains("1280x720"))
                        ? Status.PASS : Status.WARN;
                break;
            case "S09":
                r.status = out.contains("density") ? Status.PASS : Status.WARN;
                break;
            case "S10":
                r.status = out.contains("0,0,0,0") ? Status.PASS : Status.WARN;
                break;
            case "S11":
                r.status = (out.equals("1.0") || out.equals("null")) ? Status.PASS : Status.WARN;
                break;
            case "S12":
                r.status = (out.equals("0") || out.equals("null")) ? Status.PASS : Status.WARN;
                break;
            case "S13":
            case "S14":
                r.status = out.isEmpty() ? Status.WARN : Status.PASS;
                break;
            case "S15":
                r.status = Status.PASS; // juste reporter la valeur
                break;
            default:
                r.status = Status.PASS;
        }
    }

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
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "__SHELL_ERR__:interrupted";
        }
        String result = out.get();
        return result != null ? result : "__SHELL_ERR__:timeout";
    }

    // ── Report ───────────────────────────────────────────────────────────────

    public static String buildReport(List<TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DL3 · 8.8\" Screen Compatibility Report ===\n");
        sb.append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date())).append("\n\n");
        for (TestResult r : results) {
            sb.append(String.format(Locale.US, "[%-8s] %-4s  %-30s  %s\n",
                    r.status.name(), r.def.id, r.def.title, r.message));
        }
        return sb.toString();
    }
}
