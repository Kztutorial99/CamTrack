package com.kztutorial99.camtrack;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.util.Log;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real licence-plate reader (ML Kit on-device OCR).
 *
 * A plate is published only when OCR really reads an Indonesian plate on the
 * vehicle. Compared with the previous version this reader:
 *  - accepts a list of candidate crops per vehicle (different vertical bands),
 *    so the plate does not have to sit exactly where we guessed;
 *  - reads line by line instead of on one flattened string, which stops scene
 *    text on other lines from being glued into a fake plate;
 *  - votes on the region+number only and keeps the best suffix seen, so
 *    "DB 8087" and "DB 8087 KB" no longer cancel each other out;
 *  - pre-processes each crop (grayscale + contrast + strong upscale) because
 *    ML Kit needs roughly 30 px character height to be reliable.
 */
final class PlateReader {

    interface Listener {
        /** Called on a background thread with a confirmed plate for a track id. */
        void onPlate(int trackId, String plate);
    }

    private static final String TAG = "CamTrack";
    /** Indonesian format; suffix optional (distant plates lose the small row first). */
    private static final Pattern PLATE = Pattern.compile(
            "\\b([A-Z]{1,2})[\\s.\\-]{0,3}([0-9OQDILSBZ]{2,4})(?:[\\s.\\-]{0,3}([A-Z]{1,3}))?\\b");
    /** Known Indonesian registration prefixes; prevents ordinary scene text becoming a plate. */
    private static final Set<String> PREFIXES = new HashSet<>(Arrays.asList(
            "A", "B", "D", "E", "F", "G", "H", "K", "L", "M", "N", "P", "R", "S", "T", "W", "Z",
            "AA", "AB", "AD", "AE", "AG", "BA", "BB", "BD", "BE", "BG", "BH", "BK", "BL", "BM",
            "BN", "BP", "DA", "DB", "DC", "DD", "DE", "DG", "DH", "DK", "DL", "DM", "DN", "DR",
            "DS", "DT", "DW", "EA", "EB", "ED", "KB", "KH", "KT", "KU", "PA", "PB"));
    /** how many times the same region+number must be read before it is trusted */
    private static final int VOTES_NEEDED = 2;
    /** OCR needs tall characters; a plate band is upscaled to at least this height. */
    private static final int MIN_OCR_HEIGHT = 320;
    private static final int MAX_OCR_HEIGHT = 720;

    private final TextRecognizer recognizer;
    private final Listener listener;
    private final Map<Integer, Map<String, int[]>> votes = new HashMap<>();
    private final Map<Integer, Map<String, String>> suffixes = new HashMap<>();
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

    /** Runs OCR over several candidate crops of one vehicle, stopping at the first hit. */
    void submit(final int trackId, List<Bitmap> crops) {
        if (recognizer == null || crops == null || crops.isEmpty()) return;
        if (busy) return;
        busy = true;
        runNext(trackId, crops, 0);
    }

    private void runNext(final int trackId, final List<Bitmap> crops, final int index) {
        if (index >= crops.size()) { busy = false; return; }
        Bitmap crop = crops.get(index);
        if (crop == null) { runNext(trackId, crops, index + 1); return; }
        try {
            recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener(text -> {
                        String plate = null;
                        try {
                            plate = extract(text);
                        } catch (Throwable t) {
                            Log.w(TAG, "Plate parse failed", t);
                        }
                        if (plate != null) {
                            vote(trackId, plate);
                            busy = false;
                        } else {
                            runNext(trackId, crops, index + 1);
                        }
                    })
                    .addOnFailureListener(e -> runNext(trackId, crops, index + 1));
        } catch (Throwable t) {
            Log.w(TAG, "Plate OCR submit failed", t);
            busy = false;
        }
    }

    private void vote(int trackId, String plate) {
        String result = null;
        synchronized (this) {
            if (confirmed.containsKey(trackId)) return;
            String[] parts = plate.split(" ");
            String key = parts[0] + " " + parts[1];
            String suffix = parts.length > 2 ? parts[2] : null;

            Map<String, int[]> counts = votes.get(trackId);
            if (counts == null) { counts = new HashMap<>(); votes.put(trackId, counts); }
            int[] cell = counts.get(key);
            if (cell == null) { cell = new int[]{0}; counts.put(key, cell); }
            cell[0]++;

            if (suffix != null) {
                Map<String, String> best = suffixes.get(trackId);
                if (best == null) { best = new HashMap<>(); suffixes.put(trackId, best); }
                best.put(key, suffix);
            }

            if (cell[0] >= VOTES_NEEDED) {
                Map<String, String> best = suffixes.get(trackId);
                String keep = best == null ? null : best.get(key);
                result = keep == null ? key : key + " " + keep;
                confirmed.put(trackId, result);
                votes.remove(trackId);
                suffixes.remove(trackId);
            }
        }
        if (result != null && listener != null) listener.onPlate(trackId, result);
    }

    /**
     * Looks for a plate line by line (and on pairs of stacked lines, because the
     * suffix row is often recognised separately), never on the whole blob.
     */
    private static String extract(Text text) {
        if (text == null) return null;
        List<String> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText();
                if (value != null && !value.trim().isEmpty()) lines.add(value.trim());
            }
        }
        for (String line : lines) {
            String hit = match(line);
            if (hit != null) return hit;
        }
        for (int i = 0; i + 1 < lines.size(); i++) {
            String hit = match(lines.get(i) + " " + lines.get(i + 1));
            if (hit != null) return hit;
        }
        return match(text.getText());
    }

    /** Pulls the first plate-looking token, normalized as "DB 8087 KB". */
    private static String match(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String cleaned = raw.toUpperCase()
                .replace('|', 'I')
                .replaceAll("[^A-Z0-9\\s.\\-]", " ");
        Matcher m = PLATE.matcher(cleaned);
        while (m.find()) {
            String prefix = m.group(1);
            String digits = normalizeDigits(m.group(2));
            String suffix = m.group(3);
            if (prefix == null || digits == null || !PREFIXES.contains(prefix)) continue;
            if (digits.length() < 2 || digits.length() > 4) continue;
            return suffix == null || suffix.isEmpty()
                    ? prefix + " " + digits
                    : prefix + " " + digits + " " + suffix;
        }
        return null;
    }

    /** Correct only the numeric group, never the DB prefix or letter suffix. */
    private static String normalizeDigits(String value) {
        if (value == null) return null;
        return value.replace('O', '0').replace('Q', '0').replace('D', '0')
                .replace('I', '1').replace('L', '1').replace('S', '5')
                .replace('B', '8').replace('Z', '2');
    }

    /**
     * Grayscale + contrast boost + upscale. Plate characters on a 720p frame are
     * only a handful of pixels tall; without this ML Kit simply returns nothing.
     */
    static Bitmap enhance(Bitmap source) {
        if (source == null) return null;
        try {
            int height = source.getHeight();
            float factor = 1f;
            if (height < MIN_OCR_HEIGHT) factor = Math.min(6f, MIN_OCR_HEIGHT / (float) height);
            int outH = Math.min(MAX_OCR_HEIGHT, Math.max(2, Math.round(height * factor)));
            int outW = Math.max(2, Math.round(source.getWidth() * (outH / (float) height)));

            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

            ColorMatrix gray = new ColorMatrix();
            gray.setSaturation(0f);
            float c = 1.7f;                     // contrast
            float t = (-0.5f * c + 0.5f) * 255f; // keep mid grey in place
            ColorMatrix contrast = new ColorMatrix(new float[]{
                    c, 0, 0, 0, t,
                    0, c, 0, 0, t,
                    0, 0, c, 0, t,
                    0, 0, 0, 1, 0});
            gray.postConcat(contrast);
            paint.setColorFilter(new ColorMatrixColorFilter(gray));

            canvas.drawBitmap(source, null, new android.graphics.Rect(0, 0, outW, outH), paint);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "Plate enhance failed", t);
            return source;
        }
    }

    synchronized void forget(int trackId) {
        votes.remove(trackId);
        suffixes.remove(trackId);
        confirmed.remove(trackId);
    }

    synchronized void clear() {
        votes.clear();
        suffixes.clear();
        confirmed.clear();
    }

    void close() {
        try { if (recognizer != null) recognizer.close(); } catch (Throwable ignored) { }
    }
}
