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

import com.dashcast.devtools.R;
import com.dashcast.devtools.common.AppLogger;
import com.dashcast.devtools.common.Platform;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * DiLink 2 — activité diagnostique complète.
 * Lance DiLink2TestRunner (L1–L15 recon + S1–S25 shell).
 */
public class DiLink2Activity extends AppCompatActivity {

    private static final String TAG = "DiLink2Activity";

    private static final int TAB_TESTS = 0;
    private static final int TAB_RECON  = 1;

    private View mPanelTests;

    // ── Views ────────────────────────────────────────────────────────────────
    private TextView   tvSubtitle;
    private TextView   tvPill;
    private TextView   tvCounters;
    private View       btnRunAll;
    private View       btnCopyReport;
    private LinearLayout llTestList;


    // ── Recon panel — views ───────────────────────────────────────────────────
    private View         mReconPanel_dl2;
    private TextView     mReconSubtitle_dl2;
    private TextView     mReconPill_dl2;
    private TextView     mReconCounters_dl2;
    private View         mBtnReconRun_dl2;
    private View         mBtnReconCopy_dl2;
    private LinearLayout mReconList_dl2;

    // ── Recon panel — state ───────────────────────────────────────────────────
    private boolean mReconRowsPrepared_dl2 = false;
    private final List<View>                   mReconRowViews_dl2 = new ArrayList<>();
    private final List<DlReconRunner.TestResult> mReconResults_dl2 = new ArrayList<>();

    private void bindReconViews_dl2() {
        mReconSubtitle_dl2   = findViewById(R.id.tv_dl2_recon_subtitle);
        mReconPill_dl2       = findViewById(R.id.tv_dl2_recon_pill);
        mReconCounters_dl2   = findViewById(R.id.tv_dl2_recon_counters);
        mBtnReconRun_dl2     = findViewById(R.id.btn_dl2_recon_run);
        mBtnReconCopy_dl2    = findViewById(R.id.btn_dl2_recon_copy);
        mReconList_dl2       = findViewById(R.id.ll_dl2_recon_list);
        mBtnReconRun_dl2.setOnClickListener(v -> runRecon_dl2());
        mBtnReconCopy_dl2.setOnClickListener(v -> copyRecon_dl2());
        mBtnReconCopy_dl2.setEnabled(false);
    }

    private void showReconPanel_dl2(boolean show) {
        mReconPanel_dl2.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { bindReconHeader_dl2(); prepareReconRows_dl2(); }
    }

    private void bindReconHeader_dl2() {
        Platform p2 = Platform.get();
        String prod = p2.rawProductName();
        if (prod == null || prod.isEmpty()) prod = "?";
        mReconSubtitle_dl2.setText(getString(R.string.diag_platform_subtitle_fmt, prod, p2.androidApi()));
        mReconPill_dl2.setText(getString(R.string.diag_dl2_pill_detected));
        mReconCounters_dl2.setText(R.string.diag_counters_idle);
    }

    private void prepareReconRows_dl2() {
        if (mReconRowsPrepared_dl2) return;
        mReconRowsPrepared_dl2 = true;
        mReconList_dl2.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (DlReconRunner.TestDef def : DlReconRunner.catalog()) {
            View row = inf.inflate(R.layout.item_test_row, mReconList_dl2, false);
            DlReconRunner.TestResult r = new DlReconRunner.TestResult(def);
            bindReconRow_dl2(row, r);
            mReconList_dl2.addView(row);
            mReconRowViews_dl2.add(row);
            mReconResults_dl2.add(r);
        }
    }

    private void bindReconRow_dl2(View row, DlReconRunner.TestResult r) {
        ((TextView) row.findViewById(R.id.tv_test_id)).setText(r.def.id);
        ((TextView) row.findViewById(R.id.tv_test_title)).setText(r.def.title);
        ((TextView) row.findViewById(R.id.tv_test_description)).setText(r.def.description);
        TextView sv = row.findViewById(R.id.tv_test_status);
        TextView mv = row.findViewById(R.id.tv_test_message);
        TextView ev = row.findViewById(R.id.tv_test_elapsed);
        String glyph; int color;
        switch (r.status) {
            case PASS:    glyph="✓"; color=0xFF4CAF50; break;
            case FAIL:    glyph="✗"; color=0xFFE53935; break;
            case WARN:    glyph="!"; color=0xFFFFB300; break;
            case SKIPPED: glyph="⊘"; color=0xFF9E9E9E; break;
            case RUNNING: glyph="…"; color=0xFFFFB300; break;
            default:      glyph="·"; color=0xFF607D8B; break;
        }
        sv.setText(glyph); sv.setTextColor(color);
        ev.setText(r.elapsedMs > 0 ? r.elapsedMs + " ms" : "");
        if (r.message != null && !r.message.isEmpty()) {
            mv.setVisibility(View.VISIBLE); mv.setText(r.message);
            int tc; switch(r.status) {
                case FAIL: tc=0xFFE53935; break; case PASS: tc=0xFF4CAF50; break;
                case WARN: tc=0xFFFFB300; break; default: tc=0xFF9E9E9E; break;
            } mv.setTextColor(tc);
        } else { mv.setVisibility(View.GONE); }
    }

    private void runRecon_dl2() {
        mBtnReconRun_dl2.setEnabled(false);
        mBtnReconCopy_dl2.setEnabled(false);
        mReconCounters_dl2.setText(R.string.diag_counters_running);
        DlReconRunner.runAll(this, new DlReconRunner.Listener() {
            @Override public void onSuiteStarted(List<DlReconRunner.TestResult> results) {
                safeRun(() -> { mReconResults_dl2.clear(); mReconResults_dl2.addAll(results);
                    for (int i=0; i<results.size()&&i<mReconRowViews_dl2.size(); i++)
                        bindReconRow_dl2(mReconRowViews_dl2.get(i), results.get(i)); });
            }
            @Override public void onTestUpdated(int idx, DlReconRunner.TestResult r) {
                safeRun(() -> {
                    if (idx<mReconResults_dl2.size()) mReconResults_dl2.set(idx, r);
                    if (idx<mReconRowViews_dl2.size()) bindReconRow_dl2(mReconRowViews_dl2.get(idx), r);
                    updateReconCounters_dl2();
                });
            }
            @Override public void onSuiteFinished(List<DlReconRunner.TestResult> results) {
                safeRun(() -> { mBtnReconRun_dl2.setEnabled(true); mBtnReconCopy_dl2.setEnabled(true); updateReconCounters_dl2(); });
            }
        });
    }

    private void updateReconCounters_dl2() {
        int pass=0, fail=0, warn=0, skip=0;
        for (DlReconRunner.TestResult r : mReconResults_dl2) {
            switch(r.status) { case PASS:pass++; break; case FAIL:fail++; break;
                case WARN:warn++; break; case SKIPPED:skip++; break; default: break; }
        }
        mReconCounters_dl2.setText(warn>0
            ? getString(R.string.diag_counters_warn_fmt, pass, fail, warn, skip)
            : getString(R.string.diag_counters_fmt, pass, fail, skip));
    }

    private void copyRecon_dl2() {
        if (mReconResults_dl2.isEmpty()) {
            Toast.makeText(this, R.string.diag_toast_no_results, Toast.LENGTH_SHORT).show(); return;
        }
        AppLogger.shareWithReport(this, DlReconRunner.buildReport(mReconResults_dl2));
    }

    // ── State ────────────────────────────────────────────────────────────────
    private boolean mDestroyed = false;
    private boolean mRowsPrepared = false;
    private final List<View> mRowViews = new ArrayList<>();
    private final List<DiLink2TestRunner.TestResult> mLastResults = new ArrayList<>();
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dilink2);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mPanelTests = findViewById(R.id.panel_tests);
        mReconPanel_dl2 = findViewById(R.id.panel_recon_dl2);
        bindReconViews_dl2();

        TabLayout tabs = findViewById(R.id.tabs_dl2);
        tabs.addTab(tabs.newTab().setText(getString(R.string.diag_tab_tests)));
        tabs.addTab(tabs.newTab().setText(getString(R.string.diag_tab_recon_infra)));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                boolean tests = tab.getPosition() == TAB_TESTS;
                mPanelTests.setVisibility(tests ? View.VISIBLE : View.GONE);
                showReconPanel_dl2(!tests);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        tvSubtitle    = findViewById(R.id.tv_dl2_header_subtitle);
        tvPill        = findViewById(R.id.tv_dl2_signature_pill);
        tvCounters    = findViewById(R.id.tv_dl2_counters);
        btnRunAll     = findViewById(R.id.btn_dl2_run_all);
        btnCopyReport = findViewById(R.id.btn_dl2_copy_report);
        llTestList    = findViewById(R.id.ll_dl2_test_list);

        bindHeader();
        btnRunAll.setOnClickListener(v -> runAllTests());
        btnCopyReport.setOnClickListener(v -> copyReport());
        btnCopyReport.setEnabled(false);
        mPanelTests.setVisibility(View.VISIBLE);
        prepareTestRowsIfNeeded();
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

    // ── Header ────────────────────────────────────────────────────────────────

    private void bindHeader() {
        Platform p = Platform.get();
        String prod = p.rawProductName();
        if (prod == null || prod.isEmpty()) prod = "?";
        tvSubtitle.setText(getString(R.string.diag_platform_subtitle_fmt, prod, p.androidApi()));
        tvPill.setText(p.isDiLink2(this) ? getString(R.string.diag_dl2_pill_detected)
                                         : getString(R.string.diag_dl2_pill_unknown));
        tvCounters.setText(R.string.diag_counters_idle);
    }

    // ── Test rows ─────────────────────────────────────────────────────────────

    private void prepareTestRowsIfNeeded() {
        if (mRowsPrepared) return;
        mRowsPrepared = true;
        mRowViews.clear();
        mLastResults.clear();
        llTestList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (DiLink2TestRunner.TestDef def : DiLink2TestRunner.catalog()) {
            View row = inflater.inflate(R.layout.item_test_row, llTestList, false);
            DiLink2TestRunner.TestResult r = new DiLink2TestRunner.TestResult(def);
            r.status = DiLink2TestRunner.Status.PENDING;
            bindRow(row, r);
            llTestList.addView(row);
            mRowViews.add(row);
            mLastResults.add(r);
        }
    }

    private void bindRow(View row, DiLink2TestRunner.TestResult r) {
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

    // ── Run All ───────────────────────────────────────────────────────────────

    private void runAllTests() {
        btnRunAll.setEnabled(false);
        btnCopyReport.setEnabled(false);
        tvCounters.setText(R.string.diag_counters_running);
        DiLink2TestRunner.runAll(this, new DiLink2TestRunner.Listener() {
            @Override public void onSuiteStarted(List<DiLink2TestRunner.TestResult> results) {
                safeRun(() -> {
                    mLastResults.clear();
                    mLastResults.addAll(results);
                    for (int i = 0; i < results.size() && i < mRowViews.size(); i++) {
                        bindRow(mRowViews.get(i), results.get(i));
                    }
                });
            }
            @Override public void onTestUpdated(int index, DiLink2TestRunner.TestResult result) {
                safeRun(() -> {
                    if (index < mLastResults.size()) mLastResults.set(index, result);
                    if (index < mRowViews.size()) bindRow(mRowViews.get(index), result);
                    updateCounters();
                });
            }
            @Override public void onSuiteFinished(List<DiLink2TestRunner.TestResult> results) {
                safeRun(() -> {
                    btnRunAll.setEnabled(true);
                    btnCopyReport.setEnabled(true);
                    updateCounters();
                });
            }
        });
    }

    private void updateCounters() {
        int pass = 0, fail = 0, skip = 0;
        for (DiLink2TestRunner.TestResult r : mLastResults) {
            if      (r.status == DiLink2TestRunner.Status.PASS)    pass++;
            else if (r.status == DiLink2TestRunner.Status.FAIL)    fail++;
            else if (r.status == DiLink2TestRunner.Status.SKIPPED) skip++;
        }
        tvCounters.setText(getString(R.string.diag_counters_fmt, pass, fail, skip));
    }

    // ── Copy report ───────────────────────────────────────────────────────────

    private void copyReport() {
        if (mLastResults.isEmpty()) {
            Toast.makeText(this, R.string.diag_toast_no_results, Toast.LENGTH_SHORT).show();
            return;
        }
        String report = DiLink2TestRunner.buildReport(this, mLastResults);
        AppLogger.i(TAG, "DiLink 2 report:\n" + report);
        AppLogger.shareWithReport(this, report);
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
