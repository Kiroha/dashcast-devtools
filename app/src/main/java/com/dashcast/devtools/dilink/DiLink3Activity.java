package com.dashcast.devtools.dilink;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;

import com.dashcast.devtools.R;
import com.dashcast.devtools.common.AppLogger;
import com.dashcast.devtools.common.Platform;
import com.dashcast.devtools.fission.Dl3FissionActivity;
import com.dashcast.devtools.projection.Dl3ProjectionActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * DiLink3Activity — activité diagnostique DiLink 3.
 *
 * Tab 0 "8.8 Screen" : tests de compatibilité écran 8.8" (Dl3ScreenRunner, L01-S15).
 * Tab 1 "Recon"      : reconnaissance complète App+ADB (DlReconRunner, A01-F05, 25 tests).
 *
 * Règle display 0 : aucun test ne modifie le display 0. Tous les appels wm/am
 * sont en lecture seule. Le seul test d'écriture (E01 VirtualDisplay) est réversible.
 */
public class DiLink3Activity extends AppCompatActivity {

    private static final String TAG = "DiLink3Activity";

    private static final int TAB_SCREEN88  = 0;
    private static final int TAB_RECON     = 1;
    private static final int TAB_FISSION   = 2;
    private static final int TAB_PROJECTION = 3;

    // ── Views — Screen88 panel ────────────────────────────────────────────────
    private View         panelScreen88;
    private TextView     tvScreen88Subtitle;
    private TextView     tvScreen88Pill;
    private TextView     tvScreen88Counters;
    private View         btnScreen88RunAll;
    private View         btnScreen88CopyReport;
    private LinearLayout llScreen88TestList;

    // ── Views — Fission panel ──────────────────────────────────────────────────
    private View         panelFission;

    // ── Views — Projection panel ─────────────────────────────────────────────
    private View         panelProjection;

    // ── Views — Recon panel ───────────────────────────────────────────────────
    private View         panelRecon;
    private TextView     tvRecon3Subtitle;
    private TextView     tvRecon3Pill;
    private TextView     tvRecon3Counters;
    private View         btnRecon3RunAll;
    private View         btnRecon3Share;
    private LinearLayout llRecon3TestList;

    // ── State — Screen88 ──────────────────────────────────────────────────────
    private boolean mScreen88RowsPrepared = false;
    private final List<View>                         mScreen88RowViews = new ArrayList<>();
    private final List<Dl3ScreenRunner.TestResult>   mScreen88Results  = new ArrayList<>();

    // ── State — Recon ─────────────────────────────────────────────────────────
    private boolean mReconRowsPrepared = false;
    private final List<View>                         mReconRowViews = new ArrayList<>();
    private final List<DlReconRunner.TestResult>    mReconResults  = new ArrayList<>();

    // ── Common state ──────────────────────────────────────────────────────────
    private boolean mDestroyed = false;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dilink3);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // ── Tab wiring ────────────────────────────────────────────────────────
        TabLayout tabs = findViewById(R.id.tabs_dl3);
        tabs.addTab(tabs.newTab().setText(getString(R.string.diag_dl3_tab_screen88)));
        tabs.addTab(tabs.newTab().setText(getString(R.string.diag_dl3_tab_recon)));
        tabs.addTab(tabs.newTab().setText(getString(R.string.diag_dl3_tab_fission)));
        tabs.addTab(tabs.newTab().setText(getString(R.string.diag_dl3_tab_projection)));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showPanel(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // ── Screen88 views ────────────────────────────────────────────────────
        panelScreen88         = findViewById(R.id.panel_screen88);
        tvScreen88Subtitle    = findViewById(R.id.tv_screen88_subtitle);
        tvScreen88Pill        = findViewById(R.id.tv_screen88_pill);
        tvScreen88Counters    = findViewById(R.id.tv_screen88_counters);
        btnScreen88RunAll     = findViewById(R.id.btn_screen88_run_all);
        btnScreen88CopyReport = findViewById(R.id.btn_screen88_copy_report);
        llScreen88TestList    = findViewById(R.id.ll_screen88_test_list);

        btnScreen88RunAll.setOnClickListener(v -> runScreen88Tests());
        btnScreen88CopyReport.setOnClickListener(v -> copyScreen88Report());
        btnScreen88CopyReport.setEnabled(false);

        // ── Recon views ───────────────────────────────────────────────────────
        panelRecon         = findViewById(R.id.panel_recon);
        tvRecon3Subtitle   = findViewById(R.id.tv_recon3_subtitle);
        tvRecon3Pill       = findViewById(R.id.tv_recon3_pill);
        tvRecon3Counters   = findViewById(R.id.tv_recon3_counters);
        btnRecon3RunAll       = findViewById(R.id.btn_recon3_run_all);
        btnRecon3Share        = findViewById(R.id.btn_recon3_share);
        llRecon3TestList      = findViewById(R.id.ll_recon3_test_list);

        btnRecon3RunAll.setOnClickListener(v -> runReconTests());
        btnRecon3Share.setOnClickListener(v ->
                AppLogger.shareWithReport(this, DlReconRunner.buildReport(mReconResults)));
        btnRecon3Share.setEnabled(false);

        // ── Fission panel ─────────────────────────────────────────────────────
        panelFission = findViewById(R.id.panel_fission_dl3);
        panelFission.findViewById(R.id.btn_dl3_open_fission)
                .setOnClickListener(v -> startActivity(
                        new Intent(this, Dl3FissionActivity.class)));
        // ── Projection panel ───────────────────────────────────────────────
        panelProjection = findViewById(R.id.panel_projection_dl3);
        panelProjection.findViewById(R.id.btn_dl3_open_projection)
                .setOnClickListener(v -> startActivity(
                        new Intent(this, Dl3ProjectionActivity.class)));
        showPanel(TAB_SCREEN88);
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

    // ── Panel switching ───────────────────────────────────────────────────────

    private void showPanel(int pos) {
        panelScreen88.setVisibility(pos == TAB_SCREEN88  ? View.VISIBLE : View.GONE);
        panelRecon.setVisibility(pos == TAB_RECON      ? View.VISIBLE : View.GONE);
        panelFission.setVisibility(pos == TAB_FISSION   ? View.VISIBLE : View.GONE);
        panelProjection.setVisibility(pos == TAB_PROJECTION ? View.VISIBLE : View.GONE);

        if (pos == TAB_SCREEN88) {
            bindScreen88Header();
            prepareScreen88RowsIfNeeded();
        } else {
            bindReconHeader();
            prepareReconRowsIfNeeded();
        }
    }

    // ── Screen88 — Header ─────────────────────────────────────────────────────

    private void bindScreen88Header() {
        Platform p = Platform.get();
        String prod = p.rawProductName();
        if (prod == null || prod.isEmpty()) prod = "?";
        tvScreen88Subtitle.setText(getString(R.string.diag_platform_subtitle_fmt, prod, p.androidApi()));
        tvScreen88Pill.setText(p.isDiLink3(this)
                ? getString(R.string.diag_dl3_pill_detected)
                : getString(R.string.diag_dl3_pill_unknown));
        tvScreen88Counters.setText(R.string.diag_counters_idle);
    }

    // ── Screen88 — Test rows ──────────────────────────────────────────────────

    private void prepareScreen88RowsIfNeeded() {
        if (mScreen88RowsPrepared) return;
        mScreen88RowsPrepared = true;
        mScreen88RowViews.clear();
        mScreen88Results.clear();
        llScreen88TestList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Dl3ScreenRunner.TestDef def : Dl3ScreenRunner.catalog()) {
            View row = inflater.inflate(R.layout.item_test_row, llScreen88TestList, false);
            Dl3ScreenRunner.TestResult r = new Dl3ScreenRunner.TestResult(def);
            bindScreen88Row(row, r);
            llScreen88TestList.addView(row);
            mScreen88RowViews.add(row);
            mScreen88Results.add(r);
        }
    }

    private void bindScreen88Row(View row, Dl3ScreenRunner.TestResult r) {
        ((TextView) row.findViewById(R.id.tv_test_id)).setText(r.def.id);
        ((TextView) row.findViewById(R.id.tv_test_title)).setText(r.def.title);
        ((TextView) row.findViewById(R.id.tv_test_description)).setText(r.def.description);
        applyStatus(row,
                r.status.name(), r.message, r.elapsedMs,
                r.status == Dl3ScreenRunner.Status.PASS  ? 0xFF4CAF50 :
                r.status == Dl3ScreenRunner.Status.FAIL  ? 0xFFE53935 :
                r.status == Dl3ScreenRunner.Status.WARN  ? 0xFFFFB300 :
                r.status == Dl3ScreenRunner.Status.RUNNING ? 0xFFFFB300 :
                r.status == Dl3ScreenRunner.Status.SKIPPED ? 0xFF9E9E9E : 0xFF607D8B,
                r.status == Dl3ScreenRunner.Status.PASS  ? "✓" :
                r.status == Dl3ScreenRunner.Status.FAIL  ? "✗" :
                r.status == Dl3ScreenRunner.Status.WARN  ? "!" :
                r.status == Dl3ScreenRunner.Status.RUNNING ? "…" :
                r.status == Dl3ScreenRunner.Status.SKIPPED ? "⊘" : "·");
    }

    // ── Screen88 — Run ────────────────────────────────────────────────────────

    private void runScreen88Tests() {
        btnScreen88RunAll.setEnabled(false);
        btnScreen88CopyReport.setEnabled(false);
        tvScreen88Counters.setText(R.string.diag_counters_running);
        Dl3ScreenRunner.runAll(this, new Dl3ScreenRunner.Listener() {
            @Override public void onSuiteStarted(List<Dl3ScreenRunner.TestResult> results) {
                safeRun(() -> {
                    mScreen88Results.clear(); mScreen88Results.addAll(results);
                    for (int i = 0; i < results.size() && i < mScreen88RowViews.size(); i++)
                        bindScreen88Row(mScreen88RowViews.get(i), results.get(i));
                });
            }
            @Override public void onTestUpdated(int index, Dl3ScreenRunner.TestResult result) {
                safeRun(() -> {
                    if (index < mScreen88Results.size()) mScreen88Results.set(index, result);
                    if (index < mScreen88RowViews.size()) bindScreen88Row(mScreen88RowViews.get(index), result);
                    updateScreen88Counters();
                });
            }
            @Override public void onSuiteFinished(List<Dl3ScreenRunner.TestResult> results) {
                safeRun(() -> {
                    btnScreen88RunAll.setEnabled(true);
                    btnScreen88CopyReport.setEnabled(true);
                    updateScreen88Counters();
                });
            }
        });
    }

    private void updateScreen88Counters() {
        int pass = 0, fail = 0, warn = 0, skip = 0;
        for (Dl3ScreenRunner.TestResult r : mScreen88Results) {
            switch (r.status) {
                case PASS: pass++; break; case FAIL: fail++; break;
                case WARN: warn++; break; case SKIPPED: skip++; break;
                default: break;
            }
        }
        tvScreen88Counters.setText(warn > 0
                ? getString(R.string.diag_counters_warn_fmt, pass, fail, warn, skip)
                : getString(R.string.diag_counters_fmt, pass, fail, skip));
    }

    private void copyScreen88Report() {
        if (mScreen88Results.isEmpty()) {
            Toast.makeText(this, R.string.diag_toast_no_results, Toast.LENGTH_SHORT).show(); return;
        }
        AppLogger.shareWithReport(this, Dl3ScreenRunner.buildReport(mScreen88Results));
    }

    // ── Recon — Header ────────────────────────────────────────────────────────

    private void bindReconHeader() {
        Platform p = Platform.get();
        String prod = p.rawProductName();
        if (prod == null || prod.isEmpty()) prod = "?";
        tvRecon3Subtitle.setText(getString(R.string.diag_platform_subtitle_fmt, prod, p.androidApi()));
        tvRecon3Pill.setText(p.isDiLink3(this)
                ? getString(R.string.diag_dl3_pill_detected)
                : getString(R.string.diag_dl3_pill_unknown));
        tvRecon3Counters.setText(R.string.diag_counters_idle);
    }

    // ── Recon — Test rows ─────────────────────────────────────────────────────

    private void prepareReconRowsIfNeeded() {
        if (mReconRowsPrepared) return;
        mReconRowsPrepared = true;
        mReconRowViews.clear();
        mReconResults.clear();
        llRecon3TestList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (DlReconRunner.TestDef def : DlReconRunner.catalog()) {
            View row = inflater.inflate(R.layout.item_test_row, llRecon3TestList, false);
            DlReconRunner.TestResult r = new DlReconRunner.TestResult(def);
            bindReconRow(row, r);
            llRecon3TestList.addView(row);
            mReconRowViews.add(row);
            mReconResults.add(r);
        }
    }

    private void bindReconRow(View row, DlReconRunner.TestResult r) {
        ((TextView) row.findViewById(R.id.tv_test_id)).setText(r.def.id);
        ((TextView) row.findViewById(R.id.tv_test_title)).setText(r.def.title);
        ((TextView) row.findViewById(R.id.tv_test_description)).setText(r.def.description);
        applyStatus(row,
                r.status.name(), r.message, r.elapsedMs,
                r.status == DlReconRunner.Status.PASS    ? 0xFF4CAF50 :
                r.status == DlReconRunner.Status.FAIL    ? 0xFFE53935 :
                r.status == DlReconRunner.Status.WARN    ? 0xFFFFB300 :
                r.status == DlReconRunner.Status.RUNNING ? 0xFFFFB300 :
                r.status == DlReconRunner.Status.SKIPPED ? 0xFF9E9E9E : 0xFF607D8B,
                r.status == DlReconRunner.Status.PASS    ? "✓" :
                r.status == DlReconRunner.Status.FAIL    ? "✗" :
                r.status == DlReconRunner.Status.WARN    ? "!" :
                r.status == DlReconRunner.Status.RUNNING ? "…" :
                r.status == DlReconRunner.Status.SKIPPED ? "⊘" : "·");
    }

    // ── Recon — Run ───────────────────────────────────────────────────────────

    private void runReconTests() {
        btnRecon3RunAll.setEnabled(false);
        btnRecon3Share.setEnabled(false);
        tvRecon3Counters.setText(R.string.diag_counters_running);
        DlReconRunner.runAll(this, new DlReconRunner.Listener() {
            @Override public void onSuiteStarted(List<DlReconRunner.TestResult> results) {
                safeRun(() -> {
                    mReconResults.clear(); mReconResults.addAll(results);
                    for (int i = 0; i < results.size() && i < mReconRowViews.size(); i++)
                        bindReconRow(mReconRowViews.get(i), results.get(i));
                });
            }
            @Override public void onTestUpdated(int index, DlReconRunner.TestResult result) {
                safeRun(() -> {
                    if (index < mReconResults.size()) mReconResults.set(index, result);
                    if (index < mReconRowViews.size()) bindReconRow(mReconRowViews.get(index), result);
                    updateReconCounters();
                });
            }
            @Override public void onSuiteFinished(List<DlReconRunner.TestResult> results) {
                safeRun(() -> {
                    btnRecon3RunAll.setEnabled(true);
                    btnRecon3Share.setEnabled(true);
                    updateReconCounters();
                });
            }
        });
    }

    private void updateReconCounters() {
        int pass = 0, fail = 0, warn = 0, skip = 0;
        for (DlReconRunner.TestResult r : mReconResults) {
            switch (r.status) {
                case PASS: pass++; break; case FAIL: fail++; break;
                case WARN: warn++; break; case SKIPPED: skip++; break;
                default: break;
            }
        }
        tvRecon3Counters.setText(warn > 0
                ? getString(R.string.diag_counters_warn_fmt, pass, fail, warn, skip)
                : getString(R.string.diag_counters_fmt, pass, fail, skip));
    }

    // ── Shared UI helper ──────────────────────────────────────────────────────

    /** Applique le statut visuel (glyph + couleur + message) sur une ligne item_test_row. */
    private static void applyStatus(View row, String statusName, String message,
                                    long elapsedMs, int color, String glyph) {
        TextView statusView = row.findViewById(R.id.tv_test_status);
        TextView msgView    = row.findViewById(R.id.tv_test_message);
        TextView elapView   = row.findViewById(R.id.tv_test_elapsed);

        statusView.setText(glyph);
        statusView.setTextColor(color);
        statusView.setBackgroundResource(DlPillBg.forStatus(statusName));
        elapView.setText(elapsedMs > 0 ? (elapsedMs + " ms") : "");

        if (message != null && !message.isEmpty()) {
            msgView.setVisibility(View.VISIBLE);
            msgView.setText(message);
            int tc;
            switch (statusName) {
                case "FAIL": tc = 0xFFE53935; break;
                case "PASS": tc = 0xFF4CAF50; break;
                case "WARN": tc = 0xFFFFB300; break;
                default:     tc = 0xFF9E9E9E; break;
            }
            msgView.setTextColor(tc);
        } else {
            msgView.setVisibility(View.GONE);
        }
    }

    // ── Safe UI dispatch ──────────────────────────────────────────────────────

    private void safeRun(Runnable r) {
        if (mDestroyed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mUiHandler.post(() -> { if (!mDestroyed) r.run(); });
        }
    }
}
