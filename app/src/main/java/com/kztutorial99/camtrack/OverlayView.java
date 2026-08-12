package com.kztutorial99.camtrack;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public final class OverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<VehicleTracker.TrackedVehicle> vehicles = new ArrayList<>();

    public OverlayView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public OverlayView(Context context) { super(context); init(); }

    private void init() {
        setWillNotDraw(false);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5f);
        labelPaint.setStyle(Paint.Style.FILL);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(30f);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
    }

    public void setVehicles(List<VehicleTracker.TrackedVehicle> value) {
        vehicles = value;
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (VehicleTracker.TrackedVehicle vehicle : vehicles) {
            RectF rect = toViewRect(vehicle.box, vehicle.sourceWidth, vehicle.sourceHeight);
            boolean motor = "motorcycle".equals(vehicle.label);
            boxPaint.setColor(motor ? 0xFF00E5FF : 0xFF00E676);
            boxPaint.setAlpha(vehicle.fresh ? 255 : 130);
            canvas.drawRect(rect, boxPaint);

            // Full DB id (not zero-padded to 2 digits) for every visible vehicle.
            String label = String.format("DB-%d  %s  %.0f%%",
                    vehicle.id, motor ? "MOTOR" : "MOBIL", vehicle.score * 100f);

            float padding = 12f;
            float width = textPaint.measureText(label) + padding * 2;
            float top = Math.max(0f, rect.top - 42f);
            labelPaint.setColor(boxPaint.getColor());
            labelPaint.setAlpha(vehicle.fresh ? 255 : 130);
            canvas.drawRect(rect.left, top, rect.left + width, rect.top, labelPaint);
            textPaint.setColor(0xFF00120A);
            canvas.drawText(label, rect.left + padding, rect.top - 12f, textPaint);
        }
    }

    /** Boxes arrive already upright, in full-frame pixel coordinates. */
    private RectF toViewRect(RectF box, int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return new RectF(box);
        float scale = Math.min(getWidth() / (float) sourceWidth, getHeight() / (float) sourceHeight);
        float dx = (getWidth() - sourceWidth * scale) / 2f;
        float dy = (getHeight() - sourceHeight * scale) / 2f;
        return new RectF(
                box.left * scale + dx,
                box.top * scale + dy,
                box.right * scale + dx,
                box.bottom * scale + dy);
    }
}
