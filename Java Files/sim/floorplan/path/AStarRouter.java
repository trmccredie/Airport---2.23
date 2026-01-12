package sim.floorplan.path;

import sim.floorplan.model.WalkMask;

import java.awt.Point;
import java.util.*;

/**
 * Fast A* router over a WalkMask using a coarse grid "stride" (e.g., 4px).
 * Returns path points in IMAGE pixel coordinates suitable for drawing.
 */
public class AStarRouter {

    private static final class Node {
        final int idx;
        final float f;
        Node(int idx, float f) { this.idx = idx; this.f = f; }
    }

    public static List<Point> findPath(
            WalkMask mask,
            Point startPx,
            Point goalPx,
            int stridePx,
            int maxExpanded,
            boolean allowDiagonal
    ) {
        if (mask == null || startPx == null || goalPx == null) return null;

        final int w = mask.getWidth();
        final int h = mask.getHeight();
        final int stride = Math.max(1, stridePx);

        Point s = snapToNearestWalkable(mask, startPx, stride, 240);
        Point g = snapToNearestWalkable(mask, goalPx, stride, 240);
        if (s == null || g == null) return null;

        final int gw = (w + stride - 1) / stride;
        final int gh = (h + stride - 1) / stride;
        final int n = gw * gh;

        int sx = clamp(s.x / stride, 0, gw - 1);
        int sy = clamp(s.y / stride, 0, gh - 1);
        int gx = clamp(g.x / stride, 0, gw - 1);
        int gy = clamp(g.y / stride, 0, gh - 1);

        int startIdx = sy * gw + sx;
        int goalIdx  = gy * gw + gx;

        float[] gScore = new float[n];
        Arrays.fill(gScore, Float.POSITIVE_INFINITY);

        int[] cameFrom = new int[n];
        Arrays.fill(cameFrom, -1);

        boolean[] closed = new boolean[n];

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a.f));

        gScore[startIdx] = 0f;
        open.add(new Node(startIdx, heuristicOctile(sx, sy, gx, gy)));

        int expanded = 0;

        // 4-neighbor
        final int[] dx4 = { 1, -1, 0, 0 };
        final int[] dy4 = { 0, 0, 1, -1 };

        // 8-neighbor
        final int[] dx8 = { 1, -1, 0, 0,  1, 1, -1, -1 };
        final int[] dy8 = { 0, 0, 1, -1, 1,-1,  1, -1 };

        while (!open.isEmpty() && expanded < maxExpanded) {
            Node cur = open.poll();
            int cIdx = cur.idx;
            if (closed[cIdx]) continue;
            closed[cIdx] = true;

            if (cIdx == goalIdx) {
                List<Point> raw = reconstructPath(cameFrom, gw, stride, w, h, goalIdx);

                raw = simplifyCollinear(raw);
                raw = smoothLineOfSight(mask, raw);
                raw = simplifyCollinear(raw);

                return raw;
            }

            expanded++;

            int cx = cIdx % gw;
            int cy = cIdx / gw;

            int cpx = Math.min(w - 1, cx * stride);
            int cpy = Math.min(h - 1, cy * stride);

            int[] dx = allowDiagonal ? dx8 : dx4;
            int[] dy = allowDiagonal ? dy8 : dy4;

            for (int k = 0; k < dx.length; k++) {
                int nx = cx + dx[k];
                int ny = cy + dy[k];
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;

                int npx = Math.min(w - 1, nx * stride);
                int npy = Math.min(h - 1, ny * stride);

                if (!mask.isWalkable(npx, npy)) continue;

                boolean isDiag = (dx[k] != 0 && dy[k] != 0);

                // Prevent diagonal corner-cutting: require both cardinal neighbors open
                if (isDiag) {
                    int px1 = Math.min(w - 1, (cx + dx[k]) * stride);
                    int py1 = Math.min(h - 1, (cy) * stride);
                    int px2 = Math.min(w - 1, (cx) * stride);
                    int py2 = Math.min(h - 1, (cy + dy[k]) * stride);
                    if (!mask.isWalkable(px1, py1) || !mask.isWalkable(px2, py2)) continue;
                }

                // ✅ Critical fix: stride steps can "jump through" walls.
                // Ensure the segment between coarse nodes is fully walkable.
                if (!segmentAllWalkable(mask, cpx, cpy, npx, npy)) continue;

                int nIdx = ny * gw + nx;
                if (closed[nIdx]) continue;

                float stepCost = isDiag ? 1.41421356f : 1.0f;
                float tentative = gScore[cIdx] + stepCost;

                if (tentative < gScore[nIdx]) {
                    cameFrom[nIdx] = cIdx;
                    gScore[nIdx] = tentative;

                    float f = tentative + heuristicOctile(nx, ny, gx, gy);
                    open.add(new Node(nIdx, f));
                }
            }
        }

        return null;
    }

    /**
     * Snap the input pixel to nearest WALKABLE pixel on the stride grid.
     * Searches outward in rings up to maxRadiusPx.
     */
    public static Point snapToNearestWalkable(WalkMask mask, Point p, int stridePx, int maxRadiusPx) {
        if (mask == null || p == null) return null;
        int w = mask.getWidth();
        int h = mask.getHeight();
        int stride = Math.max(1, stridePx);

        int x0 = clamp(p.x, 0, w - 1);
        int y0 = clamp(p.y, 0, h - 1);

        // snap to stride grid
        int sx = (x0 / stride) * stride;
        int sy = (y0 / stride) * stride;
        sx = clamp(sx, 0, w - 1);
        sy = clamp(sy, 0, h - 1);

        if (mask.isWalkable(sx, sy)) return new Point(sx, sy);

        int maxSteps = Math.max(1, maxRadiusPx / stride);

        for (int r = 1; r <= maxSteps; r++) {
            // scan the ring (square border) at radius r in coarse steps
            for (int dx = -r; dx <= r; dx++) {
                int xA = sx + dx * stride;
                int yA = sy - r * stride;
                int yB = sy + r * stride;

                if (inBounds(mask, xA, yA) && mask.isWalkable(xA, yA)) return new Point(xA, yA);
                if (inBounds(mask, xA, yB) && mask.isWalkable(xA, yB)) return new Point(xA, yB);
            }
            for (int dy = -r + 1; dy <= r - 1; dy++) {
                int yA = sy + dy * stride;
                int xA = sx - r * stride;
                int xB = sx + r * stride;

                if (inBounds(mask, xA, yA) && mask.isWalkable(xA, yA)) return new Point(xA, yA);
                if (inBounds(mask, xB, yA) && mask.isWalkable(xB, yA)) return new Point(xB, yA);
            }
        }

        return null;
    }

    // ---------- smoothing ----------

    private static List<Point> smoothLineOfSight(WalkMask mask, List<Point> path) {
        if (mask == null || path == null || path.size() <= 2) return path;

        ArrayList<Point> out = new ArrayList<>();
        int i = 0;
        out.add(path.get(0));

        while (i < path.size() - 1) {
            int best = i + 1;

            // try farthest reachable point
            for (int j = path.size() - 1; j > i + 1; j--) {
                if (hasLineOfSight(mask, path.get(i), path.get(j))) {
                    best = j;
                    break;
                }
            }

            out.add(path.get(best));
            i = best;
        }

        return out;
    }

    /**
     * Pixel-accurate line check using Bresenham.
     * Returns true only if every pixel on the segment is walkable.
     */
    private static boolean hasLineOfSight(WalkMask mask, Point a, Point b) {
        if (mask == null || a == null || b == null) return false;
        return segmentAllWalkable(mask, a.x, a.y, b.x, b.y);
    }

    /**
     * ✅ Segment walkability check (Bresenham).
     * Used both for smoothing and to prevent stride-jumps through walls.
     */
    private static boolean segmentAllWalkable(WalkMask mask, int x0, int y0, int x1, int y1) {
        if (mask == null) return false;
        if (!inBounds(mask, x0, y0) || !inBounds(mask, x1, y1)) return false;

        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = (x0 < x1) ? 1 : -1;
        int sy = (y0 < y1) ? 1 : -1;

        int err = dx - dy;

        while (true) {
            if (!mask.isWalkable(x0, y0)) return false;
            if (x0 == x1 && y0 == y1) break;

            int e2 = err << 1;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 <  dx) { err += dx; y0 += sy; }
        }
        return true;
    }

    // ---------- internals ----------

    private static boolean inBounds(WalkMask m, int x, int y) {
        return x >= 0 && y >= 0 && x < m.getWidth() && y < m.getHeight();
    }

    private static float heuristicOctile(int x, int y, int gx, int gy) {
        int dx = Math.abs(gx - x);
        int dy = Math.abs(gy - y);
        int min = Math.min(dx, dy);
        int max = Math.max(dx, dy);
        return (float) (min * 1.41421356 + (max - min));
    }

    private static List<Point> reconstructPath(int[] cameFrom, int gw, int stride, int w, int h, int goalIdx) {
        ArrayList<Point> rev = new ArrayList<>();
        int idx = goalIdx;
        while (idx != -1) {
            int x = (idx % gw) * stride;
            int y = (idx / gw) * stride;
            x = Math.min(w - 1, Math.max(0, x));
            y = Math.min(h - 1, Math.max(0, y));
            rev.add(new Point(x, y));
            idx = cameFrom[idx];
        }
        Collections.reverse(rev);
        return rev;
    }

    /** Keep only turning points. */
    private static List<Point> simplifyCollinear(List<Point> path) {
        if (path == null || path.size() <= 2) return path;

        ArrayList<Point> out = new ArrayList<>();
        out.add(path.get(0));

        int lastDx = 0, lastDy = 0;

        for (int i = 1; i < path.size(); i++) {
            Point prev = out.get(out.size() - 1);
            Point cur = path.get(i);

            int dx = Integer.compare(cur.x - prev.x, 0);
            int dy = Integer.compare(cur.y - prev.y, 0);

            if (i == 1) {
                lastDx = dx; lastDy = dy;
                out.add(cur);
                continue;
            }

            if (dx == lastDx && dy == lastDy) {
                out.set(out.size() - 1, cur);
            } else {
                out.add(cur);
                lastDx = dx; lastDy = dy;
            }
        }

        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}