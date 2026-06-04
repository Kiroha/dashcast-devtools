# Cluster Overlay — BYD DiLink3 (Android 10 / API 29)

Référence technique : comment créer un overlay fenêtré sur le display cluster physique
depuis un process `app_process64` tournant en uid=2000 (shell).

---

## Environnement cible

| Propriété | Valeur |
|---|---|
| Modèle | BYD AUTO DiLink 3.0 |
| Android | 10 (API 29) |
| Build | `QKQ1.210910.001 / eng.build.20260204` |
| Cluster display | displayId=1, layerStack=1, 1920×720, nom=`fission` |
| Process daemon | `app_process64`, uid=2000 (shell) |
| Signature APK | `platform.keystore` (androiddebugkey) |

---

## Pourquoi un overlay sur le cluster ?

Le daemon MirrorDaemon doit obtenir une **Surface** sur le display physique du cluster
(displayId=1, layerStack=1) pour y rattacher un `VirtualDisplay`. Le contenu du VD
(l'app projetée, ex. Waze) est alors composité sur le cluster via SurfaceFlinger.

Deux chemins existent :

| Chemin | Mécanisme | Statut |
|---|---|---|
| **Overlay SurfaceView** | Window managée par WMS sur le cluster display | ✅ Fonctionne (v0.6.33+) |
| **SurfaceControl buffer layer** | Layer hardware compositor direct | ✅ Fonctionne (fallback) |

L'overlay SurfaceView est préféré car WMS gère le cycle de vie de la surface.
Le chemin SurfaceControl est le fallback si l'overlay échoue.

---

## Ce qui a échoué avant (historique)

### Tentative 1 — `new SurfaceView(sContext)`
`sContext` est un `createPackageContext("com.android.shell", 0)` en mode `systemMain()`.
Ce contexte n'a **pas de Resources chargées**. `SurfaceView(context)` appelle
`context.getResources()` → null → NPE.

```
CLUSTER_ATTACH overlay failed: NullPointerException:
  Attempt to invoke virtual method 'Resources Context.getResources()' on a null object reference
```

### Tentative 2 — `new SurfaceView(sSysContext)`
`sSysContext` = `ActivityThread.getSystemContext()`. A des Resources framework
mais `WindowManagerGlobal.getWindowSession()` échoue car `resolveClusterDisplay()`
utilisait aussi `sContext` (null Resources) pour le `DisplayManager`.

```
CLUSTER_ATTACH overlay failed: NullPointerException (même message)
```
→ NPE dans `DisplayManager.getOrCreateDisplayLocked()` qui appelle `mContext.getResources()`.

### Tentative 3 — `resolveClusterDisplay` corrigé + `WindowManagerGlobal.addView()`
`DisplayManager` passé à `sSysContext`. Mais `TYPE_APPLICATION_OVERLAY` (2038)
nécessite `SYSTEM_ALERT_WINDOW` que `com.android.shell` n'a pas dans son manifest.
→ `BadTokenException` ou timeout latch.

### Tentative 4 — `createDisplayContext` + display-scoped WM (v0.6.31)
`createDisplayContext(targetDisplay)` donne un contexte configuré pour le cluster.
Le Resources est OK. `AppOps.setMode()` pas encore tenté.
→ Latch timeout (WM rejetait quand même la fenêtre sans la permission).

---

## Ce qui fonctionne (v0.6.33+)

### Ingrédients clés

**1. `sSysContext` comme base**
`ActivityThread.systemMain().getSystemContext()` — a les Resources framework.

**2. `createDisplayContext(targetDisplay)` pour le contexte display**
Crée un `ContextImpl` configuré avec les métriques du cluster display.
Sur ce ROM BYD, le Resources est non-null pour displayId=1.

**3. Grant `OP_SYSTEM_ALERT_WINDOW` via AppOps avant `addView()`**
Shell uid=2000 est un process privilégié sur ce ROM. `AppOpsManager.setMode()` est
accessible et accorde la permission en runtime :

```java
Object appOps = sContext.getSystemService(Context.APP_OPS_SERVICE);
Method setMode = appOps.getClass().getMethod(
        "setMode", int.class, int.class, String.class, int.class);
setMode.setAccessible(true);
// OP_SYSTEM_ALERT_WINDOW=24, MODE_ALLOWED=0
setMode.invoke(appOps, 24, android.os.Process.myUid(), sContext.getPackageName(), 0);
```

**4. `displayCtx.getSystemService(WindowManager.class).addView()`**
Le WindowManager issu de `createDisplayContext(clusterDisplay)` est lié à ce display.
`addView()` route la vue vers le cluster sans passer par `WindowManagerGlobal.addView()`
avec le paramètre `Display` (qui causait des problèmes en mode systemMain).

**5. `SurfaceHolder.Callback` sur le thread principal**
Le Runnable est posté sur `Looper.getMainLooper()` (préparé par `Looper.prepareMainLooper()`
en début de `main()`). Les callbacks SurfaceHolder (`surfaceCreated`, `surfaceChanged`)
lâchent le `CountDownLatch` quand la surface est valide.

**6. `holder.setFixedSize(w, h)` avant `addView()`**
Force la taille du buffer à 1920×720 indépendamment de la taille de la fenêtre.

---

## Code complet (simplifié)

```java
// Dans handleClusterAttach(), appelé depuis le Binder thread.
// targetDisplay = cluster display (displayId=1)

CountDownLatch latch = new CountDownLatch(1);
AtomicReference<Surface> surfaceRef = new AtomicReference<>();
AtomicReference<RuntimeException> errorRef = new AtomicReference<>();

Runnable attach = () -> {
    try {
        // 1. Contexte avec Resources valides
        Context base = (sSysContext != null) ? sSysContext : sContext;
        Context displayCtx = base.createDisplayContext(targetDisplay);
        boolean hasResources = displayCtx != null && displayCtx.getResources() != null;
        Context viewCtx = hasResources ? displayCtx : base;

        // 2. Grant SYSTEM_ALERT_WINDOW en runtime
        try {
            Object appOps = sContext.getSystemService(Context.APP_OPS_SERVICE);
            Method setMode = appOps.getClass().getMethod(
                    "setMode", int.class, int.class, String.class, int.class);
            setMode.setAccessible(true);
            setMode.invoke(appOps, 24 /*OP_SYSTEM_ALERT_WINDOW*/,
                    android.os.Process.myUid(), sContext.getPackageName(), 0);
        } catch (Exception ignored) { /* swallow — fallback to SC path */ }

        // 3. SurfaceView avec taille fixe
        SurfaceView surfaceView = new SurfaceView(viewCtx);
        surfaceView.getHolder().setFixedSize(w, h);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                Surface s = h.getSurface();
                if (s != null && s.isValid()) {
                    surfaceRef.compareAndSet(null, s);
                    latch.countDown();
                }
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int ww, int hh) {
                Surface s = h.getSurface();
                if (s != null && s.isValid()) {
                    surfaceRef.compareAndSet(null, s);
                    latch.countDown();
                }
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {}
        });

        // 4. LayoutParams TYPE_APPLICATION_OVERLAY
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,  // 2038
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.OPAQUE);
        lp.setTitle("devtools_cluster_overlay");
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lp.x = 0; lp.y = 0;

        // 5. addView via le WM lié au cluster display
        WindowManager wm = (displayCtx != null)
                ? displayCtx.getSystemService(WindowManager.class) : null;
        if (wm != null) {
            wm.addView(surfaceView, lp);
        } else {
            // Fallback: WindowManagerGlobal.addView(view, params, display, null)
            Class<?> wmgCls = Class.forName("android.view.WindowManagerGlobal");
            Object wmg = wmgCls.getMethod("getInstance").invoke(null);
            Method wmgAdd = wmgCls.getDeclaredMethod("addView",
                    View.class, ViewGroup.LayoutParams.class, Display.class, Window.class);
            wmgAdd.setAccessible(true);
            wmgAdd.invoke(wmg, surfaceView, lp, targetDisplay, null);
            wm = sContext.getSystemService(WindowManager.class);
        }
        sClusterOverlayWindowManager = wm;
        sClusterOverlayView = surfaceView;

    } catch (Exception e) {
        errorRef.set(new RuntimeException(e));
        latch.countDown();
    }
};

// Exécuter sur le main looper (Looper.prepareMainLooper() doit être appelé en amont)
if (Looper.myLooper() == Looper.getMainLooper()) {
    attach.run();
} else {
    new android.os.Handler(Looper.getMainLooper()).post(attach);
}

// Attente max 2s
if (!latch.await(2, TimeUnit.SECONDS)) { /* timeout → fallback SC */ }
Surface surface = surfaceRef.get();
// surface est prête → passer à createVirtualDisplay(surface)
```

---

## Paramètres critiques

| Paramètre | Valeur | Raison |
|---|---|---|
| Window type | `TYPE_APPLICATION_OVERLAY` (2038) | Seul type autorisable avec AppOps grant |
| AppOps op | 24 (`OP_SYSTEM_ALERT_WINDOW`) | Nécessaire pour TYPE_APPLICATION_OVERLAY |
| AppOps mode | 0 (`MODE_ALLOWED`) | Accordé en runtime via setMode() |
| Format pixel | `OPAQUE` | Contenu VD = opaque, pas de blend inutile |
| FLAG_NOT_TOUCHABLE | oui | Overlay de projection, pas d'input |
| `setFixedSize(1920, 720)` | oui | Force le buffer à la résolution cluster |
| Looper | `getMainLooper()` | View system requiert le main thread |
| Timeout latch | 2 secondes | Surface généralement disponible en < 100ms |

---

## Prérequis daemon

| Prérequis | Détail |
|---|---|
| `Looper.prepareMainLooper()` | Appelé **avant** `registerService()` dans `main()` |
| `initContext()` | `sSysContext` + `sContext` initialisés avant toute transaction |
| uid=2000 (shell) | Permet `AppOpsManager.setMode()` sur ce ROM BYD |
| Signature platform | APK signé `platform.keystore` (nécessaire pour les hidden APIs) |

---

## Fallback SurfaceControl

Si l'overlay échoue (timeout, erreur WMS), le chemin SurfaceControl crée un
buffer layer hardware directement sur le layerStack=1 :

```java
// SurfaceControl.Builder(SurfaceSession) — API 29
// setSize(1920, 720) ou setBufferSize(1920, 720) selon API
// Transaction.setLayerStack(sc, 1)
// Transaction.setLayer(sc, Integer.MAX_VALUE - 1)  — au-dessus de tout
// Transaction.show(sc)
// Surface(SurfaceControl)  — constructeur @hide
```

Ce chemin ne nécessite aucune permission Window et fonctionne toujours,
mais n'est pas géré par WMS (pas de cycle de vie automatique).

---

## VirtualDisplay TRUSTED

Une fois la surface obtenue (overlay ou SC), le daemon crée le VD avec le flag TRUSTED :

```java
DisplayManager dm = sContext.getSystemService(DisplayManager.class);
// sContext = createPackageContext("com.android.shell") — uid=2000 owns it
// FLAG_TRUSTED = 0x400 = 1024 — nécessite CREATE_TRUSTED_VIRTUAL_DISPLAY (uid=2000 ✓)
VirtualDisplay vd = dm.createVirtualDisplay(
        "devtools_projection_vd", 1920, 720, 160,
        surface,
        322 | 1024);   // PRESENTATION|SUPPORTS_TOUCH|DESTROY_CONTENT_ON_REMOVAL|TRUSTED
```

Sans `FLAG_TRUSTED`, `ActivityTaskManager.canPlaceEntityOnDisplay()` retourne false
pour les activités non-resizable comme Waze (`android:resizeableActivity="false"`),
et le move de tâche vers ce display est rejeté silencieusement.

Le client app n'a **pas** `CREATE_TRUSTED_VIRTUAL_DISPLAY` → le daemon doit créer le VD.

---

## Séquence complète de projection (v0.6.36+)

```
Client                              Daemon (uid=2000)
  │                                     │
  ├── CLUSTER_ATTACH ──────────────────►│
  │   layerStack=1, w=1920, h=720       │ 1. tryAttachClusterOverlay()
  │                                     │    → SurfaceView sur displayId=1 ✓
  │                                     │ 2. createAndStoreTrustedVd(surface)
  │                                     │    → VD TRUSTED, displayId=2
  │◄── surface + displayId=2 ───────────┤
  │                                     │
  ├── LAUNCH_AND_FORCE ────────────────►│
  │   pkg=com.waze, displayId=2         │ 3. am start -n com.waze/.FreeMapAppActivity
  │                                     │ 4. getTasks() → taskId=31
  │                                     │ 5. setTaskWindowingMode(FREEFORM)
  │                                     │ 6. moveStackToDisplay(stack, 2) ✓
  │                                     │    [FreeMapAppActivity redirige vers display 0]
  │                                     │ 7. Watchdog démarre (sVirtualDisplay != null)
  │                                     │    iter=6 (T=3s): détecte task sur display 0
  │                                     │    → re-move + FREEFORM + setFocusedTask ✓
  │◄── OK taskId=31 ────────────────────┤
  │                                     │
  ├── MIRROR_START ────────────────────►│ 8. SurfaceControl.setDisplaySurface(
  │   layerStack=2, tabletSurface       │       layerStack=2, tabletSurface)
  │◄── OK ──────────────────────────────┤    → preview sur tablette
  │                                     │
  ├── MIRROR_STOP ─────────────────────►│ 9. Libère : mirror token + SC + overlay + VD
  │◄── OK ──────────────────────────────┤
```

---

## Logs de référence (session réussie v0.6.30)

```
CLUSTER_ATTACH overlay: OP_SYSTEM_ALERT_WINDOW → MODE_ALLOWED
CLUSTER_ATTACH overlay host added on displayId=1
CLUSTER_ATTACH: VD TRUSTED flags=1346 OK
CLUSTER_ATTACH: VD displayId=2
LAUNCH_AND_FORCE pkg=com.waze → display 2 taskId=15
moveStackToDisplay(stackId=12, 2) OK
setTaskWindowingMode(FREEFORM) OK
WATCHDOG iter=6: task=15 display=0 stack=13
WATCHDOG: moveStackToDisplay(13,2) OK
WATCHDOG: re-move done
MIRROR_START OK token=android.os.BinderProxy@...
```
