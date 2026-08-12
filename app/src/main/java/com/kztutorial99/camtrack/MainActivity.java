package com.kztutorial99.camtrack;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.framework.image.MediaImageBuilder;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "CamTrack";
    private static final int CAMERA_REQUEST = 1001;
    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView countText;
    private TextView statusText;
    private ObjectDetector detector;
    private final VehicleTracker tracker = new VehicleTracker();
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed = false;

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
        setContentView(root);
    }

    private void startCamera() {
        setupDetector();
        if (detector == null) {
            statusText.setText("AI detector tidak tersedia");
        }
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
                    .build();
            ObjectDetector.ObjectDetectorOptions options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setMaxResults(20)
                    .setScoreThreshold(0.35f)
                    .setCategoryAllowlist(Arrays.asList("car", "motorcycle"))
                    .setResultListener((ObjectDetectorResult result, MPImage input) -> onDetection(result))
                    .build();
            detector = ObjectDetector.createFromOptions(this, options);
            statusText.setText("AI aktif • deteksi mobil & motor");
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
        int rotation = previewView.getDisplay() != null ? previewView.getDisplay().getRotation() : 0;
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setTargetRotation(rotation);
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
            if (d == null || imageProxy.getImage() == null || destroyed) return;
            MPImage mpImage = new MediaImageBuilder(imageProxy.getImage()).build();
            d.detectAsync(mpImage, SystemClock.uptimeMillis());
        } catch (Throwable t) {
            Log.w(TAG, "Frame analysis failed", t);
        } finally {
            imageProxy.close();
        }
    }

    private void onDetection(ObjectDetectorResult result) {
        if (destroyed || result == null) return;
        try {
            List<VehicleTracker.DetectionInput> inputs = new ArrayList<>();
            for (Detection detection : result.detections()) {
                List<Category> categories = detection.categories();
                if (categories == null || categories.isEmpty()) continue;
                Category category = categories.get(0);
                String label = category.categoryName();
                if (!"car".equals(label) && !"motorcycle".equals(label)) continue;
                inputs.add(new VehicleTracker.DetectionInput(label, category.score(), detection.boundingBox()));
            }
            int rotation = previewView.getDisplay() != null ? rotationDegrees(previewView.getDisplay().getRotation()) : 0;
            List<VehicleTracker.TrackedVehicle> vehicles = tracker.update(inputs, 1280, 720, rotation);
            runOnUiThread(() -> {
                if (destroyed) return;
                overlayView.setVehicles(vehicles);
                int cars = 0, motors = 0;
                for (VehicleTracker.TrackedVehicle v : vehicles) {
                    if ("car".equals(v.label)) cars++;
                    else if ("motorcycle".equals(v.label)) motors++;
                }
                countText.setText(String.format("MOBIL %d   •   MOTOR %d   •   TOTAL %d", cars, motors, cars + motors));
            });
        } catch (Throwable t) {
            Log.w(TAG, "Detection result failed", t);
        }
    }

    private int rotationDegrees(int rotation) {
        if (rotation == 1) return 90;
        if (rotation == 2) return 180;
        if (rotation == 3) return 270;
        return 0;
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
