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
 * Drag pour dessiner des zones de projection. Long-press pour supprimer.
 */
public class ClusterCanvasView extends View {

    private static final int CW = 1920;
    private static final int CH = 720;

    private static final int COLOR_XDJA_OVERLAY = 0x99000000; // noir semi-transparent sur les barres XDJA
    private static final int COLOR_ZONE_STROKE  = 0xFFFFFFFF;
    private static final int COLOR_ZONE_LABEL   = 0xFFFFFFFF;
    private static final int COLOR_DRAWING      = 0xAAF44336;

    private final Paint mPaintXdja   = new Paint();
    private final Paint mPaintFill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintLabel  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintDraw   = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap mBg;

    private int mTop = 0, mBottom = 0, mLeft = 0, mRight = 0;
    private List<LayoutPreset.SlotDef> mSlots;

    private boolean mDrawing;
    private float   mDragStartX, mDragStartY;
    private RectF   mCurrentRect;

    public interface OnZoneDrawnListener    { void onZoneDrawn(int x, int y, int w, int h); }
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

        mGesture = new GestureDetector(getContext(),
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public void onLongPress(MotionEvent e) {
                    if (mLongPressListener == null || mSlots == null) return;
                    int idx = hitTest(e.getX(), e.getY());
                    if (idx >= 0) mLongPressListener.onZoneLongPress(idx);
                }
            });

        // Charge la photo du cluster
        try {
            mBg = BitmapFactory.decodeResource(getResources(), R.drawable.cluster_bg);
        } catch (Exception ignored) {}
    }

    public void setMargins(int top, int bottom, int left, int right) {
        mTop = top; mBottom = bottom; mLeft = left; mRight = right;
        invalidate();
    }
    public void setSlots(List<LayoutPreset.SlotDef> slots) { mSlots = slots; invalidate(); }
    public void setOnZoneDrawnListener(OnZoneDrawnListener l)     { mDrawnListener = l; }
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

    @Override
    protected void onDraw(Canvas c) {
        int vw = getWidth(), vh = getHeight();

        // Photo du cluster en fond
        if (mBg != null) {
            c.drawBitmap(mBg, null, new RectF(0, 0, vw, vh), null);
        } else {
            // Fallback si pas de photo
            c.drawColor(0xFF0A0A0A);
        }

        // Overlay semi-transparent sur les zones XDJA non-projetables
        float px = mLeft   * mScaleX;
        float py = mTop    * mScaleY;
        float pr = vw - mRight  * mScaleX;
        float pb = vh - mBottom * mScaleY;
        if (mTop    > 0) c.drawRect(0,  0,  vw, py, mPaintXdja);
        if (mBottom > 0) c.drawRect(0,  pb, vw, vh, mPaintXdja);
        if (mLeft   > 0) c.drawRect(0,  py, px, pb, mPaintXdja);
        if (mRight  > 0) c.drawRect(pr, py, vw, pb, mPaintXdja);

        // Zones du layout
        if (mSlots != null) {
            for (int i = 0; i < mSlots.size(); i++) {
                LayoutPreset.SlotDef s = mSlots.get(i);
                int col = ZONE_COLORS[i % ZONE_COLORS.length];
                mPaintFill.setColor(col);
                mPaintStroke.setColor(col | 0xFF000000);

                float l = s.x * mScaleX, t = s.y * mScaleY;
                float r = (s.x + s.w) * mScaleX, b = (s.y + s.h) * mScaleY;
                c.drawRect(l, t, r, b, mPaintFill);
                c.drawRect(l, t, r, b, mPaintStroke);

                String lbl = s.label + "\n" + s.w + "×" + s.h;
                if (s.displayId >= 0) lbl += "\nVD:" + s.displayId;
                drawCenteredText(c, lbl, (l + r) / 2f, (t + b) / 2f);
            }
        }

        // Zone en cours de dessin
        if (mDrawing && mCurrentRect != null) {
            mPaintDraw.setColor(COLOR_DRAWING);
            c.drawRect(mCurrentRect, mPaintDraw);
            Paint str = new Paint(mPaintStroke);
            str.setColor(0xFFF44336);
            str.setStrokeWidth(3f);
            c.drawRect(mCurrentRect, str);
            // Affiche les dimensions en cours
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mGesture.onTouchEvent(event);
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (isInProjectionZone(event.getX(), event.getY())) {
                    mDrawing = true;
                    mDragStartX = event.getX(); mDragStartY = event.getY();
                    mCurrentRect = new RectF(mDragStartX, mDragStartY, mDragStartX, mDragStartY);
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
                    int cx = (int) (mCurrentRect.left   / mScaleX);
                    int cy = (int) (mCurrentRect.top    / mScaleY);
                    int cw = (int) (mCurrentRect.width() / mScaleX);
                    int ch = (int) (mCurrentRect.height()/ mScaleY);
                    if (mDrawnListener != null) mDrawnListener.onZoneDrawn(cx, cy, cw, ch);
                }
                mDrawing = false; mCurrentRect = null; invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private boolean isInProjectionZone(float vx, float vy) {
        return vx >= mLeft * mScaleX && vx <= getWidth()  - mRight  * mScaleX
            && vy >= mTop  * mScaleY && vy <= getHeight() - mBottom * mScaleY;
    }
    private float clampX(float x) { return Math.max(mLeft*mScaleX, Math.min(x, getWidth()-mRight*mScaleX)); }
    private float clampY(float y) { return Math.max(mTop*mScaleY,  Math.min(y, getHeight()-mBottom*mScaleY)); }
    private int hitTest(float vx, float vy) {
        if (mSlots == null) return -1;
        for (int i = mSlots.size()-1; i >= 0; i--) {
            LayoutPreset.SlotDef s = mSlots.get(i);
            if (vx >= s.x*mScaleX && vx <= (s.x+s.w)*mScaleX
             && vy >= s.y*mScaleY && vy <= (s.y+s.h)*mScaleY) return i;
        }
        return -1;
    }
}
