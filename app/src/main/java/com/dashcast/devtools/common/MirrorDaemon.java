package com.dashcast.devtools.common;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
     * Side-effect: if daemon holds a VD (sVdCallback != null), also calls
     * IDisplayManager.setVirtualDisplaySurface() internally.
     */
    public static final int TRANSACT_CLUSTER_ATTACH = 5;
    /**
     * TRANSACT 6 — create a FLAG_TRUSTED VirtualDisplay from the daemon (shell uid=2000,
     * holds INTERNAL_SYSTEM_WINDOW). Apps like Waze check {@code Display.FLAG_TRUSTED} at
     * runtime and refuse to run on untrusted displays.
     * Wire: writeInterfaceToken + writeInt(w) + writeInt(h) + writeInt(dpi).
     * Reply: writeNoException() + writeInt(displayId) or -1 on failure.
     * The daemon stores the VD callback binder (sVdCallback). MIRROR_STOP releases it.
     */
    public static final int TRANSACT_CREATE_VD = 6;
    /**
     * TRANSACT 7 — launch {@code pkg} on display 0 then move its task to {@code displayId}.
     * Wire: writeInterfaceToken + writeString(pkg) + writeInt(displayId) + writeInt(w) + writeInt(h).
     * Reply: writeNoException() + writeString(log) — "OK taskId=X\n..." or "ERROR: ...".
     *
     * <p>Rationale: {@code am start --display <id>} triggers ATMS
     * {@code canPlaceEntityOnDisplay()} <em>before</em> launch, which rejects apps
     * like Waze that have not opted into secondary displays.  Launching on display 0
     * first and moving the task via {@code IActivityTaskManager.moveRootTaskToDisplay()}
     * bypasses that check.  uid=2000 (shell) is exempt from hidden-API restrictions.
     */
    public static final int TRANSACT_LAUNCH_AND_FORCE = 7;

    // ── Daemon state ────────────────────────────────────────────────────────

    /** Active display mirror token (created by MIRROR_START, released by MIRROR_STOP). */
    private static volatile IBinder sMirrorToken = null;
    /** Active cluster SurfaceControl layer (created by CLUSTER_ATTACH, released by MIRROR_STOP). */
    private static volatile Object sClusterSc = null;
    /** Active cluster overlay SurfaceView host (OpenBYD-style path, released by MIRROR_STOP). */
    private static volatile View sClusterOverlayView = null;
    /** Display-scoped window manager that owns {@link #sClusterOverlayView}. */
    private static volatile WindowManager sClusterOverlayWindowManager = null;
    /**
     * Display id of the VirtualDisplay where the app runs — set by MIRROR_START,
     * used by INJECT_MOTION (same pattern as production MirrorDaemon).
     */
    private static volatile int sClusterDisplayId = -1;

    // InputManager reflection — initialised once in main(), reused on every event.
    private static volatile Object sInputManager   = null;
    private static volatile Method sInjectMethod   = null;
    private static volatile Method sSetDisplayId   = null;  // MotionEvent.setDisplayId — may be null

    // Context used to call DisplayManager.createVirtualDisplay() (OpenBYD approach).
    // Obtained via ActivityThread.currentActivityThread() + createPackageContext("com.android.shell", 0).
    // Shell uid=2000 owns "com.android.shell" → validatePackageName() passes.
    private static volatile Context        sContext       = null;
    // Raw system context (ActivityThread.getSystemContext()) — has framework Resources loaded,
    // unlike sContext (createPackageContext in systemMain mode has no app resources).
    // Used exclusively where View construction requires a valid getResources() call.
    private static volatile Context        sSysContext    = null;
    /** Active VirtualDisplay — created by TRANSACT_CREATE_VD, released by MIRROR_STOP. */
    private static volatile VirtualDisplay sVirtualDisplay = null;

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
        initContext();

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
                case TRANSACT_CREATE_VD:          return handleCreateVd(data, reply);
                case TRANSACT_LAUNCH_AND_FORCE:    return handleLaunchAndForce(data, reply);
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
        // Release cluster SC layer if attached
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
        releaseClusterOverlay();
        // Release VirtualDisplay created by daemon (OpenBYD approach).
        if (sVirtualDisplay != null) {
            try {
                sVirtualDisplay.release();
                log("VD released");
            } catch (Exception e) {
                log("VD release ERROR: " + e.getMessage());
            }
            sVirtualDisplay = null;
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
     * Obtains a {@link Context} scoped to {@code com.android.shell} (uid=2000).
     *
     * <p>OpenBYD approach: use the standard {@code DisplayManager.createVirtualDisplay()} API
     * instead of raw IDisplayManager binder transactions. This avoids hand-crafted Parcel
     * format issues and ensures DisplayInfo fields (touch, interactive, etc.) are set correctly.
     *
     * <p>Implementation: {@code ActivityThread.currentActivityThread()} returns the existing
     * ActivityThread initialised by {@code app_process}. Then
     * {@code getSystemContext().createPackageContext("com.android.shell", 0)}
     * returns a Context with the shell package identity. Since the process runs as uid=2000
     * and {@code getPackagesForUid(2000)} includes {@code "com.android.shell"},
     * {@code DisplayManagerService.validatePackageName()} accepts it.
     */
    private static void initContext() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");

            // Try currentActivityThread() first (non-destructive).
            // NOTE: returns null when app_process hasn't initialized a thread yet.
            Method current = atCls.getMethod("currentActivityThread");
            Object at = current.invoke(null);

            if (at == null) {
                // No thread yet — initialise a system ActivityThread.
                // Requires Looper.prepareMainLooper() already called in main().
                Method systemMain = atCls.getDeclaredMethod("systemMain");
                systemMain.setAccessible(true);
                at = systemMain.invoke(null);
                log("initContext: used systemMain() (currentActivityThread was null)");
            }

            Method getSystemCtx = atCls.getDeclaredMethod("getSystemContext");
            getSystemCtx.setAccessible(true);
            Context sysCtx = (Context) getSystemCtx.invoke(at);

            // Flag 0 — identical to OpenBYD. uid=2000 owns com.android.shell so
            // validatePackageName() passes without needing CONTEXT_IGNORE_SECURITY.
            sSysContext = sysCtx;
            sContext = sysCtx.createPackageContext("com.android.shell", 0);
            log("Context init OK pkg=" + sContext.getPackageName()
                    + " uid=" + android.os.Process.myUid());
        } catch (Exception e) {
            log("initContext ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * TRANSACT_CREATE_VD — creates a VirtualDisplay via the standard
     * {@link DisplayManager#createVirtualDisplay} API (OpenBYD approach).
     *
     * <p>Uses {@link #sContext} (a package context for {@code com.android.shell}, obtained
     * in {@link #initContext()}). The process runs as uid=2000 which owns
     * {@code com.android.shell}, so {@code DisplayManagerService.validatePackageName()} passes.
     *
     * <p>Tries flags=1346 (322|1024) first: adds {@code VIRTUAL_DISPLAY_FLAG_TRUSTED} (0x400)
     * so that {@code DisplayContent.canAddActivity()} returns true for ALL activities, including
     * non-resizable ones (e.g. {@code com.waze/.MainActivity}). Requires
     * {@code CREATE_TRUSTED_VIRTUAL_DISPLAY} permission; falls back to flags=322 if denied.
     * Flags base 322 = PRESENTATION(0x02) | SUPPORTS_TOUCH(0x40) | DESTROY_CONTENT_ON_REMOVAL(0x100).
     *
     * <p>Wire format: writeInterfaceToken + writeInt(w) + writeInt(h) + writeInt(dpi)
     * <p>Reply: writeNoException() + writeInt(displayId) or -1 on failure.
     */
    private static boolean handleCreateVd(Parcel data, Parcel reply) {
        data.enforceInterface(DESCRIPTOR);
        int w   = data.readInt();
        int h   = data.readInt();
        int dpi = data.readInt();
        log("CREATE_VD " + w + "×" + h + " dpi=" + dpi);

        if (sContext == null) {
            log("CREATE_VD: Context not initialized (initContext failed)");
            reply.writeNoException();
            reply.writeInt(-1);
            return true;
        }

        try {
            // Release any previous VD
            if (sVirtualDisplay != null) {
                sVirtualDisplay.release();
                sVirtualDisplay = null;
            }

            DisplayManager dm = sContext.getSystemService(DisplayManager.class);
            // Try flags = 1346 (322 | 1024) = PRESENTATION | SUPPORTS_TOUCH | DESTROY_CONTENT_ON_REMOVAL | TRUSTED.
            // VIRTUAL_DISPLAY_FLAG_TRUSTED (1024) makes canPlaceEntityOnDisplay() return true for
            // ALL activities, including non-resizable ones (e.g. com.waze/.MainActivity). On some
            // Android 10 ROMs this requires CREATE_TRUSTED_VIRTUAL_DISPLAY permission; if the
            // daemon lacks it, DisplayManagerService silently strips the flag or throws — we fall
            // back to flags=322 in that case.
            VirtualDisplay vd = null;
            try {
                vd = dm.createVirtualDisplay(
                        "devtools_projection_vd",
                        w, h, dpi,
                        /*surface=*/ null,
                        /*flags=*/   1346 /* 322 | 1024: +TRUSTED */);
                if (vd != null) log("CREATE_VD using TRUSTED flags=1346");
            } catch (Exception eTrusted) {
                log("CREATE_VD TRUSTED failed (" + eTrusted.getMessage() + "), fallback to 322");
            }
            if (vd == null) {
                vd = dm.createVirtualDisplay(
                        "devtools_projection_vd",
                        w, h, dpi,
                        /*surface=*/ null,
                        /*flags=*/   322 /* PRESENTATION | SUPPORTS_TOUCH | DESTROY_CONTENT_ON_REMOVAL */);
            }

            if (vd == null) throw new RuntimeException("createVirtualDisplay returned null");

            sVirtualDisplay = vd;
            int displayId = vd.getDisplay().getDisplayId();
            log("CREATE_VD OK displayId=" + displayId);
            reply.writeNoException();
            reply.writeInt(displayId);
        } catch (Exception e) {
            log("CREATE_VD ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            sVirtualDisplay = null;
            reply.writeNoException();
            reply.writeInt(-1);
        }
        return true;
    }

    /**
     * TRANSACT_LAUNCH_AND_FORCE — launches {@code pkg} on display 0 then moves its task
     * to {@code displayId} via {@code IActivityTaskManager.moveRootTaskToDisplay()}.
     *
     * <p>Strategy (mirrors OpenBYD {@code launchAndForce}):
     * <ol>
     *   <li>Resolve LAUNCHER component: {@code cmd package resolve-activity --brief -c
     *       android.intent.category.LAUNCHER <pkg>}</li>
     *   <li>Launch on display 0: {@code am start -n <component>} (no {@code --display}).</li>
     *   <li>Poll for task ID via {@code IActivityTaskManager.getTasks()} — up to 15 × 500 ms.</li>
     *   <li>Move: {@code moveRootTaskToDisplay(taskId, displayId)} or fallback
     *       {@code moveTaskToDisplay(taskId, displayId)}.</li>
     *   <li>Focus: {@code setFocusedTask(taskId)}.</li>
     * </ol>
     * Wire: writeInterfaceToken + writeString(pkg) + writeInt(displayId) + writeInt(w) + writeInt(h).
     * Reply: writeNoException() + writeString(log) — starts with "OK" on success.
     */
    @SuppressWarnings({"unchecked", "JavaReflectionMemberAccess"})
    private static boolean handleLaunchAndForce(Parcel data, Parcel reply)
            throws RemoteException {
        data.enforceInterface(DESCRIPTOR);
        String pkg       = data.readString();
        int    displayId = data.readInt();
        int    w         = data.readInt();
        int    h         = data.readInt();

        StringBuilder sb = new StringBuilder();
        try {
            // Step 1 — resolve LAUNCHER component (same as OpenBYD strategy 1)
            String component = null;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{
                    "cmd", "package", "resolve-activity", "--brief",
                    "-c", "android.intent.category.LAUNCHER", pkg});
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("/") && !line.startsWith("No ")) {
                        component = line.trim();
                        break;
                    }
                }
                br.close();
                p.waitFor();
            } catch (Exception e) {
                sb.append("resolve-activity exception: ").append(e.getMessage()).append("\n");
            }

            // Step 2 — launch on display 0 (no --display to bypass ATMS canPlaceEntityOnDisplay)
            if (component != null) {
                Process p2 = Runtime.getRuntime().exec(new String[]{"am", "start", "-n", component});
                p2.waitFor();
                sb.append("Strategy 1 (am start -n ").append(component).append("): launched\n");
            } else {
                // Fallback: am start by package
                Process p2 = Runtime.getRuntime().exec(
                        new String[]{"am", "start", "-a", "android.intent.action.MAIN", pkg});
                p2.waitFor();
                sb.append("Strategy 2 (am start -a MAIN ").append(pkg).append("): launched\n");
            }

            // Step 3 — poll for task ID (up to 15 × 500 ms, same as OpenBYD)
            Class<?> atmCls  = Class.forName("android.app.ActivityTaskManager");
            Object   iatm    = atmCls.getMethod("getService").invoke(null);
            Class<?> iatmCls = iatm.getClass();

            // Find getTasks — try (int, boolean, boolean) first, then (int)
            java.lang.reflect.Method getTasks = null;
            for (java.lang.reflect.Method m : getAllMethods(iatmCls)) {
                if (!m.getName().equals("getTasks")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0] == int.class
                        && p[1] == boolean.class && p[2] == boolean.class) {
                    getTasks = m; break;
                }
                if (p.length == 1 && p[0] == int.class) getTasks = m;
            }
            if (getTasks == null) throw new RuntimeException("getTasks not found");
            getTasks.setAccessible(true);

            int taskId  = -1;
            int stackId = -1;
            for (int attempt = 0; attempt < 15 && taskId == -1; attempt++) {
                if (attempt > 0) Thread.sleep(500);
                java.util.List<?> tasks;
                if (getTasks.getParameterCount() == 3) {
                    tasks = (java.util.List<?>) getTasks.invoke(iatm, 100, false, false);
                } else {
                    tasks = (java.util.List<?>) getTasks.invoke(iatm, 100);
                }
                for (Object t : tasks) {
                    // Check topActivity or baseActivity package
                    for (String field : new String[]{"topActivity", "baseActivity"}) {
                        try {
                            Object comp = t.getClass().getField(field).get(t);
                            if (comp instanceof android.content.ComponentName) {
                                String tPkg = ((android.content.ComponentName) comp).getPackageName();
                                if (pkg.equals(tPkg)) {
                                    taskId = t.getClass().getField("taskId").getInt(t);
                                    // stackId available in API 29 RunningTaskInfo (deprecated in 31)
                                    try {
                                        stackId = t.getClass().getField("stackId").getInt(t);
                                    } catch (Exception ignored) {}
                                    sb.append("Found taskId=").append(taskId)
                                      .append(" stackId=").append(stackId)
                                      .append(" (attempt ").append(attempt).append(")\n");
                                    break;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    if (taskId != -1) break;
                }
            }
            if (taskId == -1) {
                reply.writeNoException();
                reply.writeString("ERROR: taskId not found for " + pkg + "\n" + sb);
                return true;
            }

            // Step 3b — resolve the REAL stackId by querying getAllStackInfos().
            // RunningTaskInfo.stackId can return the "preferred" stack, not the current one.
            // getAllStackInfos() lists all stacks with their taskIds[] — use that to find
            // the stack that actually contains our taskId.
            try {
                java.lang.reflect.Method getAllStacks = null;
                for (java.lang.reflect.Method m : getAllMethods(iatmCls)) {
                    if (m.getName().equals("getAllStackInfos") && m.getParameterCount() == 0) {
                        getAllStacks = m; break;
                    }
                }
                if (getAllStacks != null) {
                    getAllStacks.setAccessible(true);
                    java.util.List<?> stacks = (java.util.List<?>) getAllStacks.invoke(iatm);
                    if (stacks != null) {
                        for (Object si : stacks) {
                            int[] taskIds = (int[]) si.getClass().getField("taskIds").get(si);
                            if (taskIds != null) {
                                for (int tid : taskIds) {
                                    if (tid == taskId) {
                                        int realStackId = si.getClass().getField("stackId").getInt(si);
                                        sb.append("getAllStackInfos: taskId=").append(taskId)
                                          .append(" is in stackId=").append(realStackId)
                                          .append(" (was ").append(stackId).append(")\n");
                                        stackId = realStackId;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } else {
                    sb.append("getAllStackInfos not found — using RunningTaskInfo.stackId\n");
                }
            } catch (Exception e) {
                sb.append("getAllStackInfos exception: ").append(e.getMessage()).append("\n");
            }

            // Step 4a — setTaskWindowingMode(WINDOWING_MODE_FREEFORM=5) BEFORE the move.
            // Freeform tasks receive onConfigurationChanged instead of being relaunched
            // when moved between displays — this bypasses in-onCreate display checks
            // (e.g. Waze finishAndRemoveTask on getDisplayId()!=0).
            // Source: OpenBYD 2.1 CarControlImpl / d2.java
            try {
                java.lang.reflect.Method setWindowing = iatmCls.getMethod(
                        "setTaskWindowingMode", int.class, int.class, boolean.class);
                setWindowing.setAccessible(true);
                setWindowing.invoke(iatm, taskId, 5 /* WINDOWING_MODE_FREEFORM */, true);
                sb.append("setTaskWindowingMode(FREEFORM) OK\n");
            } catch (NoSuchMethodException e1) {
                try {
                    java.lang.reflect.Method setWindowing = iatmCls.getMethod(
                            "setTaskWindowingMode", int.class, int.class);
                    setWindowing.setAccessible(true);
                    setWindowing.invoke(iatm, taskId, 5);
                    sb.append("setTaskWindowingMode(FREEFORM, 2-arg) OK\n");
                } catch (Exception e2) {
                    sb.append("WARNING: setTaskWindowingMode not available: ").append(e2.getMessage()).append("\n");
                }
            }

            // Step 4b — move task/stack to the VD display.
            // API 29 (Android 10): stacks are the unit of display placement →
            //   moveStackToDisplay(stackId, displayId)  — needs stackId, not taskId
            // API 31+ (Android 12): root tasks replaced stacks →
            //   moveRootTaskToDisplay(taskId, displayId) or moveTaskToDisplay(taskId, displayId)
            boolean moved = false;

            // --- Primary: moveStackToDisplay (API 29) ---
            // Each invoke is wrapped individually so that an InvocationTargetException
            // (e.g. SecurityException or IllegalArgumentException from the system service
            // on a TRUSTED VirtualDisplay) does not abort the function — the fallback
            // task-based APIs are tried next.
            if (!moved && stackId != -1) {
                for (java.lang.reflect.Method m : getAllMethods(iatmCls)) {
                    if (m.getName().equals("moveStackToDisplay")
                            && m.getParameterCount() == 2
                            && m.getParameterTypes()[0] == int.class
                            && m.getParameterTypes()[1] == int.class) {
                        m.setAccessible(true);
                        try {
                            m.invoke(iatm, stackId, displayId);
                            sb.append("moveStackToDisplay(stackId=").append(stackId)
                              .append(", ").append(displayId).append(") OK\n");
                            moved = true;
                        } catch (Exception moveEx) {
                            Throwable cause = (moveEx.getCause() != null) ? moveEx.getCause() : moveEx;
                            sb.append("moveStackToDisplay threw: ")
                              .append(cause.getClass().getSimpleName()).append(": ")
                              .append(cause.getMessage()).append(" — trying task-based fallback\n");
                        }
                        break;
                    }
                }
            }

            // --- Fallback: moveRootTaskToDisplay / moveTaskToDisplay (API 31+) ---
            if (!moved) {
                java.lang.reflect.Method move = null;
                for (java.lang.reflect.Method m : getAllMethods(iatmCls)) {
                    if ((m.getName().equals("moveRootTaskToDisplay")
                            || m.getName().equals("moveTaskToDisplay"))
                            && m.getParameterCount() == 2
                            && m.getParameterTypes()[0] == int.class
                            && m.getParameterTypes()[1] == int.class) {
                        if (move == null || m.getName().equals("moveRootTaskToDisplay")) move = m;
                    }
                }
                if (move != null) {
                    move.setAccessible(true);
                    try {
                        move.invoke(iatm, taskId, displayId);
                        sb.append(move.getName()).append("(taskId=").append(taskId)
                          .append(", ").append(displayId).append(") OK\n");
                        moved = true;
                    } catch (Exception moveEx) {
                        Throwable cause = (moveEx.getCause() != null) ? moveEx.getCause() : moveEx;
                        sb.append(move.getName()).append(" threw: ")
                          .append(cause.getClass().getSimpleName()).append(": ")
                          .append(cause.getMessage()).append("\n");
                    }
                }
            }

            if (!moved) {
                // Diagnostic dump: log all IATM method names so we can identify the right one
                StringBuilder methodDump = new StringBuilder();
                for (java.lang.reflect.Method m : getAllMethods(iatmCls)) {
                    String n = m.getName().toLowerCase(java.util.Locale.US);
                    if (n.contains("move") || n.contains("stack") || n.contains("display")) {
                        methodDump.append(m.getName())
                                  .append("(").append(m.getParameterCount()).append(") ");
                    }
                }
                sb.append("WARNING: move method not found on ").append(iatmCls.getName())
                  .append("\n  available[move/stack/display]: ").append(methodDump).append("\n");
            }

            // Step 4b.5 — Re-apply FREEFORM windowing mode AFTER the move.
            // moveStackToDisplay() creates a new stack on the target display with a default
            // FULLSCREEN windowing mode, which OVERRIDES the task's FREEFORM mode set in Step 4a.
            // Android 10 canAddActivity() bypasses the non-resizable check when
            // currentTask.inFreeformWindowingMode() is true — so we must re-apply FREEFORM
            // on the task now that it lives in the new stack, BEFORE FreeMapAppActivity's
            // onCreate() completes and calls startActivity(MainActivity).
            // There is ~2 seconds of margin (see log: am_on_create_called appears 2s after move).
            if (moved) {
                try {
                    java.lang.reflect.Method setWindowing2 = iatmCls.getMethod(
                            "setTaskWindowingMode", int.class, int.class, boolean.class);
                    setWindowing2.setAccessible(true);
                    setWindowing2.invoke(iatm, taskId, 5 /* WINDOWING_MODE_FREEFORM */, true);
                    sb.append("setTaskWindowingMode(FREEFORM) post-move OK\n");
                } catch (NoSuchMethodException e1) {
                    try {
                        java.lang.reflect.Method setWindowing2 = iatmCls.getMethod(
                                "setTaskWindowingMode", int.class, int.class);
                        setWindowing2.setAccessible(true);
                        setWindowing2.invoke(iatm, taskId, 5);
                        sb.append("setTaskWindowingMode(FREEFORM) post-move 2-arg OK\n");
                    } catch (Exception e2) {
                        sb.append("setTaskWindowingMode post-move failed: ").append(e2.getMessage()).append("\n");
                    }
                }
            }

            // Step 4c — setTaskBounds to fit the VD exactly (mirrors OpenBYD d2.java)
            try {
                java.lang.reflect.Method resizeTask = iatmCls.getMethod(
                        "resizeTask", int.class, android.graphics.Rect.class, int.class);
                resizeTask.invoke(iatm, taskId, new android.graphics.Rect(0, 0, w, h), 1);
                sb.append("resizeTask(").append(taskId).append(", 0,0,").append(w).append(",").append(h).append(") OK\n");
            } catch (Exception ignored) {
                sb.append("resizeTask skipped\n");
            }

            // Step 5 — setFocusedTask
            try {
                java.lang.reflect.Method setFocused = iatmCls.getMethod("setFocusedTask", int.class);
                setFocused.invoke(iatm, taskId);
                sb.append("setFocusedTask(").append(taskId).append(") OK\n");
            } catch (Exception ignored) {}

            log("LAUNCH_AND_FORCE pkg=" + pkg + " → display " + displayId + " taskId=" + taskId);

            // Step 6 — Watchdog: BYD ROM / Waze's FreeMapAppActivity.launchToSide() forces
            // the entire task back to display 0 (~2.5s after launch) by calling startActivity
            // with setLaunchDisplayId(0). FreeMapAppActivity then self-destructs (~T+2.8s).
            // Strategy: poll every 500ms; once we detect task on display 0 after T=3s,
            // re-apply FREEFORM + move + FREEFORM + setFocusedTask — at that point only
            // MainActivity is running and it does NOT redirect to display 0.
            {
                final int wTaskId   = taskId;
                final int wDispId   = displayId;
                final Object wIatm  = iatm;
                final Class<?> wCls = iatmCls;
                new Thread(() -> {
                    try {
                        for (int iter = 0; iter < 20; iter++) {
                            Thread.sleep(500);
                            if (sVirtualDisplay == null) {
                                log("WATCHDOG: VD released, aborting");
                                return;
                            }
                            // Only start checking after 3 s (iter 6+)
                            if (iter < 6) continue;

                            // Find current display/stack of wTaskId
                            int curDisplay = -1;
                            int curStack   = -1;
                            try {
                                java.lang.reflect.Method gas = null;
                                for (java.lang.reflect.Method m : getAllMethods(wCls)) {
                                    if (m.getName().equals("getAllStackInfos")
                                            && m.getParameterCount() == 0) {
                                        gas = m; break;
                                    }
                                }
                                if (gas != null) {
                                    gas.setAccessible(true);
                                    java.util.List<?> ss = (java.util.List<?>) gas.invoke(wIatm);
                                    if (ss != null) {
                                        outer:
                                        for (Object si : ss) {
                                            int[] ids = (int[]) si.getClass().getField("taskIds").get(si);
                                            if (ids == null) continue;
                                            for (int tid : ids) {
                                                if (tid == wTaskId) {
                                                    curDisplay = si.getClass().getField("displayId").getInt(si);
                                                    curStack   = si.getClass().getField("stackId").getInt(si);
                                                    break outer;
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ex) {
                                log("WATCHDOG iter=" + iter + " poll error: " + ex.getMessage());
                                continue;
                            }

                            log("WATCHDOG iter=" + iter + ": task=" + wTaskId
                                    + " display=" + curDisplay + " stack=" + curStack);

                            if (curDisplay < 0) {
                                log("WATCHDOG: task gone, stopping");
                                return;
                            }
                            if (curDisplay == wDispId) continue; // still on target

                            // Task not on target display — re-apply
                            log("WATCHDOG: task on display " + curDisplay
                                    + ", re-moving to " + wDispId);

                            // a) FREEFORM pre-move
                            try {
                                try {
                                    java.lang.reflect.Method mf = wCls.getMethod(
                                            "setTaskWindowingMode", int.class, int.class, boolean.class);
                                    mf.setAccessible(true); mf.invoke(wIatm, wTaskId, 5, true);
                                } catch (NoSuchMethodException e1) {
                                    java.lang.reflect.Method mf = wCls.getMethod(
                                            "setTaskWindowingMode", int.class, int.class);
                                    mf.setAccessible(true); mf.invoke(wIatm, wTaskId, 5);
                                }
                                log("WATCHDOG: pre-FREEFORM OK");
                            } catch (Exception ex) {
                                log("WATCHDOG: pre-FREEFORM failed: " + ex.getMessage());
                            }

                            // b) Move: prefer moveTaskToDisplay, fallback to moveStackToDisplay
                            boolean moved2 = false;
                            for (java.lang.reflect.Method m : getAllMethods(wCls)) {
                                if ((m.getName().equals("moveTaskToDisplay")
                                        || m.getName().equals("moveRootTaskToDisplay"))
                                        && m.getParameterCount() == 2
                                        && m.getParameterTypes()[0] == int.class
                                        && m.getParameterTypes()[1] == int.class) {
                                    m.setAccessible(true);
                                    m.invoke(wIatm, wTaskId, wDispId);
                                    log("WATCHDOG: " + m.getName() + "(" + wTaskId + "," + wDispId + ") OK");
                                    moved2 = true;
                                    break;
                                }
                            }
                            if (!moved2 && curStack >= 0) {
                                for (java.lang.reflect.Method m : getAllMethods(wCls)) {
                                    if (m.getName().equals("moveStackToDisplay")
                                            && m.getParameterCount() == 2
                                            && m.getParameterTypes()[0] == int.class
                                            && m.getParameterTypes()[1] == int.class) {
                                        m.setAccessible(true);
                                        m.invoke(wIatm, curStack, wDispId);
                                        log("WATCHDOG: moveStackToDisplay(" + curStack + "," + wDispId + ") OK");
                                        moved2 = true;
                                        break;
                                    }
                                }
                            }
                            if (!moved2) {
                                log("WATCHDOG: no move API found");
                                return;
                            }

                            // c) FREEFORM post-move
                            try {
                                try {
                                    java.lang.reflect.Method mf = wCls.getMethod(
                                            "setTaskWindowingMode", int.class, int.class, boolean.class);
                                    mf.setAccessible(true); mf.invoke(wIatm, wTaskId, 5, true);
                                } catch (NoSuchMethodException e1) {
                                    java.lang.reflect.Method mf = wCls.getMethod(
                                            "setTaskWindowingMode", int.class, int.class);
                                    mf.setAccessible(true); mf.invoke(wIatm, wTaskId, 5);
                                }
                                log("WATCHDOG: post-FREEFORM OK");
                            } catch (Exception ex) {
                                log("WATCHDOG: post-FREEFORM failed: " + ex.getMessage());
                            }

                            // d) setFocusedTask
                            try {
                                java.lang.reflect.Method sf = wCls.getMethod("setFocusedTask", int.class);
                                sf.setAccessible(true); sf.invoke(wIatm, wTaskId);
                                log("WATCHDOG: setFocusedTask OK");
                            } catch (Exception ex) {
                                log("WATCHDOG: setFocusedTask failed: " + ex.getMessage());
                            }

                            log("WATCHDOG: re-move done");
                            return; // one retry is enough
                        }
                        log("WATCHDOG: poll loop ended without re-move");
                    } catch (InterruptedException ie) {
                        log("WATCHDOG: interrupted");
                    } catch (Exception ex) {
                        log("WATCHDOG: unexpected error: " + ex.getMessage());
                    }
                }, "waze-display-watchdog").start();
            }

            reply.writeNoException();
            reply.writeString("OK taskId=" + taskId + "\n" + sb);

        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException
                    && e.getCause() != null) ? e.getCause() : e;
            log("LAUNCH_AND_FORCE ERROR: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            reply.writeNoException();
            reply.writeString("ERROR: " + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    + "\n" + sb);
        }
        return true;
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

        Surface overlaySurface = tryAttachClusterOverlay(layerStack, w, h);
        if (overlaySurface != null) {
            if (sVirtualDisplay != null) {
                try {
                    sVirtualDisplay.setSurface(overlaySurface);
                    log("CLUSTER_ATTACH: overlay VD.setSurface OK");
                } catch (Exception e) {
                    log("CLUSTER_ATTACH: overlay VD.setSurface ERROR: " + e.getMessage());
                }
            }
            reply.writeNoException();
            reply.writeInt(1);
            reply.writeParcelable(overlaySurface, 0);
            return true;
        }

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

            // Bind the cluster SC surface to the VirtualDisplay (OpenBYD approach).
            if (sVirtualDisplay != null) {
                try {
                    sVirtualDisplay.setSurface(outputSurface);
                    log("CLUSTER_ATTACH: VD.setSurface OK");
                } catch (Exception e) {
                    log("CLUSTER_ATTACH: VD.setSurface ERROR: " + e.getMessage());
                }
            }

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

    private static Surface tryAttachClusterOverlay(int displayIdHint, int w, int h) {
        if (sContext == null) {
            log("CLUSTER_ATTACH overlay skipped: context unavailable");
            return null;
        }

        try {
            log("CLUSTER_ATTACH overlay step1: releaseClusterOverlay");
            releaseClusterOverlay();

            log("CLUSTER_ATTACH overlay step2: resolveClusterDisplay hint=" + displayIdHint);
            Display targetDisplay = resolveClusterDisplay(displayIdHint);
            if (targetDisplay == null) {
                log("CLUSTER_ATTACH overlay skipped: no display for hint=" + displayIdHint);
                return null;
            }
            log("CLUSTER_ATTACH overlay step3: targetDisplay=" + targetDisplay.getDisplayId() + " latch setup");

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Surface> surfaceRef = new AtomicReference<>();
            AtomicReference<RuntimeException> errorRef = new AtomicReference<>();

            Runnable attach = () -> {
                try {
                    log("CLUSTER_ATTACH overlay Runnable: start sSysContext="
                            + (sSysContext != null ? "non-null" : "null")
                            + " sContext=" + (sContext != null ? "non-null" : "null"));

                    // ── Step A: build a display-scoped context ───────────────────────────
                    // createDisplayContext() gives a ContextImpl configured with the cluster
                    // display's metrics. On BYD's ROM, however, it may return a context
                    // whose Resources are null for displayId=2 (the non-default cluster
                    // display). We verify before using it; fall back to the base context
                    // (which has framework Resources) for SurfaceView construction.
                    Context base = (sSysContext != null) ? sSysContext : sContext;
                    Context displayCtx = null;
                    try {
                        displayCtx = base.createDisplayContext(targetDisplay);
                    } catch (Exception e) {
                        log("CLUSTER_ATTACH overlay: createDisplayContext threw: " + e.getMessage());
                    }
                    boolean displayCtxHasResources = displayCtx != null
                            && displayCtx.getResources() != null;
                    // viewCtx must have non-null Resources for View construction.
                    Context viewCtx = displayCtxHasResources ? displayCtx : base;
                    log("CLUSTER_ATTACH overlay: displayCtx=" + (displayCtx != null ? "ok" : "null")
                            + " resources=" + (displayCtxHasResources ? "ok" : "null/missing")
                            + " → viewCtx=" + viewCtx.getClass().getSimpleName());

                    // ── Step B: grant OP_SYSTEM_ALERT_WINDOW via AppOps ─────────────────
                    // TYPE_APPLICATION_OVERLAY requires SYSTEM_ALERT_WINDOW. shell uid=2000
                    // does not declare it in its manifest, but AppOpsManager.setMode() is
                    // callable by privileged processes on most ROMs including BYD's.
                    // We try it unconditionally and swallow any SecurityException — the
                    // worst case is that addView() throws BadTokenException (caught below).
                    try {
                        Object appOps = sContext.getSystemService(Context.APP_OPS_SERVICE);
                        // OP_SYSTEM_ALERT_WINDOW = 24, MODE_ALLOWED = 0
                        Method setMode = appOps.getClass().getMethod(
                                "setMode", int.class, int.class, String.class, int.class);
                        setMode.setAccessible(true);
                        setMode.invoke(appOps, 24,
                                android.os.Process.myUid(), sContext.getPackageName(), 0);
                        log("CLUSTER_ATTACH overlay: OP_SYSTEM_ALERT_WINDOW → MODE_ALLOWED");
                    } catch (Exception appOpsEx) {
                        log("CLUSTER_ATTACH overlay: AppOps grant skipped ("
                                + appOpsEx.getClass().getSimpleName() + ": "
                                + appOpsEx.getMessage() + ")");
                    }

                    // ── Step C: create SurfaceView and attach surface callback ───────────
                    SurfaceView surfaceView = new SurfaceView(viewCtx);
                    SurfaceHolder holder = surfaceView.getHolder();
                    holder.setFixedSize(w, h);
                    holder.addCallback(new SurfaceHolder.Callback() {
                        @Override
                        public void surfaceCreated(SurfaceHolder surfaceHolder) {
                            Surface surface = surfaceHolder.getSurface();
                            if (surface != null && surface.isValid()) {
                                surfaceRef.compareAndSet(null, surface);
                                latch.countDown();
                            }
                        }

                        @Override
                        public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
                            Surface surface = surfaceHolder.getSurface();
                            if (surface != null && surface.isValid()) {
                                surfaceRef.compareAndSet(null, surface);
                                latch.countDown();
                            }
                        }

                        @Override
                        public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
                        }
                    });

                    // ── Step D: add view to the cluster display via WindowManager ────────
                    // Prefer the display-scoped WindowManager (standard API 29 path: the
                    // WindowManagerImpl bound to displayCtx routes addView to targetDisplay).
                    // If displayCtx is unavailable, fall back to WindowManagerGlobal
                    // reflection which accepts an explicit Display parameter.
                    WindowManager.LayoutParams lp = createOverlayLayoutParams(targetDisplay, w, h);
                    WindowManager wm = (displayCtx != null)
                            ? displayCtx.getSystemService(WindowManager.class) : null;
                    if (wm != null) {
                        log("CLUSTER_ATTACH overlay: addView via display-scoped WM");
                        wm.addView(surfaceView, lp);
                        sClusterOverlayWindowManager = wm;
                    } else {
                        // WindowManagerGlobal.addView(view, params, display, parentWindow)
                        // is the @hide API that WindowManagerImpl.addView() delegates to.
                        log("CLUSTER_ATTACH overlay: addView via WindowManagerGlobal (wm was null)");
                        Class<?> wmgCls = Class.forName("android.view.WindowManagerGlobal");
                        Object wmg = wmgCls.getMethod("getInstance").invoke(null);
                        Method wmgAdd = wmgCls.getDeclaredMethod("addView",
                                View.class, android.view.ViewGroup.LayoutParams.class,
                                Display.class, android.view.Window.class);
                        wmgAdd.setAccessible(true);
                        wmgAdd.invoke(wmg, surfaceView, lp, targetDisplay, null);
                        sClusterOverlayWindowManager = sContext.getSystemService(WindowManager.class);
                    }
                    sClusterOverlayView = surfaceView;
                    log("CLUSTER_ATTACH overlay host added on displayId=" + targetDisplay.getDisplayId());
                } catch (Exception e) {
                    errorRef.set(new RuntimeException(e));
                    latch.countDown();
                }
            };

            log("CLUSTER_ATTACH overlay step4: posting Runnable to Looper myLooper="
                    + (Looper.myLooper() != null ? "non-null" : "null")
                    + " mainLooper=" + (Looper.getMainLooper() != null ? "non-null" : "null"));
            if (Looper.myLooper() == Looper.getMainLooper()) {
                attach.run();
            } else {
                new android.os.Handler(Looper.getMainLooper()).post(attach);
            }

            log("CLUSTER_ATTACH overlay step5: awaiting latch");
            if (!latch.await(2, TimeUnit.SECONDS)) {
                log("CLUSTER_ATTACH overlay timed out waiting for surface");
                releaseClusterOverlay();
                return null;
            }

            RuntimeException error = errorRef.get();
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                // Unwrap InvocationTargetException from reflection calls to surface the real cause.
                if (cause instanceof java.lang.reflect.InvocationTargetException
                        && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                log("CLUSTER_ATTACH overlay ERROR: " + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage());
                releaseClusterOverlay();
                return null;
            }

            Surface surface = surfaceRef.get();
            if (surface == null || !surface.isValid()) {
                log("CLUSTER_ATTACH overlay produced no valid surface");
                releaseClusterOverlay();
                return null;
            }
            return surface;
        } catch (Exception e) {
            log("CLUSTER_ATTACH overlay failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            releaseClusterOverlay();
            return null;
        }
    }

    private static WindowManager.LayoutParams createOverlayLayoutParams(
            Display targetDisplay, int w, int h) {
        // TYPE_APPLICATION_OVERLAY (API 26+) is the correct type for a persistent
        // system-managed overlay; TYPE_PHONE is the legacy equivalent on API < 26.
        int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.OPAQUE);
        lp.setTitle("devtools_cluster_overlay");
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lp.x = 0;
        lp.y = 0;
        log("CLUSTER_ATTACH overlay params type=" + overlayType
                + " size=" + w + "×" + h
                + " targetDisplay=" + targetDisplay.getDisplayId());
        return lp;
    }

    private static Display resolveClusterDisplay(int displayIdHint) {
        // sContext (createPackageContext in systemMain mode) has null Resources;
        // DisplayManager.getOrCreateDisplayLocked() calls mContext.getResources() → NPE.
        // sSysContext (ActivityThread.getSystemContext) has valid framework Resources.
        Context dmCtx = (sSysContext != null) ? sSysContext : sContext;
        DisplayManager dm = dmCtx.getSystemService(DisplayManager.class);
        if (dm == null) return null;

        Display display = dm.getDisplay(displayIdHint);
        if (display != null) return display;

        for (Display candidate : dm.getDisplays()) {
            String name = candidate.getName();
            if (name == null) continue;
            String lowered = name.toLowerCase(Locale.US);
            if (lowered.contains("cluster") || lowered.contains("fission")) {
                return candidate;
            }
        }
        return null;
    }

    private static void releaseClusterOverlay() {
        final View overlayView = sClusterOverlayView;
        final WindowManager windowManager = sClusterOverlayWindowManager;
        sClusterOverlayView = null;
        sClusterOverlayWindowManager = null;
        if (overlayView == null || windowManager == null) {
            return;
        }

        Runnable release = () -> {
            try {
                windowManager.removeViewImmediate(overlayView);
                log("CLUSTER_ATTACH overlay removed");
            } catch (Exception e) {
                log("CLUSTER_ATTACH overlay remove error: " + e.getMessage());
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            release.run();
        } else {
            new android.os.Handler(Looper.getMainLooper()).post(release);
        }
    }

    private static List<Method> getAllMethods(Class<?> type) {
        Set<String> seen = new LinkedHashSet<>();
        List<Method> methods = new ArrayList<>();
        Class<?> current = type;
        while (current != null) {
            addMethods(current.getDeclaredMethods(), methods, seen);
            addMethods(current.getMethods(), methods, seen);
            for (Class<?> iface : current.getInterfaces()) {
                collectInterfaceMethods(iface, methods, seen);
            }
            current = current.getSuperclass();
        }
        return methods;
    }

    private static void collectInterfaceMethods(Class<?> iface, List<Method> methods, Set<String> seen) {
        addMethods(iface.getDeclaredMethods(), methods, seen);
        for (Class<?> parent : iface.getInterfaces()) {
            collectInterfaceMethods(parent, methods, seen);
        }
    }

    private static void addMethods(Method[] source, List<Method> out, Set<String> seen) {
        for (Method method : source) {
            StringBuilder signature = new StringBuilder(method.getName()).append('(');
            Class<?>[] params = method.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) signature.append(',');
                signature.append(params[i].getName());
            }
            signature.append(')');
            if (seen.add(signature.toString())) {
                out.add(method);
            }
        }
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
