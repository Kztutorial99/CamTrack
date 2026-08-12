package com.kztutorial99.camtrack;

import android.graphics.Bitmap;
import android.util.Log;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real licence-plate reader (ML Kit on-device OCR).
 *
 * The old build printed "DB-<track id>" for EVERY box, so a vehicle with no
 * readable plate still looked like it had one and the number never matched the
 * real plate. Here a plate string is published ONLY when OCR actually reads a
 * valid Indonesian plate on the vehicle, and only after the same text has been
 * read on two separate frames (kills single-frame misreads).
 */
final class PlateReader {

    interface Listener {
        /** Called on a background thread with a confirmed plate for a track id. */
        void onPlate(int trackId, String plate);
    }

    private static final String TAG = "CamTrack";
    /** Indonesian format: 1-2 letters, 1-4 digits, 1-3 letters (e.g. DB 8087 KB). */
    private static final Pattern PLATE = Pattern.compile(
            "([A-Z]{1,2})[\\s.\\-]{0,2}(\\d{2,4})[\\s.\\-]{0,2}([A-Z]{1,3})");
    /** how many times the same text must be read before it is trusted */
    private static final int VOTES_NEEDED = 2;

    private final TextRecognizer recognizer;
    private final Listener listener;
    private final Map<Integer, Map<String, Integer>> votes = new HashMap<>();
    private final Map<Integer, String> confirmed = new HashMap<>();
    private volatile boolean busy = false;

    PlateReader(Listener listener) {
        this.listener = listener;
        TextRecognizer created = null;
        try {
            created = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        } catch (Throwable t) {
            Log.w(TAG, "Plate OCR unavailable", t);
        }
        this.recognizer = created;
    }

    boolean available() { return recognizer != null; }

    /** True while an OCR pass is still running: callers skip submitting more work. */
    boolean busy() { return busy; }

    synchronized String plateOf(int trackId) { return confirmed.get(trackId); }

    /** Runs OCR on a tight crop around the vehicle's plate area. */
    void submit(final int trackId, Bitmap crop) {
        if (recognizer == null || crop == null) return;
        if (busy) return;
        busy = true;
        try {
            recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener(text -> {
                        busy = false;
                        try {
                            String plate = extract(text == null ? null : text.getText());
                            if (plate != null) vote(trackId, plate);
                        } catch (Throwable t) {
                            Log.w(TAG, "Plate parse failed", t);
                        }
                    })
                    .addOnFailureListener(e -> busy = false);
        } catch (Throwable t) {
            busy = false;
            Log.w(TAG, "Plate OCR submit failed", t);
        }
    }

    private void vote(int trackId, String plate) {
        String result = null;
        synchronized (this) {
            if (confirmed.containsKey(trackId)) return;
            Map<String, Integer> counts = votes.get(trackId);
            if (counts == null) {
                counts = new HashMap<>();
                votes.put(trackId, counts);
            }
            int count = (counts.containsKey(plate) ? counts.get(plate) : 0) + 1;
            counts.put(plate, count);
            if (count >= VOTES_NEEDED) {
                confirmed.put(trackId, plate);
                votes.remove(trackId);
                result = plate;
            }
        }
        if (result != null && listener != null) listener.onPlate(trackId, result);
    }

    /** Pulls the first text block that looks like a plate, normalized as "DB 8087 KB". */
    private static String extract(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String cleaned = raw.toUpperCase()
                .replace('O', '0')
                .replace('|', '1')
                .replaceAll("[^A-Z0-9\\s.\\-]", " ");
        // letters must stay letters in the prefix/suffix groups, so match on a
        // version where 0 can also be read back as the letter O.
        Matcher m = PLATE.matcher(cleaned.replaceAll("(?<![0-9])0(?![0-9])", "O"));
        while (m.find()) {
            String prefix = m.group(1);
            String digits = m.group(2);
            String suffix = m.group(3);
            if (prefix == null || digits == null || suffix == null) continue;
            if (digits.length() < 2) continue;
            return prefix + " " + digits + " " + suffix;
        }
        return null;
    }

    synchronized void forget(int trackId) {
        votes.remove(trackId);
        confirmed.remove(trackId);
    }

    synchronized void clear() {
        votes.clear();
        confirmed.clear();
    }

    void close() {
        try { if (recognizer != null) recognizer.close(); } catch (Throwable ignored) { }
    }
}
