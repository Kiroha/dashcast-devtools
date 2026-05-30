package com.dashcast.devtools.common;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.lang.reflect.Method;

/**
 * Platform — détection runtime de la génération BYD sur laquelle tourne le DevTool.
 *
 * Portage de {@code com.byd.dashcast.platform.Platform} (MyBYDApp).
 * Simplifié pour DevTools : pas d'override utilisateur DL5 (inutile ici — l'utilisateur
 * choisit explicitement l'Activity DiLink cible depuis la NavRail).
 */
public final class Platform {

    private static final String PREFS_NAME = "devtools_prefs";

    /** Sticky capability probe — does {@code cmd activity set-task-windowing-mode} exist? */
    public static final String PREF_CLUSTER_RESIZE_SUPPORTED = "platform_cluster_resize_supported";

    private static volatile Platform INSTANCE;

    private final String  rawProductName;
    private final String  rawModel;
    private final String  rawBrand;
    private final String  rawFingerprint;
    private final int     androidApi;
    private final boolean autoDiLink5;
    private final boolean autoDiLink4;
    private final boolean autoDiLink2;

    private Platform() {
        this.rawProductName = readProp("ro.product.name");
        this.rawModel       = safe(Build.MODEL);
        this.rawBrand       = safe(Build.BRAND);
        this.rawFingerprint = safe(Build.FINGERPRINT);
        this.androidApi     = Build.VERSION.SDK_INT;
        this.autoDiLink5    = detectDiLink5(rawProductName, rawModel, rawFingerprint, androidApi);
        this.autoDiLink4    = detectDiLink4(rawProductName, rawModel, rawFingerprint, androidApi);
        this.autoDiLink2    = detectDiLink2(rawBrand, rawProductName, androidApi);
    }

    public static Platform get() {
        Platform p = INSTANCE;
        if (p == null) {
            synchronized (Platform.class) {
                p = INSTANCE;
                if (p == null) INSTANCE = p = new Platform();
            }
        }
        return p;
    }

    // ── Détections (pures, sans Context) ─────────────────────────────────────

    private static boolean detectDiLink5(String product, String model, String fingerprint, int api) {
        String p = (product == null ? "" : product).toLowerCase();
        String m = (model    == null ? "" : model).toLowerCase();
        String f = (fingerprint == null ? "" : fingerprint).toLowerCase();
        if (p.contains("dilink5") || m.contains("dilink5") || f.contains("dilink5")) return true;
        if (p.contains("dilink_5") || m.contains("dilink 5") || f.contains("dilink 5")) return true;
        if (api >= 31 && (m.contains("byd") || f.contains("byd-auto") || f.contains("/dilink"))) return true;
        return false;
    }

    private static boolean detectDiLink4(String product, String model, String fingerprint, int api) {
        String p = (product == null ? "" : product).toLowerCase();
        String m = (model    == null ? "" : model).toLowerCase();
        String f = (fingerprint == null ? "" : fingerprint).toLowerCase();
        boolean nameHit = p.contains("dilink4") || m.contains("dilink4") || f.contains("dilink4")
                       || p.contains("dilink_4") || m.contains("dilink 4") || f.contains("dilink 4");
        if (!nameHit) return false;
        return api == 29 || api == 28;
    }

    private static boolean detectDiLink2(String brand, String product, int api) {
        String b = (brand   == null ? "" : brand).toLowerCase();
        String p = (product == null ? "" : product).toLowerCase();
        if (!"alps".equals(b)) return false;
        if (!p.contains("k65")) return false;
        return api == 28 || api == 29;
    }

    // ── Accesseurs snapshot ──────────────────────────────────────────────────

    public String  rawProductName()          { return rawProductName; }
    public String  rawModel()                { return rawModel; }
    public String  rawBrand()                { return rawBrand; }
    public String  rawFingerprint()          { return rawFingerprint; }
    public int     androidApi()              { return androidApi; }
    public boolean isAutoDetectedDiLink5()   { return autoDiLink5; }
    public boolean isAutoDetectedDiLink4()   { return autoDiLink4; }
    public boolean isAutoDetectedDiLink2()   { return autoDiLink2; }

    /** DiLink 5 effectif — dans DevTools, pas d'override utilisateur. */
    public boolean isDiLink5(Context ctx)  { return autoDiLink4 ? false : autoDiLink5; }
    public boolean isDiLink4(Context ctx)  { return autoDiLink4; }
    public boolean isDiLink2(Context ctx)  { return autoDiLink2; }
    public boolean isDiLink3(Context ctx)  { return !autoDiLink2 && !autoDiLink4 && !autoDiLink5; }

    public String describeMode(Context ctx) {
        if (autoDiLink5) return "DiLink 5 / API " + androidApi;
        if (autoDiLink4) return "DiLink 4 / API " + androidApi;
        if (autoDiLink2) return "DiLink 2 / API " + androidApi;
        return "DiLink 3 (fallback) / API " + androidApi;
    }

    // ── Cluster resize probe (DL5 uniquement) ────────────────────────────────

    private static volatile Boolean sCachedClusterResizeSupported = null;

    public boolean isClusterTaskResizeSupported(Context ctx) {
        if (!isDiLink5(ctx)) return true;
        Boolean cached = sCachedClusterResizeSupported;
        if (cached != null) return cached;
        String sticky = prefs(ctx).getString(PREF_CLUSTER_RESIZE_SUPPORTED, null);
        if ("yes".equals(sticky)) { sCachedClusterResizeSupported = Boolean.TRUE;  return true; }
        if ("no".equals(sticky))  { sCachedClusterResizeSupported = Boolean.FALSE; return false; }
        return true; // assume supported until proven otherwise
    }

    public void setClusterResizeProbeResult(Context ctx, boolean supported) {
        sCachedClusterResizeSupported = supported;
        prefs(ctx).edit()
                  .putString(PREF_CLUSTER_RESIZE_SUPPORTED, supported ? "yes" : "no")
                  .apply();
    }

    // ── Helpers internes ─────────────────────────────────────────────────────

    private static String readProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
