package sim.floorplan.ui;

import sim.floorplan.model.WalkMask;
import sim.floorplan.model.Zone;
import sim.floorplan.model.ZoneType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class FloorplanCanvas extends JPanel {

    public enum Tool {
        PAN,
        SELECT,

        PAINT_WALKABLE,
        PAINT_BLOCKED,
        POLY_FILL_WALKABLE,

        PLACE_SPAWN,
        PLACE_TICKET_COUNTER,
        PLACE_CHECKPOINT,
        PLACE_HOLDROOM,

        DRAW_TICKET_QUEUE,
        DRAW_CHECKPOINT_QUEUE,
        DRAW_CHECKPOINT_AREA, // NEW
        DRAW_HOLDROOM_AREA,

        TEST_ROUTE,
        MEASURE_SCALE
    }

    private BufferedImage image;
    private WalkMask mask;
    private boolean overlayEnabled = true;

    // Cached ARGB overlay, UPDATED INCREMENTALLY
    private BufferedImage overlayCache;
    private int overlayAlpha = 90; // 0..255

    // pan/zoom state
    private double zoom = 1.0;
    private double panX = 0;
    private double panY = 0;

    // tools
    private Tool tool = Tool.PAN;
    private int brushRadiusPx = 10;

    // editing lock (allows pan/zoom + selection + test route/measure, blocks edits)
    private boolean locked = false;

    // zones are owned by editor panel; canvas only renders / selects
    private List<Zone> zones = new ArrayList<>();
    private Zone selectedZone;

    // callbacks
    private BiConsumer<Tool, Point> onPointAction;        // PLACE_*, TEST_ROUTE, MEASURE_SCALE clicks
    private BiConsumer<Tool, Polygon> onPolygonFinished;  // DRAW_* completion
    private Consumer<Zone> onSelectionChanged;
    private Runnable onDeleteRequested;

    // notify editor when mask changes (brush/polyfill)
    private Consumer<String> onMaskEdited;

    // precomputed brush offsets
    private List<Point> brushOffsets = new ArrayList<>();

    private Point dragStartScreen;
    private Point lastMouseScreen;

    // For stroke interpolation
    private Point lastPaintImgPt = null;

    // Polygon tool state (image coords) for BOTH poly-fill + draw-polys
    private final List<Point> polyPts = new ArrayList<>();
    private Point polyHoverImg = null;

    // Test Route overlay (image coords)
    private Point testRouteStartImg = null;
    private Point testRouteEndImg = null;
    private List<Point> testRoutePathImg = null;

    // Scale measurement overlay (image coords)
    private Point measureStartImg = null;
    private Point measureEndImg = null;

    // batch mask-edited notifications
    private boolean pendingMaskEdited = false;
    private String pendingMaskEditReason = null;

    public FloorplanCanvas() {
        setBackground(Color.DARK_GRAY);
        setFocusable(true);
        rebuildBrushOffsets();

        // Key controls for polygon tools
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "polyCancel");
        getActionMap().put("polyCancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                polyPts.clear();
                polyHoverImg = null;
                repaint();
            }
        });

        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "polyUndo");
        getActionMap().put("polyUndo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (isAnyPolygonTool(tool) && !polyPts.isEmpty()) {
                    polyPts.remove(polyPts.size() - 1);
                    repaint();
                }
            }
        });

        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "polyClose");
        getActionMap().put("polyClose", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (isAnyPolygonTool(tool)) closePolygonAction();
            }
        });

        // Delete selected
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteSelected");
        getActionMap().put("deleteSelected", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (onDeleteRequested != null) onDeleteRequested.run();
            }
        });

        addMouseWheelListener(e -> {
            if (image == null) return;

            double oldZoom = zoom;
            double factor = (e.getWheelRotation() < 0) ? 1.10 : 1.0 / 1.10;
            zoom = clamp(zoom * factor, 0.10, 8.0);

            Point p = e.getPoint();
            double mx = p.getX(), my = p.getY();

            double wx = (mx - panX) / oldZoom;
            double wy = (my - panY) / oldZoom;

            panX = mx - wx * zoom;
            panY = my - wy * zoom;

            repaint();
        });

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                lastMouseScreen = e.getPoint();
                if (isAnyPolygonTool(tool) && image != null) {
                    polyHoverImg = screenToImage(e.getPoint());
                }
                repaint();
            }

            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                lastMouseScreen = e.getPoint();
                if (image == null) return;

                // Right mouse: if polygon tool => close, else pan
                if (SwingUtilities.isRightMouseButton(e)) {
                    if (isAnyPolygonTool(tool)) {
                        closePolygonAction();
                        return;
                    }
                    dragStartScreen = e.getPoint();
                    lastPaintImgPt = null;
                    return;
                }

                if (SwingUtilities.isLeftMouseButton(e)) {

                    if (tool == Tool.SELECT) {
                        Point imgPt = screenToImage(e.getPoint());
                        Zone hit = hitTest(imgPt, 10.0 / Math.max(0.25, zoom));
                        setSelectedZone(hit);
                        return;
                    }

                    if (tool == Tool.TEST_ROUTE || tool == Tool.MEASURE_SCALE) {
                        Point imgPt = screenToImage(e.getPoint());
                        if (onPointAction != null) onPointAction.accept(tool, imgPt);
                        return;
                    }

                    if (isPlaceTool(tool)) {
                        if (locked) return;
                        Point imgPt = screenToImage(e.getPoint());
                        if (onPointAction != null) onPointAction.accept(tool, imgPt);
                        return;
                    }

                    if (isAnyPolygonTool(tool)) {
                        if (locked) return;
                        Point imgPt = screenToImage(e.getPoint());
                        polyPts.add(imgPt);

                        if (e.getClickCount() >= 2) {
                            closePolygonAction();
                        } else {
                            repaint();
                        }
                        return;
                    }

                    if (tool == Tool.PAN) {
                        dragStartScreen = e.getPoint();
                        lastPaintImgPt = null;
                        return;
                    }

                    if (isPaintTool(tool)) {
                        if (locked) return;
                        Point imgPt = screenToImage(e.getPoint());
                        lastPaintImgPt = imgPt;
                        paintCircle(imgPt.x, imgPt.y, tool == Tool.PAINT_WALKABLE);
                    }
                }
            }

            @Override public void mouseDragged(MouseEvent e) {
                lastMouseScreen = e.getPoint();
                if (image == null) return;

                // Pan: right-drag OR tool PAN left-drag
                if (SwingUtilities.isRightMouseButton(e) || tool == Tool.PAN) {
                    if (dragStartScreen != null) {
                        Point cur = e.getPoint();
                        panX += (cur.x - dragStartScreen.x);
                        panY += (cur.y - dragStartScreen.y);
                        dragStartScreen = cur;
                        repaint();
                    }
                    lastPaintImgPt = null;
                    return;
                }

                // Paint (stroke interpolation)
                if (SwingUtilities.isLeftMouseButton(e) && isPaintTool(tool)) {
                    if (locked) return;
                    boolean makeWalkable = (tool == Tool.PAINT_WALKABLE);
                    Point curImg = screenToImage(e.getPoint());

                    if (lastPaintImgPt == null) lastPaintImgPt = curImg;

                    int step = Math.max(1, brushRadiusPx / 2);
                    paintStroke(lastPaintImgPt, curImg, step, makeWalkable);
                    lastPaintImgPt = curImg;
                }
            }

            @Override public void mouseReleased(MouseEvent e) {
                dragStartScreen = null;
                lastPaintImgPt = null;
                flushMaskEdited();
            }
        };

        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    // ==========================================================
    // Public API
    // ==========================================================

    public void setImage(BufferedImage img) {
        this.image = img;
        zoom = 1.0;
        panX = 0;
        panY = 0;

        polyPts.clear();
        polyHoverImg = null;

        clearTestRoute();
        clearMeasureSegment();

        // avoid overlay mismatch if image changes before mask is reset
        if (mask == null || img == null || mask.getWidth() != img.getWidth() || mask.getHeight() != img.getHeight()) {
            overlayCache = null;
        }

        revalidate();
        repaint();
    }

    public void setMask(WalkMask mask) {
        this.mask = mask;
        rebuildOverlayCache();
        repaint();
    }

    public void setOverlayEnabled(boolean on) {
        this.overlayEnabled = on;
        repaint();
    }

    public void setTool(Tool t) {
        this.tool = (t == null) ? Tool.PAN : t;

        if (!isAnyPolygonTool(this.tool)) {
            polyPts.clear();
            polyHoverImg = null;
        }
        repaint();
    }

    public Tool getTool() { return tool; }

    public void setBrushRadiusPx(int r) {
        this.brushRadiusPx = Math.max(1, r);
        rebuildBrushOffsets();
        repaint();
    }

    public int getBrushRadiusPx() { return brushRadiusPx; }

    public void resetView() {
        zoom = 1.0;
        panX = 0;
        panY = 0;
        repaint();
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        repaint();
    }

    public boolean isLocked() { return locked; }

    public void setZones(List<Zone> zones) {
        this.zones = (zones == null) ? new ArrayList<>() : zones;
        repaint();
    }

    public Zone getSelectedZone() { return selectedZone; }

    public void setSelectedZone(Zone z) {
        this.selectedZone = z;
        if (onSelectionChanged != null) onSelectionChanged.accept(z);
        repaint();
    }

    public void setOnPointAction(BiConsumer<Tool, Point> cb) { this.onPointAction = cb; }
    public void setOnPolygonFinished(BiConsumer<Tool, Polygon> cb) { this.onPolygonFinished = cb; }
    public void setOnSelectionChanged(Consumer<Zone> cb) { this.onSelectionChanged = cb; }
    public void setOnDeleteRequested(Runnable r) { this.onDeleteRequested = r; }
    public void setOnMaskEdited(Consumer<String> cb) { this.onMaskEdited = cb; }

    public void setTestRoute(Point startImg, Point endImg, List<Point> pathImg) {
        this.testRouteStartImg = (startImg == null) ? null : new Point(startImg);
        this.testRouteEndImg = (endImg == null) ? null : new Point(endImg);

        if (pathImg == null) {
            this.testRoutePathImg = null;
        } else {
            this.testRoutePathImg = new ArrayList<>();
            for (Point p : pathImg) this.testRoutePathImg.add(new Point(p));
        }
        repaint();
    }

    public void clearTestRoute() {
        this.testRouteStartImg = null;
        this.testRouteEndImg = null;
        this.testRoutePathImg = null;
        repaint();
    }

    public void setMeasureSegment(Point aImg, Point bImg) {
        this.measureStartImg = (aImg == null) ? null : new Point(aImg);
        this.measureEndImg = (bImg == null) ? null : new Point(bImg);
        repaint();
    }

    public void clearMeasureSegment() {
        this.measureStartImg = null;
        this.measureEndImg = null;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        if (image == null) return new Dimension(900, 600);
        return new Dimension(image.getWidth(), image.getHeight());
    }

    // ==========================================================
    // Tool helpers
    // ==========================================================

    private boolean isPaintTool(Tool t) {
        return t == Tool.PAINT_WALKABLE || t == Tool.PAINT_BLOCKED;
    }

    private boolean isPlaceTool(Tool t) {
        return t == Tool.PLACE_SPAWN
                || t == Tool.PLACE_TICKET_COUNTER
                || t == Tool.PLACE_CHECKPOINT
                || t == Tool.PLACE_HOLDROOM;
    }

    private boolean isDrawPolyTool(Tool t) {
        return t == Tool.DRAW_TICKET_QUEUE
                || t == Tool.DRAW_CHECKPOINT_QUEUE
                || t == Tool.DRAW_CHECKPOINT_AREA
                || t == Tool.DRAW_HOLDROOM_AREA;
    }

    private boolean isAnyPolygonTool(Tool t) {
        return t == Tool.POLY_FILL_WALKABLE || isDrawPolyTool(t);
    }

    private Point screenToImage(Point screenPt) {
        int ix = (int) Math.floor((screenPt.x - panX) / zoom);
        int iy = (int) Math.floor((screenPt.y - panY) / zoom);
        return new Point(ix, iy);
    }

    private Rectangle imageRectToScreen(int ix, int iy, int iw, int ih) {
        int sx = (int) Math.floor(panX + ix * zoom);
        int sy = (int) Math.floor(panY + iy * zoom);
        int sw = (int) Math.ceil(iw * zoom);
        int sh = (int) Math.ceil(ih * zoom);
        return new Rectangle(sx, sy, sw, sh);
    }

    // ==========================================================
    // Selection
    // ==========================================================

    private Zone hitTest(Point imgPt, double anchorRadiusPx) {
        if (zones == null || zones.isEmpty() || imgPt == null) return null;

        Zone bestAnchor = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (Zone z : zones) {
            if (z == null || z.getType() == null) continue;
            if (!z.getType().hasAnchor()) continue;
            Point a = z.getAnchor();
            if (a == null) continue;
            double d = a.distance(imgPt);
            if (d <= anchorRadiusPx && d < bestD) {
                bestD = d;
                bestAnchor = z;
            }
        }
        if (bestAnchor != null) return bestAnchor;

        Zone bestPoly = null;
        double bestArea = Double.POSITIVE_INFINITY;
        for (Zone z : zones) {
            if (z == null || z.getType() == null) continue;
            if (!z.getType().hasArea()) continue;
            Polygon p = z.getArea();
            if (p == null || p.npoints < 3) continue;
            if (p.contains(imgPt.x + 0.5, imgPt.y + 0.5)) {
                double area = polygonAreaAbs(p);
                if (area < bestArea) {
                    bestArea = area;
                    bestPoly = z;
                }
            }
        }
        return bestPoly;
    }

    private static double polygonAreaAbs(Polygon p) {
        if (p == null || p.npoints < 3) return 0.0;
        long sum = 0;
        for (int i = 0; i < p.npoints; i++) {
            int j = (i + 1) % p.npoints;
            sum += (long) p.xpoints[i] * p.ypoints[j] - (long) p.xpoints[j] * p.ypoints[i];
        }
        return Math.abs(sum) / 2.0;
    }

    // ==========================================================
    // Stroke + Painting
    // ==========================================================

    private void paintStroke(Point a, Point b, int stepPx, boolean makeWalkable) {
        if (mask == null) return;

        int dx = b.x - a.x;
        int dy = b.y - a.y;

        double dist = Math.hypot(dx, dy);
        int steps = Math.max(1, (int) Math.ceil(dist / stepPx));

        int minX = Math.min(a.x, b.x) - brushRadiusPx - 1;
        int maxX = Math.max(a.x, b.x) + brushRadiusPx + 1;
        int minY = Math.min(a.y, b.y) - brushRadiusPx - 1;
        int maxY = Math.max(a.y, b.y) + brushRadiusPx + 1;

        for (int i = 0; i <= steps; i++) {
            double t = (i / (double) steps);
            int x = (int) Math.round(a.x + dx * t);
            int y = (int) Math.round(a.y + dy * t);
            paintCircle(x, y, makeWalkable, false);
        }

        Rectangle dirtyScreen = imageRectToScreen(minX, minY, maxX - minX + 1, maxY - minY + 1);
        repaint(dirtyScreen);
    }

    private void paintCircle(int cx, int cy, boolean makeWalkable) {
        paintCircle(cx, cy, makeWalkable, true);
    }

    private void paintCircle(int cx, int cy, boolean makeWalkable, boolean doRepaint) {
        if (mask == null) return;

        boolean changedAny = false;

        for (Point off : brushOffsets) {
            int x = cx + off.x;
            int y = cy + off.y;
            if (!mask.inBounds(x, y)) continue;

            boolean before = mask.isWalkable(x, y);
            if (before != makeWalkable) {
                mask.setWalkable(x, y, makeWalkable);
                changedAny = true;

                if (overlayCache != null
                        && x >= 0 && y >= 0
                        && x < overlayCache.getWidth()
                        && y < overlayCache.getHeight()) {
                    overlayCache.setRGB(x, y, overlayColorFor(makeWalkable, overlayAlpha));
                }
            }
        }

        if (changedAny) {
            markMaskEdited(makeWalkable ? "paint walkable" : "paint blocked");
        }

        if (changedAny && doRepaint) {
            Rectangle dirty = imageRectToScreen(
                    cx - brushRadiusPx - 1,
                    cy - brushRadiusPx - 1,
                    brushRadiusPx * 2 + 3,
                    brushRadiusPx * 2 + 3
            );
            repaint(dirty);
        }
    }

    private void markMaskEdited(String reason) {
        pendingMaskEdited = true;
        if (reason != null && !reason.trim().isEmpty()) {
            pendingMaskEditReason = reason.trim();
        }
    }

    private void flushMaskEdited() {
        if (!pendingMaskEdited) return;
        pendingMaskEdited = false;

        String r = pendingMaskEditReason;
        pendingMaskEditReason = null;

        if (onMaskEdited != null) {
            onMaskEdited.accept(r == null ? "mask edited" : r);
        }
    }

    private void rebuildBrushOffsets() {
        brushOffsets.clear();
        int r = brushRadiusPx;
        int rr = r * r;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy <= rr) {
                    brushOffsets.add(new Point(dx, dy));
                }
            }
        }
    }

    private void rebuildOverlayCache() {
        if (mask == null) {
            overlayCache = null;
            return;
        }

        int w = mask.getWidth();
        int h = mask.getHeight();
        overlayCache = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean walk = mask.isWalkable(x, y);
                overlayCache.setRGB(x, y, overlayColorFor(walk, overlayAlpha));
            }
        }
    }

    private static int overlayColorFor(boolean walkable, int alpha) {
        alpha = Math.max(0, Math.min(255, alpha));
        int r = walkable ? 0   : 255;
        int g = walkable ? 255 : 0;
        int b = 0;
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    // ==========================================================
    // Polygon actions
    // ==========================================================

    private void closePolygonAction() {
        if (!isAnyPolygonTool(tool)) return;

        if (polyPts.size() < 3) {
            polyPts.clear();
            polyHoverImg = null;
            repaint();
            return;
        }

        if (tool == Tool.POLY_FILL_WALKABLE) {
            if (!locked) {
                boolean changed = fillPolygon(polyPts, true);
                if (changed && onMaskEdited != null) onMaskEdited.accept("poly fill walkable");
            }
        } else if (isDrawPolyTool(tool)) {
            if (!locked && onPolygonFinished != null) {
                Polygon p = new Polygon();
                for (Point pt : polyPts) p.addPoint(pt.x, pt.y);
                onPolygonFinished.accept(tool, p);
            }
        }

        polyPts.clear();
        polyHoverImg = null;
        repaint();
    }

    private boolean fillPolygon(List<Point> ptsImg, boolean makeWalkable) {
        if (mask == null || image == null) return false;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Point p : ptsImg) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }

        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        maxX = Math.min(image.getWidth() - 1, maxX);
        maxY = Math.min(image.getHeight() - 1, maxY);

        int bw = Math.max(1, maxX - minX + 1);
        int bh = Math.max(1, maxY - minY + 1);

        BufferedImage polyImg = new BufferedImage(bw, bh, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g2 = polyImg.createGraphics();
        try {
            g2.setColor(Color.WHITE);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

            Path2D path = new Path2D.Double();
            Point p0 = ptsImg.get(0);
            path.moveTo(p0.x - minX, p0.y - minY);
            for (int i = 1; i < ptsImg.size(); i++) {
                Point pi = ptsImg.get(i);
                path.lineTo(pi.x - minX, pi.y - minY);
            }
            path.closePath();

            g2.fill(path);
        } finally {
            g2.dispose();
        }

        int changed = 0;
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int rgb = polyImg.getRGB(x, y) & 0xFFFFFF;
                if (rgb == 0) continue;

                int ix = minX + x;
                int iy = minY + y;
                if (!mask.inBounds(ix, iy)) continue;

                boolean before = mask.isWalkable(ix, iy);
                if (before != makeWalkable) {
                    mask.setWalkable(ix, iy, makeWalkable);
                    changed++;

                    if (overlayCache != null
                            && ix >= 0 && iy >= 0
                            && ix < overlayCache.getWidth()
                            && iy < overlayCache.getHeight()) {
                        overlayCache.setRGB(ix, iy, overlayColorFor(makeWalkable, overlayAlpha));
                    }
                }
            }
        }

        if (changed > 0) {
            Rectangle dirty = imageRectToScreen(minX, minY, bw, bh);
            repaint(dirty);
            return true;
        }
        return false;
    }

    // ==========================================================
    // Rendering
    // ==========================================================

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (image == null) {
            drawCenteredText(g, "Upload and Render a PDF to preview the floorplan.");
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            AffineTransform at = new AffineTransform();
            at.translate(panX, panY);
            at.scale(zoom, zoom);
            g2.transform(at);

            g2.drawImage(image, 0, 0, null);

            if (overlayEnabled && overlayCache != null) {
                g2.drawImage(overlayCache, 0, 0, null);
            }

            drawZones(g2);
            drawTestRoute(g2);
            drawMeasure(g2);

            if (isAnyPolygonTool(tool) && !polyPts.isEmpty()) {
                g2.setStroke(new BasicStroke((float) (2.0 / zoom)));
                g2.setColor(new Color(255, 255, 255, 220));

                for (int i = 0; i < polyPts.size() - 1; i++) {
                    Point a = polyPts.get(i);
                    Point b = polyPts.get(i + 1);
                    g2.drawLine(a.x, a.y, b.x, b.y);
                }
                if (polyHoverImg != null) {
                    Point last = polyPts.get(polyPts.size() - 1);
                    g2.drawLine(last.x, last.y, polyHoverImg.x, polyHoverImg.y);
                }

                for (Point p : polyPts) {
                    int r = (int) Math.max(2, Math.round(3 / zoom));
                    g2.fillOval(p.x - r, p.y - r, r * 2, r * 2);
                }
            }

        } finally {
            g2.dispose();
        }

        if (image != null && mask != null && isPaintTool(tool) && lastMouseScreen != null) {
            Graphics2D g3 = (Graphics2D) g.create();
            try {
                g3.setColor(new Color(255, 255, 255, 180));
                int r = (int) Math.round(brushRadiusPx * zoom);
                int x = lastMouseScreen.x - r;
                int y = lastMouseScreen.y - r;
                g3.drawOval(x, y, r * 2, r * 2);
            } finally {
                g3.dispose();
            }
        }

        if (locked) {
            Graphics2D g4 = (Graphics2D) g.create();
            try {
                g4.setColor(new Color(0, 0, 0, 140));
                g4.fillRoundRect(10, 10, 220, 30, 10, 10);
                g4.setColor(new Color(255, 255, 255, 230));
                g4.drawString("LOCKED (view-only)", 22, 30);
            } finally {
                g4.dispose();
            }
        }
    }

    private void drawMeasure(Graphics2D g2) {
        if (measureStartImg == null || measureEndImg == null) return;

        g2.setStroke(new BasicStroke((float) (2.5 / zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(190, 120, 255, 220));
        g2.drawLine(measureStartImg.x, measureStartImg.y, measureEndImg.x, measureEndImg.y);

        int r = (int) Math.max(3, Math.round(5 / zoom));
        g2.setColor(new Color(190, 120, 255, 235));
        g2.fillOval(measureStartImg.x - r, measureStartImg.y - r, r * 2, r * 2);
        g2.fillOval(measureEndImg.x - r, measureEndImg.y - r, r * 2, r * 2);
    }

    private void drawTestRoute(Graphics2D g2) {
        if (testRoutePathImg != null && testRoutePathImg.size() >= 2) {
            g2.setStroke(new BasicStroke((float) (3.0 / zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(0, 220, 255, 220));
            for (int i = 0; i < testRoutePathImg.size() - 1; i++) {
                Point a = testRoutePathImg.get(i);
                Point b = testRoutePathImg.get(i + 1);
                g2.drawLine(a.x, a.y, b.x, b.y);
            }
        }
        if (testRouteStartImg != null) {
            int r = (int) Math.max(3, Math.round(6 / zoom));
            g2.setColor(new Color(0, 255, 120, 235));
            g2.fillOval(testRouteStartImg.x - r, testRouteStartImg.y - r, r * 2, r * 2);
        }
        if (testRouteEndImg != null) {
            int r = (int) Math.max(3, Math.round(6 / zoom));
            g2.setColor(new Color(255, 70, 70, 235));
            g2.fillOval(testRouteEndImg.x - r, testRouteEndImg.y - r, r * 2, r * 2);
        }
    }

    private void drawZones(Graphics2D g2) {
        if (zones == null || zones.isEmpty()) return;

        float stroke = (float) (2.0 / zoom);
        g2.setStroke(new BasicStroke(stroke));

        for (Zone z : zones) {
            if (z == null || z.getType() == null) continue;
            if (!z.getType().hasArea()) continue;
            Polygon p = z.getArea();
            if (p == null || p.npoints < 3) continue;

            Color c = colorFor(z.getType(), z == selectedZone);
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
            g2.fillPolygon(p);

            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 180));
            g2.drawPolygon(p);
        }

        for (Zone z : zones) {
            if (z == null || z.getType() == null) continue;
            if (!z.getType().hasAnchor()) continue;
            Point a = z.getAnchor();
            if (a == null) continue;

            boolean sel = (z == selectedZone);
            Color c = colorFor(z.getType(), sel);

            int r = (int) Math.max(3, Math.round(5 / zoom));
            g2.setColor(new Color(0, 0, 0, 160));
            g2.fillOval(a.x - r - 1, a.y - r - 1, (r * 2) + 2, (r * 2) + 2);

            g2.setColor(c);
            g2.fillOval(a.x - r, a.y - r, r * 2, r * 2);

            g2.setColor(new Color(255, 255, 255, 230));
            g2.drawOval(a.x - r, a.y - r, r * 2, r * 2);

            String label = z.getId();
            if (label != null && !label.trim().isEmpty()) {
                g2.setFont(getFont().deriveFont((float) (12.0 / zoom)));
                g2.setColor(new Color(0, 0, 0, 160));
                g2.drawString(label, a.x + r + 2, a.y + r + 2);
                g2.setColor(new Color(255, 255, 255, 230));
                g2.drawString(label, a.x + r + 1, a.y + r + 1);
            }
        }
    }

    private Color colorFor(ZoneType t, boolean selected) {
        Color base;
        if (t == ZoneType.SPAWN) base = new Color(0, 180, 255);
        else if (t == ZoneType.TICKET_COUNTER || t == ZoneType.TICKET_QUEUE_AREA) base = new Color(0, 200, 120);
        else if (t == ZoneType.CHECKPOINT || t == ZoneType.CHECKPOINT_QUEUE_AREA || t == ZoneType.CHECKPOINT_AREA) base = new Color(255, 200, 0);
        else if (t == ZoneType.HOLDROOM || t == ZoneType.HOLDROOM_AREA) base = new Color(200, 120, 255);
        else base = new Color(255, 255, 255);

        if (!selected) return base;
        int r = Math.min(255, base.getRed() + 35);
        int g = Math.min(255, base.getGreen() + 35);
        int b = Math.min(255, base.getBlue() + 35);
        return new Color(r, g, b);
    }

    private void drawCenteredText(Graphics g, String s) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(Color.LIGHT_GRAY);
            g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
            FontMetrics fm = g2.getFontMetrics();
            int x = (getWidth() - fm.stringWidth(s)) / 2;
            int y = (getHeight() + fm.getAscent()) / 2;
            g2.drawString(s, x, y);
        } finally {
            g2.dispose();
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

