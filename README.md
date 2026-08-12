# CamTrack 🚗🏍️

CamTrack is an Android on-device AI camera app for detecting and tracking **cars and motorcycles** in real time.

## Features

- 📷 Uses the phone's built-in rear camera via CameraX
- 🤖 On-device MediaPipe Object Detector
- 🚗 Detects cars
- 🏍️ Detects motorcycles
- 🔢 Counts visible vehicles in real time
- 🎯 Bounding boxes around detected vehicles
- 🆔 Stable lightweight tracking IDs such as `DB-01`, `DB-02`, etc.
- 📊 Shows vehicle type and confidence score
- 📱 Supports portrait and landscape orientation
- ⚡ Runs inference asynchronously and keeps only the latest camera frame
- ☁️ Builds a debug APK automatically with GitHub Actions

## AI model

The first version uses Google's MediaPipe EfficientDet-Lite0 model with COCO classes. The model is downloaded automatically during the Gradle build and is not committed as a large binary to the repository.

> Important: this version identifies the vehicle **class/type** (`car` / `motorcycle`). It does not identify an exact make/model such as Toyota Avanza, Honda Beat, etc. Exact make/model recognition will require a custom trained model.

## Architecture

```text
Phone Camera
    ↓
CameraX Preview + ImageAnalysis
    ↓
MediaPipe Object Detector
    ↓
car / motorcycle detections
    ↓
Lightweight centroid tracker
    ↓
DB-01 / DB-02 / ... IDs
    ↓
Bounding-box overlay + counters
```

## Build APK

The repository contains a GitHub Actions workflow at `.github/workflows/android.yml`.

Every push to `main` triggers a debug APK build. The generated file is published as the Actions artifact **CamTrack-debug-apk**.

### Local build

```bash
gradle :app:assembleDebug
```

The build automatically downloads the EfficientDet-Lite0 model into `app/src/main/assets/` when needed.

## Current MVP

This repository is the first working foundation for CamTrack. The next upgrades can include:

- ByteTrack/DeepSORT-style multi-object tracking for stronger IDs
- vehicle direction and speed estimation
- virtual counting lines / entry-exit counters
- recording detections to a local database
- detection history and statistics
- front/rear camera switching
- GPU delegate optimization
- custom Indonesian vehicle make/model recognition
- optional license-plate recognition with a dedicated model
