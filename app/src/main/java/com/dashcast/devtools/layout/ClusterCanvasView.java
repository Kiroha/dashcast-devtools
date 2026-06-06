package com.dashcast.devtools.layout;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.dashcast.devtools.R;

import java.util.List;

/**
 * Canvas interactif représentant le cluster DiLink3 1920×720.
 * Fond = photo réelle du cluster (cluster_bg.png).
 * Superposition semi-transparente sur les zones XDJA (marges haut/bas).
 *
 * Interactions :
 *   - Drag sur zone vide  → dessine une nouvelle zone
 *   - Drag sur coin       → redimensionne la zone
 *   - Drag au centre      → déplace la zone
 *   - Long-press          → supprime la zone
 */
public class ClusterCanvasView extends View {

    private static final int CW = 1920;
    private static final int CH = 720;

    private static final int COLOR_XDJA_OVERLAY = 0x99000000;
    private static final int COLOR_ZONE_STROKE  = 0xFFFFFFFF;
    private static final int COLOR_ZONE_LABEL   = 0xFFFFFFFF;
    private static final int COLOR_DRAWING      = 0xAAF44336;

    // Rayon en pixels vue pour détecter un coin (resize handle)
    private static final float HANDLE_RADIUS = 32f;

    // Seuil de snap en coordonnées cluster
    private static final float SNAP_THRESHOLD = 30f;

    private final Paint mPaintXdja   = new Paint();
    private final Paint mPaintFill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintLabel  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintDraw   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintHandle = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap mBg;

    private int mTop = 0, mBottom = 0, mLeft = 0, mRight = 0;
    private List<LayoutPreset.SlotDef> mSlots;

    // ── Mode de drag ──────────────────────────────────────────────────────────

    private enum DragMode { NONE, DRAW, MOVE, RESIZE }
    private DragMode mDragMode  = DragMode.NONE;
    private int      mDragIdx   = -1;   // index du slot en cours de manipulation

    // DRAW
    private float mDragStartX, mDragStartY;
    private RectF mCurrentRect;

    // MOVE : offset touch → coin TL du slot (en pixels vue)
    private float mMoveOffsetX, mMoveOffsetY;

    // RESIZE : quel coin ? 0=TL 1=TR 2=BR 3=BL
    private int mResizeCorner = -1;

    // ── Listeners ─────────────────────────────────────────────────────────────

    public interface OnZoneDrawnListener     { void onZoneDrawn(int x, int y, int w, int h); }
    public interface OnZoneLongPressListener { void onZoneLongPress(int index); }

    private OnZoneDrawnListener     mDrawnListener;
    private OnZoneLongPressListener mLongPressListener;
    private GestureDetector         mGesture;
    private float                   mScaleX, mScaleY;

    // Couleurs par slot
    private static final int[] ZONE_COLORS = {
        0x883949AB, 0x88388E3C, 0x88F57F17,
        0x88AD1457, 0x880277BD, 0x884527A0
    };

    public ClusterCanvasView(Context ctx)                  { this(ctx, null); }
    public ClusterCanvasView(Context ctx, AttributeSet at) { super(ctx, at);  init(); }

    private void init() {
        mPaintXdja.setColor(COLOR_XDJA_OVERLAY);
        mPaintXdja.setStyle(Paint.Style.FILL);

        mPaintStroke.setColor(COLOR_ZONE_STROKE);
        mPaintStroke.setStyle(Paint.Style.STROKE);
        mPaintStroke.setStrokeWidth(3f);

        mPaintLabel.setColor(COLOR_ZONE_LABEL);
        mPaintLabel.setFakeBoldText(true);
        mPaintLabel.setShadowLayer(2f, 1f, 1f, Color.BLACK);

        mPaintDraw.setColor(COLOR_DRAWING);
        mPaintDraw.setStyle(Paint.Style.FILL);

        mPaintHandle.setColor(0xFFFFFFFF);
        mPaintHandle.setStyle(Paint.Style.FILL);

        mGesture = new GestureDetector(getContext(),
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public void onLongPress(MotionEvent e) {
                    if (mDragMode != DragMode.NONE) return; // ignore si on est en drag
                    if (mLongPressListener == null || mSlots == null) return;
                    int idx = hitTest(e.getX(), e.getY());
                    if (idx >= 0) mLongPressListener.onZoneLongPress(idx);
                }
            });

        try {
            mBg = BitmapFactory.decodeResource(getResources(), R.drawable.cluster_bg);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mBg != null && !mBg.isRecycled()) {
            mBg.recycle();
            mBg = null;
        }
    }

    public void setMargins(int top, int bottom, int left, int right) {
        mTop = top; mBottom = bottom; mLeft = left; mRight = right;
        invalidate();
    }
    public void setSlots(List<LayoutPreset.SlotDef> slots) { mSlots = slots; invalidate(); }
    public void setOnZoneDrawnListener(OnZoneDrawnListener l)         { mDrawnListener = l; }
    public void setOnZoneLongPressListener(OnZoneLongPressListener l) { mLongPressListener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (w * CH / (float) CW));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mScaleX = (float) w / CW;
        mScaleY = (float) h / CH;
        mPaintLabel.setTextSize(Math.max(14f, 26f * mScaleX));
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas c) {
        int vw = getWidth(), vh = getHeight();

        if (mBg != null) {
            c.drawBitmap(mBg, null, new RectF(0, 0, vw, vh), null);
        } else {
            c.drawColor(0xFF0A0A0A);
        }

        // Overlay zones XDJA non-projetables
        float px = mLeft   * mScaleX;
        float py = mTop    * mScaleY;
        float pr = vw - mRight  * mScaleX;
        float pb = vh - mBottom * mScaleY;
        if (mTop    > 0) c.drawRect(0,  0,  vw, py, mPaintXdja);
        if (mBottom > 0) c.drawRect(0,  pb, vw, vh, mPaintXdja);
        if (mLeft   > 0) c.drawRect(0,  py, px, pb, mPaintXdja);
        if (mRight  > 0) c.drawRect(pr, py, vw, pb, mPaintXdja);

        List<LayoutPreset.SlotDef> slots = mSlots;
        if (slots != null && !slots.isEmpty()) {
            for (int i = 0; i < slots.size(); i++) {
                LayoutPreset.SlotDef s = slots.get(i);
                int col = ZONE_COLORS[i % ZONE_COLORS.length];
                mPaintFill.setColor(col);
                mPaintStroke.setColor(col | 0xFF000000);

                float l = s.x * mScaleX, t = s.y * mScaleY;
                float r = (s.x + s.w) * mScaleX, b = (s.y + s.h) * mScaleY;
                c.drawRect(l, t, r, b, mPaintFill);
                c.drawRect(l, t, r, b, mPaintStroke);

                // Poignées de redimensionnement aux 4 coins
                float hr = Math.min(HANDLE_RADIUS * 0.5f, 12f);
                c.drawCircle(l, t, hr, mPaintHandle);
                c.drawCircle(r, t, hr, mPaintHandle);
                c.drawCircle(r, b, hr, mPaintHandle);
                c.drawCircle(l, b, hr, mPaintHandle);

                String lbl = s.label + "\n" + s.w + "×" + s.h;
                if (s.displayId >= 0) lbl += "\nVD:" + s.displayId;
                drawCenteredText(c, lbl, (l + r) / 2f, (t + b) / 2f);
            }
        }

        // Zone en cours de dessin
        if (mDragMode == DragMode.DRAW && mCurrentRect != null) {
            mPaintDraw.setColor(COLOR_DRAWING);
            c.drawRect(mCurrentRect, mPaintDraw);
            Paint str = new Paint(mPaintStroke);
            str.setColor(0xFFF44336);
            str.setStrokeWidth(3f);
            c.drawRect(mCurrentRect, str);
            int cw = (int) (mCurrentRect.width()  / mScaleX);
            int ch = (int) (mCurrentRect.height() / mScaleY);
            drawCenteredText(c, cw + "×" + ch,
                    mCurrentRect.centerX(), mCurrentRect.centerY());
        }
    }

    private void drawCenteredText(Canvas c, String text, float cx, float cy) {
        String[] lines = text.split("\n");
        float lh = mPaintLabel.getTextSize() * 1.3f;
        float startY = cy - lh * (lines.length - 1) / 2f;
        for (String line : lines) {
            float tw = mPaintLabel.measureText(line);
            c.drawText(line, cx - tw / 2f, startY, mPaintLabel);
            startY += lh;
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mGesture.onTouchEvent(event);

        float vx = event.getX(), vy = event.getY();

        switch (event.getAction()) {

            case MotionEvent.ACTION_DOWN: {
                // Priorité 1 : coin d'une zone → resize
                int cornerIdx = hitCorner(vx, vy);
                if (cornerIdx >= 0) {
                    mDragMode = DragMode.RESIZE;
                    mDragIdx  = cornerIdx;
                    return true;
                }
                // Priorité 2 : intérieur d'une zone → move
                int moveIdx = hitTest(vx, vy);
                if (moveIdx >= 0) {
                    mDragMode    = DragMode.MOVE;
                    mDragIdx     = moveIdx;
                    LayoutPreset.SlotDef s = mSlots.get(moveIdx);
                    mMoveOffsetX = vx - s.x * mScaleX;
                    mMoveOffsetY = vy - s.y * mScaleY;
                    return true;
                }
                // Priorité 3 : zone vide → dessine
                if (isInProjectionZone(vx, vy)) {
                    mDragMode = DragMode.DRAW;
                    // Snap le point de départ
                    mDragStartX = snapX(vx / mScaleX, -1) * mScaleX;
                    mDragStartY = snapY(vy / mScaleY, -1) * mScaleY;
                    mCurrentRect = new RectF(mDragStartX, mDragStartY, mDragStartX, mDragStartY);
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mDragMode == DragMode.MOVE && mDragIdx >= 0
                        && mSlots != null && mDragIdx < mSlots.size()) {
                    LayoutPreset.SlotDef s = mSlots.get(mDragIdx);
                    float nx = (vx - mMoveOffsetX) / mScaleX;
                    float ny = (vy - mMoveOffsetY) / mScaleY;
                    // Clamp dans la zone de projection
                    nx = Math.max(mLeft, Math.min(nx, CW - mRight  - s.w));
                    ny = Math.max(mTop,  Math.min(ny, CH - mBottom - s.h));
                    // Snap bord à bord (les deux bords concourent)
                    nx = snapEdgeX(nx, nx + s.w, mDragIdx);
                    ny = snapEdgeY(ny, ny + s.h, mDragIdx);
                    // Re-clamp après snap
                    nx = Math.max(mLeft, Math.min(nx, CW - mRight  - s.w));
                    ny = Math.max(mTop,  Math.min(ny, CH - mBottom - s.h));
                    s.x = (int) nx; s.y = (int) ny;
                    invalidate();
                    return true;
                }

                if (mDragMode == DragMode.RESIZE && mDragIdx >= 0
                        && mSlots != null && mDragIdx < mSlots.size()) {
                    LayoutPreset.SlotDef s = mSlots.get(mDragIdx);
                    float cx = clampX(vx) / mScaleX;
                    float cy = clampY(vy) / mScaleY;
                    // Snap le coin en cours de déplacement
                    cx = snapX(cx, mDragIdx);
                    cy = snapY(cy, mDragIdx);
                    int r = s.x + s.w, b = s.y + s.h;
                    switch (mResizeCorner) {
                        case 0: // TL : déplace le coin supérieur gauche
                            s.w = Math.max(40, r - (int) cx);
                            s.h = Math.max(20, b - (int) cy);
                            s.x = r - s.w; s.y = b - s.h;
                            break;
                        case 1: // TR : déplace le coin supérieur droit
                            s.w = Math.max(40, (int) cx - s.x);
                            s.h = Math.max(20, b - (int) cy);
                            s.y = b - s.h;
                            break;
                        case 2: // BR : déplace le coin inférieur droit
                            s.w = Math.max(40, (int) cx - s.x);
                            s.h = Math.max(20, (int) cy - s.y);
                            break;
                        case 3: // BL : déplace le coin inférieur gauche
                            s.w = Math.max(40, r - (int) cx);
                            s.h = Math.max(20, (int) cy - s.y);
                            s.x = r - s.w;
                            break;
                    }
                    invalidate();
                    return true;
                }

                if (mDragMode == DragMode.DRAW && mCurrentRect != null) {
                    float x = clampX(vx), y = clampY(vy);
                    // Snap le coin mobile (en cluster, puis retour en vue)
                    float xSnap = snapX(x / mScaleX, -1) * mScaleX;
                    float ySnap = snapY(y / mScaleY, -1) * mScaleY;
                    mCurrentRect.set(Math.min(mDragStartX, xSnap), Math.min(mDragStartY, ySnap),
                                     Math.max(mDragStartX, xSnap), Math.max(mDragStartY, ySnap));
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mDragMode == DragMode.DRAW && mCurrentRect != null
                        && mCurrentRect.width() > 20 && mCurrentRect.height() > 20) {
                    int cx = (int) (mCurrentRect.left   / mScaleX);
                    int cy = (int) (mCurrentRect.top    / mScaleY);
                    int cw = (int) (mCurrentRect.width() / mScaleX);
                    int ch = (int) (mCurrentRect.height() / mScaleY);
                    if (mDrawnListener != null) mDrawnListener.onZoneDrawn(cx, cy, cw, ch);
                }
                mDragMode = DragMode.NONE;
                mDragIdx  = -1;
                mCurrentRect = null;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Retourne l'index du slot dont un coin est proche de (vx, vy), ou -1. */
    private int hitCorner(float vx, float vy) {
        List<LayoutPreset.SlotDef> slots = mSlots;
        if (slots == null) return -1;
        for (int i = slots.size() - 1; i >= 0; i--) {
            LayoutPreset.SlotDef s = slots.get(i);
            float sl = s.x * mScaleX, st = s.y * mScaleY;
            float sr = (s.x + s.w) * mScaleX, sb = (s.y + s.h) * mScaleY;
            if (near(vx, vy, sl, st)) { mResizeCorner = 0; return i; } // TL
            if (near(vx, vy, sr, st)) { mResizeCorner = 1; return i; } // TR
            if (near(vx, vy, sr, sb)) { mResizeCorner = 2; return i; } // BR
            if (near(vx, vy, sl, sb)) { mResizeCorner = 3; return i; } // BL
        }
        return -1;
    }

    private boolean near(float vx, float vy, float px, float py) {
        return Math.abs(vx - px) < HANDLE_RADIUS && Math.abs(vy - py) < HANDLE_RADIUS;
    }

    private boolean isInProjectionZone(float vx, float vy) {
        return vx >= mLeft * mScaleX && vx <= getWidth()  - mRight  * mScaleX
            && vy >= mTop  * mScaleY && vy <= getHeight() - mBottom * mScaleY;
    }

    private float clampX(float x) {
        return Math.max(mLeft * mScaleX, Math.min(x, getWidth()  - mRight  * mScaleX));
    }
    private float clampY(float y) {
        return Math.max(mTop  * mScaleY, Math.min(y, getHeight() - mBottom * mScaleY));
    }

    /** Retourne l'index du slot qui contient (vx, vy), ou -1 (priorité au dernier dessiné). */
    private int hitTest(float vx, float vy) {
        List<LayoutPreset.SlotDef> slots = mSlots;
        if (slots == null) return -1;
        for (int i = slots.size() - 1; i >= 0; i--) {
            LayoutPreset.SlotDef s = slots.get(i);
            if (vx >= s.x * mScaleX && vx <= (s.x + s.w) * mScaleX
             && vy >= s.y * mScaleY && vy <= (s.y + s.h) * mScaleY) return i;
        }
        return -1;
    }

    // ── Snap bord à bord ─────────────────────────────────────────────────────

    /** Snap x (cluster) vers le bord de projection ou d'une autre zone le plus proche. */
    private float snapX(float x, int excludeIdx) {
        float best = x, bestDist = SNAP_THRESHOLD;
        for (float c : new float[]{ mLeft, CW - mRight }) {
            float d = Math.abs(x - c);
            if (d < bestDist) { bestDist = d; best = c; }
        }
        List<LayoutPreset.SlotDef> slots = mSlots;
        if (slots != null) {
            for (int i = 0; i < slots.size(); i++) {
                if (i == excludeIdx) continue;
                LayoutPreset.SlotDef s = slots.get(i);
                for (float c : new float[]{ s.x, s.x + s.w }) {
                    float d = Math.abs(x - c);
                    if (d < bestDist) { bestDist = d; best = c; }
                }
            }
        }
        return best;
    }

    private float snapY(float y, int excludeIdx) {
        float best = y, bestDist = SNAP_THRESHOLD;
        for (float c : new float[]{ mTop, CH - mBottom }) {
            float d = Math.abs(y - c);
            if (d < bestDist) { bestDist = d; best = c; }
        }
        List<LayoutPreset.SlotDef> slots = mSlots;
        if (slots != null) {
            for (int i = 0; i < slots.size(); i++) {
                if (i == excludeIdx) continue;
                LayoutPreset.SlotDef s = slots.get(i);
                for (float c : new float[]{ s.y, s.y + s.h }) {
                    float d = Math.abs(y - c);
                    if (d < bestDist) { bestDist = d; best = c; }
                }
            }
        }
        return best;
    }

    /**
     * Pour MOVE : choisit le snap qui déplace le moins la zone,
     * en considérant le bord avant (a) et le bord arrière (b = a + taille).
     * Retourne la nouvelle valeur du bord avant.
     */
    private float snapEdgeX(float a, float b, int excludeIdx) {
        float offA = snapX(a, excludeIdx) - a;
        float offB = snapX(b, excludeIdx) - b;
        return Math.abs(offA) <= Math.abs(offB) ? a + offA : a + offB;
    }

    private float snapEdgeY(float a, float b, int excludeIdx) {
        float offA = snapY(a, excludeIdx) - a;
        float offB = snapY(b, excludeIdx) - b;
        return Math.abs(offA) <= Math.abs(offB) ? a + offA : a + offB;
    }
}
