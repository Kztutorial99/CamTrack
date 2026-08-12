package com.kztutorial99.camtrack;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight IoU + centroid tracker.
 *
 * Design goals:
 *  - stable DB-xx ids (an id must survive short misses instead of being recycled)
 *  - no id is published until the track is confirmed on several frames,
 *    so a one-frame false positive never gets a DB label
 *  - smoothed boxes so the overlay looks instant instead of jittery
 */
final class VehicleTracker {

    /** frames a track must be seen before it earns a DB id */
    private static final int CONFIRM_HITS = 2;
    /** frames a confirmed track survives without a matching detection */
    private static final int MAX_MISSES = 6;
    /** box smoothing factor: higher = snappier (more responsive), lower = smoother */
    private static final float SMOOTHING = 0.65f;
    private static final float MIN_IOU = 0.2f;

    static final class TrackedVehicle {
        final int id;
        final String label;
        final float score;
        final RectF box;
        final int sourceWidth;
        final int sourceHeight;
        final boolean fresh;

        TrackedVehicle(int id, String label, float score, RectF box,
                       int sourceWidth, int sourceHeight, boolean fresh) {
            this.id = id;
            this.label = label;
            this.score = score;
            this.box = new RectF(box);
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.fresh = fresh;
        }
    }

    private static final class Track {
        int id;
        String label;
        float score;
        final RectF box = new RectF();
        int hits;
        int misses;
        int carVotes;
        int motorVotes;
    }

    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;

    synchronized List<TrackedVehicle> update(List<DetectionInput> detections, int sourceWidth, int sourceHeight) {
        final int n = tracks.size();
        final boolean[] trackUsed = new boolean[n];
        final boolean[] detUsed = new boolean[detections.size()];

        // Greedy best-first association on IoU, falling back to centroid distance.
        while (true) {
            float bestScore = 0f;
            int bestT = -1, bestD = -1;
            for (int d = 0; d < detections.size(); d++) {
                if (detUsed[d]) continue;
                DetectionInput det = detections.get(d);
                for (int t = 0; t < n; t++) {
                    if (trackUsed[t]) continue;
                    Track tr = tracks.get(t);
                    float s = association(tr, det, sourceWidth, sourceHeight);
                    if (s > bestScore) { bestScore = s; bestT = t; bestD = d; }
                }
            }
            if (bestT < 0) break;
            trackUsed[bestT] = true;
            detUsed[bestD] = true;
            apply(tracks.get(bestT), detections.get(bestD));
        }

        // Unmatched detections become new (still unconfirmed) tracks.
        for (int d = 0; d < detections.size(); d++) {
            if (detUsed[d]) continue;
            DetectionInput det = detections.get(d);
            Track tr = new Track();
            tr.id = nextId++;
            tr.label = det.label;
            tr.box.set(det.box);
            tr.score = det.score;
            tr.hits = 1;
            vote(tr, det.label);
            tracks.add(tr);
        }

        // Age unmatched tracks.
        for (int t = n - 1; t >= 0; t--) {
            if (!trackUsed[t]) {
                Track tr = tracks.get(t);
                tr.misses++;
                // unconfirmed tracks die immediately: never label a flicker
                if (tr.misses > MAX_MISSES || tr.hits < CONFIRM_HITS) tracks.remove(t);
            }
        }

        List<TrackedVehicle> result = new ArrayList<>(tracks.size());
        for (Track tr : tracks) {
            if (tr.hits < CONFIRM_HITS) continue;
            String label = tr.motorVotes > tr.carVotes ? "motorcycle" : "car";
            result.add(new TrackedVehicle(tr.id, label, tr.score, tr.box,
                    sourceWidth, sourceHeight, tr.misses == 0));
        }
        return result;
    }

    private void apply(Track tr, DetectionInput det) {
        // Exponential smoothing keeps the box glued to the vehicle without jitter.
        tr.box.set(
                mix(tr.box.left, det.box.left),
                mix(tr.box.top, det.box.top),
                mix(tr.box.right, det.box.right),
                mix(tr.box.bottom, det.box.bottom));
        tr.score = tr.score * (1f - SMOOTHING) + det.score * SMOOTHING;
        tr.misses = 0;
        if (tr.hits < 1000) tr.hits++;
        vote(tr, det.label);
    }

    private static void vote(Track tr, String label) {
        if ("motorcycle".equals(label)) tr.motorVotes++; else tr.carVotes++;
        tr.label = tr.motorVotes > tr.carVotes ? "motorcycle" : "car";
    }

    private static float mix(float previous, float current) {
        return previous * (1f - SMOOTHING) + current * SMOOTHING;
    }

    private static float association(Track tr, DetectionInput det, int width, int height) {
        float iou = iou(tr.box, det.box);
        boolean sameClass = tr.label.equals(det.label);
        if (iou >= MIN_IOU) return (sameClass ? 1f : 0.55f) * (1f + iou);

        // Fast motion: fall back to centroid proximity within a tight gate.
        float dx = tr.box.centerX() - det.box.centerX();
        float dy = tr.box.centerY() - det.box.centerY();
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float gate = Math.max(60f, Math.min(width, height) * 0.10f);
        if (distance >= gate || !sameClass) return 0f;
        return 0.9f * (1f - distance / gate);
    }

    private static float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        if (right <= left || bottom <= top) return 0f;
        float inter = (right - left) * (bottom - top);
        float union = a.width() * a.height() + b.width() * b.height() - inter;
        return union <= 0f ? 0f : inter / union;
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
