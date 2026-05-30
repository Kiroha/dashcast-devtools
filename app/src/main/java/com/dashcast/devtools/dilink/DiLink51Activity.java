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
 * DiLink 5.1 — activité diagnostique.
 * Tab 0 "Recon" : {@link DlReconRunner} (25 sondes App + ADB).
 */
public class DiLink51Activity extends AppCompatActivity {

    private static final String TAG = "DiLink51Activity";
    private static final int TAB_RECON = 0;

    // ── Views ─────────────────────────────────────────────────────────────────
    private View         mPanelRecon;
    private TextView     mTvSubtitle;
    private TextView     mTvPill;
    private TextView     mTvCounters;
    private View         mBtnRun;
    private View         mBtnShare;
    private LinearLayout mLlList;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean mRowsPrepared = false;
    private final List<View>                     mRowViews = new ArrayList<>();
    private final List<DlReconRunner.TestResult> mResults  = new ArrayList<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    private boolean       mDestroyed = false;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dilink51);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mPanelRecon = findViewById(R.id.panel_recon_dl51);

        TabLayout tabs = findViewById(R.id.tabs_dl51);
        tabs.addTab(tabs.newTab().setText(getString(R.string.diag_tab_recon_infra)));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showPanel(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        bindReconViews();
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
        mPanelRecon.setVisibility(pos == TAB_RECON ? View.VISIBLE : View.GONE);
        if (pos == TAB_RECON) { bindHeader(); prepareRowsIfNeeded(); }
    }

    // ── Recon panel ───────────────────────────────────────────────────────────

    private void bindReconViews() {
        mTvSubtitle = findViewById(R.id.tv_dl51_recon_subtitle);
        mTvPill     = findViewById(R.id.tv_dl51_recon_pill);
        mTvCounters = findViewById(R.id.tv_dl51_recon_counters);
        mBtnRun      = findViewById(R.id.btn_dl51_recon_run);
        mBtnShare    = findViewById(R.id.btn_dl51_recon_share);
        mLlList      = findViewById(R.id.ll_dl51_recon_list);
        mBtnRun.setOnClickListener(v -> runTests());
        mBtnShare.setOnClickListener(v -> AppLogger.shareWithReport(this, DlReconRunner.buildReport(mResults)));
        mBtnShare.setEnabled(false);
    }

    private void bindHeader() {
        Platform p = Platform.get();
        String prod = p.rawProductName();
        if (prod == null || prod.isEmpty()) prod = "?";
        mTvSubtitle.setText(getString(R.string.diag_platform_subtitle_fmt, prod, p.androidApi()));
        mTvPill.setText(p.isDiLink5(this)
                ? getString(R.string.diag_dl50_pill_detected)
                : getString(R.string.diag_dl50_pill_unknown));
        mTvCounters.setText(R.string.diag_counters_idle);
    }

    private void prepareRowsIfNeeded() {
        if (mRowsPrepared) return;
        mRowsPrepared = true;
        mLlList.removeAllViews();
        mRowViews.clear();
        mResults.clear();
        LayoutInflater inf = LayoutInflater.from(this);
        for (DlReconRunner.TestDef def : DlReconRunner.catalog()) {
            View row = inf.inflate(R.layout.item_test_row, mLlList, false);
            DlReconRunner.TestResult r = new DlReconRunner.TestResult(def);
            bindRow(row, r);
            mLlList.addView(row);
            mRowViews.add(row);
            mResults.add(r);
        }
    }

    private void bindRow(View row, DlReconRunner.TestResult r) {
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

    private void runTests() {
        mBtnRun.setEnabled(false);
        mBtnShare.setEnabled(false);
        mTvCounters.setText(R.string.diag_counters_running);
        DlReconRunner.runAll(this, new DlReconRunner.Listener() {
            @Override public void onSuiteStarted(List<DlReconRunner.TestResult> results) {
                safeRun(() -> {
                    mResults.clear(); mResults.addAll(results);
                    for (int i = 0; i < results.size() && i < mRowViews.size(); i++)
                        bindRow(mRowViews.get(i), results.get(i));
                });
            }
            @Override public void onTestUpdated(int idx, DlReconRunner.TestResult r) {
                safeRun(() -> {
                    if (idx < mResults.size()) mResults.set(idx, r);
                    if (idx < mRowViews.size()) bindRow(mRowViews.get(idx), r);
                    updateCounters();
                });
            }
            @Override public void onSuiteFinished(List<DlReconRunner.TestResult> results) {
                safeRun(() -> {
                    mBtnRun.setEnabled(true);
                    mBtnShare.setEnabled(true);
                    updateCounters();
                });
            }
        });
    }

    private void updateCounters() {
        int pass = 0, fail = 0, warn = 0, skip = 0;
        for (DlReconRunner.TestResult r : mResults) {
            switch (r.status) {
                case PASS: pass++; break; case FAIL: fail++; break;
                case WARN: warn++; break; case SKIPPED: skip++; break; default: break;
            }
        }
        mTvCounters.setText(warn > 0
                ? getString(R.string.diag_counters_warn_fmt, pass, fail, warn, skip)
                : getString(R.string.diag_counters_fmt, pass, fail, skip));
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
