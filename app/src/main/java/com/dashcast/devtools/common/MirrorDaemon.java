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
import android.view.MotionEvent;
import android.view.Surface;

import java.lang.reflect.Field;
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
            sContext = sysCtx.createPackageContext("com.android.shell", 0);
            log("Context init OK pkg=" + sContext.getPackageName()
                    + " uid=" + android.os.Process.myUid());
        } catch (Exception e) {
            log("initContext ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * TRANSACT_CREATE_VD — creates a FLAG_TRUSTED VirtualDisplay via the standard
     * {@link DisplayManager#createVirtualDisplay} API (OpenBYD approach).
     *
     * <p>Uses {@link #sContext} (a package context for {@code com.android.shell}, obtained
     * in {@link #initContext()}). The process runs as uid=2000 which owns
     * {@code com.android.shell}, so {@code DisplayManagerService.validatePackageName()} passes.
     *
     * <p>Flags = 322 (0x142) — identical to OpenBYD 2.1 :
     * {@code FLAG_PRESENTATION (0x02) | FLAG_SUPPORTS_TOUCH (0x40) | FLAG_DESTROY_CONTENT_ON_REMOVAL (0x100)}.
     * FLAG_TRUSTED (0x200) is NOT used by OpenBYD and not needed here: the Waze secondary-screen
     * rejection is bypassed by launching on display 0 then calling moveRootTaskToDisplay(), not
     * by a trusted flag on the display.
     * Note: FLAG_PRESENTATION was previously avoided (v0.6.17) because am-start-on-display
     * caused an immediate topResumedLost. With launchAndForce the task arrives already alive,
     * so this issue no longer applies.
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
            // Flags = 322 (0x142) — identical to OpenBYD 2.1:
            // FLAG_PRESENTATION (0x02) | FLAG_SUPPORTS_TOUCH (0x40) | FLAG_DESTROY_CONTENT_ON_REMOVAL (0x100)
            VirtualDisplay vd = dm.createVirtualDisplay(
                    "devtools_projection_vd",
                    w, h, dpi,
                    /*surface=*/ null,
                    /*flags=*/   322 /* 0x142: PRESENTATION | SUPPORTS_TOUCH | DESTROY_CONTENT_ON_REMOVAL */);

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
            for (java.lang.reflect.Method m : iatmCls.getMethods()) {
                if (!m.getName().equals("getTasks")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0] == int.class
                        && p[1] == boolean.class && p[2] == boolean.class) {
                    getTasks = m; break;
                }
                if (p.length == 1 && p[0] == int.class) getTasks = m;
            }
            if (getTasks == null) throw new RuntimeException("getTasks not found");

            int taskId = -1;
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
                                    sb.append("Found taskId=").append(taskId)
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

            // Step 4 — moveRootTaskToDisplay (prefer) or moveTaskToDisplay
            java.lang.reflect.Method move = null;
            for (java.lang.reflect.Method m : iatmCls.getMethods()) {
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
                move.invoke(iatm, taskId, displayId);
                sb.append(move.getName()).append("(").append(taskId).append(", ")
                  .append(displayId).append(") OK\n");
            } else {
                sb.append("WARNING: move method not found\n");
            }

            // Step 5 — setFocusedTask
            try {
                java.lang.reflect.Method setFocused = iatmCls.getMethod("setFocusedTask", int.class);
                setFocused.invoke(iatm, taskId);
                sb.append("setFocusedTask(").append(taskId).append(") OK\n");
            } catch (Exception ignored) {}

            log("LAUNCH_AND_FORCE pkg=" + pkg + " → display " + displayId + " taskId=" + taskId);
            reply.writeNoException();
            reply.writeString("OK taskId=" + taskId + "\n" + sb);

        } catch (Exception e) {
            log("LAUNCH_AND_FORCE ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            reply.writeNoException();
            reply.writeString("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage()
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
