package com.dashcast.devtools.projection;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.SeekBar;
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
    private MaterialButton            btnStart;
    private MaterialButton            btnStop;
    private SurfaceView               svPreview;
    private TextView                  tvStatus;
    private android.widget.LinearLayout llSlots;
    private View                      dividerSlots;

    // ── Projection state ──────────────────────────────────────────────────────
    private boolean         mDestroyed    = false;
    private boolean         mSurfaceReady = false;
    private boolean         mProjecting   = false;
    private SurfaceHolder   mHolder;
    // VD is owned by the daemon (TRUSTED, created in CLUSTER_ATTACH, released on MIRROR_STOP).
    private IBinder         mDaemonBinder;
    private int             mVdDisplayId  = -1;
    private int             mVdLayerStack = -1;

    // Touch mapping: set to true once MIRROR_START succeeds.
    private volatile boolean mMirrorReady = false;

    // ── Overlay sizing (marges, persistées) ───────────────────────────────────
    private static final String PREFS_SIZING  = "overlay_sizing";
    private static final String PREF_TOP      = "margin_top";
    private static final String PREF_BOTTOM   = "margin_bottom";
    private static final String PREF_LEFT     = "margin_left";
    private static final String PREF_RIGHT    = "margin_right";
    private int mMarginTop = 0, mMarginBottom = 0, mMarginLeft = 0, mMarginRight = 0;

    // ── Multi-slot (une paire overlay+VD par app) ─────────────────────────────

    /** Apps de navigation connues — proposent un popup de taille au premier lancement. */
    private static final java.util.Set<String> NAV_PKGS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "com.waze", "com.google.android.apps.maps",
                    "net.osmand.plus", "net.osmand",
                    "com.here.app.maps", "com.sygic.maps",
                    "com.mapbox.navigation.examples"));

    private static final String PREFS_CATEGORIES = "app_categories";
    private static final String PREFS_RECTS      = "app_rects";

    /** Un slot actif par package. */
    private static final class SlotState {
        final String pkg;
        String label;
        int displayId;
        int layerStack;
        Rect rect; // position/taille sur le cluster

        SlotState(String pkg, String label, int displayId, Rect rect) {
            this.pkg = pkg; this.label = label;
            this.displayId = displayId; this.layerStack = displayId;
            this.rect = rect;
        }
    }

    /** Slots actifs (ordre d'insertion = ordre de lancement). */
    private final java.util.LinkedHashMap<String, SlotState> mSlots = new java.util.LinkedHashMap<>();

    private boolean isNavApp(String pkg) {
        return NAV_PKGS.contains(pkg)
                || getSharedPreferences(PREFS_CATEGORIES, MODE_PRIVATE)
                        .getBoolean("nav_" + pkg, false);
    }

    private Rect getSavedRect(String pkg) {
        String s = getSharedPreferences(PREFS_RECTS, MODE_PRIVATE).getString(pkg, null);
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 4) return null;
        try {
            return new Rect(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                            Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) { return null; }
    }

    private void saveRect(String pkg, Rect r) {
        getSharedPreferences(PREFS_RECTS, MODE_PRIVATE).edit()
                .putString(pkg, r.left + "," + r.top + "," + r.right + "," + r.bottom)
                .apply();
    }

    /** Zone effective tenant compte des marges configurées. */
    private Rect effectiveRect() {
        return new Rect(mMarginLeft, mMarginTop,
                CLUSTER_W - mMarginRight, CLUSTER_H - mMarginBottom);
    }

    /** Zone libre = zone effective moins les rects déjà occupés (algo simple left/right). */
    private Rect availableRect() {
        Rect full = effectiveRect();
        for (SlotState s : mSlots.values()) {
            // Si un slot occupe la moitié gauche → la droite est libre et vice-versa
            if (s.rect.left <= full.left && s.rect.right < full.right) full.left  = s.rect.right;
            else if (s.rect.right >= full.right && s.rect.left > full.left) full.right = s.rect.left;
        }
        return (full.width() > 0 && full.height() > 0) ? full : null;
    }

    private final Handler         mUiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExec      = Executors.newSingleThreadExecutor();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_projection);

        // Charge les marges sauvegardées
        SharedPreferences prefs = getSharedPreferences(PREFS_SIZING, Context.MODE_PRIVATE);
        mMarginTop    = prefs.getInt(PREF_TOP,    0);
        mMarginBottom = prefs.getInt(PREF_BOTTOM, 0);
        mMarginLeft   = prefs.getInt(PREF_LEFT,   0);
        mMarginRight  = prefs.getInt(PREF_RIGHT,  0);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_projection);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_projection);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_overlay_sizing) {
                showOverlaySizingDialog();
                return true;
            }
            return false;
        });

        btnStart     = findViewById(R.id.btn_projection_start);
        btnStop      = findViewById(R.id.btn_projection_stop);
        svPreview    = findViewById(R.id.sv_projection_preview);
        tvStatus     = findViewById(R.id.tv_projection_status);
        llSlots      = findViewById(R.id.ll_slots);
        dividerSlots = findViewById(R.id.divider_slots);

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

        // Touch forwarding — linéaire : coordonnées view → coordonnées cluster (1920×720)
        svPreview.setOnTouchListener((v, event) -> {
            if (!mProjecting || !mMirrorReady || mDaemonBinder == null || mVdDisplayId < 0) return false;
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
        if (!mSurfaceReady) return;
        if (mSlots.size() >= 2) {
            Toast.makeText(this, "Maximum 2 apps sur le cluster", Toast.LENGTH_SHORT).show();
            return;
        }
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
            if (pkg == null || pkg.equals(selfPkg) || pkgToLabel.containsKey(pkg)
                    || mSlots.containsKey(pkg)) continue;
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
            String lbl = sorted.get(i).getValue();
            labels[i] = (isNavApp(pkgs[i]) ? "🧭 " : "") + lbl + "  —  " + pkgs[i];
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.diag_dl50_fission_pick_title)
                .setItems(labels, (d, which) -> {
                    if (which >= 0 && which < pkgs.length)
                        resolveRectThenStart(pkgs[which], sorted.get(which).getValue());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Détermine le rect à utiliser pour cette app puis lance la projection.
     * - App nav + rect sauvegardé → utilise directement
     * - App nav + pas de rect → affiche le picker de taille
     * - Autre app → utilise la zone libre disponible
     */
    private void resolveRectThenStart(String pkg, String label) {
        if (isNavApp(pkg)) {
            Rect saved = getSavedRect(pkg);
            if (saved != null) {
                startSlot(pkg, label, saved);
            } else {
                showNavSizePickerDialog(pkg, label);
            }
        } else {
            Rect available = availableRect();
            if (available == null) {
                Toast.makeText(this, "Plus d'espace disponible sur le cluster", Toast.LENGTH_SHORT).show();
                return;
            }
            startSlot(pkg, label, available);
        }
    }

    private void showNavSizePickerDialog(String pkg, String label) {
        Rect eff = effectiveRect();
        int ew = eff.width(), eh = eff.height();
        int mx = eff.left, my = eff.top;

        String[] names = {
            "Plein écran",
            "Gauche 1/2",  "Droite 1/2",
            "Gauche 3/4",  "Droite 3/4",
            "Gauche 1/4",  "Droite 1/4"
        };
        Rect[] rects = {
            new Rect(mx,        my, mx + ew,       my + eh),
            new Rect(mx,        my, mx + ew/2,     my + eh),
            new Rect(mx + ew/2, my, mx + ew,       my + eh),
            new Rect(mx,        my, mx + ew*3/4,   my + eh),
            new Rect(mx + ew/4, my, mx + ew,       my + eh),
            new Rect(mx,        my, mx + ew/4,     my + eh),
            new Rect(mx + ew*3/4, my, mx + ew,     my + eh)
        };

        new AlertDialog.Builder(this)
                .setTitle("Taille de " + label + " sur le cluster")
                .setItems(names, (d, which) -> {
                    Rect chosen = rects[which];
                    saveRect(pkg, chosen);
                    // Option : marquer l'app comme nav si l'utilisateur a choisi manuellement
                    getSharedPreferences(PREFS_CATEGORIES, MODE_PRIVATE).edit()
                            .putBoolean("nav_" + pkg, true).apply();
                    startSlot(pkg, label, chosen);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ── Start slot ────────────────────────────────────────────────────────────

    private void startSlot(String pkg, String label, Rect rect) {
        if (!mSurfaceReady) return;
        btnStart.setEnabled(false);
        btnStop.setEnabled(false);
        setStatus(getString(R.string.projection_status_starting));

        mExec.execute(() -> {
            try {
                doStartSlot(pkg, label, rect);
            } catch (Exception e) {
                AppLogger.e(TAG, "startSlot error pkg=" + pkg, e);
                safeRun(() -> {
                    setStatus(getString(R.string.projection_status_error, e.getMessage()));
                    btnStart.setEnabled(mSurfaceReady);
                    btnStop.setEnabled(!mSlots.isEmpty());
                });
            }
        });
    }

    private void doStartSlot(String pkg, String label, Rect rect) throws Exception {
        boolean isFirst = mSlots.isEmpty();

        // Step 1 — Démarre le daemon si c'est le premier slot
        if (isFirst) {
            safeRun(() -> setStatus(getString(R.string.projection_status_step_daemon)));
            String apkPath = getPackageCodePath();
            String logPath = getExternalFilesDir(null) + "/mirrordaemon_projection.log";
            shellFire("setsid sh -c 'CLASSPATH=" + apkPath
                    + " /system/bin/app_process64 -Xnoimage-dex2oat /system/bin"
                    + " --nice-name=" + MirrorDaemon.NICE_NAME
                    + " " + MirrorDaemon.MAIN_CLASS
                    + " </dev/null >" + logPath + " 2>&1' &");
            Thread.sleep(1500);

            // Step 2 — Bind au daemon
            safeRun(() -> setStatus(getString(R.string.projection_status_step_bind)));
            mDaemonBinder = null;
            for (int i = 0; i < 6 && mDaemonBinder == null; i++) {
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
        }

        // Step 3 — ATTACH_SLOT → daemon crée overlay+VD pour ce pkg au rect demandé
        safeRun(() -> setStatus(getString(R.string.projection_status_step_attach)));
        int displayId;
        {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                data.writeString(pkg);
                data.writeInt(rect.left);
                data.writeInt(rect.top);
                data.writeInt(rect.width());
                data.writeInt(rect.height());
                mDaemonBinder.transact(MirrorDaemon.TRANSACT_ATTACH_SLOT, data, reply, 0);
                reply.readException();
                if (reply.readInt() != 1) throw new RuntimeException("ATTACH_SLOT failed");
                reply.readParcelable(Surface.class.getClassLoader()); // surface (unused client-side)
                displayId = reply.readInt();
                if (displayId < 0) throw new RuntimeException("ATTACH_SLOT: invalid displayId");
                AppLogger.d(TAG, "ATTACH_SLOT OK pkg=" + pkg + " displayId=" + displayId);
            } finally { data.recycle(); reply.recycle(); }
        }

        // Step 4 — LAUNCH_AND_FORCE → lance l'app sur ce VD
        safeRun(() -> setStatus(getString(R.string.projection_status_step_launch, pkg)));
        {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                data.writeString(pkg);
                data.writeInt(displayId);
                data.writeInt(rect.width());
                data.writeInt(rect.height());
                mDaemonBinder.transact(MirrorDaemon.TRANSACT_LAUNCH_AND_FORCE, data, reply, 0);
                reply.readException();
                String log = reply.readString();
                AppLogger.d(TAG, "LAUNCH_AND_FORCE: " + log);
                if (!log.startsWith("OK")) throw new RuntimeException("LAUNCH_AND_FORCE: " + log);
            } finally { data.recycle(); reply.recycle(); }
        }

        // Step 5 — MIRROR_START sur le premier slot (nav) pour la preview tablette
        if (isFirst) {
            safeRun(() -> setStatus(getString(R.string.projection_status_step_mirror)));
            int svW = svPreview.getWidth(), svH = svPreview.getHeight();
            if (svW <= 0 || svH <= 0) { svW = CLUSTER_W; svH = CLUSTER_H; }
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                data.writeInt(displayId); // layerStack == displayId on API 29
                data.writeInt(rect.width());
                data.writeInt(rect.height());
                data.writeInt(displayId);
                data.writeInt(svW);
                data.writeInt(svH);
                data.writeParcelable(mHolder.getSurface(), 0);
                mDaemonBinder.transact(MirrorDaemon.TRANSACT_MIRROR_START, data, reply, 0);
                reply.readException();
                mMirrorReady = (reply.readInt() == 1);
                // Mise à jour des champs legacy pour le touch forwarding
                mVdDisplayId  = displayId;
                mVdLayerStack = displayId;
            } finally { data.recycle(); reply.recycle(); }
        }

        // Enregistre le slot côté client
        SlotState slot = new SlotState(pkg, label, displayId, new Rect(rect));
        mSlots.put(pkg, slot);
        mProjecting = !mSlots.isEmpty();

        safeRun(() -> {
            updateSlotsStatus();
            btnStart.setEnabled(mSurfaceReady && mSlots.size() < 2);
            btnStop.setEnabled(!mSlots.isEmpty());
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

    /** Libère un slot individuel (sans stopper les autres). */
    private void releaseSlot(String pkg) {
        mExec.execute(() -> {
            if (mDaemonBinder != null) {
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                    data.writeString(pkg);
                    mDaemonBinder.transact(MirrorDaemon.TRANSACT_RELEASE_SLOT, data, reply, 0);
                    reply.readException();
                } catch (Exception e) { AppLogger.e(TAG, "RELEASE_SLOT error", e); }
                finally { data.recycle(); reply.recycle(); }
            }
            mSlots.remove(pkg);
            mProjecting = !mSlots.isEmpty();
            safeRun(() -> {
                updateSlotsStatus();
                btnStart.setEnabled(mSurfaceReady && mSlots.size() < 2);
                btnStop.setEnabled(!mSlots.isEmpty());
            });
        });
    }

    /** Passe un slot en plein écran : libère les autres, resize ce slot. */
    private void fullscreenSlot(String pkg) {
        SlotState slot = mSlots.get(pkg);
        if (slot == null) return;
        // Libère les autres slots
        for (String other : new ArrayList<>(mSlots.keySet())) {
            if (!other.equals(pkg)) releaseSlot(other);
        }
        // Resize vers plein écran effectif
        Rect full = effectiveRect();
        saveRect(pkg, full);
        sendResizeOverlay(full.left, full.top, full.width(), full.height());
        slot.rect = full;
        safeRun(this::updateSlotsStatus);
    }

    /** Repeuple la liste des slots actifs avec une ligne par app. */
    private void updateSlotsStatus() {
        if (llSlots == null) return;
        llSlots.removeAllViews();

        if (mSlots.isEmpty()) {
            llSlots.setVisibility(View.GONE);
            dividerSlots.setVisibility(View.GONE);
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(R.string.projection_status_idle);
            return;
        }

        llSlots.setVisibility(View.VISIBLE);
        dividerSlots.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.GONE);

        for (SlotState slot : mSlots.values()) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_slot, llSlots, false);

            TextView tvLabel = row.findViewById(R.id.tv_slot_label);
            tvLabel.setText(slot.label
                    + "  " + slot.rect.width() + "×" + slot.rect.height()
                    + "  @(" + slot.rect.left + "," + slot.rect.top + ")");

            MaterialButton btnResize = row.findViewById(R.id.btn_slot_resize);
            btnResize.setOnClickListener(v -> showSlotResizeDialog(slot));

            MaterialButton btnFs = row.findViewById(R.id.btn_slot_fullscreen);
            btnFs.setEnabled(mSlots.size() > 1 || !slot.rect.equals(effectiveRect()));
            btnFs.setOnClickListener(v -> fullscreenSlot(slot.pkg));

            MaterialButton btnClose = row.findViewById(R.id.btn_slot_close);
            btnClose.setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setTitle("Fermer " + slot.label + " ?")
                            .setPositiveButton("Fermer", (d, w) -> releaseSlot(slot.pkg))
                            .setNegativeButton("Annuler", null)
                            .show());

            llSlots.addView(row);
        }
    }

    private void stopProjectionInternal() {
        mProjecting = false;
        mMirrorReady = false;
        mSlots.clear();
        // MIRROR_STOP
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
        // VD owned and released by daemon on MIRROR_STOP.
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

    // ── Overlay sizing dialog ─────────────────────────────────────────────────

    private void showOverlaySizingDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_overlay_sizing, null);

        SeekBar sbTop    = dialogView.findViewById(R.id.sb_margin_top);
        SeekBar sbBottom = dialogView.findViewById(R.id.sb_margin_bottom);
        SeekBar sbLeft   = dialogView.findViewById(R.id.sb_margin_left);
        SeekBar sbRight  = dialogView.findViewById(R.id.sb_margin_right);
        TextView tvTop    = dialogView.findViewById(R.id.tv_margin_top_val);
        TextView tvBottom = dialogView.findViewById(R.id.tv_margin_bottom_val);
        TextView tvLeft   = dialogView.findViewById(R.id.tv_margin_left_val);
        TextView tvRight  = dialogView.findViewById(R.id.tv_margin_right_val);
        TextView tvRect   = dialogView.findViewById(R.id.tv_overlay_rect);

        // Max: 300px haut/bas, 500px gauche/droite
        sbTop.setMax(300);    sbTop.setProgress(mMarginTop);
        sbBottom.setMax(300); sbBottom.setProgress(mMarginBottom);
        sbLeft.setMax(500);   sbLeft.setProgress(mMarginLeft);
        sbRight.setMax(500);  sbRight.setProgress(mMarginRight);

        Runnable updateRect = () -> {
            int t = sbTop.getProgress(), b = sbBottom.getProgress();
            int l = sbLeft.getProgress(), r = sbRight.getProgress();
            tvTop.setText(t + " px");
            tvBottom.setText(b + " px");
            tvLeft.setText(l + " px");
            tvRight.setText(r + " px");
            int x = l, y = t, w = CLUSTER_W - l - r, h = CLUSTER_H - t - b;
            tvRect.setText("Overlay : x=" + x + " y=" + y + "  " + w + "×" + h + " px");
        };
        updateRect.run();

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) { updateRect.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
        sbTop.setOnSeekBarChangeListener(listener);
        sbBottom.setOnSeekBarChangeListener(listener);
        sbLeft.setOnSeekBarChangeListener(listener);
        sbRight.setOnSeekBarChangeListener(listener);

        new AlertDialog.Builder(this)
                .setTitle("Ajustement de l'overlay cluster")
                .setView(dialogView)
                .setPositiveButton("Appliquer", (d, w) -> {
                    mMarginTop    = sbTop.getProgress();
                    mMarginBottom = sbBottom.getProgress();
                    mMarginLeft   = sbLeft.getProgress();
                    mMarginRight  = sbRight.getProgress();
                    // Persist
                    getSharedPreferences(PREFS_SIZING, Context.MODE_PRIVATE).edit()
                            .putInt(PREF_TOP,    mMarginTop)
                            .putInt(PREF_BOTTOM, mMarginBottom)
                            .putInt(PREF_LEFT,   mMarginLeft)
                            .putInt(PREF_RIGHT,  mMarginRight)
                            .apply();
                    // Resize en live si projection active
                    if (mProjecting && mDaemonBinder != null) {
                        sendResizeOverlay(mMarginLeft, mMarginTop,
                                CLUSTER_W - mMarginLeft - mMarginRight,
                                CLUSTER_H - mMarginTop - mMarginBottom);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showSlotResizeDialog(SlotState slot) {
        Rect eff = effectiveRect();
        int ew = eff.width(), eh = eff.height(), mx = eff.left, my = eff.top;

        String[] names = {
            "Plein écran",
            "Gauche 1/2",  "Droite 1/2",
            "Gauche 3/4",  "Droite 3/4",
            "Gauche 1/4",  "Droite 1/4"
        };
        Rect[] rects = {
            new Rect(mx,            my, mx + ew,       my + eh),
            new Rect(mx,            my, mx + ew/2,     my + eh),
            new Rect(mx + ew/2,     my, mx + ew,       my + eh),
            new Rect(mx,            my, mx + ew*3/4,   my + eh),
            new Rect(mx + ew/4,     my, mx + ew,       my + eh),
            new Rect(mx,            my, mx + ew/4,     my + eh),
            new Rect(mx + ew*3/4,   my, mx + ew,       my + eh)
        };

        new AlertDialog.Builder(this)
                .setTitle("Redimensionner " + slot.label)
                .setItems(names, (d, which) -> {
                    Rect chosen = rects[which];
                    saveRect(slot.pkg, chosen);
                    slot.rect = new Rect(chosen);
                    if (mDaemonBinder != null) sendResizeSlot(slot.pkg, chosen);
                    safeRun(this::updateSlotsStatus);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void sendResizeSlot(String pkg, Rect r) {
        mExec.execute(() -> {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                data.writeString(pkg);
                data.writeInt(r.left); data.writeInt(r.top);
                data.writeInt(r.width()); data.writeInt(r.height());
                mDaemonBinder.transact(MirrorDaemon.TRANSACT_RESIZE_SLOT, data, reply, 0);
                reply.readException();
                AppLogger.d(TAG, "RESIZE_SLOT pkg=" + pkg + " ok=" + reply.readInt());
            } catch (Exception e) {
                AppLogger.e(TAG, "RESIZE_SLOT error", e);
            } finally { data.recycle(); reply.recycle(); }
        });
    }

    private void sendResizeOverlay(int x, int y, int w, int h) {
        mExec.execute(() -> {
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                data.writeInt(x);
                data.writeInt(y);
                data.writeInt(w);
                data.writeInt(h);
                mDaemonBinder.transact(MirrorDaemon.TRANSACT_RESIZE_OVERLAY, data, reply, 0);
                reply.readException();
                int ok = reply.readInt();
                AppLogger.d(TAG, "RESIZE_OVERLAY reply ok=" + ok + " rect=(" + x + "," + y + "," + w + "×" + h + ")");
            } catch (Exception e) {
                AppLogger.e(TAG, "RESIZE_OVERLAY error", e);
            } finally {
                data.recycle();
                reply.recycle();
            }
        });
    }

    /**
     * Mappe les coordonnées touch du SurfaceView preview (viewW×viewH) vers l'espace
     * cluster (1920×720) et envoie via TRANSACT_INJECT_MOTION au daemon (FLAG_ONEWAY).
     *
     * Mapping linéaire : clusterX = x / viewW * 1920, clusterY = y / viewH * 720.
     * Correct car MIRROR_START projette src(1920×720) → dest(viewW×viewH) sans letterbox.
     */
    private void forwardTouch(MotionEvent event, int viewW, int viewH) {
        if (mDaemonBinder == null || viewW <= 0 || viewH <= 0) return;

        // Mapping linéaire view → cluster (pas de letterbox : le mirror remplit tout le SurfaceView)
        float clusterX = event.getX() / viewW * CLUSTER_W;
        float clusterY = event.getY() / viewH * CLUSTER_H;
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
