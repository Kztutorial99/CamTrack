package com.kztutorial99.camtrack;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "CamTrack";
    private static final int CAMERA_REQUEST = 1001;
    /**
     * EfficientDet-Lite0 runs at 320x320 internally, so anything larger is
     * downscaled here (cheap) instead of inside the graph (expensive).
     * Upscaling adds zero information and only costs latency.
     */
    private static volatile int detectorInput = 320;

    private PreviewView previewView;
    private OverlayView overlayView;
    private RoiView roiView;
    private TextView countText;
    private TextView statusText;
    private ObjectDetector detector;
    private Camera camera;
    private SeekBar zoomBar;
    private TextView zoomLabel;

    private final VehicleTracker tracker = new VehicleTracker();
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong frameStamp = new AtomicLong(0L);
    /** Drops frames while inference is still running: always work on the freshest frame. */
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile boolean destroyed = false;
    private volatile RectF roi = new RectF(0.1f, 0.15f, 0.9f, 0.85f);

    // Geometry of the frame that produced the pending detection (upright pixels).
    private volatile int frameWidth = 0;
    private volatile int frameHeight = 0;
    private volatile float cropLeft = 0f;
    private volatile float cropTop = 0f;
    private volatile float cropScale = 1f;
    private volatile int detectedInLastFrame = 0;
    private volatile long lastLatencyMs = 0L;
    private volatile long inferenceStart = 0L;

    // Reused buffers so we stop allocating a full-frame bitmap 30x per second.
    private Bitmap rawBuffer;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        } catch (Throwable ignored) { }
        try {
            buildUi();
        } catch (Throwable t) {
            Log.e(TAG, "UI build failed", t);
            TextView fallback = new TextView(this);
            fallback.setText("CamTrack gagal memuat tampilan");
            fallback.setTextColor(Color.WHITE);
            fallback.setPadding(32, 96, 32, 32);
            setContentView(fallback);
            return;
        }
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Startup failed", t);
            if (statusText != null) statusText.setText("Kamera tidak dapat dimulai di perangkat ini");
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        overlayView = new OverlayView(this);
        root.addView(overlayView, new FrameLayout.LayoutParams(-1, -1));

        roiView = new RoiView(this);
        roiView.setListener(value -> roi = value);
        root.addView(roiView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(24, 18, 24, 18);
        header.setBackgroundColor(0xCC050807);
        TextView title = new TextView(this);
        title.setText("CAMTRACK  •  AI VEHICLE TRACKING");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title);
        countText = new TextView(this);
        countText.setText("MOBIL 0   •   MOTOR 0   •   TOTAL 0");
        countText.setTextColor(0xFF00E676);
        countText.setTextSize(15f);
        countText.setPadding(0, 8, 0, 0);
        header.addView(countText);
        statusText = new TextView(this);
        statusText.setText("Menyiapkan kamera...");
        statusText.setTextColor(0xFFD0D8D4);
        statusText.setTextSize(12f);
        header.addView(statusText);
        root.addView(header, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        root.addView(buildControls(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
    }

    private View buildControls() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(24, 16, 24, 28);
        bar.setBackgroundColor(0xCC050807);

        TextView hint = new TextView(this);
        hint.setText("Geser kotak untuk memindahkan • tarik sudut untuk mengubah ukuran");
        hint.setTextColor(0xFFD0D8D4);
        hint.setTextSize(12f);
        bar.addView(hint);

        SeekBar sizeBar = new SeekBar(this);
        sizeBar.setMax(100);
        sizeBar.setProgress(80);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && roiView != null) roiView.setSizePercent(Math.max(12, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        bar.addView(sizeBar);

        zoomLabel = new TextView(this);
        zoomLabel.setText("ZOOM KAMERA  1.0x");
        zoomLabel.setTextColor(0xFF00E676);
        zoomLabel.setTextSize(12f);
        zoomLabel.setPadding(0, 6, 0, 0);
        bar.addView(zoomLabel);

        zoomBar = new SeekBar(this);
        zoomBar.setMax(100);
        zoomBar.setProgress(0);
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) applyZoom(progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        bar.addView(zoomBar);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button full = new Button(this);
        full.setText("FULL LAYAR");
        full.setOnClickListener(v -> {
            if (roiView != null) roiView.setFullFrame();
            sizeBar.setProgress(100);
        });
        Button reset = new Button(this);
        reset.setText("RESET KOTAK");
        reset.setOnClickListener(v -> {
            if (roiView != null) roiView.setSizePercent(80);
            sizeBar.setProgress(80);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        buttons.addView(full, lp);
        buttons.addView(reset, lp);
        bar.addView(buttons);
        return bar;
    }

    /** Optical/digital zoom via CameraX linear zoom (0f = wide, 1f = max tele). */
    private void applyZoom(float linear) {
        try {
            Camera cam = camera;
            if (cam == null) return;
            float value = Math.max(0f, Math.min(1f, linear));
            cam.getCameraControl().setLinearZoom(value);
            ZoomState state = cam.getCameraInfo().getZoomState().getValue();
            if (zoomLabel != null) {
                float ratio = state != null ? state.getZoomRatio() : 1f;
                zoomLabel.setText(String.format("ZOOM KAMERA  %.1fx", ratio));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Zoom failed", t);
        }
    }

    private void startCamera() {
        setupDetector();
        final ListenableFuture<ProcessCameraProvider> future;
        try {
            future = ProcessCameraProvider.getInstance(this);
        } catch (Throwable t) {
            Log.e(TAG, "Camera provider init failed", t);
            statusText.setText("Kamera tidak dapat dimulai");
            return;
        }
        future.addListener(() -> {
            if (destroyed) return;
            try {
                bindCamera(future.get());
            } catch (Throwable t) {
                Log.e(TAG, "Camera bind failed", t);
                runOnUiThread(() -> statusText.setText("Gagal membuka kamera"));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setupDetector() {
        // YOLO-n first (fast + sharper on small/far vehicles). If the asset is
        // missing or the runtime rejects it, fall back to EfficientDet-Lite0 so the
        // app never loses detection entirely.
        detector = createDetector("yolo_n.tflite", 320);
        if (detector == null) detector = createDetector("efficientdet_lite0.tflite", 320);
        if (detector == null) detector = createDetector("efficientdet_lite2.tflite", 448);
        if (detector == null) {
            statusText.setText("AI gagal dimuat • kamera tetap tersedia");
        } else {
            statusText.setText("AI aktif • deteksi di dalam kotak");
        }
    }

    /**
     * CPU delegate only. The GPU delegate crashes the process (native SIGSEGV,
     * uncatchable in Java) on many Mali/Adreno drivers the moment the first
     * inference runs, which is why the app force-closed at detection time.
     */
    private ObjectDetector createDetector(String modelPath, int inputSize) {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelPath)
                    .setDelegate(Delegate.CPU)
                    .build();
            ObjectDetector.ObjectDetectorOptions options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setMaxResults(25)
                    // lower threshold: vehicles inside the box were being dropped
                    .setScoreThreshold(0.22f)
                    // bicycle removed: it was being reported as a motorcycle and wrecked accuracy
                    .setCategoryAllowlist(Arrays.asList("car", "motorcycle", "bus", "truck"))

                    .setResultListener((ObjectDetectorResult result, MPImage input) -> onDetection(result))
                    .setErrorListener(error -> {
                        Log.e(TAG, "Detector error", error);
                        inFlight.set(false);
                    })
                    .build();
            ObjectDetector created = ObjectDetector.createFromOptions(this, options);
            if (created != null) detectorInput = inputSize;
            return created;
        } catch (Throwable t) {
            Log.w(TAG, "Detector init failed for " + modelPath, t);
            return null;
        }
    }

    private void bindCamera(ProcessCameraProvider provider) {
        if (destroyed) return;
        provider.unbindAll();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        // 640x480 analysis: less pixels to copy/crop per frame -> lower ms/frame.
        // The preview stays at full camera resolution, only the AI input shrinks.
        ResolutionSelector resolution = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(
                        new Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
        try {
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            if (zoomBar != null) applyZoom(zoomBar.getProgress() / 100f);
        } catch (Throwable t) {
            Log.e(TAG, "bindToLifecycle failed", t);
            statusText.setText("Kamera gagal dibuka");
        }
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        try {
            ObjectDetector d = detector;
            if (d == null || destroyed) return;
            // Skip this frame if the previous inference has not returned yet.
            if (!inFlight.compareAndSet(false, true)) return;

            boolean submitted = false;
            try {
                Bitmap raw = toBitmap(imageProxy);
                if (raw == null) return;

                final int rotation = imageProxy.getImageInfo().getRotationDegrees();
                final boolean swap = rotation == 90 || rotation == 270;
                final int rawW = raw.getWidth();
                final int rawH = raw.getHeight();
                final int upW = swap ? rawH : rawW;
                final int upH = swap ? rawW : rawH;
                frameWidth = upW;
                frameHeight = upH;
                if (roiView != null) roiView.post(() -> roiView.setSourceAspect(upW, upH));

                RectF r = roi;
                int left = clamp((int) (r.left * upW), 0, upW - 2);
                int top = clamp((int) (r.top * upH), 0, upH - 2);
                int right = clamp((int) (r.right * upW), left + 2, upW);
                int bottom = clamp((int) (r.bottom * upH), top + 2, upH);

                // Crop in raw sensor space first, then rotate only the crop.
                // Rotating the full frame first was the single biggest source of lag.
                int rl, rt, rr, rb;
                if (rotation == 90) {
                    rl = top;            rt = rawH - right;  rr = bottom;        rb = rawH - left;
                } else if (rotation == 270) {
                    rl = rawW - bottom;  rt = left;          rr = rawW - top;    rb = right;
                } else if (rotation == 180) {
                    rl = rawW - right;   rt = rawH - bottom; rr = rawW - left;   rb = rawH - top;
                } else {
                    rl = left;           rt = top;           rr = right;         rb = bottom;
                }
                rl = clamp(rl, 0, rawW - 2);
                rt = clamp(rt, 0, rawH - 2);
                rr = clamp(rr, rl + 2, rawW);
                rb = clamp(rb, rt + 2, rawH);

                Bitmap crop = Bitmap.createBitmap(raw, rl, rt, rr - rl, rb - rt);
                if (rotation != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(rotation);
                    crop = Bitmap.createBitmap(crop, 0, 0, crop.getWidth(), crop.getHeight(), matrix, false);
                }

                // Downscale (never upscale) to the detector's native input size.
                float scale = 1f;
                int longSide = Math.max(crop.getWidth(), crop.getHeight());
                if (longSide > detectorInput) {
                    scale = detectorInput / (float) longSide;
                    crop = Bitmap.createScaledBitmap(crop,
                            Math.max(2, Math.round(crop.getWidth() * scale)),
                            Math.max(2, Math.round(crop.getHeight() * scale)), false);
                }

                cropLeft = left;
                cropTop = top;
                cropScale = 1f / scale;

                // createBitmap() returns the SOURCE itself when nothing is cropped or
                // rotated. Handing the reused frame buffer to MediaPipe let the next
                // frame overwrite pixels mid-inference -> native crash. Always copy.
                if (crop == raw || crop == rawBuffer) {
                    crop = crop.copy(Bitmap.Config.ARGB_8888, false);
                    if (crop == null) return;
                }

                inferenceStart = SystemClock.uptimeMillis();
                d.detectAsync(new BitmapImageBuilder(crop).build(), frameStamp.incrementAndGet());
                submitted = true;
            } finally {
                if (!submitted) inFlight.set(false);
            }
        } catch (Throwable t) {
            inFlight.set(false);
            Log.w(TAG, "Frame analysis failed", t);
        } finally {
            imageProxy.close();
        }
    }

    /** RGBA_8888 ImageProxy -> Bitmap in raw sensor orientation, reusing one buffer. */
    private Bitmap toBitmap(ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            buffer.rewind();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            int paddedWidth = rowStride / Math.max(1, pixelStride);

            Bitmap buf = rawBuffer;
            if (buf == null || buf.getWidth() != paddedWidth || buf.getHeight() != height) {
                buf = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
                rawBuffer = buf;
            }
            buf.copyPixelsFromBuffer(buffer);
            if (paddedWidth != width) {
                return Bitmap.createBitmap(buf, 0, 0, width, height);
            }
            return buf;
        } catch (Throwable t) {
            Log.w(TAG, "Bitmap conversion failed", t);
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void onDetection(ObjectDetectorResult result) {
        inFlight.set(false);
        if (destroyed || result == null) return;
        try {
            lastLatencyMs = SystemClock.uptimeMillis() - inferenceStart;
            final int w = frameWidth;
            final int h = frameHeight;
            if (w <= 0 || h <= 0) return;

            final float offsetX = cropLeft;
            final float offsetY = cropTop;
            final float scale = cropScale;

            List<VehicleTracker.DetectionInput> inputs = new ArrayList<>();
            for (Detection detection : result.detections()) {
                List<Category> categories = detection.categories();
                if (categories == null || categories.isEmpty()) continue;
                Category category = categories.get(0);
                String label = normalize(category.categoryName());
                if (label == null) continue;
                RectF box = new RectF(detection.boundingBox());
                RectF mapped = new RectF(
                        offsetX + box.left * scale,
                        offsetY + box.top * scale,
                        offsetX + box.right * scale,
                        offsetY + box.bottom * scale);
                if (mapped.width() < 8f || mapped.height() < 8f) continue;
                inputs.add(new VehicleTracker.DetectionInput(label, category.score(), mapped));
            }

            detectedInLastFrame = inputs.size();
            List<VehicleTracker.TrackedVehicle> vehicles = tracker.update(inputs, w, h);
            final long latency = lastLatencyMs;
            runOnUiThread(() -> {
                if (destroyed || overlayView == null || countText == null || statusText == null) return;
                overlayView.setVehicles(vehicles);
                int cars = 0, motors = 0;
                for (VehicleTracker.TrackedVehicle v : vehicles) {
                    if (!v.fresh) continue;
                    if ("motorcycle".equals(v.label)) motors++; else cars++;
                }
                countText.setText(String.format("MOBIL %d   •   MOTOR %d   •   TOTAL %d", cars, motors, cars + motors));
                statusText.setText(String.format("AI aktif • %d objek • %d ms/frame", detectedInLastFrame, latency));
            });
        } catch (Throwable t) {
            Log.w(TAG, "Detection result failed", t);
        }
    }

    /** Map COCO labels onto the two categories the app counts. */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String label = raw.toLowerCase();
        if (label.contains("motorcycle") || label.contains("motorbike") || label.contains("scooter")) return "motorcycle";
        if (label.contains("car") || label.contains("bus") || label.contains("truck") || label.contains("van")) return "car";
        return null;
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else if (statusText != null) {
            statusText.setText("Izin kamera diperlukan untuk CamTrack");
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (detector != null) {
            try { detector.close(); } catch (Throwable ignored) { }
            detector = null;
        }
        tracker.clear();
        rawBuffer = null;
        camera = null;
        cameraExecutor.shutdownNow();
        super.onDestroy();
    }
}
