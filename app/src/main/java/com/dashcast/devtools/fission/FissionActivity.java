package com.dashcast.devtools.fission;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.dashcast.devtools.R;
import com.dashcast.devtools.common.AppLogger;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FissionActivity — DL3 Fission VirtualDisplay pipeline test (F01..F09).
 *
 * <p>Layout:
 * <ul>
 *   <li>MaterialToolbar + back arrow (setNavigationOnClickListener → finish)</li>
 *   <li>NestedScrollView with header card + test rows (one per F01..F09)</li>
 *   <li>SurfaceView 96 dp (always visible) — its Surface is passed to {@link Dl3FissionRunner} F07</li>
 *   <li>Button "Lancer DL3 Fission" — enabled only after {@code surfaceCreated}</li>
 * </ul>
 */
public class FissionActivity extends Activity {

    private static final String TAG = "FissionActivity";

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView     tvCounters;
    private LinearLayout llTestList;
    private SurfaceView  svCluster;
    private View         btnRun;
    private View         btnShare;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean mDestroyed    = false;
    private boolean mSurfaceReady = false;
    private SurfaceHolder mHolder;

    private final List<View>                        mRowViews = new ArrayList<>();
    private final List<Dl3FissionRunner.TestResult> mResults  = new ArrayList<>();
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fission);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvCounters = findViewById(R.id.tv_fission_counters);
        llTestList = findViewById(R.id.ll_fission_test_list);
        svCluster  = findViewById(R.id.sv_fission_cluster);
        btnRun     = findViewById(R.id.btn_fission_run);
        btnShare   = findViewById(R.id.btn_fission_share);

        tvCounters.setText(R.string.diag_counters_idle);
        btnRun.setEnabled(false);    // enabled in surfaceCreated
        btnShare.setEnabled(false);

        // SurfaceView — fixed 1920×720 buffer so the daemon can project at full resolution
        mHolder = svCluster.getHolder();
        mHolder.setFixedSize(1920, 720);
        mHolder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                mSurfaceReady = true;
                btnRun.setEnabled(true);
                AppLogger.d(TAG, "surfaceCreated");
            }
            @Override public void surfaceChanged(SurfaceHolder h, int fmt, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                mSurfaceReady = false;
                btnRun.setEnabled(false);
                AppLogger.d(TAG, "surfaceDestroyed");
            }
        });

        btnRun.setOnClickListener(v -> pickAppThenRun());
        btnShare.setOnClickListener(v -> shareReport());

        prepareRows();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
        mUiHandler.removeCallbacksAndMessages(null);
    }

    // ── Row preparation ───────────────────────────────────────────────────────

    private void prepareRows() {
        mRowViews.clear();
        mResults.clear();
        llTestList.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (Dl3FissionRunner.TestDef def : Dl3FissionRunner.catalog()) {
            View row = inf.inflate(R.layout.item_test_row, llTestList, false);
            Dl3FissionRunner.TestResult r = new Dl3FissionRunner.TestResult(def);
            bindRow(row, r);
            llTestList.addView(row);
            mRowViews.add(row);
            mResults.add(r);
        }
    }

    // ── App picker + Run ──────────────────────────────────────────────

    /** Shows an AlertDialog listing all installed launcher apps, then starts the test suite. */
    private void pickAppThenRun() {
        if (!mSurfaceReady) return;
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
                    if (which >= 0 && which < pkgs.length) startRun(pkgs[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startRun(String targetPkg) {
        if (!mSurfaceReady) return;
        prepareRows();
        btnRun.setEnabled(false);
        btnShare.setEnabled(false);
        tvCounters.setText(R.string.diag_counters_running);

        // Pass 1920×720 as view dimensions so the daemon fills the SurfaceView buffer completely.
        Dl3FissionRunner.run(this,
                targetPkg,
                mHolder.getSurface(),
                /*mirrorViewW=*/ 1920, /*mirrorViewH=*/ 720,
                new Dl3FissionRunner.Listener() {

                    @Override
                    public void onSuiteStarted(List<Dl3FissionRunner.TestResult> results) {
                        safeRun(() -> {
                            mResults.clear();
                            mResults.addAll(results);
                            refreshAllRows();
                        });
                    }

                    @Override
                    public void onTestUpdated(int index, Dl3FissionRunner.TestResult result) {
                        safeRun(() -> {
                            if (index < mResults.size()) mResults.set(index, result);
                            if (index < mRowViews.size()) bindRow(mRowViews.get(index), result);
                            updateCounters();
                        });
                    }

                    @Override
                    public void onSuiteFinished(List<Dl3FissionRunner.TestResult> results) {
                        safeRun(() -> {
                            btnRun.setEnabled(mSurfaceReady);
                            btnShare.setEnabled(!mResults.isEmpty());
                            updateCounters();
                        });
                    }

                    @Override
                    public void onPromptYesNo(String title, String message,
                                             java.util.function.Consumer<Boolean> callback) {
                        if (mDestroyed) { callback.accept(false); return; }
                        // Post to UI thread; callback is invoked here (not from synchronized block)
                        mUiHandler.post(() -> {
                            if (mDestroyed) { callback.accept(false); return; }
                            new AlertDialog.Builder(FissionActivity.this)
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

    // ── Row binding ───────────────────────────────────────────────────────────

    private void refreshAllRows() {
        for (int i = 0; i < mResults.size() && i < mRowViews.size(); i++) {
            bindRow(mRowViews.get(i), mResults.get(i));
        }
    }

    private void bindRow(View row, Dl3FissionRunner.TestResult r) {
        ((TextView) row.findViewById(R.id.tv_test_id)).setText(r.def.id);
        ((TextView) row.findViewById(R.id.tv_test_title)).setText(r.def.title);
        ((TextView) row.findViewById(R.id.tv_test_description)).setText(r.def.description);

        TextView sv = row.findViewById(R.id.tv_test_status);
        TextView mv = row.findViewById(R.id.tv_test_message);
        TextView ev = row.findViewById(R.id.tv_test_elapsed);

        String glyph; int color; int pillRes;
        switch (r.status) {
            case PASS:
                glyph = "✓"; color = 0xFF4CAF50; pillRes = R.drawable.bg_test_pill_pass; break;
            case FAIL:
                glyph = "✗"; color = 0xFFE53935; pillRes = R.drawable.bg_test_pill_fail; break;
            case SKIPPED:
                glyph = "⊘"; color = 0xFF9E9E9E; pillRes = R.drawable.bg_test_pill_skip; break;
            case RUNNING:
                glyph = "…"; color = 0xFFFFB300; pillRes = R.drawable.bg_test_pill_warn; break;
            default:
                glyph = "·"; color = 0xFF607D8B; pillRes = R.drawable.bg_test_pill;      break;
        }
        sv.setText(glyph);
        sv.setTextColor(color);
        sv.setBackgroundResource(pillRes);
        ev.setText(r.elapsedMs > 0 ? (r.elapsedMs + " ms") : "");

        if (r.message != null && !r.message.isEmpty()) {
            mv.setVisibility(View.VISIBLE);
            mv.setText(r.message);
            int tc;
            switch (r.status) {
                case FAIL:    tc = 0xFFE53935; break;
                case PASS:    tc = 0xFF4CAF50; break;
                default:      tc = 0xFF9E9E9E; break;
            }
            mv.setTextColor(tc);
        } else {
            mv.setVisibility(View.GONE);
        }
    }

    // ── Counters ──────────────────────────────────────────────────────────────

    private void updateCounters() {
        int pass = 0, fail = 0, skip = 0;
        for (Dl3FissionRunner.TestResult r : mResults) {
            switch (r.status) {
                case PASS:    pass++; break;
                case FAIL:    fail++; break;
                case SKIPPED: skip++; break;
                default:      break;
            }
        }
        tvCounters.setText(getString(R.string.diag_counters_fmt, pass, fail, skip));
    }

    // ── Share ─────────────────────────────────────────────────────────────────

    private void shareReport() {
        if (mResults.isEmpty()) return;
        AppLogger.shareWithReport(this, Dl3FissionRunner.renderReport(mResults));
    }

    // ── Safe UI ───────────────────────────────────────────────────────────────

    private void safeRun(Runnable r) {
        if (mDestroyed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mUiHandler.post(() -> { if (!mDestroyed) r.run(); });
        }
    }
}
