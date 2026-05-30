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

import java.util.ArrayList;
import java.util.List;

/**
 * DiLink 4 — activité diagnostique complète.
 * Lance DiLink4TestRunner (L1–L15 recon + S1–S15 shell + D1–D10 destruktif).
 */
public class DiLink4Activity extends AppCompatActivity {

    private static final String TAG = "DiLink4Activity";

    private TextView     tvSubtitle;
    private TextView     tvPill;
    private TextView     tvCounters;
    private View         btnRunAll;
    private View         btnCopyReport;
    private LinearLayout llTestList;

    private boolean mDestroyed = false;
    private boolean mRowsPrepared = false;
    private final List<View> mRowViews = new ArrayList<>();
    private final List<DiLink4TestRunner.TestResult> mLastResults = new ArrayList<>();
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dilink4);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        tvSubtitle    = findViewById(R.id.tv_dl4_header_subtitle);
        tvPill        = findViewById(R.id.tv_dl4_signature_pill);
        tvCounters    = findViewById(R.id.tv_dl4_counters);
        btnRunAll     = findViewById(R.id.btn_dl4_run_all);
        btnCopyReport = findViewById(R.id.btn_dl4_copy_report);
        llTestList    = findViewById(R.id.ll_dl4_test_list);

        bindHeader();
        btnRunAll.setOnClickListener(v -> runAllTests());
        btnCopyReport.setOnClickListener(v -> copyReport());
        btnCopyReport.setEnabled(false);
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

    private void bindHeader() {
        Platform p = Platform.get();
        String prod = p.rawProductName();
        if (prod == null || prod.isEmpty()) prod = "?";
        tvSubtitle.setText(getString(R.string.diag_platform_subtitle_fmt, prod, p.androidApi()));
        tvPill.setText(p.isDiLink4(this) ? getString(R.string.diag_dl4_pill_detected)
                                         : getString(R.string.diag_dl4_pill_unknown));
        tvCounters.setText(R.string.diag_counters_idle);
    }

    private void prepareTestRowsIfNeeded() {
        if (mRowsPrepared) return;
        mRowsPrepared = true;
        mRowViews.clear();
        mLastResults.clear();
        llTestList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (DiLink4TestRunner.TestDef def : DiLink4TestRunner.catalog()) {
            View row = inflater.inflate(R.layout.item_test_row, llTestList, false);
            DiLink4TestRunner.TestResult r = new DiLink4TestRunner.TestResult(def);
            r.status = DiLink4TestRunner.Status.PENDING;
            bindRow(row, r);
            llTestList.addView(row);
            mRowViews.add(row);
            mLastResults.add(r);
        }
    }

    private void bindRow(View row, DiLink4TestRunner.TestResult r) {
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

    private void runAllTests() {
        btnRunAll.setEnabled(false);
        btnCopyReport.setEnabled(false);
        tvCounters.setText(R.string.diag_counters_running);
        DiLink4TestRunner.runAll(this, new DiLink4TestRunner.Listener() {
            @Override public void onSuiteStarted(List<DiLink4TestRunner.TestResult> results) {
                safeRun(() -> {
                    mLastResults.clear();
                    mLastResults.addAll(results);
                    for (int i = 0; i < results.size() && i < mRowViews.size(); i++) {
                        bindRow(mRowViews.get(i), results.get(i));
                    }
                });
            }
            @Override public void onTestUpdated(int index, DiLink4TestRunner.TestResult result) {
                safeRun(() -> {
                    if (index < mLastResults.size()) mLastResults.set(index, result);
                    if (index < mRowViews.size()) bindRow(mRowViews.get(index), result);
                    updateCounters();
                });
            }
            @Override public void onSuiteFinished(List<DiLink4TestRunner.TestResult> results) {
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
        for (DiLink4TestRunner.TestResult r : mLastResults) {
            if      (r.status == DiLink4TestRunner.Status.PASS)    pass++;
            else if (r.status == DiLink4TestRunner.Status.FAIL)    fail++;
            else if (r.status == DiLink4TestRunner.Status.SKIPPED) skip++;
        }
        tvCounters.setText(getString(R.string.diag_counters_fmt, pass, fail, skip));
    }

    private void copyReport() {
        if (mLastResults.isEmpty()) {
            Toast.makeText(this, R.string.diag_toast_no_results, Toast.LENGTH_SHORT).show();
            return;
        }
        String report = DiLink4TestRunner.buildReport(this, mLastResults);
        AppLogger.i(TAG, "DiLink 4 report:\n" + report);
        AppLogger.shareWithReport(this, report);
    }

    private void safeRun(Runnable r) {
        if (mDestroyed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mUiHandler.post(() -> { if (!mDestroyed) r.run(); });
        }
    }
}
