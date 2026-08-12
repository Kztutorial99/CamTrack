package com.kztutorial99.camtrack;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;

final class VehicleTracker {
    static final class TrackedVehicle {
        final int id;
        final String label;
        final float score;
        final RectF box;
        final int sourceWidth;
        final int sourceHeight;
        final int rotation;

        TrackedVehicle(int id, String label, float score, RectF box, int sourceWidth, int sourceHeight, int rotation) {
            this.id = id;
            this.label = label;
            this.score = score;
            this.box = new RectF(box);
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.rotation = rotation;
        }
    }

    private static final class Track {
        int id;
        String label;
        float score;
        RectF box;
        int missed;

        Track(int id, String label, float score, RectF box) {
            this.id = id;
            this.label = label;
            this.score = score;
            this.box = new RectF(box);
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;

    synchronized List<TrackedVehicle> update(List<DetectionInput> detections, int sourceWidth, int sourceHeight, int rotation) {
        boolean[] matched = new boolean[tracks.size()];
        List<TrackedVehicle> result = new ArrayList<>();

        for (DetectionInput detection : detections) {
            int bestIndex = -1;
            float bestDistance = Float.MAX_VALUE;
            float cx = detection.box.centerX();
            float cy = detection.box.centerY();

            for (int i = 0; i < tracks.size(); i++) {
                if (matched[i]) continue;
                Track track = tracks.get(i);
                if (!track.label.equals(detection.label)) continue;
                float dx = track.box.centerX() - cx;
                float dy = track.box.centerY() - cy;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float gate = Math.max(90f, Math.min(sourceWidth, sourceHeight) * 0.14f);
                if (distance < gate && distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }

            if (bestIndex >= 0) {
                Track track = tracks.get(bestIndex);
                matched[bestIndex] = true;
                track.box = new RectF(detection.box);
                track.score = detection.score;
                track.missed = 0;
                result.add(new TrackedVehicle(track.id, track.label, track.score, track.box, sourceWidth, sourceHeight, rotation));
            } else {
                Track track = new Track(nextId++, detection.label, detection.score, detection.box);
                tracks.add(track);
                result.add(new TrackedVehicle(track.id, track.label, track.score, track.box, sourceWidth, sourceHeight, rotation));
            }
        }

        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track track = tracks.get(i);
            if (i >= matched.length || !matched[i]) track.missed++;
            if (track.missed > 12) tracks.remove(i);
        }
        return result;
    }

    synchronized void clear() {
        tracks.clear();
        nextId = 1;
    }

    static final class DetectionInput {
        final String label;
        final float score;
        final RectF box;

        DetectionInput(String label, float score, RectF box) {
            this.label = label;
            this.score = score;
            this.box = new RectF(box);
        }
    }
}
