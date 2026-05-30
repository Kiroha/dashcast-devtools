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

    /**
     * Warm-up: open a connection in background to surface the
     * "Allow USB debugging?" popup as early as possible (typically at
     * app launch), so the user accepts it once and the very first real
     * shell command from any feature succeeds immediately.
     *
     * <p>Safe to call multiple times — no-op if the connection succeeds.
     */
    public static void warmUp(final Context context) {
        final Context appCtx = context.getApplicationContext();
        EXEC.submit(() -> {
            try (Dadb dadb = connect(appCtx)) {
                AdbShellResponse r = dadb.shell("id -u");
                AppLogger.i(TAG, "warmUp OK: uid=" + r.getAllOutput().trim());
            } catch (Exception e) {
                AppLogger.w(TAG, "warmUp failed (popup refused / port closed?): " + e.getMessage());
            }
        });
    }

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

    /** Lock for key generation: prevents TOCTOU if two ADB calls land
     *  simultaneously on first launch before the .key/.pub files exist. */
    private static final Object sKeyLock = new Object();

    private static Dadb connect(Context context) throws Exception {
        File keyDir = context.getFilesDir();
        // Same filenames as DashCast (adb.key / adb.pub) — keeps a single
        // RSA fingerprint per user across both apps so the popup is shown
        // only once even when both DashCast and DevTools are installed.
        File pri = new File(keyDir, "adb.key");
        File pub = new File(keyDir, "adb.pub");
        AdbKeyPair keyPair;
        synchronized (sKeyLock) {
            if (!pri.exists() || !pub.exists()) {
                AppLogger.i(TAG, "Generating new ADB key pair");
                AdbKeyPair.generate(pri, pub);
            }
            keyPair = AdbKeyPair.read(pri, pub);
        }

        // Retry loop — gives the user up to ~30s to tap 'Always allow from
        // this computer' on the "Allow USB debugging?" popup. Without this
        // the very first call after a fresh install fails instantly while
        // the popup is still on screen, and the user is left wondering
        // why nothing happens.
        int retries = 15;
        Exception last = null;
        while (retries-- > 0) {
            try {
                return Dadb.create("localhost", ADB_PORT, keyPair);
            } catch (Exception e) {
                last = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                AppLogger.w(TAG, "ADB connect failed (popup pending?), retry in 2s, " + retries + " left");
                try { Thread.sleep(2000); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        throw last != null ? last : new Exception("ADB connect: unknown failure");
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…[" + s.length() + " chars]" : s;
    }
}
