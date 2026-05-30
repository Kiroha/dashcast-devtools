package com.dashcast.devtools.common;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Slim logger inspired by DashCast's AppLogger.
 * In-memory ring buffer + Android logcat passthrough.
 */
public final class AppLogger {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    public static final class Entry {
        public final long timestampMs;
        public final Level level;
        public final String tag;
        public final String message;
        public final String thread;

        Entry(Level level, String tag, String message) {
            this.timestampMs = System.currentTimeMillis();
            this.level = level;
            this.tag = tag;
            this.message = message;
            this.thread = Thread.currentThread().getName();
        }
    }

    private static final int MAX_ENTRIES = 3000;
    private static final CopyOnWriteArrayList<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private AppLogger() {}

    public static void d(String tag, String msg) { add(Level.DEBUG, tag, msg); Log.d(tag, msg); }
    public static void i(String tag, String msg) { add(Level.INFO,  tag, msg); Log.i(tag, msg); }
    public static void w(String tag, String msg) { add(Level.WARN,  tag, msg); Log.w(tag, msg); }
    public static void e(String tag, String msg) { add(Level.ERROR, tag, msg); Log.e(tag, msg); }

    public static void e(String tag, String msg, Throwable t) {
        add(Level.ERROR, tag, msg + " : " + t.getClass().getSimpleName() + " " + t.getMessage());
        Log.e(tag, msg, t);
    }

    private static void add(Level level, String tag, String msg) {
        if (ENTRIES.size() >= MAX_ENTRIES) ENTRIES.remove(0);
        ENTRIES.add(new Entry(level, tag, msg));
    }

    public static java.util.List<Entry> snapshot() {
        return new java.util.ArrayList<>(ENTRIES);
    }

    public static String formatEntry(Entry e) {
        return TS_FMT.format(new Date(e.timestampMs))
                + " " + e.level.name().charAt(0)
                + "/" + e.tag + ": " + e.message;
    }

    public static void clear() { ENTRIES.clear(); }
}
