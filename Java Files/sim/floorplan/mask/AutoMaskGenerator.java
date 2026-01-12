package sim.floorplan.mask;

import sim.floorplan.model.WalkMask;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class AutoMaskGenerator {

    /** Parameters you can expose in UI (you already have thr + inflate). */
    public static class Params {
        public int threshold = 200;         // grayscale threshold: darker => blocked
        public int inflatePx = 6;           // wall thickness
        public boolean removeOutside = true;

        /** NEW: extra dilation used ONLY for outside detection to “seal” door gaps. */
        public int sealGapsPx = 14;

        /** NEW: if true, ignore threshold and compute it automatically (Otsu). */
        public boolean autoThreshold = false;
    }

    /** Backward-compatible signature (your existing call sites). */
    public static WalkMask generate(BufferedImage img, int threshold, int inflatePx) {
        Params p = new Params();
        p.threshold = threshold;
        p.inflatePx = inflatePx;
        return generate(img, p);
    }

    /** New improved generator. */
    public static WalkMask generate(BufferedImage img, Params p) {
        if (img == null) throw new IllegalArgumentException("img is null");

        int w = img.getWidth();
        int h = img.getHeight();

        // 1) Grayscale
        int[] gray = toGrayscale(img);

        // 2) Threshold (manual or auto)
        int thr = (p != null && p.autoThreshold) ? otsuThreshold(gray) : (p == null ? 200 : p.threshold);
        thr = clamp(thr, 0, 255);

        // 3) Raw blocked map: dark pixels = blocked
        boolean[] blocked = new boolean[w * h];
        for (int i = 0; i < blocked.length; i++) {
            blocked[i] = gray[i] < thr;
        }

        // 4) Inflate walls (thicken blocked)
        int inflate = Math.max(0, p == null ? 6 : p.inflatePx);
        boolean[] blockedInflated = (inflate > 0) ? dilate(blocked, w, h, inflate) : blocked;

        // 5) Remove outside (FIX: seal gaps FIRST so flood-fill can't leak through doors)
        boolean removeOutside = (p == null) || p.removeOutside;
        if (removeOutside) {
            int seal = Math.max(0, p == null ? 14 : p.sealGapsPx);

            // Only for outside-detection: extra dilation to “close” perimeter openings.
            boolean[] sealed = (seal > 0) ? dilate(blockedInflated, w, h, seal) : blockedInflated;

            boolean[] outside = floodFillOutside(sealed, w, h);
            // Anything reachable from image border through “walkable” is outside => force blocked
            for (int i = 0; i < outside.length; i++) {
                if (outside[i]) blockedInflated[i] = true;
            }
        }

        // 6) Convert to WalkMask (walkable = NOT blocked)
        WalkMask mask = newMask(w, h);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                boolean walkable = !blockedInflated[row + x];
                mask.setWalkable(x, y, walkable);
            }
        }
        return mask;
    }

    // =========================
    // Helpers
    // =========================

    private static int[] toGrayscale(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] out = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = (argb) & 0xFF;

                // Luma (good for plans)
                int gray = (int) Math.round(0.2126 * r + 0.7152 * g + 0.0722 * b);
                out[y * w + x] = gray;
            }
        }
        return out;
    }

    /** Otsu threshold: robust for “black lines on white background” plans. */
    private static int otsuThreshold(int[] gray) {
        int[] hist = new int[256];
        for (int v : gray) hist[clamp(v, 0, 255)]++;

        int total = gray.length;
        long sum = 0;
        for (int i = 0; i < 256; i++) sum += (long) i * hist[i];

        long sumB = 0;
        int wB = 0;
        int wF;

        double varMax = -1.0;
        int threshold = 200;

        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;

            wF = total - wB;
            if (wF == 0) break;

            sumB += (long) t * hist[t];

            double mB = sumB / (double) wB;
            double mF = (sum - sumB) / (double) wF;

            double varBetween = (double) wB * (double) wF * (mB - mF) * (mB - mF);
            if (varBetween > varMax) {
                varMax = varBetween;
                threshold = t;
            }
        }
        return threshold;
    }

    /** Dilation with a circular structuring element. */
    private static boolean[] dilate(boolean[] src, int w, int h, int radius) {
        if (radius <= 0) return src;

        int r = radius;
        int rr = r * r;

        // offsets in a disk
        List<Point> offsets = new ArrayList<>();
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy <= rr) offsets.add(new Point(dx, dy));
            }
        }

        boolean[] out = new boolean[w * h];

        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (!src[row + x]) continue;

                for (Point o : offsets) {
                    int nx = x + o.x;
                    int ny = y + o.y;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    out[ny * w + nx] = true;
                }
            }
        }
        return out;
    }

    /**
     * Flood-fill "outside": start from border pixels that are WALKABLE (not blocked),
     * and mark everything reachable as outside.
     *
     * srcBlocked = true means blocked.
     * Outside fill moves through !blocked.
     */
    private static boolean[] floodFillOutside(boolean[] srcBlocked, int w, int h) {
        boolean[] outside = new boolean[w * h];
        ArrayDeque<Integer> q = new ArrayDeque<>();

        // Seed from borders where NOT blocked
        for (int x = 0; x < w; x++) {
            seedIfWalkable(0, x, srcBlocked, w, outside, q);
            seedIfWalkable(h - 1, x, srcBlocked, w, outside, q);
        }
        for (int y = 0; y < h; y++) {
            seedIfWalkable(y, 0, srcBlocked, w, outside, q);
            seedIfWalkable(y, w - 1, srcBlocked, w, outside, q);
        }

        // BFS 4-neighbor
        while (!q.isEmpty()) {
            int idx = q.removeFirst();
            int x = idx % w;
            int y = idx / w;

            // neighbors
            if (x > 0)       tryVisit(idx - 1, srcBlocked, outside, q);
            if (x < w - 1)   tryVisit(idx + 1, srcBlocked, outside, q);
            if (y > 0)       tryVisit(idx - w, srcBlocked, outside, q);
            if (y < h - 1)   tryVisit(idx + w, srcBlocked, outside, q);
        }

        return outside;
    }

    private static void seedIfWalkable(int y, int x, boolean[] blocked, int w,
                                       boolean[] outside, ArrayDeque<Integer> q) {
        int idx = y * w + x;
        if (!blocked[idx] && !outside[idx]) {
            outside[idx] = true;
            q.add(idx);
        }
    }

    private static void tryVisit(int idx, boolean[] blocked, boolean[] outside, ArrayDeque<Integer> q) {
        if (outside[idx]) return;
        if (blocked[idx]) return;
        outside[idx] = true;
        q.add(idx);
    }

    /** Create WalkMask with reflection so we don't depend on a specific constructor signature. */
    private static WalkMask newMask(int w, int h) {
        try {
            Constructor<WalkMask> c = WalkMask.class.getConstructor(int.class, int.class);
            return c.newInstance(w, h);
        } catch (Exception ignored) { }

        try {
            Constructor<WalkMask> c = WalkMask.class.getConstructor(int.class, int.class, boolean.class);
            return c.newInstance(w, h, true);
        } catch (Exception e) {
            throw new RuntimeException("WalkMask constructors not found. Need (int,int) or (int,int,boolean).", e);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}