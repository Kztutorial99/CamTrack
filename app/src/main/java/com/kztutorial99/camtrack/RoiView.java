package com.kztutorial99.camtrack;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Adjustable region-of-interest box. Detection only runs inside this box.
 * The ROI is stored normalized (0..1) relative to the displayed camera image,
 * so it survives rotation / view resizes.
 */
public final class RoiView extends View {

    public interface OnRoiChanged {
        void onRoiChanged(RectF normalized);
    }

    private static final float MIN_SIZE = 0.12f;

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF roi = new RectF(0.1f, 0.15f, 0.9f, 0.85f);
    private float aspectW = 16f;
    private float aspectH = 9f;

    private OnRoiChanged listener;
    private int dragMode = 0; // 0 none, 1 move, 2..5 corners
    private float lastX, lastY;

    public RoiView(Context context) {
        super(context);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(6f);
        borderPaint.setColor(0xFF00E676);
        borderPaint.setPathEffect(new DashPathEffect(new float[]{28f, 18f}, 0f));
        dimPaint.setStyle(Paint.Style.FILL);
        dimPaint.setColor(0x80000000);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFF00E676);
    }

    public void setListener(OnRoiChanged value) {
        listener = value;
        notifyChanged();
    }

    public void setSourceAspect(int width, int height) {
        if (width > 0 && height > 0) {
            aspectW = width;
            aspectH = height;
            invalidate();
        }
    }

    /** ROI normalized to the camera image (0..1). */
    public RectF getNormalizedRoi() {
        return new RectF(roi);
    }

    /** Resize the box around its own center, percent 10..100 of the full image. */
    public void setSizePercent(int percent) {
        float size = Math.max(10, Math.min(100, percent)) / 100f;
        float cx = roi.centerX();
        float cy = roi.centerY();
        float half = size / 2f;
        roi.set(cx - half, cy - half, cx + half, cy + half);
        clampRoi();
        invalidate();
        notifyChanged();
    }

    public void setFullFrame() {
        roi.set(0f, 0f, 1f, 1f);
        invalidate();
        notifyChanged();
    }

    private RectF imageRect() {
        float vw = getWidth();
        float vh = getHeight();
        if (vw <= 0 || vh <= 0) return new RectF(0, 0, 1, 1);
        float scale = Math.min(vw / aspectW, vh / aspectH);
        float w = aspectW * scale;
        float h = aspectH * scale;
        float dx = (vw - w) / 2f;
        float dy = (vh - h) / 2f;
        return new RectF(dx, dy, dx + w, dy + h);
    }

    private RectF roiPixels() {
        RectF img = imageRect();
        return new RectF(
                img.left + roi.left * img.width(),
                img.top + roi.top * img.height(),
                img.left + roi.right * img.width(),
                img.top + roi.bottom * img.height());
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RectF r = roiPixels();
        canvas.drawRect(0, 0, getWidth(), r.top, dimPaint);
        canvas.drawRect(0, r.bottom, getWidth(), getHeight(), dimPaint);
        canvas.drawRect(0, r.top, r.left, r.bottom, dimPaint);
        canvas.drawRect(r.right, r.top, getWidth(), r.bottom, dimPaint);
        canvas.drawRect(r, borderPaint);

        float s = 26f;
        canvas.drawRect(r.left, r.top, r.left + s, r.top + s, handlePaint);
        canvas.drawRect(r.right - s, r.top, r.right, r.top + s, handlePaint);
        canvas.drawRect(r.left, r.bottom - s, r.left + s, r.bottom, handlePaint);
        canvas.drawRect(r.right - s, r.bottom - s, r.right, r.bottom, handlePaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        RectF r = roiPixels();
        float x = event.getX();
        float y = event.getY();
        float touch = 90f;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (near(x, y, r.left, r.top, touch)) dragMode = 2;
                else if (near(x, y, r.right, r.top, touch)) dragMode = 3;
                else if (near(x, y, r.left, r.bottom, touch)) dragMode = 4;
                else if (near(x, y, r.right, r.bottom, touch)) dragMode = 5;
                else if (r.contains(x, y)) dragMode = 1;
                else return false;
                lastX = x;
                lastY = y;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (dragMode == 0) return false;
                RectF img = imageRect();
                if (img.width() <= 0 || img.height() <= 0) return true;
                float dx = (x - lastX) / img.width();
                float dy = (y - lastY) / img.height();
                lastX = x;
                lastY = y;
                if (dragMode == 1) roi.offset(dx, dy);
                else if (dragMode == 2) { roi.left += dx; roi.top += dy; }
                else if (dragMode == 3) { roi.right += dx; roi.top += dy; }
                else if (dragMode == 4) { roi.left += dx; roi.bottom += dy; }
                else if (dragMode == 5) { roi.right += dx; roi.bottom += dy; }
                clampRoi();
                invalidate();
                notifyChanged();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = 0;
                return true;
            default:
                return false;
        }
    }

    private static boolean near(float x, float y, float px, float py, float tolerance) {
        return Math.abs(x - px) < tolerance && Math.abs(y - py) < tolerance;
    }

    private void clampRoi() {
        if (roi.width() < MIN_SIZE) roi.right = roi.left + MIN_SIZE;
        if (roi.height() < MIN_SIZE) roi.bottom = roi.top + MIN_SIZE;
        if (roi.width() > 1f) { roi.left = 0f; roi.right = 1f; }
        if (roi.height() > 1f) { roi.top = 0f; roi.bottom = 1f; }
        if (roi.left < 0f) roi.offset(-roi.left, 0f);
        if (roi.top < 0f) roi.offset(0f, -roi.top);
        if (roi.right > 1f) roi.offset(1f - roi.right, 0f);
        if (roi.bottom > 1f) roi.offset(0f, 1f - roi.bottom);
    }

    private void notifyChanged() {
        if (listener != null) listener.onRoiChanged(new RectF(roi));
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    static int accent() { return Color.GREEN; }
}
