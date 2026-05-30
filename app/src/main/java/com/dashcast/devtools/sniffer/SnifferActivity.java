package com.dashcast.devtools.sniffer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.dashcast.devtools.R;
import com.dashcast.devtools.common.AdbClient;
import com.dashcast.devtools.common.AppLogger;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * SnifferActivity — continuous logcat + dumpsys snapshots into a single
 * BYD_RE_Sniffer_*.txt file in this app's external files dir.
 *
 * <p>Background processes use {@code setsid} so they survive Activity
 * destruction (rotation, app paused, even Activity finished). State is
 * restored from SharedPreferences on each onCreate.
 *
 * <p>Lifted from DashCast's DiagActivity sniffer section.
 */
public class SnifferActivity extends Activity {

    private static final String TAG = "Sniffer";

    private static final String RE_SNIFFER_TAG    = ".re_sniffer_run";
    private static final String RE_SNIFFER_PIDS   = ".re_sniffer_pids";
    private static final String RE_SNIFFER_PREFIX = "BYD_RE_Sniffer_";
    private static final String PREF_SNIFFER      = "sniffer_prefs";
    private static final String PREF_SNIFFER_PATH = "re_sniffer_file_path";

    private TextView tvStatusPill;
    private TextView tvStatus;
    private Button   btnStart;
    private Button   btnStop;
    private Button   btnSnapshot;
    private Button   btnExport;
    private Button   btnCleanup;

    private volatile File mSnifferFile;
    private volatile boolean mDestroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sniffer);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvStatusPill = findViewById(R.id.tv_sniffer_status_pill);
        tvStatus     = findViewById(R.id.tv_sniffer_status);
        btnStart     = findViewById(R.id.btn_sniffer_start);
        btnStop      = findViewById(R.id.btn_sniffer_stop);
        btnSnapshot  = findViewById(R.id.btn_sniffer_snapshot);
        btnExport    = findViewById(R.id.btn_sniffer_export);
        btnCleanup   = findViewById(R.id.btn_sniffer_cleanup);

        btnStart.setOnClickListener(v -> startSniffer());
        btnStop.setOnClickListener(v -> stopSniffer());
        btnSnapshot.setOnClickListener(v -> snapshotSniffer());
        btnExport.setOnClickListener(v -> exportSniffer());
        btnCleanup.setOnClickListener(v -> cleanupSnifferFiles());

        restoreSnifferState();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        super.onDestroy();
    }

    private void safeRunOnUi(Runnable r) {
        if (mDestroyed) return;
        runOnUiThread(() -> { if (!mDestroyed) r.run(); });
    }

    // ─── UI state ───────────────────────────────────────────────────────────

    private void setUiActive(boolean active, String detail) {
        if (mDestroyed) return;
        tvStatusPill.setText(active ? R.string.sniffer_pill_active : R.string.sniffer_pill_inactive);
        tvStatusPill.setTextColor(active ? 0xFF69F0AE : 0xFFFF8A80);
        if (detail != null) tvStatus.setText(detail);
        btnStart.setEnabled(!active);
        btnStop.setEnabled(active);
        btnSnapshot.setEnabled(active);
        btnExport.setEnabled(mSnifferFile != null && mSnifferFile.exists() && mSnifferFile.length() > 0);
    }

    private File buildSnifferFile() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new File(dir, RE_SNIFFER_PREFIX + ts + ".txt");
    }

    private void restoreSnifferState() {
        String saved = getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE)
                .getString(PREF_SNIFFER_PATH, null);
        if (saved == null) { setUiActive(false, getString(R.string.sniffer_idle)); return; }

        File f = new File(saved);
        if (!f.exists() || f.length() == 0) {
            getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                    .remove(PREF_SNIFFER_PATH).apply();
            setUiActive(false, getString(R.string.sniffer_idle));
            return;
        }

        mSnifferFile = f;
        AdbClient.executeShellWithResult(this,
                "[ -f /data/local/tmp/" + RE_SNIFFER_TAG + " ] && echo ACTIVE || echo STOPPED",
                new AdbClient.Callback() {
            @Override public void onSuccess(String out) {
                final boolean active = out.trim().equals("ACTIVE");
                safeRunOnUi(() -> {
                    if (active) {
                        setUiActive(true, getString(R.string.sniffer_active_detail_fmt,
                                f.getName(), (int) (f.length() / 1024)));
                    } else {
                        getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                                .remove(PREF_SNIFFER_PATH).apply();
                        setUiActive(false, getString(R.string.sniffer_previous_stopped_fmt,
                                f.getName(), (int) (f.length() / 1024)));
                    }
                });
            }
            @Override public void onError(String err) {
                safeRunOnUi(() -> setUiActive(false,
                        getString(R.string.sniffer_previous_no_adb_fmt,
                                f.getName(), (int) (f.length() / 1024))));
            }
        });
    }

    // ─── Start / Stop ───────────────────────────────────────────────────────

    private void startSniffer() {
        killSnifferProcesses();

        mSnifferFile = buildSnifferFile();
        getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                .putString(PREF_SNIFFER_PATH, mSnifferFile.getAbsolutePath()).apply();
        final String p  = mSnifferFile.getAbsolutePath();
        final String pf = "/data/local/tmp/" + RE_SNIFFER_PIDS;
        AppLogger.i(TAG, "Starting → " + p);

        setUiActive(false, getString(R.string.sniffer_initializing));
        btnStart.setEnabled(false);

        // Snap file : état courant, écrasé à chaque cycle (jamais cumulatif)
        final String pSnap    = p.replace(".txt", "_snap.txt");
        final String pSnapTmp = pSnap + ".tmp";

        // Header : uniquement logcat — les dumpsys vont dans le snap file séparé
        String headerCmd =
            "logcat -c 2>/dev/null"
            + " ; touch /data/local/tmp/" + RE_SNIFFER_TAG
            + " ; echo === BYD RE SNIFFER === > " + p
            + " ; date >> " + p
            + " ; getprop ro.product.model >> " + p
            + " ; getprop ro.build.fingerprint >> " + p
            + " ; echo === LIVE CAPTURE START === >> " + p
            // Snapshot initial → snap file (overwrite)
            + " ; printf '=== SNAP INITIAL %s ===\\n' $(date +%H:%M:%S) > " + pSnapTmp
            + " ; dumpsys display 2>/dev/null >> " + pSnapTmp
            + " ; dumpsys SurfaceFlinger 2>/dev/null >> " + pSnapTmp
            + " ; dumpsys window 2>/dev/null >> " + pSnapTmp
            + " ; dumpsys activity 2>/dev/null >> " + pSnapTmp
            + " ; dumpsys meminfo 2>/dev/null >> " + pSnapTmp
            + " ; ps -A 2>/dev/null >> " + pSnapTmp
            + " ; mv " + pSnapTmp + " " + pSnap;

        AdbClient.executeShellWithResult(this, headerCmd, new AdbClient.Callback() {
            @Override public void onSuccess(String out) {
                // Snap loop : écrase le snap file toutes les 60s (via tmp pour atomicité)
                String snapLoop =
                    "while [ -f /data/local/tmp/" + RE_SNIFFER_TAG + " ]; do sleep 60;"
                    + " printf '=== SNAP %s ===\\n' $(date +%H:%M:%S) > " + pSnapTmp + ";"
                    + " dumpsys display 2>/dev/null >> " + pSnapTmp + ";"
                    + " dumpsys SurfaceFlinger 2>/dev/null >> " + pSnapTmp + ";"
                    + " dumpsys window 2>/dev/null >> " + pSnapTmp + ";"
                    + " dumpsys activity 2>/dev/null >> " + pSnapTmp + ";"
                    + " dumpsys meminfo 2>/dev/null >> " + pSnapTmp + ";"
                    + " ps -A 2>/dev/null >> " + pSnapTmp + ";"
                    + " mv " + pSnapTmp + " " + pSnap + ";"
                    + " done";

                // exec : remplace le shell par logcat — kill -9 $pid tue logcat directement
                String bgCmd =
                    "echo > " + pf
                    + " ; setsid sh -c 'exec logcat -v threadtime >> " + p + " 2>&1'"
                    + "   & echo $! >> " + pf
                    + " ; setsid sh -c '" + snapLoop + "'"
                    + "   & echo $! >> " + pf
                    + " ; setsid sh -c 'exec logcat -b events -v time >> " + p + " 2>&1'"
                    + "   & echo $! >> " + pf;

                AdbClient.executeShell(SnifferActivity.this, bgCmd);

                safeRunOnUi(() -> {
                    setUiActive(true, getString(R.string.sniffer_active_fmt, mSnifferFile.getName()));
                    Toast.makeText(getApplicationContext(),
                            getString(R.string.sniffer_toast_started_fmt, mSnifferFile.getName()),
                            Toast.LENGTH_LONG).show();
                });
            }
            @Override public void onError(String err) {
                safeRunOnUi(() -> {
                    setUiActive(false, getString(R.string.sniffer_init_fail_fmt, err));
                    AppLogger.e(TAG, "init failed: " + err);
                });
            }
        });
    }

    private void killSnifferProcesses() {
        String pidFile = "/data/local/tmp/" + RE_SNIFFER_PIDS;
        String killCmd =
            "rm -f /data/local/tmp/" + RE_SNIFFER_TAG
            + " ; if [ -f " + pidFile + " ]; then"
            + "   while IFS= read -r pid; do"
            + "     [ -n \"$pid\" ] && kill -9 \"$pid\" 2>/dev/null; done < " + pidFile + ";"
            + "   rm -f " + pidFile + ";"
            + " fi"
            + " ; pkill -f " + RE_SNIFFER_PREFIX + " 2>/dev/null; true";
        AdbClient.executeShell(this, killCmd);
    }

    private void stopSniffer() {
        killSnifferProcesses();
        getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                .remove(PREF_SNIFFER_PATH).apply();
        final String fileName = mSnifferFile != null ? mSnifferFile.getName() : "—";
        if (mSnifferFile != null) {
            AdbClient.executeShell(this,
                    "echo '[RE Sniffer] Stopped.' >> " + mSnifferFile.getAbsolutePath());
        }
        setUiActive(false, getString(R.string.sniffer_stopped_fmt, fileName));
    }

    // ─── Snapshot / Export / Cleanup ────────────────────────────────────────

    private void snapshotSniffer() {
        if (mSnifferFile == null) return;
        final String p       = mSnifferFile.getAbsolutePath();
        final String pSnap    = p.replace(".txt", "_snap.txt");
        final String pSnapTmp = pSnap + ".tmp";
        // Marquer l'instant dans le log principal (une seule ligne, pas de dumpsys)
        String cmdLog = "printf '\\n=== USER SNAP %s ===\\n' $(date +%H:%M:%S) >> " + p;
        // Écraser le snap file avec l'état courant (via tmp pour atomicité)
        String cmdSnap =
              "printf '=== USER SNAP %s ===\\n' $(date +%H:%M:%S) > " + pSnapTmp
            + " ; dumpsys display 2>/dev/null >> " + pSnapTmp
            + " ; dumpsys SurfaceFlinger 2>/dev/null >> " + pSnapTmp
            + " ; dumpsys window 2>/dev/null >> " + pSnapTmp
            + " ; dumpsys activity 2>/dev/null >> " + pSnapTmp
            + " ; dumpsys meminfo 2>/dev/null >> " + pSnapTmp
            + " ; ps -A 2>/dev/null >> " + pSnapTmp
            + " ; mv " + pSnapTmp + " " + pSnap;
        AdbClient.executeShell(this, cmdLog + " ; " + cmdSnap);
        Toast.makeText(getApplicationContext(),
                getString(R.string.sniffer_toast_snapshot), Toast.LENGTH_SHORT).show();
    }

    private void exportSniffer() {
        if (mSnifferFile == null || !mSnifferFile.exists()) {
            Toast.makeText(getApplicationContext(),
                    getString(R.string.sniffer_no_file), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(FileProvider.getUriForFile(this,
                    "com.dashcast.devtools.fileprovider", mSnifferFile));
            File snapFile = new File(mSnifferFile.getAbsolutePath().replace(".txt", "_snap.txt"));
            if (snapFile.exists() && snapFile.length() > 0) {
                uris.add(FileProvider.getUriForFile(this,
                        "com.dashcast.devtools.fileprovider", snapFile));
            }
            Intent send;
            if (uris.size() > 1) {
                send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            } else {
                send = new Intent(Intent.ACTION_SEND);
                send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                send.putExtra(Intent.EXTRA_SUBJECT, mSnifferFile.getName());
            }
            send.setType("text/plain");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.sniffer_export_chooser)));
        } catch (Exception e) {
            AppLogger.e(TAG, "export failed", e);
            Toast.makeText(getApplicationContext(),
                    getString(R.string.sniffer_export_failed_fmt, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void cleanupSnifferFiles() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        int deleted = 0;
        // Exclure le log ET le snap file de la session courante
        String currentSnapPath = mSnifferFile != null
                ? mSnifferFile.getAbsolutePath().replace(".txt", "_snap.txt") : null;
        File[] all = dir.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.getName().startsWith(RE_SNIFFER_PREFIX)
                        && !f.equals(mSnifferFile)
                        && (currentSnapPath == null
                                || !f.getAbsolutePath().equals(currentSnapPath))) {
                    if (f.delete()) deleted++;
                }
            }
        }
        Toast.makeText(getApplicationContext(),
                getString(R.string.sniffer_cleanup_done_fmt, deleted),
                Toast.LENGTH_SHORT).show();
    }
}
