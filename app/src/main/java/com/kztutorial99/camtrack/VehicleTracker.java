package com.kztutorial99.camtrack;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight IoU + centroid tracker with constant-velocity prediction.
 *
 * Design goals:
 *  - NO ghost boxes: only tracks matched on the current frame are published
 *  - ACCURATE DB ids: an id is handed out only after a track has been seen on
 *    several consecutive frames, is locked to the vehicle class, and survives
 *    short detector dropouts instead of being recycled into a new id
 *  - fast objects still match, because association uses the predicted position
 */
final class VehicleTracker {

    /** frames a track must be seen before it earns a DB id (kills flicker ids) */
    private static final int CONFIRM_HITS = 3;
    /** frames a confirmed track keeps its id alive while the detector misses it */
    private static final int MAX_MISSES = 12;
    /** frames a not-yet-confirmed candidate may miss before it is discarded */
    private static final int MAX_PENDING_MISSES = 2;
    /** box smoothing: high = snappy/responsive (little lag behind the vehicle) */
    private static final float SMOOTHING = 0.75f;
    private static final float MIN_IOU = 0.2f;
    /** a new id is only created for a reasonably confident detection */
    private static final float NEW_TRACK_SCORE = 0.35f;
    /** a fresh candidate overlapping this much with an existing track is a duplicate */
    private static final float DUPLICATE_IOU = 0.55f;

    static final class TrackedVehicle {
        final int id;
        final String label;
        final float score;
        final RectF box;
        final int sourceWidth;
        final int sourceHeight;
        final boolean fresh;
        /** real plate text read by OCR, or null when no plate was readable */
        final String plate;

        TrackedVehicle(int id, String label, float score, RectF box,
                       int sourceWidth, int sourceHeight, boolean fresh, String plate) {
            this.id = id;
            this.label = label;
            this.score = score;
            this.box = new RectF(box);
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.fresh = fresh;
            this.plate = plate;
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
        /** class is frozen once the id is published so the label cannot flip */
        boolean classLocked;
    }

    private final List<Track> tracks = new ArrayList<>();
    /** plate text per track id, filled in by the OCR reader (never invented) */
    private final Map<Integer, String> plates = new HashMap<>();
    private int nextId = 1;

    /** Attach an OCR-confirmed plate to a track id. */
    synchronized void setPlate(int trackId, String plate) {
        if (trackId > 0 && plate != null) plates.put(trackId, plate);
    }

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

        // Age unmatched tracks BEFORE spawning new ones, so a coasting track can
        // still absorb the next frame instead of losing its id to a fresh track.
        for (int t = n - 1; t >= 0; t--) {
            if (!trackUsed[t]) {
                Track tr = tracks.get(t);
                tr.misses++;
                // coast the box along its last velocity so the id survives a short miss
                tr.box.offset(tr.vx, tr.vy);
                boolean confirmed = tr.id > 0 && tr.hits >= CONFIRM_HITS;
                int allowed = confirmed ? MAX_MISSES : MAX_PENDING_MISSES;
                if (tr.misses > allowed) {
                    plates.remove(tr.id);
                    tracks.remove(t);
                }
            }
        }

        // Unmatched detections become candidates. An id is handed out only after
        // CONFIRM_HITS frames, and duplicates of an existing track are ignored.
        for (int d = 0; d < detections.size(); d++) {
            if (detUsed[d]) continue;
            DetectionInput det = detections.get(d);
            if (det.score < NEW_TRACK_SCORE) continue;
            if (overlapsExisting(det)) continue;
            Track tr = new Track();
            tr.id = 0;
            tr.label = det.label;
            tr.box.set(det.box);
            tr.score = det.score;
            tr.hits = 1;
            vote(tr, det.label);
            tracks.add(tr);
        }

        // Publish ONLY vehicles seen on this very frame: no leftover boxes.
        List<TrackedVehicle> result = new ArrayList<>(tracks.size());
        for (Track tr : tracks) {
            if (tr.misses != 0 || tr.hits < CONFIRM_HITS || tr.id <= 0) continue;
            result.add(new TrackedVehicle(tr.id, tr.label, tr.score, tr.box,
                    sourceWidth, sourceHeight, true, plates.get(tr.id)));
        }
        return result;
    }

    private boolean overlapsExisting(DetectionInput det) {
        for (Track tr : tracks) {
            if (iou(tr.box, det.box) >= DUPLICATE_IOU) return true;
        }
        return false;
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
        vote(tr, det.label);
        if (tr.id <= 0 && tr.hits >= CONFIRM_HITS) {
            tr.id = nextId++;
            // freeze the class at the moment the DB id becomes visible
            tr.classLocked = true;
        }
    }

    private static void vote(Track tr, String label) {
        if ("motorcycle".equals(label)) tr.motorVotes++; else tr.carVotes++;
        if (!tr.classLocked) {
            tr.label = tr.motorVotes > tr.carVotes ? "motorcycle" : "car";
        }
    }

    private static float mix(float previous, float current) {
        return previous * (1f - SMOOTHING) + current * SMOOTHING;
    }

    private static float association(Track tr, RectF predictedBox, DetectionInput det, int width, int height) {
        // A locked track never accepts a detection of the other class: that swap
        // was the reason a DB id could suddenly belong to a different vehicle.
        if (tr.classLocked && !tr.label.equals(det.label)) return 0f;

        float iou = Math.max(iou(tr.box, det.box), iou(predictedBox, det.box));
        boolean sameClass = tr.label.equals(det.label);
        if (iou >= MIN_IOU) return (sameClass ? 1f : 0.6f) * (1f + iou);

        // Fast motion: centroid proximity around the PREDICTED position.
        float dx = predictedBox.centerX() - det.box.centerX();
        float dy = predictedBox.centerY() - det.box.centerY();
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float span = Math.max(predictedBox.width(), predictedBox.height());
        // tighter gate than before: a wide gate let a nearby vehicle steal the id
        float gate = Math.max(60f, Math.min(span * 0.9f, Math.min(width, height) * 0.14f));
        if (distance >= gate) return 0f;
        // size sanity: a match must be roughly the same box size
        float sizeRatio = Math.min(area(predictedBox), area(det.box))
                / Math.max(1f, Math.max(area(predictedBox), area(det.box)));
        if (sizeRatio < 0.25f) return 0f;
        return (sameClass ? 0.9f : 0.5f) * (1f - distance / gate) * sizeRatio;
    }

    private static float area(RectF r) {
        return Math.max(0f, r.width()) * Math.max(0f, r.height());
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
        plates.clear();
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
