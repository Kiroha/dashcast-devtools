# Projection Cluster BYD DiLink3 — Documentation technique complète

> **Version validée :** v0.6.39-alpha  
> **Dernière mise à jour :** 2026-06-04  
> **Statut :** ✅ Fonctionnel — Waze visible sur le cluster physique

---

## Environnement cible

| Propriété | Valeur |
|---|---|
| Modèle | BYD AUTO DiLink 3.0 |
| Android | 10 (API 29) |
| Build | `QKQ1.210910.001 / eng.build.20260204` |
| Cluster display | displayId=1, layerStack=1, 1920×720, nom=`fission` |
| Nature du display cluster | VirtualDisplay XDJA (`fission_bg_xdjaVirtualSurface`) |
| Process daemon | `app_process64`, uid=2000 (shell) |
| Signature APK | `platform.keystore` (androiddebugkey) |

---

## Architecture du cluster DiLink3

Le cluster physique n'est **pas** un display Android natif. C'est un `VirtualDisplay` créé et géré par `com.xdja.containerservice` :

```
┌──────────────────────────────────────────────────────┐
│  Qt C++ native rendering engine (pid=436)            │
│  EGLSurface → libxdjacontainerservice_jni.so         │
└───────────────────────┬──────────────────────────────┘
                        │ JNI
                        ▼
┌──────────────────────────────────────────────────────┐
│  AutoDisplayService (com.xdja.containerservice)      │
│  getQtProjectionDispInfoNative() → QtDisplayInfo     │
│  createVirtualDisplay(name="fission_bg_xdjaVirtual   │
│    Surface", 1920×720, flags=11, qtEglSurface)       │
│  → displayId=1 "fission"                             │
└───────────────────────┬──────────────────────────────┘
                        │ Android DisplayManager
                        ▼
┌──────────────────────────────────────────────────────┐
│  displayId=1 "fission" (VD XDJA)                    │
│  FLAG_PRESENTATION | FLAG_OWN_CONTENT_ONLY           │
│  1920×720 @ 320dpi — layerStack=1                   │
│  Output → Qt EGL surface → cluster hardware         │
└──────────────────────────────────────────────────────┘
```

**Conséquence** : Pour afficher du contenu sur le cluster, il faut y ajouter un overlay window. Le contenu est composité par SurfaceFlinger dans le pipeline du VD XDJA, puis Qt le rend sur le hardware physique.

---

## Principe de la projection (chemin OpenBYD)

```
Application (Waze)
    │
    │ render dans
    ▼
VirtualDisplay (displayId=N, layerStack=N)         ← VD TRUSTED créé par le daemon
    │
    │ output surface =
    ▼
SurfaceHolder surface (dans l'overlay)             ← overlay TYPE_SYSTEM_OVERLAY sur displayId=1
    │
    │ composité par SurfaceFlinger dans
    ▼
displayId=1 "fission" (VD XDJA)                   ← display cluster
    │
    │ output Qt EGL surface
    ▼
Cluster physique (hardware)                        ← visible sur le tableau de bord
```

---

## Séquence complète de projection

```
Client (Dl3ProjectionActivity)      Daemon (uid=2000, app_process64)
  │                                       │
  ├── CLUSTER_ATTACH ────────────────────►│
  │   layerStack=1, w=1920, h=720         │ 1. tryAttachClusterOverlay()
  │                                       │    a. resolveClusterDisplay(1) → displayId=1
  │                                       │    b. createDisplayContext(display=1)
  │                                       │    c. AppOps SYSTEM_ALERT_WINDOW → MODE_ALLOWED
  │                                       │    d. new SurfaceView(displayCtx)
  │                                       │    e. holder.setFixedSize(1920, 720)
  │                                       │    f. addView(surfaceView, TYPE_SYSTEM_OVERLAY)
  │                                       │       → performShowLocked: HAS_DRAWN ✓
  │                                       │    g. latch → surface valide
  │                                       │
  │                                       │ 2. createAndStoreTrustedVd(overlaySurface)
  │                                       │    dm.createVirtualDisplay(flags=1346) → displayId=5
  │                                       │
  │◄── surface + displayId=5 ─────────────┤
  │                                       │
  ├── LAUNCH_AND_FORCE ──────────────────►│ 3. am start com.waze/.FreeMapAppActivity
  │   pkg=com.waze, displayId=5           │ 4. getTasks() → taskId=43
  │                                       │ 5. setTaskWindowingMode(FREEFORM)
  │                                       │ 6. moveStackToDisplay(stack, 5) ✓
  │                                       │
  │                                       │ 7. Watchdog (500ms polls)
  │                                       │    iter=6 (T≈3s): task sur display 0
  │                                       │    → re-move + FREEFORM + setFocusedTask ✓
  │◄── OK taskId=43 ──────────────────────┤
  │                                       │
  ├── MIRROR_START ─────────────────────►│ 8. SurfaceControl.setDisplaySurface(
  │   layerStack=5, tabletSurface         │       layerStack=5, tabletSurface)
  │◄── OK ────────────────────────────────┤    → preview sur tablette
  │                                       │
  ├── MIRROR_STOP ──────────────────────►│ 9. Libère : overlay + VD + SC
  │◄── OK ────────────────────────────────┤
```

---

## Composants du daemon (MirrorDaemon.java)

### `main()` — Point d'entrée

```java
public static void main(String[] args) {
    // 1. Prépare le Looper principal — OBLIGATOIRE avant registerService et initContext
    Looper.prepareMainLooper();

    // 2. Enregistre le service Binder
    MirrorBinder binder = new MirrorBinder();
    registerService("devtools_mirror_daemon", binder);

    // 3. Initialise InputManager et le contexte Android
    initInputManager();
    initContext();

    // 4. Boucle d'événements (bloquant)
    Looper.loop();
}
```

**Prérequis critiques** :
- `Looper.prepareMainLooper()` AVANT tout le reste — les `SurfaceHolder.Callback` requièrent un Looper
- L'ordre `registerService → initContext` est important (le service doit être disponible avant que des clients se connectent)

---

### `initContext()` — Initialisation des contextes Android

```java
static void initContext() {
    Class<?> atCls = Class.forName("android.app.ActivityThread");

    // currentActivityThread() retourne null si app_process n'a pas encore d'ActivityThread
    Object at = atCls.getMethod("currentActivityThread").invoke(null);
    if (at == null) {
        // systemMain() crée un ActivityThread système — nécessite prepareMainLooper() en amont
        Method systemMain = atCls.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        at = systemMain.invoke(null);
    }

    // sSysContext = "android" package, uid=1000 — a des Resources valides
    Method getSystemCtx = atCls.getDeclaredMethod("getSystemContext");
    getSystemCtx.setAccessible(true);
    sSysContext = (Context) getSystemCtx.invoke(at);

    // sContext = "com.android.shell" package, uid=2000 — utilisé pour AppOps et DisplayManager
    // Flag 0 : uid=2000 owns com.android.shell → validatePackageName() passe sans IGNORE_SECURITY
    sContext = sSysContext.createPackageContext("com.android.shell", 0);
}
```

**Deux contextes distincts** :

| Variable | Package | UID | Rôle |
|---|---|---|---|
| `sSysContext` | `android` | 1000 | `createDisplayContext()`, `DisplayManager` (Resources valides) |
| `sContext` | `com.android.shell` | 2000 | AppOps grants, `lp.packageName`, `createVirtualDisplay` |

**Pourquoi deux contextes ?** `sContext` (createPackageContext en mode systemMain) n'a pas de Resources chargées. `DisplayManager.getOrCreateDisplayLocked()` appelle `mContext.getResources()` → NPE si on utilise `sContext`. D'où `sSysContext` pour tout ce qui touche au display.

---

### `resolveClusterDisplay()` — Résolution du display cluster

```java
private static Display resolveClusterDisplay(int displayIdHint) {
    // IMPORTANT : utiliser sSysContext, pas sContext — Resources valides requises
    Context dmCtx = (sSysContext != null) ? sSysContext : sContext;
    DisplayManager dm = dmCtx.getSystemService(DisplayManager.class);

    // 1. Tenter l'ID direct (vient de CLUSTER_ATTACH layerStack)
    Display display = dm.getDisplay(displayIdHint);
    if (display != null) return display;

    // 2. Fallback : chercher par nom ("cluster" ou "fission")
    for (Display candidate : dm.getDisplays()) {
        String name = candidate.getName();
        if (name == null) continue;
        String lowered = name.toLowerCase(Locale.US);
        if (lowered.contains("cluster") || lowered.contains("fission")) {
            return candidate;
        }
    }
    return null;
}
```

Sur DiLink3, `displayIdHint=1` → `dm.getDisplay(1)` retourne le display `fission` directement.

---

### `tryAttachClusterOverlay()` — Création de l'overlay SurfaceView

C'est le cœur du système. Crée un overlay fenêtré sur le cluster (displayId=1) et retourne sa Surface pour l'utiliser comme output du VirtualDisplay.

#### Step A — Contexte display-scoped

```java
Context base = (sSysContext != null) ? sSysContext : sContext;
Context displayCtx = null;
try {
    // createDisplayContext() configure le ContextImpl avec les métriques du cluster
    displayCtx = base.createDisplayContext(targetDisplay);
} catch (Exception e) { /* log */ }

boolean displayCtxHasResources = displayCtx != null && displayCtx.getResources() != null;
// viewCtx = contexte avec Resources valides pour construire la SurfaceView
Context viewCtx = displayCtxHasResources ? displayCtx : base;
```

**Pourquoi** : `new SurfaceView(context)` appelle `context.getResources()` → NPE si null. `createDisplayContext()` depuis `sSysContext` retourne un contexte avec Resources valides sur ce ROM.

#### Step B — Grant AppOps SYSTEM_ALERT_WINDOW

```java
try {
    Object appOps = sContext.getSystemService(Context.APP_OPS_SERVICE);
    Method setMode = appOps.getClass().getMethod(
            "setMode", int.class, int.class, String.class, int.class);
    setMode.setAccessible(true);
    // OP_SYSTEM_ALERT_WINDOW=24, uid=2000, pkg="com.android.shell", MODE_ALLOWED=0
    setMode.invoke(appOps, 24, android.os.Process.myUid(), sContext.getPackageName(), 0);
} catch (Exception appOpsEx) { /* swallow — TYPE_SYSTEM_OVERLAY ne nécessite pas AppOps */ }
```

**Note** : Ce grant est nécessaire pour le fallback TYPE_APPLICATION_OVERLAY. Pour TYPE_SYSTEM_OVERLAY (chemin principal), il est ignoré par WMS mais conservé par sécurité.

#### Step C — SurfaceView avec taille fixe

```java
SurfaceView surfaceView = new SurfaceView(viewCtx);
SurfaceHolder holder = surfaceView.getHolder();
holder.setFixedSize(w, h); // Force le buffer à 1920×720

holder.addCallback(new SurfaceHolder.Callback() {
    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        Surface surface = surfaceHolder.getSurface();
        if (surface != null && surface.isValid()) {
            surfaceRef.compareAndSet(null, surface);
            latch.countDown(); // Débloque le thread Binder
        }
    }
    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {
        Surface surface = h.getSurface();
        if (surface != null && surface.isValid()) {
            surfaceRef.compareAndSet(null, surface);
            latch.countDown();
        }
    }
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
});
```

**`setFixedSize(1920, 720)` est critique** : sans ça, le buffer suit la taille de la fenêtre (variable), pas la résolution native du cluster.

#### Step D — addView sur le display cluster

```java
// Doit s'exécuter sur le main looper (View system)
Runnable attach = () -> {
    WindowManager.LayoutParams lp = createOverlayLayoutParams(targetDisplay, w, h);
    WindowManager wm = displayCtx.getSystemService(WindowManager.class); // WM lié à displayId=1

    try {
        wm.addView(surfaceView, lp); // TYPE_SYSTEM_OVERLAY → performShowLocked ✓
        sClusterOverlayWindowManager = wm;
    } catch (SecurityException se) {
        // Fallback TYPE_APPLICATION_OVERLAY si INTERNAL_SYSTEM_WINDOW refusé pour uid=2000
        lp = createOverlayLayoutParamsFallback(targetDisplay, w, h);
        wm.addView(surfaceView, lp);
        sClusterOverlayWindowManager = wm;
    }
    sClusterOverlayView = surfaceView;
};

if (Looper.myLooper() == Looper.getMainLooper()) {
    attach.run();
} else {
    new android.os.Handler(Looper.getMainLooper()).post(attach);
}

// Attente max 2s — la surface est généralement disponible en < 100ms
latch.await(2, TimeUnit.SECONDS);
```

**Le WindowManager doit venir de `displayCtx`** : `displayCtx.getSystemService(WindowManager.class)` retourne un `WindowManagerImpl` lié au display cluster. `addView()` route automatiquement la fenêtre vers displayId=1 sans paramètre supplémentaire.

---

### `createOverlayLayoutParams()` — Paramètres fenêtre TYPE_SYSTEM_OVERLAY

```java
private static WindowManager.LayoutParams createOverlayLayoutParams(
        Display targetDisplay, int w, int h) {

    // TYPE_SYSTEM_OVERLAY (2006) — chemin principal
    // Requiert INTERNAL_SYSTEM_WINDOW (permission manifeste, accordée par signature platform)
    // Bypass total AppOps → mPolicyVisibility=true garanti → performShowLocked est appelé
    int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? 2006 : TYPE_PHONE;

    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            w, h,
            overlayType,
            FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE);

    lp.setTitle("devtools_cluster_overlay");
    lp.gravity = Gravity.TOP | Gravity.START;
    lp.x = 0;
    lp.y = 0;

    // CRITIQUE : packageName explicite pour que WMS utilise le bon uid/package
    // WMS résout le package depuis lp.packageName pour les checks de permission.
    // Sans ce champ, WMS peut résoudre "android" (uid=1000) au lieu de "com.android.shell" (uid=2000),
    // créant un mismatch avec notre grant AppOps → mPolicyVisibility=false → performShowLocked jamais appelé.
    lp.packageName = sContext.getPackageName(); // "com.android.shell"

    return lp;
}
```

#### Fallback TYPE_APPLICATION_OVERLAY

```java
private static WindowManager.LayoutParams createOverlayLayoutParamsFallback(
        Display targetDisplay, int w, int h) {
    // Utilisé si TYPE_SYSTEM_OVERLAY lève SecurityException (INTERNAL_SYSTEM_WINDOW refusé pour uid=2000)
    int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? TYPE_APPLICATION_OVERLAY  // 2038
            : TYPE_PHONE;               // 2002
    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            w, h, overlayType,
            FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE);
    lp.setTitle("devtools_cluster_overlay");
    lp.gravity = Gravity.TOP | Gravity.START;
    lp.x = 0; lp.y = 0;
    lp.packageName = sContext.getPackageName();
    return lp;
}
```

---

### `createAndStoreTrustedVd()` — Création du VirtualDisplay TRUSTED

```java
private static int createAndStoreTrustedVd(Surface surface, int w, int h) {
    // Libère le VD précédent si existant
    if (sVirtualDisplay != null) {
        sVirtualDisplay.release();
        sVirtualDisplay = null;
    }

    // IMPORTANT : utiliser sContext (com.android.shell, uid=2000)
    // uid=2000 possède CREATE_TRUSTED_VIRTUAL_DISPLAY via le ROM BYD
    DisplayManager dm = sContext.getSystemService(DisplayManager.class);

    VirtualDisplay vd = null;
    try {
        // flags=1346 = 322 | 1024
        // 322 = PRESENTATION(2) | SUPPORTS_TOUCH(64) | DESTROY_CONTENT_ON_REMOVAL(256)
        // 1024 = TRUSTED (FLAG_VIRTUAL_DISPLAY_FLAG_TRUSTED = 0x400)
        // TRUSTED est requis pour que canPlaceEntityOnDisplay() accepte les apps
        // non-resizable (Waze : android:resizeableActivity="false")
        vd = dm.createVirtualDisplay("devtools_projection_vd", w, h, 160, surface, 1346);
        if (vd != null) log("VD TRUSTED flags=1346 OK");
    } catch (Exception e) {
        log("VD TRUSTED failed, fallback 322: " + e.getMessage());
    }

    if (vd == null) {
        // Fallback sans TRUSTED (apps resizable uniquement)
        vd = dm.createVirtualDisplay("devtools_projection_vd", w, h, 160, surface, 322);
    }
    if (vd == null) throw new RuntimeException("createVirtualDisplay returned null");

    sVirtualDisplay = vd;
    return vd.getDisplay().getDisplayId();
}
```

**Pourquoi TRUSTED** : Sans `FLAG_TRUSTED`, `ActivityTaskManager.canPlaceEntityOnDisplay()` retourne `false` pour Waze (`resizeableActivity=false`). Le move de tâche vers le VD est rejeté silencieusement.

**Pourquoi `sContext` et pas `sSysContext`** : La permission `CREATE_TRUSTED_VIRTUAL_DISPLAY` est vérifiée contre le UID appelant. `sContext` a uid=2000 (shell), qui possède cette permission sur ce ROM BYD.

---

### `handleClusterAttach()` — Handler Binder principal

```java
private static boolean handleClusterAttach(Parcel data, Parcel reply) {
    int layerStack = data.readInt(); // = 1 pour le cluster DiLink3
    int w = data.readInt();          // = 1920
    int h = data.readInt();          // = 720

    // ── Chemin principal : overlay → VD (pattern OpenBYD 2.2) ────────────
    Surface overlaySurface = tryAttachClusterOverlay(layerStack, w, h);
    if (overlaySurface != null) {
        int displayId = createAndStoreTrustedVd(overlaySurface, w, h);
        if (displayId >= 0) {
            reply.writeNoException();
            reply.writeInt(1);
            reply.writeParcelable(overlaySurface, 0);
            reply.writeInt(displayId);
            return true;
        }
        releaseClusterOverlay();
    }

    // ── Fallback : SurfaceControl à layerStack=1, layer=MAX_INT-1 ─────────
    // Utilisé si l'overlay échoue. SC bypasse WMS → toujours visible.
    // ... (voir code source complet)
}
```

---

### `releaseClusterOverlay()` — Nettoyage de l'overlay

```java
private static void releaseClusterOverlay() {
    final View overlayView = sClusterOverlayView;
    final WindowManager windowManager = sClusterOverlayWindowManager;
    sClusterOverlayView = null;
    sClusterOverlayWindowManager = null;
    if (overlayView == null || windowManager == null) return;

    Runnable release = () -> {
        try {
            windowManager.removeViewImmediate(overlayView);
        } catch (Exception e) { /* log */ }
    };

    // removeViewImmediate doit être appelé depuis le thread qui a créé la view (main looper)
    if (Looper.myLooper() == Looper.getMainLooper()) {
        release.run();
    } else {
        new android.os.Handler(Looper.getMainLooper()).post(release);
    }
}
```

---

## Paramètres critiques — table de référence

| Paramètre | Valeur | Raison |
|---|---|---|
| Window type | `TYPE_SYSTEM_OVERLAY` (2006) | Bypass AppOps → `mPolicyVisibility=true` → `performShowLocked` |
| Window type fallback | `TYPE_APPLICATION_OVERLAY` (2038) | Si INTERNAL_SYSTEM_WINDOW refusé pour uid=2000 |
| `lp.packageName` | `"com.android.shell"` | Évite mismatch uid lors des checks WMS |
| `setFixedSize(w, h)` | `1920, 720` | Force la résolution du buffer SurfaceHolder |
| VD flags primary | `1346` (322\|TRUSTED) | TRUSTED requis pour apps non-resizable |
| VD flags fallback | `322` | Sans TRUSTED — apps resizable uniquement |
| `DisplayManager` context | `sSysContext` | Resources valides (évite NPE) |
| `createVirtualDisplay` context | `sContext` | uid=2000 → CREATE_TRUSTED_VIRTUAL_DISPLAY |
| Looper | `getMainLooper()` | View system requiert le main thread |
| Latch timeout | 2 secondes | Surface disponible en < 100ms normalement |

---

## Prérequis du daemon

| Prérequis | Détail |
|---|---|
| `Looper.prepareMainLooper()` | Avant tout — requis pour View system et SurfaceHolder.Callback |
| `initContext()` | `sSysContext` + `sContext` initialisés avant toute transaction |
| Signature platform | `platform.keystore` — accorde `INTERNAL_SYSTEM_WINDOW`, `ACCESS_SURFACE_FLINGER` |
| uid=2000 (shell) | `CREATE_TRUSTED_VIRTUAL_DISPLAY`, `AppOpsManager.setMode()` |
| Manifest | `INTERNAL_SYSTEM_WINDOW`, `MANAGE_ACTIVITY_STACKS`, `ACCESS_SURFACE_FLINGER` |

---

## Historique des tentatives échouées (pourquoi ces choix)

### Tentative 1 — `new SurfaceView(sContext)` → NPE

`sContext = createPackageContext("com.android.shell", 0)` en mode `systemMain()` n'a pas de Resources chargées. `SurfaceView(context)` → `context.getResources()` → null → NPE.

```
CLUSTER_ATTACH overlay failed: NullPointerException:
  Attempt to invoke virtual method 'Resources Context.getResources()' on a null object reference
```

**Fix** : utiliser `sSysContext.createDisplayContext(targetDisplay)` comme viewCtx.

---

### Tentative 2 — `DisplayManager` depuis `sContext` → NPE

`DisplayManager.getDisplay()` → `getOrCreateDisplayLocked()` → `mContext.getResources()` → NPE si sContext.

**Fix** : utiliser `sSysContext` pour obtenir le `DisplayManager`.

---

### Tentative 3 — `TYPE_APPLICATION_OVERLAY` (2038) → `performShowLocked` jamais appelé

La window était bien ajoutée (log `addWindow`), la surface créée, le draw cycle lancé (`commitFinishDrawingLocked: READY_TO_SHOW` en boucle), mais `performShowLocked` n'était **jamais** appelé. L'overlay restait invisible sur le cluster.

**Diagnostic** : sur ce ROM BYD, pour un process uid=2000 avec `TYPE_APPLICATION_OVERLAY` sur le display `fission` (VD XDJA avec `FLAG_PRESENTATION | FLAG_OWN_CONTENT_ONLY`), WMS ne transite jamais de `READY_TO_SHOW` vers `HAS_DRAWN`. `mPolicyVisibility` reste false (échec silencieux de la vérification de permission dans `PhoneWindowManager.checkAddPermission()`).

**Fix** : `TYPE_SYSTEM_OVERLAY` (2006) qui utilise `INTERNAL_SYSTEM_WINDOW` (permission manifeste) au lieu d'AppOps → `checkAddPermission()` retourne `ADD_OKAY` → `mPolicyVisibility=true` → `performShowLocked` appelé.

```
# v0.6.38 (TYPE_APPLICATION_OVERLAY) : jamais vu
# v0.6.39 (TYPE_SYSTEM_OVERLAY) : ✓
performShowLocked:mDrawState=HAS_DRAWN in:Window{d07f446 u0 devtools_cluster_overlay}
```

---

### Tentative 4 — VD sans flag TRUSTED → `Failed to put TaskRecord on display`

Sans `FLAG_TRUSTED` (1024), `canPlaceEntityOnDisplay()` retourne false pour Waze (`resizeableActivity=false`). Le move de tâche vers le VD est rejeté → Waze reste sur display 0.

**Fix** : daemon crée le VD avec flags=1346 (322|TRUSTED). L'app cliente n'a pas `CREATE_TRUSTED_VIRTUAL_DISPLAY`, seul uid=2000 l'a.

---

### Tentative 5 — Watchdog avec sentinelle `sClusterSc==null`

Quand on utilisait le chemin overlay (pas SC), `sClusterSc` était null immédiatement → watchdog s'arrêtait au premier tick.

**Fix** : sentinelle changée vers `sVirtualDisplay == null` — le VD est le vrai indicateur de session active.

---

## Logs de référence — session réussie (v0.6.39)

```
# Démarrage daemon
MirrorDaemon: starting (pid=10053 uid=2000)
MirrorDaemon: registered as devtools_mirror_daemon, entering Looper
MirrorDaemon: initContext: used systemMain()
MirrorDaemon: Context init OK pkg=com.android.shell uid=2000

# CLUSTER_ATTACH
MirrorDaemon: CLUSTER_ATTACH layerStack=1 1920×720
MirrorDaemon: CLUSTER_ATTACH overlay step2: resolveClusterDisplay hint=1
MirrorDaemon: CLUSTER_ATTACH overlay: displayCtx=ok resources=ok → viewCtx=ContextImpl
MirrorDaemon: CLUSTER_ATTACH overlay: OP_SYSTEM_ALERT_WINDOW → MODE_ALLOWED
MirrorDaemon: CLUSTER_ATTACH overlay params type=2006 size=1920×720 targetDisplay=1 pkg=com.android.shell
MirrorDaemon: CLUSTER_ATTACH overlay: addView type=2006 via display-scoped WM
WindowManager: debug_draw addWindow window:Window{d07f446 u0 devtools_cluster_overlay}
WindowManager: debug_draw makeSurface with name:devtools_cluster_overlay
WindowManager: debug_draw commitFinishDrawingLocked: mDrawState=READY_TO_SHOW
WindowManager: debug_draw performShowLocked:mDrawState=HAS_DRAWN in:Window{d07f446 u0 devtools_cluster_overlay}  ← CLÉ
MirrorDaemon: CLUSTER_ATTACH: VD TRUSTED flags=1346 OK
MirrorDaemon: CLUSTER_ATTACH: VD displayId=5
MirrorDaemon: CLUSTER_ATTACH: using overlay surface for VD displayId=5

# LAUNCH + WATCHDOG
MirrorDaemon: LAUNCH_AND_FORCE pkg=com.waze → display 5 taskId=43
MirrorDaemon: WATCHDOG iter=6: task=43 display=0 stack=39
MirrorDaemon: WATCHDOG: moveStackToDisplay(39,5) OK
MirrorDaemon: WATCHDOG: re-move done

# MIRROR_START (preview tablette)
MirrorDaemon: MIRROR_START layerStack=5 src=1920×720 view=1920×720
MirrorDaemon: MIRROR_START OK token=android.os.BinderProxy@e5cb636

# MIRROR_STOP
MirrorDaemon: MIRROR_STOP
MirrorDaemon: VD released
MirrorDaemon: CLUSTER_ATTACH overlay removed
```

---

## Fallback SurfaceControl

Si `tryAttachClusterOverlay()` échoue (timeout, erreur WMS), le daemon bascule sur un chemin `SurfaceControl` :

```java
// Crée un buffer layer hardware directement sur layerStack=1
SurfaceControl sc = new SurfaceControl.Builder(session)
    .setName("devtools_cluster_out")
    .setSize(1920, 720) // API 29 : setSize(); API 30+ : setBufferSize()
    .build();

new SurfaceControl.Transaction()
    .setLayerStack(sc, 1)           // rattaché au cluster display
    .setLayer(sc, Integer.MAX_VALUE - 1)  // au-dessus de tout
    .show(sc)
    .apply();

Surface outputSurface = new Surface(sc); // constructeur @hide
// → VD output vers cette surface SC
```

Ce chemin bypasse WMS entièrement (HWC direct). Toujours visible mais sans gestion de cycle de vie automatique.

---

## XDJA containerservice — Rôle et relation

`com.xdja.containerservice` (uid=1000, sharedUserId=android.uid.system) crée le display `fission` au démarrage :

```java
// AutoDisplayService.java (XDJA interne)
QtDisplayInfo info = getQtProjectionDispInfoNative(0); // JNI → Qt EGL surface
VirtualDisplay fissionVd = dm.createVirtualDisplay(
    "fission_bg_xdjaVirtualSurface",
    1920, 720, 320,
    info.qtEglSurface,  // output vers Qt renderer → hardware cluster
    11  // PRESENTATION|SECURE|OWN_CONTENT_ONLY
);
// → devient displayId=1 "fission"
```

Notre overlay (`TYPE_SYSTEM_OVERLAY` sur displayId=1) s'insère dans le pipeline de ce VD. SurfaceFlinger compose notre overlay dans le frame XDJA, Qt le rend sur le hardware.

---

## Checklist déploiement

- [ ] APK signé avec `platform.keystore`
- [ ] Manifest : `INTERNAL_SYSTEM_WINDOW`, `MANAGE_ACTIVITY_STACKS`, `ACCESS_SURFACE_FLINGER`
- [ ] `Looper.prepareMainLooper()` avant `initContext()`
- [ ] `sContext` = `createPackageContext("com.android.shell", 0)`
- [ ] `sSysContext` = `ActivityThread.systemMain().getSystemContext()`
- [ ] `createOverlayLayoutParams()` : type=2006, `lp.packageName = "com.android.shell"`
- [ ] `createAndStoreTrustedVd()` : flags=1346 (1346=322|TRUSTED) via `sContext`
- [ ] Watchdog : sentinelle = `sVirtualDisplay == null`
