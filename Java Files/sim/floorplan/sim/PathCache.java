package sim.floorplan.sim;

import sim.floorplan.model.WalkMask;

import java.awt.Point;
import java.util.*;

/**
 * LRU cache for A* routed paths on a WalkMask.
 *
 * Features:
 * - Caches paths between snapped walkable endpoints.
 * - Stores BOTH directions (A->B and B->A) for symmetry.
 * - Caches path metrics (cumulative distances + total length) so repeated length/time queries are cheap.
 * - Automatically invalidates when mask version changes or mask object swaps.
 * - Self-contained A* (no external router dependency).
 */
public class PathCache {

    private static final class Key {
        final int ax, ay, bx, by, stride;

        Key(int ax, int ay, int bx, int by, int stride) {
            this.ax = ax; this.ay = ay;
            this.bx = bx; this.by = by;
            this.stride = stride;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return ax == k.ax && ay == k.ay && bx == k.bx && by == k.by && stride == k.stride;
        }

        @Override public int hashCode() { return Objects.hash(ax, ay, bx, by, stride); }
    }

    private static final class Metrics {
        final double[] cum; // cumulative distance at each vertex (pixels)
        final double total; // total length (pixels)
        Metrics(double[] cum, double total) { this.cum = cum; this.total = total; }
    }

    private static final class Entry {
        final List<Point> path;     // may be null if no path
        final Metrics metrics;      // may be null if path null/degenerate
        Entry(List<Point> path, Metrics metrics) { this.path = path; this.metrics = metrics; }
    }

    private WalkMask mask;
    private WalkMask lastMaskRef;
    private int lastMaskVersion;

    private final int stridePx;
    private final boolean allowDiagonal;
    private final int maxEntries;

    /**
     * LRU cache (access-order). On eviction, entries (including metrics) fall out naturally.
     */
    private final LinkedHashMap<Key, Entry> cache;

    public PathCache(WalkMask mask, int stridePx, boolean allowDiagonal) {
        this(mask, stridePx, allowDiagonal, 4000);
    }

    public PathCache(WalkMask mask, int stridePx, boolean allowDiagonal, int maxEntries) {
        this.mask = mask;
        this.lastMaskRef = mask;
        this.lastMaskVersion = (mask == null) ? 0 : safeMaskVersion(mask);

        this.stridePx = Math.max(1, stridePx);
        this.allowDiagonal = allowDiagonal;
        this.maxEntries = Math.max(200, maxEntries);

        this.cache = new LinkedHashMap<Key, Entry>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
                return size() > PathCache.this.maxEntries;
            }
        };
    }

    public synchronized void setMask(WalkMask newMask) {
        if (this.mask != newMask) {
            this.mask = newMask;
            this.lastMaskRef = newMask;
            this.lastMaskVersion = (newMask == null) ? 0 : safeMaskVersion(newMask);
            clear();
        }
    }

    public synchronized WalkMask getMask() { return mask; }

    public synchronized void clear() { cache.clear(); }

    private synchronized void ensureFresh() {
        if (mask == null) return;

        // If mask object swapped without setMask, detect and clear anyway
        if (mask != lastMaskRef) {
            clear();
            lastMaskRef = mask;
            lastMaskVersion = safeMaskVersion(mask);
            return;
        }

        int v = safeMaskVersion(mask);
        if (v != lastMaskVersion) {
            clear();
            lastMaskVersion = v;
        }
    }

    // ----------------------------------------------------------------------
    // Core lookups
    // ----------------------------------------------------------------------

    /** Returns the cached routed path (A*). Null if no path found. */
    public List<Point> path(Point a, Point b) {
        Entry e = entry(a, b);
        return (e == null) ? null : e.path;
    }

    /** Cached routed polyline length (pixels). Falls back to straight-line if routing fails. */
    public double lengthPixels(Point a, Point b) {
        if (a == null || b == null) return 0.0;

        Entry e = entry(a, b);
        if (e != null && e.metrics != null && e.metrics.total > 0) {
            return e.metrics.total;
        }

        // If routing fails (mask gaps, etc.), fallback to Euclidean so sim still progresses.
        return a.distance(b);
    }

    /** Returns point at fraction t01 along the path measured by Euclidean distance. */
    public Point pointAlong(List<Point> path, double t01) {
        if (path == null || path.isEmpty()) return null;
        if (path.size() == 1) return path.get(0);

        double t = Math.max(0.0, Math.min(1.0, t01));

        Metrics m = computeMetrics(path);
        if (m.total <= 0.0001) return path.get(0);

        double target = t * m.total;

        int i = 1;
        while (i < m.cum.length && m.cum[i] < target) i++;

        if (i <= 0) return path.get(0);
        if (i >= path.size()) return path.get(path.size() - 1);

        Point p0 = path.get(i - 1);
        Point p1 = path.get(i);

        double d0 = m.cum[i - 1];
        double d1 = m.cum[i];
        double seg = Math.max(0.0001, d1 - d0);

        double u = (target - d0) / seg;
        u = Math.max(0.0, Math.min(1.0, u));

        int x = (int) Math.round(p0.x + (p1.x - p0.x) * u);
        int y = (int) Math.round(p0.y + (p1.y - p0.y) * u);
        return new Point(x, y);
    }

    /** Convenience: point along routed path between a and b (uses cached path). */
    public Point pointAlong(Point a, Point b, double t01) {
        List<Point> p = path(a, b);
        if (p == null || p.size() < 2) {
            if (a == null) return b;
            if (b == null) return a;
            double t = Math.max(0.0, Math.min(1.0, t01));
            int x = (int) Math.round(a.x + (b.x - a.x) * t);
            int y = (int) Math.round(a.y + (b.y - a.y) * t);
            return new Point(x, y);
        }
        return pointAlong(p, t01);
    }

    // ----------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------

    private Entry entry(Point a, Point b) {
        if (a == null || b == null) return null;

        WalkMask m;
        synchronized (this) {
            ensureFresh();
            m = this.mask;
        }
        if (m == null) return null;

        // Snap endpoints to nearest walkable for stable caching & no "start inside wall"
        Point aa = snapToNearestWalkable(m, a, stridePx, 240);
        Point bb = snapToNearestWalkable(m, b, stridePx, 240);
        if (aa == null || bb == null) return null;

        Key k = new Key(aa.x, aa.y, bb.x, bb.y, stridePx);

        synchronized (this) {
            Entry got = cache.get(k);
            if (got != null) return got;
        }

        // Compute A* outside the synchronized block (avoid blocking EDT)
        List<Point> p = findPath(m, aa, bb, stridePx, allowDiagonal, 2_000_000);

        Metrics metrics = null;
        if (p != null && p.size() >= 2) metrics = computeMetrics(p);
        Entry e = new Entry(p, metrics);

        // Store in LRU (and also store reverse direction for free)
        synchronized (this) {
            cache.put(k, e);

            if (p != null && p.size() >= 2) {
                List<Point> rev = reverseCopy(p);
                Metrics revMetrics = (metrics == null) ? null : computeMetrics(rev);

                Key kr = new Key(bb.x, bb.y, aa.x, aa.y, stridePx);
                cache.put(kr, new Entry(rev, revMetrics));
            }
        }

        return e;
    }

    private static List<Point> reverseCopy(List<Point> path) {
        ArrayList<Point> rev = new ArrayList<>(path.size());
        for (int i = path.size() - 1; i >= 0; i--) rev.add(path.get(i));
        return rev;
    }

    private static Metrics computeMetrics(List<Point> path) {
        int n = path.size();
        double[] cum = new double[n];
        cum[0] = 0.0;

        double total = 0.0;
        Point prev = path.get(0);

        for (int i = 1; i < n; i++) {
            Point cur = path.get(i);
            double d = 0.0;
            if (prev != null && cur != null) d = prev.distance(cur);
            total += d;
            cum[i] = total;
            prev = cur;
        }

        return new Metrics(cum, total);
    }

    private static int safeMaskVersion(WalkMask mask) {
        try { return mask.getVersion(); } catch (Throwable t) { return 0; }
    }

    /** Utility: polyline length in pixels (Euclidean). */
    public static double polylineLengthPixels(List<Point> path) {
        if (path == null || path.size() < 2) return 0.0;
        double sum = 0.0;
        Point prev = path.get(0);
        for (int i = 1; i < path.size(); i++) {
            Point cur = path.get(i);
            if (prev != null && cur != null) sum += prev.distance(cur);
            prev = cur;
        }
        return sum;
    }

    // ----------------------------------------------------------------------
    // Self-contained routing helpers (A* on stride grid)
    // ----------------------------------------------------------------------

    /** Snap to nearest walkable pixel within maxRadius; returns null if not found. */
    public static Point snapToNearestWalkable(WalkMask m, Point p, int stride, int maxRadius) {
        if (m == null || p == null) return null;
        if (m.inBounds(p.x, p.y) && m.isWalkable(p.x, p.y)) return new Point(p);

        int maxR = Math.max(1, maxRadius);
        int step = Math.max(1, stride);
        for (int r = step; r <= maxR; r += step) {
            int x0 = Math.max(0, p.x - r);
            int x1 = Math.min(m.getWidth() - 1, p.x + r);
            int y0 = Math.max(0, p.y - r);
            int y1 = Math.min(m.getHeight() - 1, p.y + r);
            for (int y = y0; y <= y1; y += step) {
                for (int x = x0; x <= x1; x += step) {
                    if (!m.inBounds(x, y)) continue;
                    if (m.isWalkable(x, y)) return new Point(x, y);
                }
            }
        }
        return null;
    }

    /** A* routing on a stride grid. Returns list of points including endpoints, or null. */
    public static List<Point> findPath(WalkMask m, Point start, Point goal, int stride, boolean allowDiagonal, int maxExpanded) {
        if (m == null || start == null || goal == null) return null;
        int s = Math.max(1, stride);

        // Heuristic: Euclidean distance
        final class Node implements Comparable<Node> {
            final int x, y;
            final double g, f;
            final Node prev;
            Node(int x, int y, double g, double f, Node prev) { this.x = x; this.y = y; this.g = g; this.f = f; this.prev = prev; }
            @Override public int compareTo(Node o) { return Double.compare(this.f, o.f); }
        }

        PriorityQueue<Node> open = new PriorityQueue<>();
        boolean[] closed = new boolean[m.getWidth() * m.getHeight()];
        double[] bestG = new double[m.getWidth() * m.getHeight()];
        Arrays.fill(bestG, Double.POSITIVE_INFINITY);

        int sx = clampToStride(start.x, s);
        int sy = clampToStride(start.y, s);
        int gx = clampToStride(goal.x, s);
        int gy = clampToStride(goal.y, s);

        if (!m.inBounds(sx, sy) || !m.isWalkable(sx, sy)) return null;
        if (!m.inBounds(gx, gy) || !m.isWalkable(gx, gy)) return null;

        double h0 = dist(sx, sy, gx, gy);
        open.add(new Node(sx, sy, 0.0, h0, null));
        bestG[sy * m.getWidth() + sx] = 0.0;

        int expanded = 0;
        int[][] dirs4 = new int[][]{{s,0},{-s,0},{0,s},{0,-s}};
        int[][] dirs8 = new int[][]{{s,0},{-s,0},{0,s},{0,-s},{s,s},{s,-s},{-s,s},{-s,-s}};
        int[][] dirs = allowDiagonal ? dirs8 : dirs4;

        while (!open.isEmpty()) {
            Node cur = open.poll();
            int idx = cur.y * m.getWidth() + cur.x;
            if (closed[idx]) continue;
            closed[idx] = true;

            expanded++;
            if (expanded > Math.max(1000, maxExpanded)) break; // safety

            if (cur.x == gx && cur.y == gy) {
                // reconstruct
                ArrayList<Point> path = new ArrayList<>();
                Node n = cur;
                while (n != null) { path.add(new Point(n.x, n.y)); n = n.prev; }
                Collections.reverse(path);
                return path;
            }

            for (int[] d : dirs) {
                int nx = cur.x + d[0];
                int ny = cur.y + d[1];
                if (!m.inBounds(nx, ny)) continue;
                if (!m.isWalkable(nx, ny)) continue;

                // Optional: for diagonal, ensure at least one of the orthogonals is walkable to avoid corner cut
                if (allowDiagonal && d[0] != 0 && d[1] != 0) {
                    int ox = cur.x + d[0];
                    int oy = cur.y;
                    int px = cur.x;
                    int py = cur.y + d[1];
                    if (!m.isWalkable(ox, oy) && !m.isWalkable(px, py)) continue;
                }

                int nIdx = ny * m.getWidth() + nx;
                double ng = cur.g + dist(cur.x, cur.y, nx, ny);
                if (ng >= bestG[nIdx]) continue;

                bestG[nIdx] = ng;
                double nf = ng + dist(nx, ny, gx, gy);
                open.add(new Node(nx, ny, ng, nf, cur));
            }
        }

        // No path found
        return null;
    }

    private static int clampToStride(int v, int stride) {
        if (stride <= 1) return v;
        int r = v % stride;
        return v - r;
    }

    private static double dist(int x0, int y0, int x1, int y1) {
        int dx = x1 - x0, dy = y1 - y0;
        return Math.hypot(dx, dy);
    }
}
