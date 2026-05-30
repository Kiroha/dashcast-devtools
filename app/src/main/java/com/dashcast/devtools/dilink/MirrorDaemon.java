package com.dashcast.devtools.dilink;

/**
 * MirrorDaemon — constantes du protocole Binder IMirrorDaemon.
 *
 * Portage minimal depuis {@code com.byd.dashcast.daemon.MirrorDaemon}.
 * Le daemon tourne côté shell uid=2000 (app_process) ; ce fichier expose
 * uniquement les constantes nécessaires à {@link Dl5VdTestRunner}.
 */
public final class MirrorDaemon {

    private MirrorDaemon() {}

    /** Action broadcastée quand le daemon est prêt (IBinder dans l'extra). */
    public static final String ACTION_DAEMON_READY = "com.byd.dashcast.MIRROR_DAEMON_READY";

    /** Descriptor du Binder IMirrorDaemon (doit correspondre côté daemon). */
    public static final String DESCRIPTOR = "com.byd.dashcast.daemon.IMirrorDaemon";

    // Codes de transaction Binder ─────────────────────────────────────────────

    /** TRANSACT 1 : configure le miroir SurfaceControl du display cluster. */
    public static final int TRANSACT_MIRROR_START  = 1;
    /** TRANSACT 2 : injecte un MotionEvent sur le display cluster. */
    public static final int TRANSACT_INJECT_MOTION = 2;
    /** TRANSACT 3 : injecte un KeyEvent sur le display cluster. */
    public static final int TRANSACT_INJECT_KEY    = 3;
    /** TRANSACT 4 : détruit le miroir. */
    public static final int TRANSACT_MIRROR_STOP   = 4;
}
