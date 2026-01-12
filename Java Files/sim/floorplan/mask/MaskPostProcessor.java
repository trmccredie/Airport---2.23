package sim.floorplan.mask;

import sim.floorplan.model.WalkMask;

import java.util.ArrayDeque;

public class MaskPostProcessor {

    /**
     * Flood-fill from borders through WALKABLE pixels.
     * Anything reachable from the border is "outside" -> set to BLOCKED.
     */
    public static WalkMask removeOutsideByBorderFloodFill(WalkMask mask) {
        int w = mask.getWidth();
        int h = mask.getHeight();

        boolean[] visited = new boolean[w * h];
        ArrayDeque<int[]> q = new ArrayDeque<>();

        // enqueue border walkable pixels
        for (int x = 0; x < w; x++) {
            tryEnqueue(mask, visited, q, x, 0);
            tryEnqueue(mask, visited, q, x, h - 1);
        }
        for (int y = 0; y < h; y++) {
            tryEnqueue(mask, visited, q, 0, y);
            tryEnqueue(mask, visited, q, w - 1, y);
        }

        // BFS 4-neighbor
        while (!q.isEmpty()) {
            int[] p = q.removeFirst();
            int x = p[0], y = p[1];

            tryEnqueue(mask, visited, q, x + 1, y);
            tryEnqueue(mask, visited, q, x - 1, y);
            tryEnqueue(mask, visited, q, x, y + 1);
            tryEnqueue(mask, visited, q, x, y - 1);
        }

        // visited == outside => block it
        WalkMask out = mask.copy();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (visited[row + x]) out.setWalkable(x, y, false);
            }
        }
        return out;
    }

    private static void tryEnqueue(WalkMask mask, boolean[] visited, ArrayDeque<int[]> q, int x, int y) {
        int w = mask.getWidth();
        int h = mask.getHeight();
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int idx = y * w + x;
        if (visited[idx]) return;
        if (!mask.isWalkable(x, y)) return;
        visited[idx] = true;
        q.addLast(new int[]{x, y});
    }

    /**
     * Inflates blocked pixels by radiusPx.
     * (i.e., if a pixel is within radius of any blocked pixel, it becomes blocked)
     */
    public static WalkMask inflateWalls(WalkMask mask, int radiusPx) {
        int r = Math.max(0, radiusPx);
        if (r == 0) return mask.copy();

        int w = mask.getWidth();
        int h = mask.getHeight();

        WalkMask out = mask.copy();

        // Collect blocked pixels first (walls tend to be sparse)
        int[] xs = new int[w * h];
        int[] ys = new int[w * h];
        int n = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!mask.isWalkable(x, y)) {
                    xs[n] = x;
                    ys[n] = y;
                    n++;
                }
            }
        }

        int rr = r * r;

        for (int i = 0; i < n; i++) {
            int cx = xs[i];
            int cy = ys[i];

            int minX = Math.max(0, cx - r);
            int maxX = Math.min(w - 1, cx + r);
            int minY = Math.max(0, cy - r);
            int maxY = Math.min(h - 1, cy + r);

            for (int y = minY; y <= maxY; y++) {
                int dy = y - cy;
                for (int x = minX; x <= maxX; x++) {
                    int dx = x - cx;
                    if (dx * dx + dy * dy <= rr) {
                        out.setWalkable(x, y, false);
                    }
                }
            }
        }

        return out;
    }
}