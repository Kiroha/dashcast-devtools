package com.dashcast.devtools.projection;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.dashcast.devtools.R;
import com.dashcast.devtools.common.AdbClient;
import com.dashcast.devtools.common.AppLogger;
import com.dashcast.devtools.common.MirrorDaemon;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dl3ProjectionActivity — projection en temps réel d'une application Android
 * sur le cluster physique (display id=1, layerStack=1) via SurfaceControl + VirtualDisplay.
 *
 * <p>Architecture :
 * <pre>
 *   [App cible] → VirtualDisplay (layerStack=N)
 *     → setSurface(SC layer layerStack=1)  ← CLUSTER_ATTACH
 *     → SurfaceFlinger → cluster physique
 *   VD layerStack=N → MIRROR_START
 *     → SurfaceView tablette (preview + touch forwarding)
 * </pre>
 *
 * <p>Touch forwarding : les événements tactiles sur le SurfaceView preview sont scalés
 * vers les coordonnées 1920×720 puis envoyés au daemon via TRANSACT_INJECT_MOTION.
 */
public class Dl3ProjectionActivity extends Activity {

    private static final String TAG = "Dl3ProjectionActivity";

    // Cluster display physical dimensions
    private static final int CLUSTER_W = 1920;
    private static final int CLUSTER_H = 720;

    // ── Views ─────────────────────────────────────────────────────────────────
    private MaterialButton btnStart;
    private MaterialButton btnStop;
    private SurfaceView    svPreview;
    private TextView       tvStatus;

    // ── Projection state ──────────────────────────────────────────────────────
    private boolean         mDestroyed    = false;
    private boolean         mSurfaceReady = false;
    private boolean         mProjecting   = false;
    private SurfaceHolder   mHolder;
    // mVd is created by the daemon (TRANSACT_CREATE_VD) so the daemon can use FLAG_TRUSTED
    // (shell uid=2000 has INTERNAL_SYSTEM_WINDOW; app does not). We only track the IDs.
    private IBinder         mDaemonBinder;
    private int             mVdDisplayId  = -1;
    private int             mVdLayerStack = -1;

    // Projection mapping — letterbox parameters from MIRROR_START, used by forwardTouch.
    // Same formula as production ClusterMirrorManager: scale = min(viewW/CW, viewH/CH).
    private int   mProjOffsetX = 0;
    private int   mProjOffsetY = 0;
    private float mProjScale   = 0f;  // 0 = not yet set

    private final Handler         mUiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExec      = Executors.newSingleThreadExecutor();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_projection);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_projection);
        toolbar.setNavigationOnClickListener(v -> finish());

        btnStart   = findViewById(R.id.btn_projection_start);
        btnStop    = findViewById(R.id.btn_projection_stop);
        svPreview  = findViewById(R.id.sv_projection_preview);
        tvStatus   = findViewById(R.id.tv_projection_status);

        btnStop.setEnabled(false);

        // SurfaceView fixed buffer 1920×720 for the mirror
        mHolder = svPreview.getHolder();
        mHolder.setFixedSize(CLUSTER_W, CLUSTER_H);
        mHolder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                mSurfaceReady = true;
                btnStart.setEnabled(!mProjecting);
                AppLogger.d(TAG, "surfaceCreated");
            }
            @Override public void surfaceChanged(SurfaceHolder h, int fmt, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                mSurfaceReady = false;
                btnStart.setEnabled(false);
                AppLogger.d(TAG, "surfaceDestroyed");
            }
        });

        // Touch forwarding — scale from view coords to cluster coords
        svPreview.setOnTouchListener((v, event) -> {
            if (!mProjecting || mDaemonBinder == null || mVdDisplayId < 0) return false;
            forwardTouch(event, v.getWidth(), v.getHeight());
            return true;
        });

        btnStart.setOnClickListener(v -> pickAppThenStart());
        btnStop.setOnClickListener(v -> stopProjection());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
        mUiHandler.removeCallbacksAndMessages(null);
        mExec.shutdown();
        if (mProjecting) stopProjectionInternal();
    }

    // ── App picker ────────────────────────────────────────────────────────────

    private void pickAppThenStart() {
        if (!mSurfaceReady || mProjecting) return;
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        if (infos == null || infos.isEmpty()) {
            Toast.makeText(this, R.string.diag_dl50_fission_pick_empty, Toast.LENGTH_LONG).show();
            return;
        }
        String selfPkg = getPackageName();
        Map<String, String> pkgToLabel = new LinkedHashMap<>();
        for (ResolveInfo ri : infos) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(selfPkg) || pkgToLabel.containsKey(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            pkgToLabel.put(pkg, label == null ? pkg : label.toString());
        }
        if (pkgToLabel.isEmpty()) {
            Toast.makeText(this, R.string.diag_dl50_fission_pick_empty, Toast.LENGTH_LONG).show();
            return;
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(pkgToLabel.entrySet());
        Collections.sort(sorted, (a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        final String[] pkgs   = new String[sorted.size()];
        final String[] labels = new String[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            pkgs[i]   = sorted.get(i).getKey();
            labels[i] = sorted.get(i).getValue() + "  —  " + sorted.get(i).getKey();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.diag_dl50_fission_pick_title)
                .setItems(labels, (d, which) -> {
                    if (which >= 0 && which < pkgs.length) startProjection(pkgs[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── Start projection ──────────────────────────────────────────────────────

    private void startProjection(String targetPkg) {
        if (mProjecting || !mSurfaceReady) return;
        btnStart.setEnabled(false);
        btnStop.setEnabled(false);
        setStatus(getString(R.string.projection_status_starting));

        mExec.execute(() -> {
            try {
                doStartProjection(targetPkg);
            } catch (Exception e) {
                AppLogger.e(TAG, "startProjection error", e);
                safeRun(() -> {
                    setStatus(getString(R.string.projection_status_error, e.getMessage()));
                    btnStart.setEnabled(mSurfaceReady);
                    btnStop.setEnabled(false);
                });
            }
        });
    }

    private void doStartProjection(String targetPkg) throws Exception {
        // Step 1 — Launch daemon (must come first: daemon creates VD with FLAG_TRUSTED)
        safeRun(() -> setStatus(getString(R.string.projection_status_step_daemon)));
        String apkPath  = getPackageCodePath();
        String logPath  = getExternalFilesDir(null) + "/mirrordaemon_projection.log";
        String daemonCmd = "setsid sh -c 'CLASSPATH=" + apkPath
                + " /system/bin/app_process64 -Xnoimage-dex2oat /system/bin"
                + " --nice-name=" + MirrorDaemon.NICE_NAME
                + " " + MirrorDaemon.MAIN_CLASS
                + " </dev/null >" + logPath + " 2>&1' &";
        shellFire(daemonCmd);
        Thread.sleep(1500); // let daemon register

        // Step 2 — Bind to daemon
        safeRun(() -> setStatus(getString(R.string.projection_status_step_bind)));
        mDaemonBinder = null;
        for (int attempt = 0; attempt < 6 && mDaemonBinder == null; attempt++) {
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                java.lang.reflect.Method get = sm.getDeclaredMethod("getService", String.class);
                get.setAccessible(true);
                mDaemonBinder = (IBinder) get.invoke(null, MirrorDaemon.SERVICE_NAME);
            } catch (Exception ignored) {}
            if (mDaemonBinder == null) Thread.sleep(500);
        }
        if (mDaemonBinder == null) throw new RuntimeException("Binder daemon introuvable");
        AppLogger.d(TAG, "Daemon binder OK");

        // Step 3 — Ask daemon to create VirtualDisplay with FLAG_TRUSTED
        // Daemon (shell uid=2000) has INTERNAL_SYSTEM_WINDOW → FLAG_TRUSTED allowed.
        // Apps like Waze check Display.FLAG_TRUSTED at runtime and refuse untrusted displays.
        safeRun(() -> setStatus(getString(R.string.projection_status_step_vd)));
        {
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                data.writeInt(CLUSTER_W);
                data.writeInt(CLUSTER_H);
                data.writeInt(160);
                mDaemonBinder.transact(MirrorDaemon.TRANSACT_CREATE_VD, data, reply, 0);
                reply.readException();
                mVdDisplayId = reply.readInt();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        if (mVdDisplayId < 0) throw new RuntimeException("CREATE_VD: daemon returned -1 (FLAG_TRUSTED non supporté sur ce ROM?)");
        AppLogger.d(TAG, "CREATE_VD OK displayId=" + mVdDisplayId);

        // Get the VD's layerStack (needed for MIRROR_START preview).
        // On AOSP (API 29), DisplayManagerService.assignLayerStackLocked() always returns displayId,
        // so layerStack == displayId. We skip DisplayManager.getDisplay(id) because on FLAG_TRUSTED
        // VirtualDisplays created from a different process (uid=2000 daemon), getDisplayInfo() may
        // return null due to OEM access-control checks on FLAG_TRUSTED displays.
        mVdLayerStack = mVdDisplayId;
        AppLogger.d(TAG, "VD layerStack=" + mVdLayerStack + " (== displayId, AOSP guarantee)");

        // Step 4 — CLUSTER_ATTACH → daemon creates SC layer + internally calls setVirtualDisplaySurface
        safeRun(() -> setStatus(getString(R.string.projection_status_step_attach)));
        {
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                data.writeInt(1);          // layerStack=1 (cluster BYD)
                data.writeInt(CLUSTER_W);
                data.writeInt(CLUSTER_H);
                mDaemonBinder.transact(MirrorDaemon.TRANSACT_CLUSTER_ATTACH, data, reply, 0);
                reply.readException();
                int ok = reply.readInt();
                if (ok != 1) throw new RuntimeException("CLUSTER_ATTACH ok=0");
                // Daemon called setVirtualDisplaySurface internally — VD is now bound to cluster SC.
                // Surface is still returned in reply for backward compat with fission runners.
                reply.readParcelable(Surface.class.getClassLoader()); // read and discard
                AppLogger.d(TAG, "CLUSTER_ATTACH OK (daemon set VD surface)");
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        // Step 5 — Launch target app on VD
        // Strategy (OpenBYD launchAndForce pattern):
        //   1) Daemon resolves component and does "am start -n <comp>" on display 0.
        //      Launching on display 0 bypasses ATMS canPlaceEntityOnDisplay() which would
        //      reject apps like Waze ("cannot be launched on secondary screens").
        //   2) Daemon polls task ID via IActivityTaskManager.getTasks().
        //   3) Daemon calls moveRootTaskToDisplay(taskId, displayId) — uid=2000 is exempt
        //      from hidden-API restrictions, unlike the app process.
        //   4) Daemon calls setFocusedTask(taskId).
        //
        // Do NOT use am start --display: it triggers the canPlaceEntityOnDisplay check
        // before launch, which Waze fails.
        final String pkg = targetPkg;
        safeRun(() -> setStatus(getString(R.string.projection_status_step_launch, pkg)));
        {
            Parcel lafData  = Parcel.obtain();
            Parcel lafReply = Parcel.obtain();
            try {
                lafData.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                lafData.writeString(pkg);
                lafData.writeInt(mVdDisplayId);
                lafData.writeInt(CLUSTER_W);
                lafData.writeInt(CLUSTER_H);
                mDaemonBinder.transact(MirrorDaemon.TRANSACT_LAUNCH_AND_FORCE, lafData, lafReply, 0);
                lafReply.readException();
                String lafLog = lafReply.readString();
                AppLogger.d(TAG, "LAUNCH_AND_FORCE: " + lafLog);
                if (!lafLog.startsWith("OK")) {
                    throw new RuntimeException("LAUNCH_AND_FORCE: " + lafLog);
                }
            } finally {
                lafData.recycle();
                lafReply.recycle();
            }
        }

        // Step 6 — MIRROR_START → tablet SurfaceView preview
        safeRun(() -> setStatus(getString(R.string.projection_status_step_mirror)));
        Surface tabletSurface = mHolder.getSurface();
        Parcel mData  = Parcel.obtain();
        Parcel mReply = Parcel.obtain();
        try {
            mData.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            mData.writeInt(mVdLayerStack);
            mData.writeInt(CLUSTER_W);
            mData.writeInt(CLUSTER_H);
            mData.writeInt(mVdDisplayId);
            mData.writeInt(CLUSTER_W);
            mData.writeInt(CLUSTER_H);
            mData.writeParcelable(tabletSurface, 0);
            mDaemonBinder.transact(MirrorDaemon.TRANSACT_MIRROR_START, mData, mReply, 0);
            mReply.readException();
            int ok = mReply.readInt();
            AppLogger.d(TAG, "MIRROR_START reply ok=" + ok);

            // Compute and store letterbox projection params for touch mapping.
            // Must mirror the formula in MirrorDaemon.handleMirrorStart (src=1920×720 → view=1920×720
            // → scale=1, offsets=0 for our full-size SurfaceView; stored anyway for correctness).
            float scale   = Math.min((float) CLUSTER_W / CLUSTER_W, (float) CLUSTER_H / CLUSTER_H);
            int   drawW   = (int) (CLUSTER_W * scale);
            int   drawH   = (int) (CLUSTER_H * scale);
            mProjOffsetX  = (CLUSTER_W - drawW) / 2;
            mProjOffsetY  = (CLUSTER_H - drawH) / 2;
            mProjScale    = scale;
        } finally {
            mData.recycle();
            mReply.recycle();
        }

        // Done
        final String runningPkg = targetPkg;
        mProjecting = true;
        safeRun(() -> {
            setStatus(getString(R.string.projection_status_running, runningPkg));
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
        });
    }

    // ── Stop projection ───────────────────────────────────────────────────────

    private void stopProjection() {
        if (!mProjecting) return;
        setStatus(getString(R.string.projection_status_stopping));
        mExec.execute(() -> {
            stopProjectionInternal();
            safeRun(() -> {
                setStatus(getString(R.string.projection_status_idle));
                btnStart.setEnabled(mSurfaceReady);
                btnStop.setEnabled(false);
            });
        });
    }

    private void stopProjectionInternal() {
        mProjecting = false;
        // MIRROR_STOP (also releases SC layer)
        if (mDaemonBinder != null) {
            Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                mDaemonBinder.transact(MirrorDaemon.TRANSACT_MIRROR_STOP, data, reply, 0);
                reply.readException();
            } catch (Exception e) {
                AppLogger.e(TAG, "MIRROR_STOP error", e);
            } finally {
                data.recycle(); reply.recycle();
            }
            mDaemonBinder = null;
        }
        // VD + SC surface managed by daemon — released on MIRROR_STOP via IDisplayManager.
        mVdDisplayId  = -1;
        mVdLayerStack = -1;
        // Kill daemon
        try {
            AdbClient.executeShell(this, "pkill -f com.dashcast.devtools.mirrordaemon");
        } catch (Exception e) {
            AppLogger.e(TAG, "pkill error", e);
        }
    }

    // ── Shell helpers ─────────────────────────────────────────────────────────

    /** Fire-and-forget ADB shell command (non-blocking). */
    private void shellFire(String cmd) {
        AdbClient.executeShell(this, cmd);
    }

    /** Gets Display.getLayerStack() via reflection (@hide method). */
    private static int getLayerStack(Display d) {
        try {
            Method m = Display.class.getDeclaredMethod("getLayerStack");
            m.setAccessible(true);
            return (int) m.invoke(d);
        } catch (Exception e) {
            AppLogger.w("Dl3ProjectionActivity", "getLayerStack: " + e.getMessage());
            return -1;
        }
    }

    // ── Touch forwarding ──────────────────────────────────────────────────────

    /**
     * Scales touch event from SurfaceView preview dimensions → cluster coordinates (1920×720)
     * and forwards via TRANSACT_INJECT_MOTION to the daemon (FLAG_ONEWAY, no reply).
     *
     * <p>Touch mapping accounts for letterboxing: the preview SurfaceView has a fixed 1920×720
     * buffer but may be displayed at a different size. The mProjOffset/Scale values track the
     * exact same letterbox formula used in MIRROR_START so touches land on the correct pixel.
     *
     * <p>The daemon uses its stored {@code sClusterDisplayId} (set during MIRROR_START) to route
     * the event — no need to send the displayId in the Parcel.
     */
    private void forwardTouch(MotionEvent event, int viewW, int viewH) {
        if (mDaemonBinder == null || mProjScale <= 0f) return;
        if (viewW <= 0 || viewH <= 0) return;

        // Map view coords → cluster coords, accounting for letterbox offset.
        // mirror of ClusterMirrorManager.startMirrorViaDaemon projection formula.
        float clusterX = (event.getX() - mProjOffsetX) / mProjScale;
        float clusterY = (event.getY() - mProjOffsetY) / mProjScale;
        // Clamp to valid cluster area
        clusterX = Math.max(0, Math.min(clusterX, CLUSTER_W - 1));
        clusterY = Math.max(0, Math.min(clusterY, CLUSTER_H - 1));

        MotionEvent scaled = MotionEvent.obtain(event);
        scaled.setLocation(clusterX, clusterY);

        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            // No displayId in wire — daemon uses sClusterDisplayId set at MIRROR_START
            data.writeParcelable(scaled, 0);
            // FLAG_ONEWAY: fire-and-forget, no reply (latency-critical at 60-120 events/s)
            mDaemonBinder.transact(MirrorDaemon.TRANSACT_INJECT_MOTION, data, null,
                    android.os.IBinder.FLAG_ONEWAY);
        } catch (android.os.DeadObjectException doe) {
            AppLogger.w(TAG, "forwardTouch: daemon binder dead");
        } catch (Exception e) {
            AppLogger.e(TAG, "forwardTouch error", e);
        } finally {
            data.recycle();
            scaled.recycle();
        }
    }

    // ── Status helper ─────────────────────────────────────────────────────────

    private void setStatus(String msg) {
        AppLogger.d(TAG, "status: " + msg);
        if (tvStatus != null) tvStatus.setText(msg);
    }

    // ── Safe UI ───────────────────────────────────────────────────────────────

    private void safeRun(Runnable r) {
        if (mDestroyed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) { r.run(); return; }
        mUiHandler.post(() -> { if (!mDestroyed) r.run(); });
    }
}
