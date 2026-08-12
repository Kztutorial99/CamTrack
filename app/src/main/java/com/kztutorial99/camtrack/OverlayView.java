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
        vehicles = new ArrayList<>(value);
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (VehicleTracker.TrackedVehicle vehicle : vehicles) {
            RectF rect = toViewRect(vehicle.box, vehicle.sourceWidth, vehicle.sourceHeight, vehicle.rotation);
            boxPaint.setColor(vehicle.label.equals("motorcycle") ? 0xFF00E5FF : 0xFF00E676);
            canvas.drawRect(rect, boxPaint);

            String name = vehicle.label.equals("motorcycle") ? "MOTOR" : "MOBIL";
            String label = String.format("DB-%02d  %s  %.0f%%", vehicle.id, name, vehicle.score * 100f);
            float padding = 12f;
            float width = textPaint.measureText(label) + padding * 2;
            float top = Math.max(0f, rect.top - 42f);
            labelPaint.setColor(boxPaint.getColor());
            canvas.drawRect(rect.left, top, rect.left + width, rect.top, labelPaint);
            textPaint.setColor(0xFF00120A);
            canvas.drawText(label, rect.left + padding, rect.top - 12f, textPaint);
        }
    }

    private RectF toViewRect(RectF box, int sourceWidth, int sourceHeight, int rotation) {
        RectF r = new RectF(box);
        float rotatedWidth = sourceWidth;
        float rotatedHeight = sourceHeight;
        if (rotation == 90) {
            r = new RectF(sourceHeight - box.bottom, box.left, sourceHeight - box.top, box.right);
            rotatedWidth = sourceHeight; rotatedHeight = sourceWidth;
        } else if (rotation == 180) {
            r = new RectF(sourceWidth - box.right, sourceHeight - box.bottom, sourceWidth - box.left, sourceHeight - box.top);
        } else if (rotation == 270) {
            r = new RectF(box.top, sourceWidth - box.right, box.bottom, sourceWidth - box.left);
            rotatedWidth = sourceHeight; rotatedHeight = sourceWidth;
        }

        float scale = Math.min(getWidth() / rotatedWidth, getHeight() / rotatedHeight);
        float dx = (getWidth() - rotatedWidth * scale) / 2f;
        float dy = (getHeight() - rotatedHeight * scale) / 2f;
        r.left = r.left * scale + dx;
        r.right = r.right * scale + dx;
        r.top = r.top * scale + dy;
        r.bottom = r.bottom * scale + dy;
        return r;
    }
}
