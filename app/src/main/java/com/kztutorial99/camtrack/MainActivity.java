package com.kztutorial99.camtrack;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
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
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
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
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "CamTrack";
    private static final int CAMERA_REQUEST = 1001;
    /** Detector input is upscaled to at least this size so far-away vehicles stay detectable. */
    private static final int MIN_CROP_SIZE = 480;

    private PreviewView previewView;
    private OverlayView overlayView;
    private RoiView roiView;
    private TextView countText;
    private TextView statusText;
    private ObjectDetector detector;

    private final VehicleTracker tracker = new VehicleTracker();
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong frameStamp = new AtomicLong(0L);
    private volatile boolean destroyed = false;
    private volatile RectF roi = new RectF(0.1f, 0.15f, 0.9f, 0.85f);

    // Geometry of the frame that produced the pending detection.
    private volatile int frameWidth = 0;
    private volatile int frameHeight = 0;
    private volatile float cropLeft = 0f;
    private volatile float cropTop = 0f;
    private volatile float cropScaleX = 1f;
    private volatile float cropScaleY = 1f;
    private volatile int detectedInLastSecond = 0;

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
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("efficientdet_lite0.tflite")
                    .setDelegate(Delegate.CPU)
                    .build();
            ObjectDetector.ObjectDetectorOptions options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setMaxResults(25)
                    .setScoreThreshold(0.28f)
                    .setCategoryAllowlist(Arrays.asList("car", "motorcycle", "bus", "truck", "bicycle"))
                    .setResultListener((ObjectDetectorResult result, MPImage input) -> onDetection(result))
                    .setErrorListener(error -> Log.e(TAG, "Detector error", error))
                    .build();
            detector = ObjectDetector.createFromOptions(this, options);
            statusText.setText("AI aktif • deteksi di dalam kotak");
        } catch (Throwable t) {
            detector = null;
            Log.e(TAG, "Detector initialization failed", t);
            statusText.setText("AI gagal dimuat • kamera tetap tersedia");
        }
    }

    private void bindCamera(ProcessCameraProvider provider) {
        if (destroyed) return;
        provider.unbindAll();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
        try {
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
        } catch (Throwable t) {
            Log.e(TAG, "bindToLifecycle failed", t);
            statusText.setText("Kamera gagal dibuka");
        }
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        try {
            ObjectDetector d = detector;
            if (d == null || destroyed) return;

            Bitmap upright = toUprightBitmap(imageProxy);
            if (upright == null) return;

            final int w = upright.getWidth();
            final int h = upright.getHeight();
            frameWidth = w;
            frameHeight = h;
            if (roiView != null) {
                final int aw = w, ah = h;
                roiView.post(() -> roiView.setSourceAspect(aw, ah));
            }

            RectF r = roi;
            int left = clamp((int) (r.left * w), 0, w - 2);
            int top = clamp((int) (r.top * h), 0, h - 2);
            int right = clamp((int) (r.right * w), left + 2, w);
            int bottom = clamp((int) (r.bottom * h), top + 2, h);
            int cw = right - left;
            int ch = bottom - top;

            Bitmap crop = Bitmap.createBitmap(upright, left, top, cw, ch);

            // Upscale small crops so distant vehicles fill enough pixels to be detected.
            float upscale = 1f;
            int minSide = Math.min(cw, ch);
            if (minSide < MIN_CROP_SIZE) {
                upscale = Math.min(3f, MIN_CROP_SIZE / (float) minSide);
                crop = Bitmap.createScaledBitmap(crop, Math.round(cw * upscale), Math.round(ch * upscale), true);
            }

            cropLeft = left;
            cropTop = top;
            cropScaleX = 1f / upscale;
            cropScaleY = 1f / upscale;

            MPImage mpImage = new BitmapImageBuilder(crop).build();
            d.detectAsync(mpImage, frameStamp.incrementAndGet());
        } catch (Throwable t) {
            Log.w(TAG, "Frame analysis failed", t);
        } finally {
            imageProxy.close();
        }
    }

    /** RGBA_8888 ImageProxy -> Bitmap rotated to upright orientation. */
    private Bitmap toUprightBitmap(ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            buffer.rewind();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            int paddedWidth = rowStride / Math.max(1, pixelStride);

            Bitmap bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            if (paddedWidth != width) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            }

            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
            return bitmap;
        } catch (Throwable t) {
            Log.w(TAG, "Bitmap conversion failed", t);
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void onDetection(ObjectDetectorResult result) {
        if (destroyed || result == null) return;
        try {
            final int w = frameWidth;
            final int h = frameHeight;
            if (w <= 0 || h <= 0) return;

            float offsetX = cropLeft;
            float offsetY = cropTop;
            float scaleX = cropScaleX;
            float scaleY = cropScaleY;

            List<VehicleTracker.DetectionInput> inputs = new ArrayList<>();
            for (Detection detection : result.detections()) {
                List<Category> categories = detection.categories();
                if (categories == null || categories.isEmpty()) continue;
                Category category = categories.get(0);
                String label = normalize(category.categoryName());
                if (label == null) continue;
                RectF box = new RectF(detection.boundingBox());
                RectF mapped = new RectF(
                        offsetX + box.left * scaleX,
                        offsetY + box.top * scaleY,
                        offsetX + box.right * scaleX,
                        offsetY + box.bottom * scaleY);
                if (mapped.width() < 8f || mapped.height() < 8f) continue;
                inputs.add(new VehicleTracker.DetectionInput(label, category.score(), mapped));
            }

            detectedInLastSecond = inputs.size();
            List<VehicleTracker.TrackedVehicle> vehicles = tracker.update(inputs, w, h, 0);
            runOnUiThread(() -> {
                if (destroyed) return;
                overlayView.setVehicles(vehicles);
                int cars = 0, motors = 0;
                for (VehicleTracker.TrackedVehicle v : vehicles) {
                    if ("car".equals(v.label)) cars++;
                    else if ("motorcycle".equals(v.label)) motors++;
                }
                countText.setText(String.format("MOBIL %d   •   MOTOR %d   •   TOTAL %d", cars, motors, cars + motors));
                statusText.setText(String.format("AI aktif • %d objek di dalam kotak", detectedInLastSecond));
            });
        } catch (Throwable t) {
            Log.w(TAG, "Detection result failed", t);
        }
    }

    /** Map COCO labels onto the two categories the app counts. */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String label = raw.toLowerCase();
        if (label.contains("motor") || label.contains("bicycle") || label.contains("scooter")) return "motorcycle";
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
        cameraExecutor.shutdownNow();
        super.onDestroy();
    }
}
