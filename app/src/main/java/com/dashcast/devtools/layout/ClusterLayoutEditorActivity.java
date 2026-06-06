package com.dashcast.devtools.layout;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.dashcast.devtools.R;
import com.dashcast.devtools.common.AppLogger;
import com.dashcast.devtools.common.MirrorDaemon;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClusterLayoutEditorActivity extends Activity {

    private static final String TAG        = "ClusterLayoutEditor";
    private static final String PREFS_KEY  = "cluster_layouts_v1";
    private static final String ACTIVE_KEY = "active_layout_id";

    // Partagé avec Dl3ProjectionActivity pour le zone picker
    public static volatile IBinder                    sDaemonBinder;
    public static volatile List<LayoutPreset.SlotDef> sActiveSlots;

    private ClusterCanvasView  mCanvas;
    private LinearLayout       mLlLayouts;
    private MaterialButton     mBtnClear, mBtnSave, mBtnFreeMode;

    private List<LayoutPreset> mPresets = new ArrayList<>();
    private LayoutPreset       mEditing;       // layout en cours d'édition (zones dessinées)
    private String             mActiveId;      // id du layout actuellement activé (VDs persistants)

    private final ExecutorService mExec = Executors.newSingleThreadExecutor();

    // Marges XDJA (transmises depuis Dl3ProjectionActivity via extras)
    private int mMarginTop = 0, mMarginBottom = 0, mMarginLeft = 0, mMarginRight = 0;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_cluster_layout_editor);

        // Marges depuis l'intent
        mMarginTop    = getIntent().getIntExtra("marginTop",    0);
        mMarginBottom = getIntent().getIntExtra("marginBottom", 0);
        mMarginLeft   = getIntent().getIntExtra("marginLeft",   0);
        mMarginRight  = getIntent().getIntExtra("marginRight",  0);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_layout_editor);
        toolbar.setNavigationOnClickListener(v -> finish());

        mCanvas    = findViewById(R.id.cluster_canvas);
        mLlLayouts = findViewById(R.id.ll_layouts);
        mBtnClear  = findViewById(R.id.btn_clear_zones);
        mBtnSave   = findViewById(R.id.btn_save_layout);
        mBtnFreeMode = findViewById(R.id.btn_free_mode);

        mCanvas.setMargins(mMarginTop, mMarginBottom, mMarginLeft, mMarginRight);

        mEditing = new LayoutPreset("Nouveau layout");
        mCanvas.setSlots(mEditing.slots);

        mCanvas.setOnZoneDrawnListener((x, y, w, h) -> {
            showAddZoneDialog(x, y, w, h);
        });
        mCanvas.setOnZoneLongPressListener(idx -> {
            new AlertDialog.Builder(this)
                    .setTitle("Supprimer la zone ?")
                    .setPositiveButton("Supprimer", (d, w2) -> {
                        mEditing.slots.remove(idx);
                        mCanvas.invalidate();
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
        });

        mBtnClear.setOnClickListener(v -> {
            mEditing.slots.clear();
            mCanvas.invalidate();
        });

        mBtnSave.setOnClickListener(v -> {
            if (mEditing.slots.isEmpty()) {
                Toast.makeText(this, "Dessinez au moins une zone", Toast.LENGTH_SHORT).show();
                return;
            }
            showSaveDialog();
        });

        mBtnFreeMode.setOnClickListener(v -> deactivateLayout());

        // Charge les layouts sauvegardés
        loadPresets();
        mActiveId = getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
                .getString(ACTIVE_KEY, null);
        refreshLayoutList();
    }

    // ── Gestion des zones ─────────────────────────────────────────────────────

    private void showAddZoneDialog(int x, int y, int w, int h) {
        EditText et = new EditText(this);
        et.setHint("Nom de la zone");
        et.setText(mEditing.nextSlotLabel());
        et.selectAll();

        new AlertDialog.Builder(this)
                .setTitle("Nouvelle zone — " + w + "×" + h + " px")
                .setView(et)
                .setPositiveButton("Ajouter", (d, which) -> {
                    String label = et.getText().toString().trim();
                    if (label.isEmpty()) label = mEditing.nextSlotLabel();
                    mEditing.slots.add(new LayoutPreset.SlotDef(label, x, y, w, h));
                    mCanvas.invalidate();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ── Sauvegarde ────────────────────────────────────────────────────────────

    private void showSaveDialog() {
        EditText et = new EditText(this);
        et.setHint("Nom du layout");
        et.setText(mEditing.name);
        et.selectAll();

        new AlertDialog.Builder(this)
                .setTitle("Enregistrer le layout")
                .setView(et)
                .setPositiveButton("Enregistrer", (d, which) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) name = "Layout " + (mPresets.size() + 1);
                    mEditing.name = name;
                    mPresets.add(mEditing);
                    savePresets();
                    // Prépare un nouveau layout vide pour l'édition
                    mEditing = new LayoutPreset("Nouveau layout");
                    mCanvas.setSlots(mEditing.slots);
                    refreshLayoutList();
                    Toast.makeText(this, "Layout enregistré", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ── Activation ────────────────────────────────────────────────────────────

    private void activateLayout(LayoutPreset preset) {
        IBinder binder = sDaemonBinder;
        if (binder == null) {
            Toast.makeText(this, "Daemon non connecté — lancez d'abord une projection", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Activation de " + preset.name + "…", Toast.LENGTH_SHORT).show();

        mExec.execute(() -> {
            // Désactive le layout précédent si différent
            if (mActiveId != null && !mActiveId.equals(preset.id)) {
                sendDeactivateLayout(binder);
            }

            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                data.writeInt(preset.slots.size());
                for (LayoutPreset.SlotDef s : preset.slots) {
                    data.writeString(s.label);
                    data.writeInt(s.x); data.writeInt(s.y);
                    data.writeInt(s.w); data.writeInt(s.h);
                }
                binder.transact(MirrorDaemon.TRANSACT_ACTIVATE_LAYOUT, data, reply, 0);
                reply.readException();
                int n = reply.readInt();
                boolean ok = true;
                // Daemon returns exactly n displayIds; read them all defensively
                for (int i = 0; i < n; i++) {
                    int displayId = reply.dataAvail() > 0 ? reply.readInt() : -1;
                    if (i < preset.slots.size()) {
                        preset.slots.get(i).displayId = displayId;
                        if (displayId < 0) ok = false;
                    }
                }
                mActiveId = preset.id;
                sActiveSlots = new ArrayList<>(preset.slots);
                getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE).edit()
                        .putString(ACTIVE_KEY, mActiveId).apply();

                final boolean success = ok;
                final String name = preset.name;
                runOnUiThread(() -> {
                    refreshLayoutList();
                    Toast.makeText(this,
                            success ? name + " activé ✓" : name + " activé (certains slots ont échoué)",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                AppLogger.e(TAG, "ACTIVATE_LAYOUT error", e);
                runOnUiThread(() -> Toast.makeText(this, "Erreur: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally { data.recycle(); reply.recycle(); }
        });
    }

    private void deactivateLayout() {
        IBinder binder = sDaemonBinder;
        mActiveId = null;
        sActiveSlots = null;
        getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE).edit()
                .remove(ACTIVE_KEY).apply();
        for (LayoutPreset p : mPresets) {
            for (LayoutPreset.SlotDef s : p.slots) s.displayId = -1;
        }
        refreshLayoutList();
        if (binder != null) {
            mExec.execute(() -> sendDeactivateLayout(binder));
        }
        Toast.makeText(this, "Mode libre activé", Toast.LENGTH_SHORT).show();
    }

    private void sendDeactivateLayout(IBinder binder) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            binder.transact(MirrorDaemon.TRANSACT_DEACTIVATE_LAYOUT, data, reply, 0);
            reply.readException();
        } catch (Exception ignored) {} finally { data.recycle(); reply.recycle(); }
    }

    // ── Assignation d'app à un slot activé ───────────────────────────────────

    private void assignApp(LayoutPreset.SlotDef slot) {
        if (slot.displayId < 0) {
            Toast.makeText(this, "Zone [" + slot.label + "] non activée — activez le layout d'abord",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // Ouvre directement le picker d'app pour ce slot
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN);
        main.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        if (infos == null || infos.isEmpty()) {
            Toast.makeText(this, "Aucune app disponible", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.Map<String, String> pkgToLabel = new java.util.LinkedHashMap<>();
        String self = getPackageName();
        for (android.content.pm.ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(self) || pkgToLabel.containsKey(pkg)) continue;
            CharSequence lbl = ri.loadLabel(pm);
            pkgToLabel.put(pkg, lbl != null ? lbl.toString() : pkg);
        }
        java.util.List<java.util.Map.Entry<String,String>> entries = new java.util.ArrayList<>(pkgToLabel.entrySet());
        entries.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        String[] labels = new String[entries.size()];
        String[] pkgs   = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            pkgs[i]   = entries.get(i).getKey();
            labels[i] = entries.get(i).getValue() + "  —  " + pkgs[i];
        }
        final com.dashcast.devtools.layout.LayoutPreset.SlotDef finalSlot = slot;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Lancer dans [" + slot.label + "]")
                .setItems(labels, (d, which) -> launchAppInSlot(pkgs[which], labels[which], finalSlot))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void launchAppInSlot(String pkg, String appLabel, LayoutPreset.SlotDef slot) {
        IBinder binder = sDaemonBinder;
        if (binder == null) {
            Toast.makeText(this, "Daemon non connecté", Toast.LENGTH_SHORT).show();
            return;
        }
        mExec.execute(() -> {
            android.os.Parcel data = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken(com.dashcast.devtools.common.MirrorDaemon.DESCRIPTOR);
                data.writeString(pkg);
                data.writeInt(slot.displayId);
                data.writeInt(slot.w);
                data.writeInt(slot.h);
                binder.transact(com.dashcast.devtools.common.MirrorDaemon.TRANSACT_LAUNCH_AND_FORCE, data, reply, 0);
                reply.readException();
                String log = reply.readString();
                runOnUiThread(() -> Toast.makeText(this,
                        log.startsWith("OK") ? appLabel + " lancé dans [" + slot.label + "]"
                                             : "Erreur: " + log,
                        Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Erreur: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally { data.recycle(); reply.recycle(); }
        });
    }

    // ── Persistance ───────────────────────────────────────────────────────────

    private void loadPresets() {
        mPresets.clear();
        try {
            String json = getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
                    .getString("presets", "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                mPresets.add(LayoutPreset.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) { AppLogger.e(TAG, "loadPresets", e); }
    }

    private void savePresets() {
        try {
            JSONArray arr = new JSONArray();
            for (LayoutPreset p : mPresets) arr.put(p.toJson());
            getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE).edit()
                    .putString("presets", arr.toString()).apply();
        } catch (Exception e) { AppLogger.e(TAG, "savePresets", e); }
    }

    // ── UI liste ─────────────────────────────────────────────────────────────

    private void refreshLayoutList() {
        mLlLayouts.removeAllViews();
        if (mPresets.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Aucun layout sauvegardé");
            tv.setPadding(16, 8, 16, 8);
            mLlLayouts.addView(tv);
            return;
        }
        for (LayoutPreset preset : mPresets) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_layout_preset, mLlLayouts, false);

            TextView tvName  = row.findViewById(R.id.tv_layout_name);
            TextView tvSlots = row.findViewById(R.id.tv_layout_slots);
            MaterialButton btnActivate = row.findViewById(R.id.btn_activate_layout);
            MaterialButton btnEdit     = row.findViewById(R.id.btn_edit_layout);
            MaterialButton btnDelete   = row.findViewById(R.id.btn_delete_layout);
            LinearLayout   llActive   = row.findViewById(R.id.ll_active_slots);

            boolean isActive = preset.id.equals(mActiveId);
            tvName.setText(preset.name + (isActive ? "  ●" : ""));
            tvSlots.setText(preset.slots.size() + " zones");

            btnActivate.setText(isActive ? "Désactiver" : "Activer");
            btnActivate.setOnClickListener(v -> {
                if (isActive) deactivateLayout();
                else activateLayout(preset);
            });

            btnEdit.setOnClickListener(v -> {
                mEditing = new LayoutPreset(preset.name);
                mEditing.id = preset.id;
                for (LayoutPreset.SlotDef s : preset.slots) {
                    mEditing.slots.add(new LayoutPreset.SlotDef(s.label, s.x, s.y, s.w, s.h));
                }
                mCanvas.setSlots(mEditing.slots);
                mCanvas.invalidate();
                Toast.makeText(this, "Modifiez les zones puis enregistrez", Toast.LENGTH_SHORT).show();
            });

            btnDelete.setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setTitle("Supprimer " + preset.name + " ?")
                            .setPositiveButton("Supprimer", (d, w) -> {
                                if (preset.id.equals(mActiveId)) deactivateLayout();
                                mPresets.remove(preset);
                                savePresets();
                                refreshLayoutList();
                            })
                            .setNegativeButton("Annuler", null)
                            .show());

            // Affiche les slots actifs avec bouton "Assigner app"
            if (isActive) {
                llActive.setVisibility(View.VISIBLE);
                for (LayoutPreset.SlotDef s : preset.slots) {
                    MaterialButton btnAssign = new MaterialButton(this,
                            null, com.google.android.material.R.attr.borderlessButtonStyle);
                    String label = s.label + "  " + s.w + "×" + s.h
                            + (s.displayId >= 0 ? "  [VD:" + s.displayId + "]" : "  [ERREUR]");
                    btnAssign.setText(label);
                    btnAssign.setOnClickListener(vv -> assignApp(s));
                    llActive.addView(btnAssign);
                }
            }

            mLlLayouts.addView(row);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExec.shutdown();
        try { mExec.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
    }
}
