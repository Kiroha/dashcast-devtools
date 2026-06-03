package com.dashcast.devtools.fission;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.view.Display;
import android.view.Surface;

import com.dashcast.devtools.R;
import com.dashcast.devtools.common.AdbClient;
import com.dashcast.devtools.common.AppLogger;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Dl3FissionRunner — F01..F10 battery test for the DL3 Fission VirtualDisplay pipeline.
 *
 * <p>Validates the following pipeline end-to-end on DiLink 3
 * (API 29, Qualcomm trinket, cluster = VirtualDisplay xdja displayId=1, layerStack=1):
 * <pre>
 *   F06 CLUSTER_ATTACH: daemon creates SurfaceControl layer (layerStack=1) → Surface output
 *   VirtualDisplay byd_test_vd (setSurface → cluster output Surface)
 *     → app launched via am start --display N
 *     → VD renders into cluster output Surface → SC layer → SurfaceFlinger → cluster physique
 *   F08 MIRROR_START: also mirrors VD layerStack → SurfaceView tablette (preview + touch)
 * </pre>
 *
 * <p>Design notes:
 * <ul>
 *   <li>Self-contained types (Status / TestDef / TestResult / Listener) — no dependency on
 *       {@code DiLink5TestRunner} or any other runner.</li>
 *   <li>All runner callbacks are delivered on the UI thread.</li>
 *   <li>F08 uses {@link CountDownLatch#await} <em>outside</em> any {@code synchronized} block —
 *       unlike {@code Object.wait()}, {@code CountDownLatch.await()} does not release monitors,
 *       which would deadlock if the callback also tries to acquire the same monitor.</li>
 * </ul>
 */
public final class Dl3FissionRunner {

    private static final String TAG = "Dl3Fission";

    // ── Public types ─────────────────────────────────────────────────────────

    public enum Status { PENDING, RUNNING, PASS, FAIL, SKIPPED }

    public static final class TestDef {
        public final String id;
        public final String title;
        public final String description;
        public TestDef(String id, String title, String description) {
            this.id = id; this.title = title; this.description = description;
        }
    }

    public static final class TestResult {
        public final TestDef def;
        public volatile Status status   = Status.PENDING;
        public volatile String message  = "";   // short result — shown in the UI row
        public volatile long   elapsedMs;
        public TestResult(TestDef def) { this.def = def; }
    }

    public interface Listener {
        void onSuiteStarted(List<TestResult> results);
        void onTestUpdated(int index, TestResult result);
        void onSuiteFinished(List<TestResult> results);

        /**
         * Interactive Yes/No prompt displayed by the Activity.
         * The runner blocks until {@code callback} is invoked (or the 120 s timeout elapses).
         * <p><b>Important:</b> {@code callback} must be called on <em>any</em> thread but
         * never from inside a {@code synchronized} block that the runner thread already holds.
         */
        default void onPromptYesNo(String title, String message, Consumer<Boolean> callback) {
            callback.accept(false);
        }
    }

    // ── Executor / UI handler ────────────────────────────────────────────────

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dl3-fission");
        t.setDaemon(true);
        return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Dl3FissionRunner() {}

    // ── Test catalog ─────────────────────────────────────────────────────────

    public static List<TestDef> catalog() {
        List<TestDef> list = new ArrayList<>();
        list.add(new TestDef("F01",
                "ADB sanity — uid shell",
                "id -u via ADB → uid=2000 attendu (shell). Valide la connectivité ADB locale."));
        list.add(new TestDef("F02",
                "Displays présents",
                "DisplayManager.getDisplays() → ≥ 2 displays (id=0 principal + id=1 cluster xdja)."));
        list.add(new TestDef("F04",
                "Démarrer MirrorDaemon",
                "setsid app_process64 CLASSPATH=<apk> MirrorDaemon → sleep 3 s"
                + " → ps -A | grep [m]irrordaemon → PID trouvé."));
        list.add(new TestDef("F05",
                "Connecter Binder daemon",
                "ServiceManager.getService(\"devtools_mirror_daemon\") via reflection → non-null."));
        list.add(new TestDef("F06",
                "TRANSACT_CLUSTER_ATTACH — Surface cluster",
                "Daemon crée SurfaceControl layer layerStack=1 → retourne Surface."
                + " Le client créera le VD avec cette Surface (OpenBYD 2.2 pattern)."));
        list.add(new TestDef("F03",
                "Créer VirtualDisplay avec Surface cluster (OpenBYD 2.2)",
                "createVirtualDisplay(\"byd_test_vd\", 1920×720, dpi=160, surface=clusterSC,"
                + " flags=322) → displayId + layerStack. Surface passée à la construction."));
        list.add(new TestDef("F07",
                "Lancer app sur VD",
                "am start --display <vdId> -n <component> 2>&1 → pas d'erreur am."));
        list.add(new TestDef("F08",
                "TRANSACT_MIRROR_START (preview tablette)",
                "Binder.transact(vdLayerStack, 1920, 720, vdId, viewW=1920, viewH=720,"
                + " tabletSurface) → reply.readInt() == 1. Miroir VD → SurfaceView tablette."));
        list.add(new TestDef("F09",
                "Confirmation visuelle cluster",
                "AlertDialog : app est-elle visible sur le cluster physique ?"
                + " OUI=PASS / NON=FAIL. Timeout 120 s."));
        list.add(new TestDef("F10",
                "Cleanup",
                "MIRROR_STOP + VD.release() + pkill (libère aussi le SC layer cluster)."));
        return list;
    }

    public static List<TestResult> emptyResults() {
        List<TestResult> out = new ArrayList<>();
        for (TestDef def : catalog()) {
            TestResult r = new TestResult(def);
            r.status = Status.PENDING;
            out.add(r);
        }
        return out;
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    /**
     * Asynchronous run — all listener callbacks delivered on the UI thread.
     *
     * @param ctx            application context
     * @param targetPkg      package to launch on the VD (e.g. "com.android.settings");
     *                       pass {@code null} to fall back to Settings.
     * @param clusterSurface valid Surface from {@link Dl3FissionActivity}'s SurfaceView
     * @param mirrorViewW    width of the SurfaceView buffer (1920)
     * @param mirrorViewH    height of the SurfaceView buffer (720)
     * @param listener       event sink
     */
    public static void run(Context ctx,
                           String targetPkg,
                           Surface clusterSurface, int mirrorViewW, int mirrorViewH,
                           Listener listener) {
        final Context appCtx = ctx.getApplicationContext();
        final List<TestResult> results = emptyResults();

        EXEC.submit(() -> {
            UI.post(() -> listener.onSuiteStarted(results));

            State st = new State();
            st.targetPkg      = (targetPkg != null && !targetPkg.isEmpty())
                                ? targetPkg : "com.android.settings";
            st.clusterSurface = clusterSurface;
            st.mirrorViewW    = mirrorViewW;
            st.mirrorViewH    = mirrorViewH;

            for (int i = 0; i < results.size(); i++) {
                TestResult r = results.get(i);
                r.status = Status.RUNNING;
                final int idx = i;
                UI.post(() -> listener.onTestUpdated(idx, r));

                long t0 = android.os.SystemClock.elapsedRealtime();
                runStep(appCtx, r, st, listener);
                r.elapsedMs = android.os.SystemClock.elapsedRealtime() - t0;

                UI.post(() -> listener.onTestUpdated(idx, r));
            }

            UI.post(() -> listener.onSuiteFinished(results));
        });
    }

    // ── Internal state ───────────────────────────────────────────────────────

    private static final class State {
        String         targetPkg;          // chosen by the user in the picker
        String         targetActivity;     // resolved by F06 via PackageManager
        Surface        clusterSurface;
        int            mirrorViewW;
        int            mirrorViewH;

        // Set by F03
        int            vdDisplayId  = -1;
        int            vdLayerStack = -1;
        VirtualDisplay vd           = null;

        // Set by F05
        IBinder        daemonBinder = null;

        // Set by F06 (CLUSTER_ATTACH)
        Surface        clusterOutputSurface = null;

        // Cleanup flags
        boolean mirrorStarted = false;
        boolean abortFromHere = false;
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    private static void runStep(Context ctx, TestResult r, State st, Listener listener) {
        switch (r.def.id) {
            case "F01": runF01(ctx, r, st); break;
            case "F02": runF02(ctx, r, st); break;
            case "F03": runF03(ctx, r, st); break;
            case "F04": runF04(ctx, r, st); break;
            case "F05": runF05(ctx, r, st); break;
            case "F06": runF06(ctx, r, st); break;
            case "F07": runF07(ctx, r, st); break;
            case "F08": runF08(ctx, r, st); break;
            case "F09": runF09(ctx, r, st, listener); break;
            case "F10": runF10(ctx, r, st); break;
            default: skip(r, "unknown step"); break;
        }
    }

    // ── F01 — ADB sanity uid shell ───────────────────────────────────────────

    private static void runF01(Context ctx, TestResult r, State st) {
        String out = shell(ctx, "id -u", 8000);
        if (out.startsWith("ERROR:")) {
            r.status  = Status.FAIL;
            r.message = "ADB inaccessible : " + out;
            st.abortFromHere = true;
        } else if (out.trim().equals("2000")) {
            r.status  = Status.PASS;
            r.message = "uid=2000 ✓";
        } else {
            r.status  = Status.FAIL;
            r.message = "uid inattendu : \"" + out.trim() + "\" (attendu 2000)";
            st.abortFromHere = true;
        }
    }

    // ── F02 — Displays présents ───────────────────────────────────────────────

    private static void runF02(Context ctx, TestResult r, State st) {
        if (st.abortFromHere) { skip(r, "aborted"); return; }
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            r.status  = Status.FAIL;
            r.message = "DisplayManager unavailable";
            st.abortFromHere = true;
            return;
        }
        Display[] displays = dm.getDisplays();
        int count = (displays == null) ? 0 : displays.length;
        if (count < 2) {
            r.status  = Status.FAIL;
            r.message = count + " display(s) — cluster absent ou non initialisé";
            st.abortFromHere = true;
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Display d : displays) sb.append("id=").append(d.getDisplayId()).append(' ');
        r.status  = Status.PASS;
        r.message = count + " displays : " + sb.toString().trim();
    }

    // ── F03 — Créer VirtualDisplay avec la Surface cluster (OpenBYD 2.2) ────────

    private static void runF03(Context ctx, TestResult r, State st) {
        if (st.abortFromHere) { skip(r, "aborted"); return; }
        if (st.clusterOutputSurface == null || !st.clusterOutputSurface.isValid()) {
            r.status  = Status.FAIL;
            r.message = "clusterOutputSurface null/invalide (F06 échoué)";
            st.abortFromHere = true;
            return;
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                r.status  = Status.FAIL;
                r.message = "DisplayManager unavailable";
                st.abortFromHere = true;
                return;
            }
            // flags=322 = PRESENTATION(2)|SUPPORTS_TOUCH(0x40)|DESTROY_CONTENT_ON_REMOVAL(0x100)
            // Surface passée à la construction — VD rend directement dans le SC layer cluster.
            st.vd = dm.createVirtualDisplay(
                    "byd_test_vd", 1920, 720, /*dpi=*/ 160,
                    st.clusterOutputSurface, /*flags=*/ 322);

            if (st.vd == null) {
                r.status  = Status.FAIL;
                r.message = "createVirtualDisplay() → null";
                st.abortFromHere = true;
                return;
            }
            Display d = st.vd.getDisplay();
            if (d == null) {
                r.status  = Status.FAIL;
                r.message = "VD créé mais getDisplay() null";
                st.abortFromHere = true;
                return;
            }
            st.vdDisplayId  = d.getDisplayId();
            st.vdLayerStack = getLayerStackForDisplay(ctx, st.vdDisplayId);
            if (st.vdLayerStack < 0) {
                r.status  = Status.FAIL;
                r.message = "VD id=" + st.vdDisplayId + " mais getLayerStack() échoué";
                st.abortFromHere = true;
                return;
            }
            r.status  = Status.PASS;
            r.message = "VD id=" + st.vdDisplayId
                      + " layerStack=" + st.vdLayerStack + " flags=322 surface=SC";
        } catch (Exception e) {
            r.status  = Status.FAIL;
            r.message = e.getClass().getSimpleName() + ": " + e.getMessage();
            st.abortFromHere = true;
        }
    }

    // ── F04 — Démarrer MirrorDaemon ───────────────────────────────────────────

    private static void runF04(Context ctx, TestResult r, State st) {
        if (st.abortFromHere) { skip(r, "aborted"); return; }
        try {
            String apkPath = ctx.getPackageCodePath();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String logPath = "/data/local/tmp/devtools_mirrordaemon_" + ts + ".log";
            String cmd = "setsid sh -c 'CLASSPATH=" + apkPath
                    + " /system/bin/app_process64 -Xnoimage-dex2oat /system/bin"
                    + " --nice-name=" + com.dashcast.devtools.common.MirrorDaemon.NICE_NAME
                    + " " + com.dashcast.devtools.common.MirrorDaemon.MAIN_CLASS
                    + " </dev/null >" + logPath + " 2>&1' &";
            AdbClient.executeShell(ctx, cmd);

            // Give the daemon time to register with ServiceManager
            Thread.sleep(3000);

            String ps = shell(ctx, "ps -A | grep [m]irrordaemon", 5000);
            if (ps.startsWith("ERROR:") || ps.trim().isEmpty()) {
                r.status  = Status.FAIL;
                r.message = "MirrorDaemon non trouvé dans ps — log : " + logPath;
                st.abortFromHere = true;
            } else {
                r.status  = Status.PASS;
                r.message = "PID : " + ps.trim().replaceAll("\\s+", " ");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            r.status  = Status.FAIL;
            r.message = "Interrompu pendant sleep(3000)";
            st.abortFromHere = true;
        } catch (Exception e) {
            r.status  = Status.FAIL;
            r.message = e.getClass().getSimpleName() + ": " + e.getMessage();
            st.abortFromHere = true;
        }
    }

    // ── F05 — Connecter Binder daemon ─────────────────────────────────────────

    private static void runF05(Context ctx, TestResult r, State st) {
        if (st.abortFromHere) { skip(r, "aborted"); return; }
        try {
            Class<?> sm  = Class.forName("android.os.ServiceManager");
            Method   get = sm.getDeclaredMethod("getService", String.class);
            get.setAccessible(true);
            IBinder binder = (IBinder) get.invoke(null, "devtools_mirror_daemon");
            if (binder == null) {
                r.status  = Status.FAIL;
                r.message = "ServiceManager.getService(\"devtools_mirror_daemon\") → null";
                st.abortFromHere = true;
            } else {
                st.daemonBinder = binder;
                r.status  = Status.PASS;
                r.message = "Binder ✓ (" + binder.getClass().getSimpleName() + ")";
            }
        } catch (Exception e) {
            r.status  = Status.FAIL;
            r.message = e.getClass().getSimpleName() + ": " + e.getMessage();
            st.abortFromHere = true;
        }
    }

    // ── F06 — TRANSACT_CLUSTER_ATTACH — récupère la Surface SC cluster ────────────

    private static void runF06(Context ctx, TestResult r, State st) {
        if (st.abortFromHere) { skip(r, "aborted"); return; }
        if (st.daemonBinder == null) {
            r.status = Status.FAIL;
            r.message = "Binder null (F05 échoué)";
            st.abortFromHere = true;
            return;
        }
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(com.dashcast.devtools.common.MirrorDaemon.DESCRIPTOR);
            data.writeInt(1);    // layerStack = 1 (cluster BYD)
            data.writeInt(1920);
            data.writeInt(720);
            st.daemonBinder.transact(
                    com.dashcast.devtools.common.MirrorDaemon.TRANSACT_CLUSTER_ATTACH,
                    data, reply, 0);
            reply.readException();
            int ok = reply.readInt();
            if (ok == 1) {
                st.clusterOutputSurface =
                        reply.readParcelable(Surface.class.getClassLoader());
                if (st.clusterOutputSurface != null && st.clusterOutputSurface.isValid()) {
                    r.status  = Status.PASS;
                    r.message = "SC layer layerStack=1 ✓ — Surface prête pour VD (F03)";
                } else {
                    r.status  = Status.FAIL;
                    r.message = "Surface retournée invalide ou null";
                    st.abortFromHere = true;
                }
            } else {
                r.status  = Status.FAIL;
                r.message = "CLUSTER_ATTACH retourne ok=0 — voir logcat MirrorDaemon";
                st.abortFromHere = true;
            }
        } catch (android.os.DeadObjectException doe) {
            r.status  = Status.FAIL;
            r.message = "Binder mort : " + doe.getMessage();
            st.abortFromHere = true;
        } catch (Exception e) {
            r.status  = Status.FAIL;
            r.message = e.getClass().getSimpleName() + ": " + e.getMessage();
            st.abortFromHere = true;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ── F07 — Lancer app sur VD ─────────────────────────────────────────────

    private static void runF07(Context ctx, TestResult r, State st) {
        if (st.abortFromHere) { skip(r, "aborted"); return; }

        // Resolve the launcher component for the chosen package
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        android.content.Intent launchIntent = pm.getLaunchIntentForPackage(st.targetPkg);
        if (launchIntent == null || launchIntent.getComponent() == null) {
            // Fallback: try Settings directly
            if ("com.android.settings".equals(st.targetPkg)) {
                st.targetActivity = "com.android.settings/.Settings";
            } else {
                r.status  = Status.FAIL;
                r.message = "Aucune activité launcher trouvée pour " + st.targetPkg;
                st.abortFromHere = true;
                return;
            }
        } else {
            st.targetActivity = launchIntent.getComponent().flattenToShortString();
        }

        String out = shell(ctx,
                "am start --display " + st.vdDisplayId
                + " -n " + st.targetActivity + " 2>&1",
                6000);
        String low = out.toLowerCase(Locale.US);
        if (out.startsWith("ERROR:") || low.contains("error") || low.contains("exception")) {
            r.status  = Status.FAIL;
            r.message = "am start échoué : " + out.trim();
            st.abortFromHere = true;
        } else {
            r.status  = Status.PASS;
            r.message = st.targetActivity + (out.trim().isEmpty() ? " → OK" : " → " + out.trim());
        }
    }

    // ── F08 — TRANSACT_MIRROR_START (preview tablette) ───────────────────────────

    private static void runF08(Context ctx, TestResult r, State st) {
        if (st.abortFromHere) { skip(r, "aborted"); return; }
        if (st.daemonBinder == null) {
            r.status  = Status.FAIL;
            r.message = "Binder null (F05 n'a pas réussi)";
            st.abortFromHere = true;
            return;
        }
        if (st.clusterSurface == null || !st.clusterSurface.isValid()) {
            r.status  = Status.FAIL;
            r.message = "SurfaceView surface invalide ou détruite";
            st.abortFromHere = true;
            return;
        }
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(com.dashcast.devtools.common.MirrorDaemon.DESCRIPTOR);
            data.writeInt(st.vdLayerStack);
            data.writeInt(1920);                    // srcW
            data.writeInt(720);                     // srcH
            data.writeInt(st.vdDisplayId);          // clusterDisplayId (touch injection target)
            data.writeInt(st.mirrorViewW);           // viewW
            data.writeInt(st.mirrorViewH);           // viewH
            data.writeParcelable(st.clusterSurface, 0);

            st.daemonBinder.transact(com.dashcast.devtools.common.MirrorDaemon.TRANSACT_MIRROR_START, data, reply, 0);
            reply.readException();
            boolean ok = reply.readInt() == 1;

            if (ok) {
                st.mirrorStarted = true;
                r.status  = Status.PASS;
                r.message = "MIRROR_START ✓ layerStack=" + st.vdLayerStack
                          + " vdId=" + st.vdDisplayId;
            } else {
                r.status  = Status.FAIL;
                r.message = "Daemon retourne ok=0 — voir logcat MirrorDaemon";
                st.abortFromHere = true;
            }
        } catch (android.os.DeadObjectException doe) {
            r.status  = Status.FAIL;
            r.message = "Binder mort : " + doe.getMessage();
            st.abortFromHere = true;
        } catch (Exception e) {
            r.status  = Status.FAIL;
            r.message = e.getClass().getSimpleName() + ": " + e.getMessage();
            st.abortFromHere = true;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ── F09 — Confirmation visuelle cluster ────────────────────────────────────────

    private static void runF09(Context ctx, TestResult r, State st, Listener listener) {
        if (st.abortFromHere) { skip(r, "aborted"); return; }

        // RÈGLE ABSOLUE : CountDownLatch.await() HORS de tout synchronized.
        // Contrairement à Object.wait(), await() ne libère PAS le moniteur →
        // deadlock si le callback tente d'acquérir le même lock pour countDown().
        final CountDownLatch latch  = new CountDownLatch(1);
        final boolean[]      answer = { false };

        final String title   = ctx.getString(R.string.fission_dl3_prompt_title);
        final String message = ctx.getString(R.string.fission_dl3_prompt_msg_fmt, st.targetPkg);

        UI.post(() -> listener.onPromptYesNo(title, message, yes -> {
            answer[0] = yes;
            latch.countDown();
        }));

        // await() est appelé ici, sur le thread dl3-fission, PAS dans synchronized.
        try {
            latch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (answer[0]) {
            r.status  = Status.PASS;
            r.message = st.targetPkg + " visible sur le cluster ✓";
        } else {
            r.status  = Status.FAIL;
            r.message = "Non confirmé (ou timeout 120 s)";
        }
    }

    // ── F10 — Cleanup ───────────────────────────────────────────────────────────

    private static void runF10(Context ctx, TestResult r, State st) {
        // F09 was mirrorStarted check — also note F10 always runs regardless of abortFromHere
        StringBuilder sb = new StringBuilder();

        // 1. TRANSACT_MIRROR_STOP
        if (st.mirrorStarted && st.daemonBinder != null) {
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(com.dashcast.devtools.common.MirrorDaemon.DESCRIPTOR);
                st.daemonBinder.transact(com.dashcast.devtools.common.MirrorDaemon.TRANSACT_MIRROR_STOP, data, reply, 0);
                reply.readException();
                sb.append("MIRROR_STOP ✓  ");
            } catch (Exception e) {
                sb.append("MIRROR_STOP ERR: ").append(e.getMessage()).append("  ");
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        // 2. Release VirtualDisplay
        if (st.vd != null) {
            try { st.vd.release(); } catch (Exception ignored) {}
            sb.append("VD.release(id=").append(st.vdDisplayId).append(") ✓  ");
        }

        // 3. Release cluster output surface
        if (st.clusterOutputSurface != null) {
            try { st.clusterOutputSurface.release(); } catch (Exception ignored) {}
            sb.append("clusterSurface.release() ✓  ");
        }

        // 4. Kill daemon process
        String kill = shell(ctx, "pkill -f com.dashcast.devtools.mirrordaemon", 4000);
        sb.append("pkill: ").append(kill.trim().isEmpty() ? "OK" : kill.trim());

        r.status  = Status.PASS;
        r.message = sb.toString().trim();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void skip(TestResult r, String reason) {
        r.status  = Status.SKIPPED;
        r.message = reason;
    }

    /**
     * Blocking shell command via ADB.
     * Uses {@code Object.wait()} which DOES release the monitor — safe to call while holding
     * a lock, unlike {@code CountDownLatch.await()}.
     */
    private static String shell(Context ctx, String cmd, long timeoutMs) {
        AtomicReference<String> out = new AtomicReference<>("");
        AtomicReference<String> err = new AtomicReference<>(null);
        final Object lock = new Object();

        AdbClient.executeShellWithResult(ctx, cmd, new AdbClient.Callback() {
            @Override public void onSuccess(String s) {
                out.set(s == null ? "" : s);
                synchronized (lock) { lock.notifyAll(); }
            }
            @Override public void onError(String e) {
                err.set(e == null ? "?" : e);
                synchronized (lock) { lock.notifyAll(); }
            }
        });

        // Object.wait() releases the monitor → no deadlock with the callback above.
        synchronized (lock) {
            try { lock.wait(timeoutMs); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        return err.get() != null ? ("ERROR: " + err.get()) : out.get();
    }

    /**
     * Derives the layerStack for a given display id via the hidden {@code Display.getLayerStack()}
     * API. Polls up to 2 s to handle the brief delay before a new VD appears in DisplayManager.
     */
    private static int getLayerStackForDisplay(Context ctx, int displayId) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return -1;
            for (int attempt = 0; attempt < 10; attempt++) {
                for (Display d : dm.getDisplays()) {
                    if (d.getDisplayId() == displayId) {
                        Method m = Display.class.getDeclaredMethod("getLayerStack");
                        m.setAccessible(true);
                        return (int) m.invoke(d);
                    }
                }
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "getLayerStackForDisplay(" + displayId + "): " + e.getMessage());
        }
        return -1;
    }

    // ── Report ────────────────────────────────────────────────────────────────

    public static String renderReport(List<TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("DashCast DevTools — DL3 Fission Pipeline Test\n");
        sb.append("===============================================\n");
        for (TestResult r : results) {
            sb.append('[').append(r.status).append("] ")
              .append(r.def.id).append(" — ").append(r.def.title).append('\n');
            if (r.message != null && !r.message.isEmpty()) {
                sb.append("    ").append(r.message).append('\n');
            }
        }
        return sb.toString();
    }
}
