package com.kztutorial99.camtrack;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight IoU + centroid tracker with constant-velocity prediction.
 *
 * Design goals:
 *  - NO ghost boxes: only tracks matched on the current frame are published
 *  - stable DB ids: an id is assigned once, at confirmation, and never recycled
 *    while the vehicle is still in view
 *  - fast objects still match, because association uses the predicted position
 */
final class VehicleTracker {

    /** frames a track must be seen before it earns a DB id (1 = instant response) */
    private static final int CONFIRM_HITS = 1;
    /** frames a confirmed track is kept alive internally (id memory only, not drawn) */
    private static final int MAX_MISSES = 1;
    /** box smoothing: high = snappy/responsive (little lag behind the vehicle) */
    private static final float SMOOTHING = 0.92f;
    private static final float MIN_IOU = 0.12f;

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
        float vx, vy;
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

        // Predict where each track should be on this frame before matching.
        final RectF[] predicted = new RectF[n];
        for (int t = 0; t < n; t++) {
            Track tr = tracks.get(t);
            predicted[t] = new RectF(
                    tr.box.left + tr.vx, tr.box.top + tr.vy,
                    tr.box.right + tr.vx, tr.box.bottom + tr.vy);
        }

        // Greedy best-first association on IoU, falling back to centroid distance.
        while (true) {
            float bestScore = 0f;
            int bestT = -1, bestD = -1;
            for (int d = 0; d < detections.size(); d++) {
                if (detUsed[d]) continue;
                DetectionInput det = detections.get(d);
                for (int t = 0; t < n; t++) {
                    if (trackUsed[t]) continue;
                    float s = association(tracks.get(t), predicted[t], det, sourceWidth, sourceHeight);
                    if (s > bestScore) { bestScore = s; bestT = t; bestD = d; }
                }
            }
            if (bestT < 0) break;
            trackUsed[bestT] = true;
            detUsed[bestD] = true;
            apply(tracks.get(bestT), detections.get(bestD));
        }

        // Unmatched detections become new tracks (id handed out at confirmation).
        for (int d = 0; d < detections.size(); d++) {
            if (detUsed[d]) continue;
            DetectionInput det = detections.get(d);
            Track tr = new Track();
            tr.id = 0;
            tr.label = det.label;
            tr.box.set(det.box);
            tr.score = det.score;
            tr.hits = 1;
            vote(tr, det.label);
            if (tr.hits >= CONFIRM_HITS) tr.id = nextId++;
            tracks.add(tr);
        }

        // Age unmatched tracks.
        for (int t = n - 1; t >= 0; t--) {
            if (!trackUsed[t]) {
                Track tr = tracks.get(t);
                tr.misses++;
                // coast the box along its last velocity so the id survives a short miss
                tr.box.offset(tr.vx, tr.vy);
                if (tr.misses > MAX_MISSES || tr.hits < CONFIRM_HITS) tracks.remove(t);
            }
        }

        // Publish ONLY vehicles seen on this very frame: no leftover boxes.
        List<TrackedVehicle> result = new ArrayList<>(tracks.size());
        for (Track tr : tracks) {
            if (tr.misses != 0 || tr.hits < CONFIRM_HITS || tr.id <= 0) continue;
            String label = tr.motorVotes > tr.carVotes ? "motorcycle" : "car";
            result.add(new TrackedVehicle(tr.id, label, tr.score, tr.box,
                    sourceWidth, sourceHeight, true));
        }
        return result;
    }

    private void apply(Track tr, DetectionInput det) {
        float prevCx = tr.box.centerX();
        float prevCy = tr.box.centerY();
        // Exponential smoothing keeps the box glued to the vehicle without jitter.
        tr.box.set(
                mix(tr.box.left, det.box.left),
                mix(tr.box.top, det.box.top),
                mix(tr.box.right, det.box.right),
                mix(tr.box.bottom, det.box.bottom));
        tr.vx = (tr.box.centerX() - prevCx) * 0.7f + tr.vx * 0.3f;
        tr.vy = (tr.box.centerY() - prevCy) * 0.7f + tr.vy * 0.3f;
        tr.score = tr.score * (1f - SMOOTHING) + det.score * SMOOTHING;
        tr.misses = 0;
        if (tr.hits < 1000) tr.hits++;
        if (tr.id <= 0 && tr.hits >= CONFIRM_HITS) tr.id = nextId++;
        vote(tr, det.label);
    }

    private static void vote(Track tr, String label) {
        if ("motorcycle".equals(label)) tr.motorVotes++; else tr.carVotes++;
        tr.label = tr.motorVotes > tr.carVotes ? "motorcycle" : "car";
    }

    private static float mix(float previous, float current) {
        return previous * (1f - SMOOTHING) + current * SMOOTHING;
    }

    private static float association(Track tr, RectF predictedBox, DetectionInput det, int width, int height) {
        float iou = Math.max(iou(tr.box, det.box), iou(predictedBox, det.box));
        boolean sameClass = tr.label.equals(det.label);
        if (iou >= MIN_IOU) return (sameClass ? 1f : 0.6f) * (1f + iou);

        // Fast motion: centroid proximity around the PREDICTED position, wide gate.
        float dx = predictedBox.centerX() - det.box.centerX();
        float dy = predictedBox.centerY() - det.box.centerY();
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float span = Math.max(predictedBox.width(), predictedBox.height());
        float gate = Math.max(120f, Math.max(span * 1.2f, Math.min(width, height) * 0.22f));
        if (distance >= gate) return 0f;
        return (sameClass ? 0.9f : 0.5f) * (1f - distance / gate);
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
