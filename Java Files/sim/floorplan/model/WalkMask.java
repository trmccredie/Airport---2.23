package sim.floorplan.model;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

public class WalkMask {
    private final int width;
    private final int height;
    // true = walkable, false = blocked
    private final boolean[] walkable;

    // ✅ increments whenever the mask meaningfully changes
    private int version = 0;

    public WalkMask(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid mask size.");
        this.width = width;
        this.height = height;
        this.walkable = new boolean[width * height];
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    /** ✅ Monotonic version used to invalidate routing caches. */
    public int getVersion() { return version; }

    private void bumpVersion() { version++; }

    public boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    public boolean isWalkable(int x, int y) {
        if (!inBounds(x, y)) return false;
        return walkable[y * width + x];
    }

    public void setWalkable(int x, int y, boolean value) {
        if (!inBounds(x, y)) return;
        int idx = y * width + x;
        if (walkable[idx] == value) return; // ✅ no-op -> no version bump
        walkable[idx] = value;
        bumpVersion();
    }

    /** Fast set without repeated version bumps (callers can bump once). */
    private void setWalkableNoVersion(int x, int y, boolean value) {
        if (!inBounds(x, y)) return;
        walkable[y * width + x] = value;
    }

    public void fillWalkable(boolean value) {
        boolean changed = false;
        for (int i = 0; i < walkable.length; i++) {
            if (walkable[i] != value) {
                walkable[i] = value;
                changed = true;
            }
        }
        if (changed) bumpVersion();
    }

    /**
     * ✅ Brush fill: sets a circle of radius r around (cx,cy) to walkable/blocked.
     * Bumps version once if anything changed.
     */
    public void fillCircle(int cx, int cy, int radius, boolean value) {
        int r = Math.max(0, radius);
        if (r == 0) {
            setWalkable(cx, cy, value);
            return;
        }

        int r2 = r * r;

        int x0 = Math.max(0, cx - r);
        int x1 = Math.min(width - 1, cx + r);
        int y0 = Math.max(0, cy - r);
        int y1 = Math.min(height - 1, cy + r);

        boolean changed = false;

        for (int y = y0; y <= y1; y++) {
            int dy = y - cy;
            int dy2 = dy * dy;
            for (int x = x0; x <= x1; x++) {
                int dx = x - cx;
                if (dx * dx + dy2 <= r2) {
                    int idx = y * width + x;
                    if (walkable[idx] != value) {
                        walkable[idx] = value;
                        changed = true;
                    }
                }
            }
        }

        if (changed) bumpVersion();
    }

    /**
     * ✅ Polygon fill: sets pixels inside polygon to walkable/blocked.
     * Bumps version once if anything changed.
     */
    public void fillPolygon(Polygon poly, boolean value) {
        if (poly == null || poly.npoints < 3) return;

        Rectangle b = poly.getBounds();
        if (b.width <= 0 || b.height <= 0) return;

        int x0 = Math.max(0, b.x);
        int y0 = Math.max(0, b.y);
        int x1 = Math.min(width - 1, b.x + b.width);
        int y1 = Math.min(height - 1, b.y + b.height);

        boolean changed = false;

        for (int y = y0; y <= y1; y++) {
            int row = y * width;
            for (int x = x0; x <= x1; x++) {
                if (!poly.contains(x + 0.5, y + 0.5)) continue;
                int idx = row + x;
                if (walkable[idx] != value) {
                    walkable[idx] = value;
                    changed = true;
                }
            }
        }

        if (changed) bumpVersion();
    }

    public WalkMask copy() {
        WalkMask c = new WalkMask(width, height);
        System.arraycopy(this.walkable, 0, c.walkable, 0, this.walkable.length);
        c.version = this.version;
        return c;
    }

    /**
     * Utility: builds a semi-transparent overlay image.
     * Walkable pixels -> green tint, blocked -> red tint.
     */
    public BufferedImage toOverlayImage(int alpha /*0..255*/) {
        int a = Math.max(0, Math.min(255, alpha));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int walkARGB  = (a << 24) | (0x00 << 16) | (0xCC << 8) | 0x00; // green-ish
        int blockARGB = (a << 24) | (0xCC << 16) | (0x00 << 8) | 0x00; // red-ish

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, walkable[row + x] ? walkARGB : blockARGB);
            }
        }
        return out;
    }

    /**
     * Save-friendly representation:
     * White = walkable, Black = blocked (TYPE_BYTE_BINARY).
     */
    public BufferedImage toBinaryImage() {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        int white = 0xFFFFFF;
        int black = 0x000000;

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, walkable[row + x] ? white : black);
            }
        }
        return out;
    }

    /**
     * Load from binary image (white-ish => walkable).
     */
    public static WalkMask fromBinaryImage(BufferedImage img) {
        if (img == null) throw new IllegalArgumentException("img is null");
        int w = img.getWidth();
        int h = img.getHeight();
        WalkMask m = new WalkMask(w, h);

        boolean any = false;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                boolean v = (rgb != 0x000000);
                int idx = y * w + x;
                if (m.walkable[idx] != v) {
                    m.walkable[idx] = v;
                    any = true;
                }
            }
        }
        if (any) m.bumpVersion();
        return m;
    }
}
