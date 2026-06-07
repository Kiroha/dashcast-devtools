package com.dashcast.devtools.sniffer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 * <p>Background processes use {@code setsid} + {@code trap "" HUP} so they
 * survive Activity destruction (rotation, app paused, even Activity finished).
 * State is restored from SharedPreferences on each onCreate.
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

    private static final int SIZE_REFRESH_MS = 5_000;
    private static final int BG_CHECK_MS     = 8_000;

    private TextView tvStatusPill;
    private TextView tvStatus;
    private Button   btnStart;
    private Button   btnStop;
    private Button   btnSnapshot;
    private Button   btnExport;
    private Button   btnCleanup;

    private volatile File    mSnifferFile;
    private volatile boolean mDestroyed;

    private final Handler  mHandler            = new Handler(Looper.getMainLooper());
    private       Runnable mSizeRefreshRunnable;
    private       Runnable mBgCheckRunnable;

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
        stopSizeRefresh();
        cancelBgCheck();
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
        tvStatusPill.setTextColor(getColor(active ? R.color.md_status_ok : R.color.md_status_err));
        tvStatusPill.setBackgroundResource(active
                ? R.drawable.bg_sniffer_pill_active
                : R.drawable.bg_sniffer_pill_inactive);
        if (detail != null) tvStatus.setText(detail);
        btnStart.setEnabled(!active);
        btnStop.setEnabled(active);
        btnSnapshot.setEnabled(active);
        btnExport.setEnabled(mSnifferFile != null && mSnifferFile.exists() && mSnifferFile.length() > 0);
        if (active) startSizeRefresh(); else stopSizeRefresh();
    }

    // Rafraîchit le status toutes les SIZE_REFRESH_MS avec la taille courante du fichier.
    private void startSizeRefresh() {
        stopSizeRefresh();
        mSizeRefreshRunnable = new Runnable() {
            @Override public void run() {
                if (mDestroyed || mSnifferFile == null) return;
                tvStatus.setText(getString(R.string.sniffer_active_size_fmt,
                        mSnifferFile.getName(), mSnifferFile.length() / 1024));
                mHandler.postDelayed(this, SIZE_REFRESH_MS);
            }
        };
        mHandler.postDelayed(mSizeRefreshRunnable, SIZE_REFRESH_MS);
    }

    private void stopSizeRefresh() {
        if (mSizeRefreshRunnable != null) {
            mHandler.removeCallbacks(mSizeRefreshRunnable);
            mSizeRefreshRunnable = null;
        }
    }

    private void cancelBgCheck() {
        if (mBgCheckRunnable != null) {
            mHandler.removeCallbacks(mBgCheckRunnable);
            mBgCheckRunnable = null;
        }
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
        tvStatus.setText(getString(R.string.sniffer_checking));
        btnStart.setEnabled(false);
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
                // ADB inaccessible : on ne sait pas si le sniffer tourne encore.
                // On laisse Stop activé pour que l'utilisateur puisse forcer l'arrêt.
                safeRunOnUi(() -> {
                    setUiActive(false, getString(R.string.sniffer_previous_no_adb_fmt,
                            f.getName(), (int) (f.length() / 1024)));
                    btnStop.setEnabled(true);
                });
            }
        });
    }

    // ─── Start / Stop ───────────────────────────────────────────────────────

    private void startSniffer() {
        mSnifferFile = buildSnifferFile();
        getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                .putString(PREF_SNIFFER_PATH, mSnifferFile.getAbsolutePath()).apply();
        final String p  = mSnifferFile.getAbsolutePath();
        final String pf = "/data/local/tmp/" + RE_SNIFFER_PIDS;
        AppLogger.i(TAG, "Starting → " + p);

        setUiActive(false, getString(R.string.sniffer_initializing));

        final String pSnap    = p.replace(".txt", "_snap.txt");
        final String pSnapTmp = pSnap + ".tmp";

        // Kill + header dans une seule commande shell pour éviter la race condition :
        // si kill et touch tournaient sur deux threads concurrents, rm -f pouvait
        // supprimer le sentinel juste après que touch l'ait créé.
        String headerCmd =
            buildKillCmd()
            + " ; logcat -c 2>/dev/null"
            + " ; touch /data/local/tmp/" + RE_SNIFFER_TAG
            + " ; echo === BYD RE SNIFFER === > " + p
            + " ; date >> " + p
            + " ; getprop ro.product.model >> " + p
            + " ; getprop ro.build.fingerprint >> " + p
            + " ; echo === LIVE CAPTURE START === >> " + p
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
                // Les double-quotes à l'intérieur d'un sh -c '...' sont littérales
                // pour le shell externe. Le shell interne les évalue correctement.
                // On évite ainsi le conflit de single-quotes avec printf '...'.
                String snapLoop =
                    "trap \"\" HUP;"
                    + "while [ -f /data/local/tmp/" + RE_SNIFFER_TAG + " ]; do sleep 60;"
                    + " printf \"=== SNAP %s ===\\n\" $(date +%H:%M:%S) > " + pSnapTmp + ";"
                    + " dumpsys display 2>/dev/null >> " + pSnapTmp + ";"
                    + " dumpsys SurfaceFlinger 2>/dev/null >> " + pSnapTmp + ";"
                    + " dumpsys window 2>/dev/null >> " + pSnapTmp + ";"
                    + " dumpsys activity 2>/dev/null >> " + pSnapTmp + ";"
                    + " dumpsys meminfo 2>/dev/null >> " + pSnapTmp + ";"
                    + " ps -A 2>/dev/null >> " + pSnapTmp + ";"
                    + " mv " + pSnapTmp + " " + pSnap + ";"
                    + " done";

                // setsid crée une nouvelle session (immunité SIGHUP structurelle).
                // trap "" HUP à l'intérieur du sh -c est hérité par exec : pas de
                // dépendance à nohup qui n'est pas disponible sur tous les shells BYD.
                String bgCmd =
                    "echo > " + pf
                    + " ; setsid sh -c 'trap \"\" HUP; exec logcat -v threadtime >> " + p + " 2>&1'"
                    + "   & echo $! >> " + pf
                    + " ; setsid sh -c '" + snapLoop + "'"
                    + "   & echo $! >> " + pf
                    + " ; setsid sh -c 'trap \"\" HUP; exec logcat -b events -v time >> " + p + " 2>&1'"
                    + "   & echo $! >> " + pf;

                final long sizeAfterHeader = mSnifferFile != null ? mSnifferFile.length() : 0;
                AdbClient.executeShell(SnifferActivity.this, bgCmd);

                safeRunOnUi(() -> {
                    setUiActive(true, getString(R.string.sniffer_active_fmt, mSnifferFile.getName()));
                    Toast.makeText(getApplicationContext(),
                            getString(R.string.sniffer_toast_started_fmt, mSnifferFile.getName()),
                            Toast.LENGTH_LONG).show();

                    // Vérification différée : si le fichier n'a pas grossi après BG_CHECK_MS,
                    // bgCmd a probablement échoué silencieusement.
                    // Stocké dans mBgCheckRunnable pour pouvoir l'annuler si Stop
                    // est tapé avant l'expiration (évite un faux positif).
                    final File fileRef = mSnifferFile;
                    cancelBgCheck();
                    mBgCheckRunnable = () -> {
                        mBgCheckRunnable = null;
                        if (mDestroyed || fileRef != mSnifferFile) return;
                        if (fileRef.length() <= sizeAfterHeader) {
                            AppLogger.w(TAG, "bgCmd may have failed — file not growing after " + BG_CHECK_MS + "ms");
                            getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                                    .remove(PREF_SNIFFER_PATH).apply();
                            setUiActive(false, getString(R.string.sniffer_bg_failed));
                        }
                    };
                    mHandler.postDelayed(mBgCheckRunnable, BG_CHECK_MS);
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

    private String buildKillCmd() {
        String pidFile = "/data/local/tmp/" + RE_SNIFFER_PIDS;
        return "rm -f /data/local/tmp/" + RE_SNIFFER_TAG
            + " ; if [ -f " + pidFile + " ]; then"
            + "   while IFS= read -r pid; do"
            + "     [ -n \"$pid\" ] && kill -9 \"$pid\" 2>/dev/null; done < " + pidFile + ";"
            + "   rm -f " + pidFile + ";"
            + " fi"
            + " ; pkill -f " + RE_SNIFFER_PREFIX + " 2>/dev/null; true";
    }

    private void killSnifferProcesses() {
        AdbClient.executeShell(this, buildKillCmd());
    }

    private void stopSniffer() {
        cancelBgCheck();
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
        final String p        = mSnifferFile.getAbsolutePath();
        final String pSnap    = p.replace(".txt", "_snap.txt");
        final String pSnapTmp = pSnap + ".tmp";
        String cmdLog = "printf '\\n=== USER SNAP %s ===\\n' $(date +%H:%M:%S) >> " + p;
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
