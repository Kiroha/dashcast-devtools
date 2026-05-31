package com.dashcast.devtools.common;

import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.MotionEvent;
import android.view.Surface;

import java.lang.reflect.Method;

/**
 * MirrorDaemon — DevTools daemon shell uid=2000 (app_process64).
 *
 * <p><b>Shared across all fission test runners</b> (DL3, DL4, …). Any runner
 * that needs to drive a VirtualDisplay onto a cluster surface should start this
 * daemon and connect to it via {@link #SERVICE_NAME}.
 *
 * <p>Launch pattern (from any {@code FissionRunner.runFXX()}):
 * <pre>
 *   String apkPath = ctx.getPackageCodePath();
 *   String cmd = "setsid sh -c 'CLASSPATH=" + apkPath
 *       + " /system/bin/app_process64 -Xnoimage-dex2oat /system/bin"
 *       + " --nice-name=" + NICE_NAME
 *       + " " + MAIN_CLASS
 *       + " &lt;/dev/null &gt;" + logPath + " 2&gt;&amp;1' &amp;";
 * </pre>
 *
 * <p>Registers itself as {@value #SERVICE_NAME} in ServiceManager, then
 * loops processing Binder transactions.
 *
 * <p>Handles two transactions:
 * <ul>
 *   <li>{@link #TRANSACT_MIRROR_START} — create a display mirror from {@code vdLayerStack}
 *       onto the provided {@link Surface} using the static SurfaceControl API (API 29).</li>
 *   <li>{@link #TRANSACT_MIRROR_STOP} — tear down the mirror and release the display token.</li>
 * </ul>
 *
 * <p><b>Note on API level:</b> detects the runtime API level and uses the appropriate path:
 * <ul>
 *   <li>API ≤ 30 (DL3 / Android 10): static {@code SurfaceControl.openTransaction()} /
 *       {@code closeTransaction()} model via reflection.</li>
 *   <li>API ≥ 31 (DL5 / Android 12+): {@code SurfaceControl.Transaction} object model
 *       via reflection ({@code setDisplayLayerStack}, {@code setDisplaySurface},
 *       {@code setDisplayProjection}, {@code apply}).</li>
 * </ul>
 */
public final class MirrorDaemon {

    // ── Launch constants ────────────────────────────────────────────────────

    /** Fully-qualified class name to pass to {@code app_process64}. */
    public static final String MAIN_CLASS =
            "com.dashcast.devtools.common.MirrorDaemon";

    /** {@code --nice-name} argument (visible in {@code ps -A}). */
    public static final String NICE_NAME =
            "com.dashcast.devtools.mirrordaemon";

    // ── Protocol constants ──────────────────────────────────────────────────

    /** ServiceManager key under which the daemon registers itself. */
    public static final String SERVICE_NAME = "devtools_mirror_daemon";

    /** Action broadcast when daemon is ready. Not used by runners (they poll ServiceManager). */
    public static final String ACTION_DAEMON_READY =
            "com.dashcast.devtools.MIRROR_DAEMON_READY";

    /** Binder interface descriptor — must match the client's {@code writeInterfaceToken} call. */
    public static final String DESCRIPTOR =
            "com.dashcast.devtools.daemon.IMirrorDaemon";

    /** TRANSACT 1 — configure the SurfaceControl mirror. */
    public static final int TRANSACT_MIRROR_START  = 1;
    /** TRANSACT 2 — inject a MotionEvent onto the cluster display. */
    public static final int TRANSACT_INJECT_MOTION = 2;
    /** TRANSACT 3 — inject a KeyEvent onto the cluster display. */
    public static final int TRANSACT_INJECT_KEY    = 3;
    /** TRANSACT 4 — destroy the mirror. */
    public static final int TRANSACT_MIRROR_STOP    = 4;
    /**
     * TRANSACT 5 — create a SurfaceControl buffer layer on layerStack=1 (cluster display),
     * return its {@link Surface} as the output surface of the VirtualDisplay.
     * Wire: writeInterfaceToken + writeInt(layerStack) + writeInt(w) + writeInt(h).
     * Reply: writeNoException() + writeInt(1) + writeParcelable(Surface) on success,
     *        writeInt(0) on failure.
     */
    public static final int TRANSACT_CLUSTER_ATTACH = 5;

    // ── Daemon state ────────────────────────────────────────────────────────

    /** Active display mirror token (created by MIRROR_START, released by MIRROR_STOP). */
    private static volatile IBinder sMirrorToken = null;
    /** Active cluster SurfaceControl layer (created by CLUSTER_ATTACH, released by MIRROR_STOP). */
    private static volatile Object sClusterSc = null;
    /**
     * Display id of the VirtualDisplay where the app runs — set by MIRROR_START,
     * used by INJECT_MOTION (same pattern as production MirrorDaemon).
     */
    private static volatile int sClusterDisplayId = -1;

    // InputManager reflection — initialised once in main(), reused on every event.
    private static volatile Object sInputManager   = null;
    private static volatile Method sInjectMethod   = null;
    private static volatile Method sSetDisplayId   = null;  // MotionEvent.setDisplayId — may be null

    // ── Entry point ─────────────────────────────────────────────────────────

    /**
     * Process entry point — called by {@code app_process64} via the {@code CLASSPATH} trick.
     */
    public static void main(String[] args) {
        // setArgV0 is @hide — use reflection (--nice-name in launch cmd already sets the name)
        try {
            java.lang.reflect.Method setArgV0 = android.os.Process.class
                    .getDeclaredMethod("setArgV0", String.class);
            setArgV0.setAccessible(true);
            setArgV0.invoke(null, NICE_NAME);
        } catch (Exception ignored) {}
        log("starting (pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid() + ")");

        Looper.prepareMainLooper();

        MirrorBinder binder = new MirrorBinder();
        if (!registerService(SERVICE_NAME, binder)) {
            log("FATAL: ServiceManager.addService failed — exiting");
            return;
        }
        log("registered as " + SERVICE_NAME + ", entering Looper");

        initInputManager();

        Looper.loop();
    }

    // ── Binder implementation ────────────────────────────────────────────────

    private static final class MirrorBinder extends Binder {
        MirrorBinder() {
            attachInterface(null, DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case TRANSACT_MIRROR_START:   return handleMirrorStart(data, reply);
                case TRANSACT_MIRROR_STOP:    return handleMirrorStop(data, reply);
                case TRANSACT_CLUSTER_ATTACH: return handleClusterAttach(data, reply);
                case TRANSACT_INJECT_MOTION:  return handleInjectMotion(data, reply);
                default: return super.onTransact(code, data, reply, flags);
            }
        }
    }

    // ── Transaction handlers ─────────────────────────────────────────────────

    /**
     * TRANSACT_MIRROR_START — wire format written by the fission runner:
     * <pre>
     *   writeInterfaceToken(DESCRIPTOR)
     *   writeInt(vdLayerStack)       — layerStack of the VirtualDisplay to mirror
     *   writeInt(srcW = 1920)        — source width
     *   writeInt(srcH = 720)         — source height
     *   writeInt(clusterDisplayId)   — display id (touch injection target)
     *   writeInt(viewW)              — destination surface width
     *   writeInt(viewH)              — destination surface height
     *   writeParcelable(clusterSurface)
     * </pre>
     * Reply: {@code writeNoException() + writeInt(1)} on success, {@code writeInt(0)} on failure.
     */
    private static boolean handleMirrorStart(Parcel data, Parcel reply) {
        data.enforceInterface(DESCRIPTOR);
        int layerStack       = data.readInt();
        int srcW             = data.readInt();
        int srcH             = data.readInt();
        int clusterDisplayId = data.readInt();
        sClusterDisplayId    = clusterDisplayId; // store for INJECT_MOTION
        int viewW            = data.readInt();
        int viewH            = data.readInt();
        Surface surface      = data.readParcelable(Surface.class.getClassLoader());

        log("MIRROR_START layerStack=" + layerStack
                + " src=" + srcW + "×" + srcH
                + " view=" + viewW + "×" + viewH
                + " clusterDisplayId=" + clusterDisplayId);

        try {
            if (sMirrorToken != null) {
                scDestroyDisplay(sMirrorToken);
                sMirrorToken = null;
            }

            IBinder token = scCreateDisplay("devtools_mirror_" + layerStack, /*secure=*/ false);

            Rect src  = new Rect(0, 0, srcW, srcH);
            Rect dest = new Rect(0, 0, viewW, viewH);
            if (Build.VERSION.SDK_INT >= 31) {
                log("MIRROR_START using Transaction API (SDK " + Build.VERSION.SDK_INT + ")");
                scApplyTransaction(token, layerStack, surface, src, dest);
            } else {
                log("MIRROR_START using static openTransaction API (SDK " + Build.VERSION.SDK_INT + ")");
                scOpenTransaction();
                try {
                    scSetDisplayLayerStack(token, layerStack);
                    scSetDisplaySurface(token, surface);
                    scSetDisplayProjection(token, /*orientation=*/ 0, src, dest);
                } finally {
                    scCloseTransaction();
                }
            }

            sMirrorToken = token;
            log("MIRROR_START OK token=" + token);
            reply.writeNoException();
            reply.writeInt(1);

        } catch (Exception e) {
            log("MIRROR_START ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            reply.writeNoException();
            reply.writeInt(0);
        }
        return true;
    }

    private static boolean handleMirrorStop(Parcel data, Parcel reply) {
        data.enforceInterface(DESCRIPTOR);
        log("MIRROR_STOP");
        if (sMirrorToken != null) {
            try { scDestroyDisplay(sMirrorToken); } catch (Exception ignored) {}
            sMirrorToken = null;
        }
        // Also release cluster SC layer if attached
        if (sClusterSc != null) {
            try {
                Class<?> scCls = Class.forName("android.view.SurfaceControl");
                Method release = scCls.getDeclaredMethod("release");
                release.setAccessible(true);
                release.invoke(sClusterSc);
                log("CLUSTER SC released");
            } catch (Exception e) {
                log("CLUSTER SC release error: " + e.getMessage());
            }
            sClusterSc = null;
        }
        reply.writeNoException();
        return true;
    }

    /**
     * TRANSACT_INJECT_MOTION — injects a {@link MotionEvent} onto the VirtualDisplay.
     * Called FLAG_ONEWAY by the client (reply may be null) — never writes to reply.
     *
     * <p>Wire format (client side):
     * <pre>
     *   writeInterfaceToken(DESCRIPTOR)
     *   writeParcelable(event) — MotionEvent with coords in display space (1920×720)
     * </pre>
     * The target displayId is {@link #sClusterDisplayId}, set by the last MIRROR_START.
     * No reply (fire-and-forget — latency-critical at 60-120 events/s).
     */
    private static boolean handleInjectMotion(Parcel data, Parcel reply) {
        data.enforceInterface(DESCRIPTOR);
        MotionEvent event = data.readParcelable(MotionEvent.class.getClassLoader());
        if (event != null) {
            try {
                if (sSetDisplayId != null) {
                    sSetDisplayId.invoke(event, sClusterDisplayId);
                }
                if (sInjectMethod != null) {
                    sInjectMethod.invoke(sInputManager, event, 0 /* ASYNC */);
                }
            } catch (Exception e) {
                log("INJECT_MOTION ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                event.recycle();
            }
        }
        // FLAG_ONEWAY: reply is null — do not touch it.
        return true;
    }

    /** Initialises InputManager + reflection methods once at startup. */
    private static void initInputManager() {
        try {
            Class<?> imCls = Class.forName("android.hardware.input.InputManager");
            Method getInst = imCls.getDeclaredMethod("getInstance");
            getInst.setAccessible(true);
            sInputManager = getInst.invoke(null);
            sInjectMethod = imCls.getDeclaredMethod(
                    "injectInputEvent", android.view.InputEvent.class, int.class);
            sInjectMethod.setAccessible(true);
            try {
                sSetDisplayId = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
                sSetDisplayId.setAccessible(true);
            } catch (Exception ignored) { /* ROM without setDisplayId */ }
            log("InputManager init OK");
        } catch (Exception e) {
            log("initInputManager ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * TRANSACT_CLUSTER_ATTACH — creates a SurfaceControl buffer layer assigned to
     * {@code layerStack} (=1 for the BYD cluster display) and returns its {@link Surface}.
     * The caller passes this Surface to {@link android.hardware.display.VirtualDisplay#setSurface}
     * so that anything rendered on the VirtualDisplay is composited directly onto the cluster.
     *
     * <p>Wire format (client side):
     * <pre>
     *   writeInterfaceToken(DESCRIPTOR)
     *   writeInt(layerStack)   — target display layerStack (1 = cluster)
     *   writeInt(w)            — buffer width  (1920)
     *   writeInt(h)            — buffer height (720)
     * </pre>
     * Reply: {@code writeNoException() + writeInt(1) + writeParcelable(Surface)} on success,
     *        {@code writeNoException() + writeInt(0)} on failure.
     */
    private static boolean handleClusterAttach(Parcel data, Parcel reply) {
        data.enforceInterface(DESCRIPTOR);
        int layerStack = data.readInt();
        int w          = data.readInt();
        int h          = data.readInt();
        log("CLUSTER_ATTACH layerStack=" + layerStack + " " + w + "×" + h);
        try {
            Class<?> scCls      = Class.forName("android.view.SurfaceControl");
            Class<?> sessionCls = Class.forName("android.view.SurfaceSession");

            // 1. SurfaceSession — needed by Builder on API 29
            Object session = sessionCls.getDeclaredConstructor().newInstance();

            // 2. SurfaceControl.Builder — API 29: Builder(SurfaceSession), API 31+: Builder()
            Class<?> builderCls = Class.forName("android.view.SurfaceControl$Builder");
            Object builder;
            try {
                java.lang.reflect.Constructor<?> ctor =
                        builderCls.getDeclaredConstructor(sessionCls);
                ctor.setAccessible(true);
                builder = ctor.newInstance(session);
            } catch (NoSuchMethodException e) {
                java.lang.reflect.Constructor<?> ctor =
                        builderCls.getDeclaredConstructor();
                ctor.setAccessible(true);
                builder = ctor.newInstance();
            }

            // 3. Configure: name + buffer size
            Method mName = builderCls.getDeclaredMethod("setName", String.class);
            mName.setAccessible(true);
            mName.invoke(builder, "devtools_cluster_out");

            // setBufferSize (API 30+) or setSize (API 29)
            try {
                Method m = builderCls.getDeclaredMethod("setBufferSize", int.class, int.class);
                m.setAccessible(true); m.invoke(builder, w, h);
            } catch (NoSuchMethodException e) {
                Method m = builderCls.getDeclaredMethod("setSize", int.class, int.class);
                m.setAccessible(true); m.invoke(builder, w, h);
            }

            // 4. build() → SurfaceControl instance
            Method build = builderCls.getDeclaredMethod("build");
            build.setAccessible(true);
            Object sc = build.invoke(builder);

            // 5. Apply layer properties: setLayerStack + setLayer + show
            // Transaction.setLayerStack(SurfaceControl, int) exists since API 29.
            // The static setLayerStack(SurfaceControl, int) does NOT exist on API 29
            // (static setLayerStack only accepts IBinder displayToken, not SurfaceControl).
            // → always use Transaction regardless of SDK level.
            android.view.SurfaceControl.Transaction tx =
                    new android.view.SurfaceControl.Transaction();
            Class<?> txCls = tx.getClass();
            Method mSetLS  = txCls.getDeclaredMethod("setLayerStack", scCls, int.class);
            Method mSetLyr = txCls.getDeclaredMethod("setLayer",      scCls, int.class);
            Method mShow   = txCls.getDeclaredMethod("show",          scCls);
            mSetLS.setAccessible(true);  mSetLS.invoke(tx, sc, layerStack);
            mSetLyr.setAccessible(true); mSetLyr.invoke(tx, sc, Integer.MAX_VALUE - 1);
            mShow.setAccessible(true);   mShow.invoke(tx, sc);
            tx.apply();

            // 6. Wrap SurfaceControl in a Surface via @hide constructor Surface(SurfaceControl)
            java.lang.reflect.Constructor<?> surfCtor =
                    Surface.class.getDeclaredConstructor(scCls);
            surfCtor.setAccessible(true);
            Surface outputSurface = (Surface) surfCtor.newInstance(sc);

            sClusterSc = sc;
            log("CLUSTER_ATTACH OK sc=" + sc + " surface.valid=" + outputSurface.isValid());
            reply.writeNoException();
            reply.writeInt(1);
            reply.writeParcelable(outputSurface, 0);

        } catch (Exception e) {
            log("CLUSTER_ATTACH ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            reply.writeNoException();
            reply.writeInt(0);
        }
        return true;
    }

    // ── ServiceManager ───────────────────────────────────────────────────────

    private static boolean registerService(String name, IBinder binder) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            try {
                Method add = sm.getDeclaredMethod("addService", String.class, IBinder.class);
                add.setAccessible(true);
                add.invoke(null, name, binder);
                return true;
            } catch (NoSuchMethodException ignored) {}
            // 4-param fallback (API 26+)
            Method add4 = sm.getDeclaredMethod("addService",
                    String.class, IBinder.class, boolean.class, int.class);
            add4.setAccessible(true);
            add4.invoke(null, name, binder, false, 0);
            return true;
        } catch (Exception e) {
            log("registerService ERROR: " + e);
            return false;
        }
    }

    // ── SurfaceControl — API 31+ Transaction path ───────────────────────────

    /**
     * API 31+ path: apply display config via {@code SurfaceControl.Transaction}.
     * Uses reflection since these are {@code @hide} methods.
     */
    private static void scApplyTransaction(IBinder token, int layerStack, Surface surface,
                                           Rect src, Rect dest) throws Exception {
        Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
        Object txn = txClass.getDeclaredConstructor().newInstance();
        try {
            Method setLayerStack = txClass.getDeclaredMethod(
                    "setDisplayLayerStack", IBinder.class, int.class);
            setLayerStack.setAccessible(true);
            setLayerStack.invoke(txn, token, layerStack);

            Method setSurface = txClass.getDeclaredMethod(
                    "setDisplaySurface", IBinder.class, Surface.class);
            setSurface.setAccessible(true);
            setSurface.invoke(txn, token, surface);

            Method setProjection = txClass.getDeclaredMethod(
                    "setDisplayProjection",
                    IBinder.class, int.class, Rect.class, Rect.class);
            setProjection.setAccessible(true);
            setProjection.invoke(txn, token, 0, src, dest);

            Method apply = txClass.getDeclaredMethod("apply");
            apply.setAccessible(true);
            apply.invoke(txn);
        } finally {
            // SurfaceControl.Transaction implements AutoCloseable on API 31+
            try {
                Method close = txClass.getDeclaredMethod("close");
                close.setAccessible(true);
                close.invoke(txn);
            } catch (NoSuchMethodException ignored) {}
        }
    }

    // ── SurfaceControl static API (API ≤ 30) — via reflection ────────────────

    private static IBinder scCreateDisplay(String name, boolean secure) throws Exception {
        return (IBinder) sc("createDisplay", new Class[]{String.class, boolean.class}, name, secure);
    }
    private static void scDestroyDisplay(IBinder token) throws Exception {
        sc("destroyDisplay", new Class[]{IBinder.class}, token);
    }
    private static void scOpenTransaction() throws Exception {
        sc("openTransaction", new Class[]{});
    }
    private static void scCloseTransaction() throws Exception {
        sc("closeTransaction", new Class[]{});
    }
    private static void scSetDisplayLayerStack(IBinder token, int layerStack) throws Exception {
        sc("setDisplayLayerStack", new Class[]{IBinder.class, int.class}, token, layerStack);
    }
    private static void scSetDisplaySurface(IBinder token, Surface surface) throws Exception {
        sc("setDisplaySurface", new Class[]{IBinder.class, Surface.class}, token, surface);
    }
    private static void scSetDisplayProjection(IBinder token, int orientation,
                                                Rect layerStackRect, Rect displayRect)
            throws Exception {
        sc("setDisplayProjection",
                new Class[]{IBinder.class, int.class, Rect.class, Rect.class},
                token, orientation, layerStackRect, displayRect);
    }
    private static Object sc(String method, Class<?>[] params, Object... args) throws Exception {
        Class<?> scClass = Class.forName("android.view.SurfaceControl");
        Method m = scClass.getDeclaredMethod(method, params);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    private static void log(String msg) {
        System.out.println("[MirrorDaemon] " + msg);
        try {
            Class<?> logClass = Class.forName("android.util.Log");
            Method d = logClass.getDeclaredMethod("d", String.class, String.class);
            d.invoke(null, "MirrorDaemon", msg);
        } catch (Exception ignored) {}
    }

    private MirrorDaemon() {}
}
