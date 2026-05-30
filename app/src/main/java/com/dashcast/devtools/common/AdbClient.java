package com.dashcast.devtools.common;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * Slim ADB local client — connects to localhost:5555 via dadb.
 *
 * <p>Lifted from DashCast's {@code AdbLocalClient} but stripped of Beta proxy
 * fallback so this new app has no dependency on the heavy BetaProxy daemon
 * infrastructure. All shell calls go straight through dadb.
 *
 * <p>The very first connection triggers the device's "Allow USB debugging?"
 * popup (one-time). RSA key pair is persisted in app's internal files.
 */
@SuppressWarnings("try")
public final class AdbClient {

    private static final String TAG = "AdbClient";
    private static final int ADB_PORT = 5555;

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "adb-local-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    public interface Callback {
        void onSuccess(String output);
        void onError(String error);
    }

    private AdbClient() {}

    /** Fire-and-forget shell command. */
    public static void executeShell(final Context context, final String command) {
        final Context appCtx = context.getApplicationContext();
        EXEC.submit(() -> {
            long t0 = SystemClock.elapsedRealtime();
            try (Dadb dadb = connect(appCtx)) {
                AdbShellResponse r = dadb.shell(command);
                AppLogger.d(TAG, "executeShell (" + (SystemClock.elapsedRealtime() - t0) + "ms): "
                        + command + " -> " + truncate(r.getAllOutput().trim()));
            } catch (Exception e) {
                AppLogger.e(TAG, "executeShell ERROR: " + command, e);
            }
        });
    }

    /** Shell command with captured output. */
    public static void executeShellWithResult(final Context context, final String command,
                                              final Callback cb) {
        final Context appCtx = context.getApplicationContext();
        EXEC.submit(() -> {
            try (Dadb dadb = connect(appCtx)) {
                String output = dadb.shell(command).getAllOutput().trim();
                AppLogger.d(TAG, "executeShellWithResult: " + command + " -> " + truncate(output));
                cb.onSuccess(output);
            } catch (Exception e) {
                AppLogger.e(TAG, "executeShellWithResult ERROR: " + command, e);
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private static Dadb connect(Context context) throws Exception {
        File keyDir = context.getFilesDir();
        File pri = new File(keyDir, "adbkey");
        File pub = new File(keyDir, "adbkey.pub");
        if (!pri.exists() || !pub.exists()) {
            AppLogger.i(TAG, "Generating new ADB key pair");
            AdbKeyPair.generate(pri, pub);
        }
        AdbKeyPair keyPair = AdbKeyPair.read(pri, pub);
        return Dadb.create("127.0.0.1", ADB_PORT, keyPair);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…[" + s.length() + " chars]" : s;
    }
}
