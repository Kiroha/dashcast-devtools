package com.dashcast.devtools.layout;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import java.util.List;

/**
 * Canvas interactif représentant le cluster DiLink3 1920×720.
 *
 * Dessin :
 *  - Barres XDJA haut/bas en teal (non interactives)
 *  - Zone centrale noire = zone de projection
 *  - Rectangles semi-transparents colorés = zones du layout en cours d'édition
 *  - Drag pour dessiner une nouvelle zone
 *  - Long-press sur une zone pour la supprimer
 */
public class ClusterCanvasView extends View {

    // Dimensions logiques du cluster
    private static final int CW = 1920;
    private static final int CH = 720;

    // Couleurs issues de la photo du cluster
    private static final int COLOR_FRAME      = 0xFF1A1A1A;
    private static final int COLOR_XDJA_BAR   = 0xFF80DEEA; // teal clair
    private static final int COLOR_PROJ_ZONE  = 0xFF0A0A0A; // quasi-noir
    private static final int COLOR_ZONE_FILL  = 0x883949AB; // bleu indigo semi-transparent
    private static final int COLOR_ZONE_STROKE = 0xFF3949AB;
    private static final int COLOR_ZONE_LABEL = 0xFFFFFFFF;
    private static final int COLOR_DRAWING    = 0xAAF44336; // rouge pendant le dessin

    private final Paint mPaintBg     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintXdja   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintProj   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintFill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintLabel  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintDraw   = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Marges XDJA (en pixels cluster, initialisées via setMargins)
    private int mTop = 0, mBottom = 0, mLeft = 0, mRight = 0;

    // Zones du layout courant
    private List<LayoutPreset.SlotDef> mSlots;

    // Zone en cours de dessin (drag)
    private boolean mDrawing = false;
    private float mDragStartX, mDragStartY;
    private RectF mCurrentRect;

    // Callback
    public interface OnZoneDrawnListener {
        void onZoneDrawn(int x, int y, int w, int h); // coordonnées cluster
    }
    public interface OnZoneLongPressListener {
        void onZoneLongPress(int index);
    }

    private OnZoneDrawnListener    mDrawnListener;
    private OnZoneLongPressListener mLongPressListener;

    private GestureDetector mGesture;

    // Facteur d'échelle view → cluster
    private float mScaleX, mScaleY;

    public ClusterCanvasView(Context ctx) { this(ctx, null); }
    public ClusterCanvasView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        mPaintBg.setColor(COLOR_FRAME);
        mPaintBg.setStyle(Paint.Style.FILL);

        mPaintXdja.setColor(COLOR_XDJA_BAR);
        mPaintXdja.setStyle(Paint.Style.FILL);

        mPaintProj.setColor(COLOR_PROJ_ZONE);
        mPaintProj.setStyle(Paint.Style.FILL);

        mPaintFill.setColor(COLOR_ZONE_FILL);
        mPaintFill.setStyle(Paint.Style.FILL);

        mPaintStroke.setColor(COLOR_ZONE_STROKE);
        mPaintStroke.setStyle(Paint.Style.STROKE);
        mPaintStroke.setStrokeWidth(3f);

        mPaintLabel.setColor(COLOR_ZONE_LABEL);
        mPaintLabel.setTextSize(28f);
        mPaintLabel.setFakeBoldText(true);

        mPaintDraw.setColor(COLOR_DRAWING);
        mPaintDraw.setStyle(Paint.Style.FILL);

        mGesture = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public void onLongPress(MotionEvent e) {
                        if (mLongPressListener == null || mSlots == null) return;
                        int idx = hitTest(e.getX(), e.getY());
                        if (idx >= 0) mLongPressListener.onZoneLongPress(idx);
                    }
                });
    }

    public void setMargins(int top, int bottom, int left, int right) {
        mTop = top; mBottom = bottom; mLeft = left; mRight = right;
        invalidate();
    }

    public void setSlots(List<LayoutPreset.SlotDef> slots) {
        mSlots = slots;
        invalidate();
    }

    public void setOnZoneDrawnListener(OnZoneDrawnListener l) { mDrawnListener = l; }
    public void setOnZoneLongPressListener(OnZoneLongPressListener l) { mLongPressListener = l; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mScaleX = (float) w / CW;
        mScaleY = (float) h / CH;
        // Ajuste la taille du texte proportionnellement
        mPaintLabel.setTextSize(Math.max(14f, 28f * mScaleX));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        // Préserve le ratio 1920:720
        int h = (int) (w * CH / (float) CW);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        int vw = getWidth(), vh = getHeight();

        // Fond (bezel)
        c.drawRect(0, 0, vw, vh, mPaintBg);

        // Projection zone (noir)
        float px = mLeft * mScaleX;
        float py = mTop  * mScaleY;
        float pr = vw - mRight  * mScaleX;
        float pb = vh - mBottom * mScaleY;
        c.drawRect(px, py, pr, pb, mPaintProj);

        // Barres XDJA (teal)
        if (mTop    > 0) c.drawRect(0,   0,   vw,   py, mPaintXdja);
        if (mBottom > 0) c.drawRect(0,   pb,  vw,   vh, mPaintXdja);
        if (mLeft   > 0) c.drawRect(0,   py,  px,   pb, mPaintXdja);
        if (mRight  > 0) c.drawRect(pr,  py,  vw,   pb, mPaintXdja);

        // Zones du layout
        if (mSlots != null) {
            int[] colors = { 0x883949AB, 0x88388E3C, 0x88F57F17,
                             0x88AD1457, 0x880277BD, 0x884527A0 };
            for (int i = 0; i < mSlots.size(); i++) {
                LayoutPreset.SlotDef s = mSlots.get(i);
                int c1 = colors[i % colors.length];
                mPaintFill.setColor(c1);
                mPaintStroke.setColor(c1 | 0xFF000000);

                float l = s.x * mScaleX, t2 = s.y * mScaleY;
                float r = (s.x + s.w) * mScaleX, b2 = (s.y + s.h) * mScaleY;
                c.drawRect(l, t2, r, b2, mPaintFill);
                c.drawRect(l, t2, r, b2, mPaintStroke);

                // Label + dimensions
                String lbl = s.label + "\n" + s.w + "×" + s.h;
                float cx = (l + r) / 2f, cy = (t2 + b2) / 2f;
                drawCenteredText(c, lbl, cx, cy);

                // DisplayId si activé
                if (s.displayId >= 0) {
                    mPaintLabel.setTextSize(Math.max(10f, 18f * mScaleX));
                    c.drawText("VD:" + s.displayId, l + 4, t2 + 20 * mScaleY, mPaintLabel);
                    mPaintLabel.setTextSize(Math.max(14f, 28f * mScaleX));
                }
            }
        }

        // Zone en cours de dessin
        if (mDrawing && mCurrentRect != null) {
            mPaintDraw.setColor(COLOR_DRAWING);
            c.drawRect(mCurrentRect, mPaintDraw);
            Paint stroke = new Paint(mPaintDraw);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(3f);
            stroke.setColor(0xFFF44336);
            c.drawRect(mCurrentRect, stroke);
        }
    }

    private void drawCenteredText(Canvas c, String text, float cx, float cy) {
        String[] lines = text.split("\n");
        float lineH = mPaintLabel.getTextSize() * 1.3f;
        float totalH = lineH * lines.length;
        float startY = cy - totalH / 2f + mPaintLabel.getTextSize();
        for (String line : lines) {
            float tw = mPaintLabel.measureText(line);
            c.drawText(line, cx - tw / 2f, startY, mPaintLabel);
            startY += lineH;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mGesture.onTouchEvent(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (isInProjectionZone(event.getX(), event.getY())) {
                    mDrawing = true;
                    mDragStartX = event.getX();
                    mDragStartY = event.getY();
                    mCurrentRect = new RectF(mDragStartX, mDragStartY,
                                            mDragStartX, mDragStartY);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mDrawing) {
                    float x = clampX(event.getX()), y = clampY(event.getY());
                    mCurrentRect.set(Math.min(mDragStartX, x), Math.min(mDragStartY, y),
                                     Math.max(mDragStartX, x), Math.max(mDragStartY, y));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDrawing && mCurrentRect != null
                        && mCurrentRect.width() > 20 && mCurrentRect.height() > 20) {
                    // Convertit en coordonnées cluster
                    int cx = (int) (mCurrentRect.left   / mScaleX);
                    int cy = (int) (mCurrentRect.top    / mScaleY);
                    int cw = (int) (mCurrentRect.width() / mScaleX);
                    int ch = (int) (mCurrentRect.height()/ mScaleY);
                    if (mDrawnListener != null) mDrawnListener.onZoneDrawn(cx, cy, cw, ch);
                }
                mDrawing = false;
                mCurrentRect = null;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private boolean isInProjectionZone(float vx, float vy) {
        return vx >= mLeft * mScaleX && vx <= getWidth()  - mRight  * mScaleX
            && vy >= mTop  * mScaleY && vy <= getHeight() - mBottom * mScaleY;
    }

    private float clampX(float x) {
        return Math.max(mLeft * mScaleX, Math.min(x, getWidth() - mRight * mScaleX));
    }
    private float clampY(float y) {
        return Math.max(mTop * mScaleY, Math.min(y, getHeight() - mBottom * mScaleY));
    }

    private int hitTest(float vx, float vy) {
        if (mSlots == null) return -1;
        for (int i = mSlots.size() - 1; i >= 0; i--) {
            LayoutPreset.SlotDef s = mSlots.get(i);
            float l = s.x * mScaleX, t = s.y * mScaleY;
            float r = (s.x + s.w) * mScaleX, b = (s.y + s.h) * mScaleY;
            if (vx >= l && vx <= r && vy >= t && vy <= b) return i;
        }
        return -1;
    }
}
