package com.dashcast.devtools.dilink;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.dashcast.devtools.R;
import com.dashcast.devtools.common.AppLogger;
import com.dashcast.devtools.common.Platform;
import com.dashcast.devtools.dilink.DlReconRunner;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * DiLink 5.0 — activité diagnostique avec deux tabs :
 * <ul>
 *   <li>Recon → Dl5ClusterReconRunner (R01–R30)</li>
 *   <li>Fission → Dl5VdTestRunner (V01–V07, avec SurfaceView pour V05)</li>
 * </ul>
 */
public class DiLink50Activity extends AppCompatActivity {

    private static final String TAG = "DiLink50Activity";
    private static final int TAB_RECON   = 0;
    private static final int TAB_FISSION = 1;
    private static final int TAB_SONDES  = 2;

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private TabLayout tabs;
    private View      panelRecon;
    private View      panelFission;
    private View      panelSondes;

    // ── Recon views ───────────────────────────────────────────────────────────
    private TextView     tvReconSubtitle;
    private TextView     tvReconPill;
    private TextView     tvReconCounters;
    private View         btnReconRunAll;
    private View         btnReconShare;
    private LinearLayout llReconTestList;

    // ── Fission views ─────────────────────────────────────────────────────────
    private TextView     tvFissionSubtitle;
    private TextView     tvFissionPill;
    private TextView     tvFissionCounters;
    private View         btnFissionRun;
    private View         btnFissionShare;
    private LinearLayout llFissionTestList;
    private FrameLayout  flFissionSurfaceWrapper;
    private SurfaceView  svFissionCluster;
    private SurfaceHolder mVdSurfaceHolder;

    // ── Recon state ───────────────────────────────────────────────────────────
    private boolean mReconRowsPrepared = false;
    private final List<View> mReconRowViews = new ArrayList<>();
    private final List<DiLink5TestRunner.TestResult> mReconLastResults = new ArrayList<>();

    // ── Fission state ─────────────────────────────────────────────────────────
    private boolean mFissionRowsPrepared = false;
    private final List<View> mFissionRowViews = new ArrayList<>();
    private final List<DiLink5TestRunner.TestResult> mFissionLastResults = new ArrayList<>();
    private android.os.IBinder mVdDaemonBinder = null;

    // ── Common ────────────────────────────────────────────────────────────────
    private boolean mDestroyed = false;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());

    // ── Sondes (DlReconRunner) — state ─────────────────────────────────────────
    private boolean mSondesRowsPrepared = false;
    private final List<View>                     mSondesRowViews = new ArrayList<>();
    private final List<DlReconRunner.TestResult> mSondesResults  = new ArrayList<>();
    private View         mBtnSondesRun;
    private View         mBtnSondesShare;
    private TextView     mTvSondesSubtitle;
    private TextView     mTvSondesPill;
    private TextView     mTvSondesCounters;
    private LinearLayout mLlSondesList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dilink50);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        tabs         = findViewById(R.id.tabs_dl50);
        panelRecon   = findViewById(R.id.panel_recon);
        panelFission = findViewById(R.id.panel_fission);
        panelSondes  = findViewById(R.id.panel_sondes);

        // Onglets
        tabs.addTab(tabs.newTab().setText(R.string.diag_tab_recon));
        tabs.addTab(tabs.newTab().setText(R.string.diag_tab_fission));
        tabs.addTab(tabs.newTab().setText(R.string.diag_dl50_tab_sondes));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab)   { showPanel(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        bindReconViews();
        bindFissionViews();
        bindSondesViews();
        showPanel(TAB_RECON);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
        mUiHandler.removeCallbacksAndMessages(null);
    }

    // ── Panel switch ──────────────────────────────────────────────────────────

    private void showPanel(int pos) {
        panelRecon.setVisibility(pos == TAB_RECON    ? View.VISIBLE : View.GONE);
        panelFission.setVisibility(pos == TAB_FISSION ? View.VISIBLE : View.GONE);
        panelSondes.setVisibility(pos == TAB_SONDES  ? View.VISIBLE : View.GONE);
        if (pos == TAB_RECON)   prepareReconRowsIfNeeded();
        if (pos == TAB_FISSION) prepareFissionRowsIfNeeded();
        if (pos == TAB_SONDES)  { bindSondesHeader(); prepareSondesRowsIfNeeded(); }
    }

    // ── Recon panel ───────────────────────────────────────────────────────────

    private void bindReconViews() {
        tvReconSubtitle     = panelRecon.findViewById(R.id.tv_recon_subtitle);
        tvReconPill         = panelRecon.findViewById(R.id.tv_recon_pill);
        tvReconCounters     = panelRecon.findViewById(R.id.tv_recon_counters);
        btnReconRunAll      = panelRecon.findViewById(R.id.btn_recon_run_all);
        btnReconShare       = panelRecon.findViewById(R.id.btn_recon_share);
        llReconTestList     = panelRecon.findViewById(R.id.ll_recon_test_list);

        Platform p = Platform.get();
        String prod = p.rawProductName();
        if (prod == null || prod.isEmpty()) prod = "?";
        tvReconSubtitle.setText(getString(R.string.diag_platform_subtitle_fmt, prod, p.androidApi()));
        tvReconPill.setText(p.isDiLink5(this) ? getString(R.string.diag_dl50_pill_detected)
                                              : getString(R.string.diag_dl50_pill_unknown));
        tvReconCounters.setText(R.string.diag_counters_idle);

        btnReconRunAll.setOnClickListener(v -> runReconAllTests());
        btnReconShare.setOnClickListener(v -> AppLogger.shareWithReport(this,
                Dl5ClusterReconRunner.renderReport(mReconLastResults)));
        btnReconShare.setEnabled(false);
    }

    private void prepareReconRowsIfNeeded() {
        if (mReconRowsPrepared) return;
        mReconRowsPrepared = true;
        mReconRowViews.clear();
        mReconLastResults.clear();
        llReconTestList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (DiLink5TestRunner.TestDef def : Dl5ClusterReconRunner.catalog()) {
            View row = inflater.inflate(R.layout.item_test_row, llReconTestList, false);
            DiLink5TestRunner.TestResult r = new DiLink5TestRunner.TestResult(def);
            r.status = DiLink5TestRunner.Status.PENDING;
            bindDl5Row(row, r);
            llReconTestList.addView(row);
            mReconRowViews.add(row);
            mReconLastResults.add(r);
        }
    }

    private void runReconAllTests() {
        prepareReconRowsFor(Dl5ClusterReconRunner.catalog(), mReconRowViews, mReconLastResults, llReconTestList);
        btnReconRunAll.setEnabled(false);
        btnReconShare.setEnabled(false);
        tvReconCounters.setText(R.string.diag_counters_running);
        Dl5ClusterReconRunner.runAll(this, new Dl5ClusterReconRunner.Listener() {
            @Override public void onSuiteStarted(List<DiLink5TestRunner.TestResult> results) {
                safeRun(() -> {
                    mReconLastResults.clear(); mReconLastResults.addAll(results);
                    for (int i = 0; i < results.size() && i < mReconRowViews.size(); i++)
                        bindDl5Row(mReconRowViews.get(i), results.get(i));
                });
            }
            @Override public void onTestUpdated(int index, DiLink5TestRunner.TestResult result) {
                safeRun(() -> {
                    if (index < mReconLastResults.size()) mReconLastResults.set(index, result);
                    if (index < mReconRowViews.size()) bindDl5Row(mReconRowViews.get(index), result);
                    updateReconCounters();
                });
            }
            @Override public void onSuiteFinished(List<DiLink5TestRunner.TestResult> results) {
                safeRun(() -> {
                    btnReconRunAll.setEnabled(true);
                    btnReconShare.setEnabled(true);
                    updateReconCounters();
                });
            }
        });
    }

    private void updateReconCounters() {
        int pass = 0, fail = 0, skip = 0, warn = 0;
        for (DiLink5TestRunner.TestResult r : mReconLastResults) {
            switch (r.status) {
                case PASS: pass++; break; case FAIL: fail++; break;
                case SKIPPED: skip++; break; case WARN: warn++; break;
                default: break;
            }
        }
        tvReconCounters.setText(getString(R.string.diag_counters_warn_fmt, pass, fail, warn, skip));
    }

    // ── Fission panel ─────────────────────────────────────────────────────────

    private void bindFissionViews() {
        tvFissionSubtitle      = panelFission.findViewById(R.id.tv_fission_subtitle);
        tvFissionPill          = panelFission.findViewById(R.id.tv_fission_pill);
        tvFissionCounters      = panelFission.findViewById(R.id.tv_fission_counters);
        btnFissionRun          = panelFission.findViewById(R.id.btn_fission_run);
        btnFissionShare        = panelFission.findViewById(R.id.btn_fission_share);
        llFissionTestList      = panelFission.findViewById(R.id.ll_fission_test_list);
        flFissionSurfaceWrapper= panelFission.findViewById(R.id.fl_fission_surface_wrapper);
        svFissionCluster       = panelFission.findViewById(R.id.sv_fission_cluster);

        Platform p = Platform.get();
        String prod = p.rawProductName();
        if (prod == null || prod.isEmpty()) prod = "?";
        tvFissionSubtitle.setText(getString(R.string.diag_platform_subtitle_fmt, prod, p.androidApi()));
        tvFissionPill.setText(p.isDiLink5(this) ? getString(R.string.diag_dl50_pill_detected)
                                                : getString(R.string.diag_dl50_pill_unknown));
        tvFissionCounters.setText(R.string.diag_counters_idle);

        // Prépare le SurfaceHolder (V05 mirror output)
        mVdSurfaceHolder = svFissionCluster.getHolder();
        mVdSurfaceHolder.setFixedSize(1920, 720);

        btnFissionRun.setOnClickListener(v -> pickVdTargetThenRun());
        btnFissionShare.setOnClickListener(v -> AppLogger.shareWithReport(this,
                Dl5VdTestRunner.renderReport(mFissionLastResults)));
        btnFissionShare.setEnabled(false);
    }

    private void prepareFissionRowsIfNeeded() {
        if (mFissionRowsPrepared) return;
        mFissionRowsPrepared = true;
        prepareReconRowsFor(Dl5VdTestRunner.catalog(), mFissionRowViews, mFissionLastResults, llFissionTestList);
    }

    /** Shared row inflater helper — resets the list container and fills it from {@code defs}. */
    private void prepareReconRowsFor(List<DiLink5TestRunner.TestDef> defs,
                                     List<View> rowViews,
                                     List<DiLink5TestRunner.TestResult> results,
                                     LinearLayout container) {
        rowViews.clear();
        results.clear();
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (DiLink5TestRunner.TestDef def : defs) {
            View row = inflater.inflate(R.layout.item_test_row, container, false);
            DiLink5TestRunner.TestResult r = new DiLink5TestRunner.TestResult(def);
            r.status = DiLink5TestRunner.Status.PENDING;
            bindDl5Row(row, r);
            container.addView(row);
            rowViews.add(row);
            results.add(r);
        }
    }

    private void pickVdTargetThenRun() {
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN);
        main.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        if (infos == null || infos.isEmpty()) {
            Toast.makeText(this, R.string.diag_dl50_fission_pick_empty, Toast.LENGTH_LONG).show();
            return;
        }
        String selfPkg = getPackageName();
        java.util.Map<String, String> pkgToLabel = new java.util.LinkedHashMap<>();
        for (android.content.pm.ResolveInfo ri : infos) {
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
        List<java.util.Map.Entry<String, String>> sorted = new java.util.ArrayList<>(pkgToLabel.entrySet());
        java.util.Collections.sort(sorted, (a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        final String[] pkgs   = new String[sorted.size()];
        final String[] labels = new String[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            pkgs[i]   = sorted.get(i).getKey();
            labels[i] = sorted.get(i).getValue() + "  —  " + sorted.get(i).getKey();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.diag_dl50_fission_pick_title)
                .setItems(labels, (d, which) -> {
                    if (which >= 0 && which < pkgs.length) runFissionTests(pkgs[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runFissionTests(String targetPkg) {
        prepareReconRowsFor(Dl5VdTestRunner.catalog(), mFissionRowViews, mFissionLastResults, llFissionTestList);
        mFissionRowsPrepared = true;

        btnFissionRun.setEnabled(false);
        btnFissionShare.setEnabled(false);
        tvFissionCounters.setText(R.string.diag_counters_running);
        flFissionSurfaceWrapper.setVisibility(View.VISIBLE);

        // Obtenir le binder daemon via ServiceManager (null si daemon absent → V05 FAIL gracieux)
        mVdDaemonBinder = tryGetVdDaemonBinder();

        Dl5VdTestRunner.run(this, targetPkg,
                mVdDaemonBinder,
                mVdSurfaceHolder != null ? mVdSurfaceHolder.getSurface() : null,
                1920, 720,
                new Dl5VdTestRunner.Listener() {
                    @Override public void onSuiteStarted(List<DiLink5TestRunner.TestResult> results) {
                        safeRun(() -> {
                            mFissionLastResults.clear();
                            mFissionLastResults.addAll(results);
                            for (int i = 0; i < results.size() && i < mFissionRowViews.size(); i++)
                                bindDl5Row(mFissionRowViews.get(i), results.get(i));
                        });
                    }
                    @Override public void onTestUpdated(int index, DiLink5TestRunner.TestResult result) {
                        safeRun(() -> {
                            if (index < mFissionLastResults.size()) mFissionLastResults.set(index, result);
                            if (index < mFissionRowViews.size()) bindDl5Row(mFissionRowViews.get(index), result);
                            updateFissionCounters();
                        });
                    }
                    @Override public void onSuiteFinished(List<DiLink5TestRunner.TestResult> results) {
                        safeRun(() -> {
                            btnFissionRun.setEnabled(true);
                            btnFissionShare.setEnabled(true);
                            updateFissionCounters();
                        });
                    }
                    @Override public void onPromptYesNo(String title, String message,
                                                        java.util.function.Consumer<Boolean> callback) {
                        if (mDestroyed) { callback.accept(false); return; }
                        // IMPORTANT : callback.accept() doit être appelé sur le thread Executor,
                        // pas depuis synchronized(LOCK). Ici on est déjà hors LOCK.
                        mUiHandler.post(() -> {
                            if (mDestroyed) { callback.accept(false); return; }
                            new AlertDialog.Builder(DiLink50Activity.this)
                                    .setTitle(title)
                                    .setMessage(message)
                                    .setCancelable(false)
                                    .setPositiveButton(R.string.diag_prompt_yes,
                                            (d, w) -> callback.accept(true))
                                    .setNegativeButton(R.string.diag_prompt_no,
                                            (d, w) -> callback.accept(false))
                                    .show();
                        });
                    }
                });
    }

    private void updateFissionCounters() {
        int pass = 0, fail = 0, skip = 0, warn = 0;
        for (DiLink5TestRunner.TestResult r : mFissionLastResults) {
            switch (r.status) {
                case PASS: pass++; break; case FAIL: fail++; break;
                case SKIPPED: skip++; break; case WARN: warn++; break;
                default: break;
            }
        }
        tvFissionCounters.setText(getString(R.string.diag_counters_warn_fmt, pass, fail, warn, skip));
    }

    private void copyFissionReport_unused() {
        if (mFissionLastResults.isEmpty()) {
            Toast.makeText(this, R.string.diag_toast_no_results, Toast.LENGTH_SHORT).show();
            return;
        }
        String report = Dl5VdTestRunner.renderReport(mFissionLastResults);
        AppLogger.i(TAG, "Fission report:\n" + report);
        AppLogger.shareWithReport(this, report);
    }

    private android.os.IBinder tryGetVdDaemonBinder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method get = sm.getDeclaredMethod("getService", String.class);
            get.setAccessible(true);
            return (android.os.IBinder) get.invoke(null, "byd_mirror_daemon");
        } catch (Exception e) {
            AppLogger.w(TAG, "tryGetVdDaemonBinder: " + e);
            return null;
        }
    }

    // ── Shared row binder (DL5 TestResult type) ───────────────────────────────

    private void bindDl5Row(View row, DiLink5TestRunner.TestResult r) {
        ((TextView) row.findViewById(R.id.tv_test_id)).setText(r.def.id);
        ((TextView) row.findViewById(R.id.tv_test_title)).setText(r.def.title);
        ((TextView) row.findViewById(R.id.tv_test_description)).setText(r.def.description);

        TextView statusView = row.findViewById(R.id.tv_test_status);
        TextView msgView    = row.findViewById(R.id.tv_test_message);
        TextView elapView   = row.findViewById(R.id.tv_test_elapsed);

        String glyph; int color;
        switch (r.status) {
            case PASS:    glyph = "✓"; color = 0xFF4CAF50; break;
            case FAIL:    glyph = "✗"; color = 0xFFE53935; break;
            case WARN:    glyph = "!"; color = 0xFFFFB300; break;
            case SKIPPED: glyph = "⊘"; color = 0xFF9E9E9E; break;
            case RUNNING: glyph = "…"; color = 0xFFFFB300; break;
            default:      glyph = "·"; color = 0xFF607D8B; break;
        }
        statusView.setText(glyph);
        statusView.setTextColor(color);
        elapView.setText(r.elapsedMs > 0 ? (r.elapsedMs + " ms") : "");

        if (r.message != null && !r.message.isEmpty()) {
            msgView.setVisibility(View.VISIBLE);
            msgView.setText(r.message);
            int tc;
            switch (r.status) {
                case FAIL: tc = 0xFFE53935; break;
                case PASS: tc = 0xFF4CAF50; break;
                case WARN: tc = 0xFFFFB300; break;
                default:   tc = 0xFF9E9E9E; break;
            }
            msgView.setTextColor(tc);
        } else {
            msgView.setVisibility(View.GONE);
        }
    }

    // ── Sondes panel (DlReconRunner) ──────────────────────────────────────────

    private void bindSondesViews() {
        mTvSondesSubtitle = panelSondes.findViewById(R.id.tv_dl50_sondes_subtitle);
        mTvSondesPill     = panelSondes.findViewById(R.id.tv_dl50_sondes_pill);
        mTvSondesCounters = panelSondes.findViewById(R.id.tv_dl50_sondes_counters);
        mBtnSondesRun      = panelSondes.findViewById(R.id.btn_dl50_sondes_run);
        mBtnSondesShare    = panelSondes.findViewById(R.id.btn_dl50_sondes_share);
        mLlSondesList      = panelSondes.findViewById(R.id.ll_dl50_sondes_list);
        mBtnSondesRun.setOnClickListener(v -> runSondesTests());
        mBtnSondesShare.setOnClickListener(v ->
                AppLogger.shareWithReport(this, DlReconRunner.buildReport(mSondesResults)));
        mBtnSondesShare.setEnabled(false);
    }

    private void bindSondesHeader() {
        Platform p = Platform.get();
        String prod = p.rawProductName();
        if (prod == null || prod.isEmpty()) prod = "?";
        mTvSondesSubtitle.setText(getString(R.string.diag_platform_subtitle_fmt, prod, p.androidApi()));
        mTvSondesPill.setText(p.isDiLink5(this)
                ? getString(R.string.diag_dl50_pill_detected)
                : getString(R.string.diag_dl50_pill_unknown));
        mTvSondesCounters.setText(R.string.diag_counters_idle);
    }

    private void prepareSondesRowsIfNeeded() {
        if (mSondesRowsPrepared) return;
        mSondesRowsPrepared = true;
        mLlSondesList.removeAllViews();
        mSondesRowViews.clear();
        mSondesResults.clear();
        LayoutInflater inf = LayoutInflater.from(this);
        for (DlReconRunner.TestDef def : DlReconRunner.catalog()) {
            View row = inf.inflate(R.layout.item_test_row, mLlSondesList, false);
            DlReconRunner.TestResult r = new DlReconRunner.TestResult(def);
            bindSondesRow(row, r);
            mLlSondesList.addView(row);
            mSondesRowViews.add(row);
            mSondesResults.add(r);
        }
    }

    private void bindSondesRow(View row, DlReconRunner.TestResult r) {
        ((TextView) row.findViewById(R.id.tv_test_id)).setText(r.def.id);
        ((TextView) row.findViewById(R.id.tv_test_title)).setText(r.def.title);
        ((TextView) row.findViewById(R.id.tv_test_description)).setText(r.def.description);
        TextView sv = row.findViewById(R.id.tv_test_status);
        TextView mv = row.findViewById(R.id.tv_test_message);
        TextView ev = row.findViewById(R.id.tv_test_elapsed);
        String glyph; int color;
        switch (r.status) {
            case PASS:    glyph = "✓"; color = 0xFF4CAF50; break;
            case FAIL:    glyph = "✗"; color = 0xFFE53935; break;
            case WARN:    glyph = "!"; color = 0xFFFFB300; break;
            case SKIPPED: glyph = "⊘"; color = 0xFF9E9E9E; break;
            case RUNNING: glyph = "…"; color = 0xFFFFB300; break;
            default:      glyph = "·"; color = 0xFF607D8B; break;
        }
        sv.setText(glyph); sv.setTextColor(color);
        ev.setText(r.elapsedMs > 0 ? r.elapsedMs + " ms" : "");
        if (r.message != null && !r.message.isEmpty()) {
            mv.setVisibility(View.VISIBLE); mv.setText(r.message);
            int tc; switch (r.status) {
                case FAIL: tc = 0xFFE53935; break; case PASS: tc = 0xFF4CAF50; break;
                case WARN: tc = 0xFFFFB300; break; default: tc = 0xFF9E9E9E; break;
            } mv.setTextColor(tc);
        } else { mv.setVisibility(View.GONE); }
    }

    private void runSondesTests() {
        mBtnSondesRun.setEnabled(false);
        mBtnSondesShare.setEnabled(false);
        mTvSondesCounters.setText(R.string.diag_counters_running);
        DlReconRunner.runAll(this, new DlReconRunner.Listener() {
            @Override public void onSuiteStarted(List<DlReconRunner.TestResult> results) {
                safeRun(() -> {
                    mSondesResults.clear(); mSondesResults.addAll(results);
                    for (int i = 0; i < results.size() && i < mSondesRowViews.size(); i++)
                        bindSondesRow(mSondesRowViews.get(i), results.get(i));
                });
            }
            @Override public void onTestUpdated(int idx, DlReconRunner.TestResult r) {
                safeRun(() -> {
                    if (idx < mSondesResults.size()) mSondesResults.set(idx, r);
                    if (idx < mSondesRowViews.size()) bindSondesRow(mSondesRowViews.get(idx), r);
                    updateSondesCounters();
                });
            }
            @Override public void onSuiteFinished(List<DlReconRunner.TestResult> results) {
                safeRun(() -> {
                    mBtnSondesRun.setEnabled(true);
                    mBtnSondesShare.setEnabled(true);
                    updateSondesCounters();
                });
            }
        });
    }

    private void updateSondesCounters() {
        int pass = 0, fail = 0, warn = 0, skip = 0;
        for (DlReconRunner.TestResult r : mSondesResults) {
            switch (r.status) {
                case PASS: pass++; break; case FAIL: fail++; break;
                case WARN: warn++; break; case SKIPPED: skip++; break; default: break;
            }
        }
        mTvSondesCounters.setText(warn > 0
                ? getString(R.string.diag_counters_warn_fmt, pass, fail, warn, skip)
                : getString(R.string.diag_counters_fmt, pass, fail, skip));
    }

    private void copySondesReport_unused() {
        if (mSondesResults.isEmpty()) {
            Toast.makeText(this, R.string.diag_toast_no_results, Toast.LENGTH_SHORT).show();
            return;
        }
        AppLogger.shareWithReport(this, DlReconRunner.buildReport(mSondesResults));
    }

    // ── Lifecycle guard ───────────────────────────────────────────────────────

    private void safeRun(Runnable r) {
        if (mDestroyed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mUiHandler.post(() -> { if (!mDestroyed) r.run(); });
        }
    }
}
