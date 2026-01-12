package sim.floorplan.ui;

import sim.floorplan.model.FloorplanProject;
import sim.floorplan.model.WalkMask;
import sim.floorplan.sim.FloorplanBindings;
import sim.floorplan.sim.PathCache;
import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;
import sim.ui.CheckpointConfig;
import sim.ui.TicketCounterConfig;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.util.*;
import java.util.List;

/**
 * Visual simulation panel for the floorplan.
 *
 * Key updates in this rewrite:
 * 1) Uses double-based rate math (minutes/hours as doubles) for per-interval staggering.
 * 2) Uses ABSOLUTE SECONDS windowing based on engine's *_AbsSecond getters to decide who leaves in the
 *    current interval and to clamp transit so arrivals land before the exact scheduled absolute second.
 * 3) stepAlpha01 is now truly continuous within each second using a sub-second wall-clock delta,
 *    so sub-second staggering is visible even with a 1-second playback step.
 * 4) Pending maps from engine are treated as ABSOLUTE-SECOND keyed maps.
 * 5) Prunes stepStartAbsSec / stepIntervalSec to a rolling window (prevents unbounded growth)
 *    and repopulates mapping on rewind to keep things consistent even if older entries were pruned.
 *
 * SPEC-1 additions:
 * A) Queue sliding animation for Ticket & Checkpoint queues (0.40s per slot, cubic ease-in-out).
 * B) Checkpoint service-time semantics in UI: when service starts at tStart (doneAbs - serviceSec),
 *    passenger leaves the checkpoint queue, briefly nudges into the checkpoint area (0.20s), then dwells
 *    at the checkpoint anchor until doneAbs. Service time depends only on lane rate (pax/hour).
 * C) Support drawing service passenger inside CHECKPOINT_AREA (via bindings.getCheckpointAreaPolygon).
 */
public class FloorplanSimulationPanel extends JPanel {

    private SimulationEngine engine;
    private FloorplanProject project;

    private FloorplanBindings bindings;
    private PathCache pathCache;

    private int slotSpacingPx = 2;     // extra gap beyond footprint
    private int pathStridePx = 4;

    // pan/zoom
    private double zoom = 1.0;
    private double panX = 0;
    private double panY = 0;
    private Point lastMouse = null;

    private boolean isPanning = false;
    private static final int PAN_MARGIN_PX = 40;

    // ===== Display clock (seconds) & animation =====
    private javax.swing.Timer animTimer;
    private static final int ANIM_FPS_MS = 33;  // ~30 fps repaint cadence (visual only)

    // UI-controlled playback step (seconds per UI tick). SimulationFrame drives advance/rewind.
    private int displayStepSeconds = 1;         // 1..300
    private int secondsIntoInterval = 0;        // 0..intervalSeconds-1

    // Sub-second animation clock (wall time since last integer-second change)
    private long subSecondStartNanos = System.nanoTime();

    // Exposed alpha for UI bindings
    private double stepAlpha01 = 0.0;

    // ===== Absolute-time model (ALWAYS 1-second base) =====
    private long absoluteSeconds = 0L; // since start of simulation view

    // Rolling window retention for step mapping (prevents quiet memory creep)
    private static final long STEP_RETENTION_SEC = 3L * 3600; // keep ~3 hours of mapping history

    private final Map<Integer, Long> stepStartAbsSec = new HashMap<>();   // engine step -> absolute start seconds
    private final Map<Integer, Integer> stepIntervalSec = new HashMap<>(); // engine step -> interval length at that step

    // ===== Visual staggering (per-interval scheduling) =====
    private final Map<Integer, Double> ticketCarry = new HashMap<>();
    private final Map<Integer, Double> cpCarry     = new HashMap<>();
    private final Map<Integer, double[]> ticketStagger = new HashMap<>(); // debug/inspection
    private final Map<Integer, double[]> cpStagger     = new HashMap<>(); // debug/inspection
    private int lastScheduledEngineInterval = -1;

    // Per-passenger release FRACTIONS/ABS seconds for the *current* interval only
    private final Map<Passenger, Double> ticketReleaseFrac = new HashMap<>();
    private final Map<Passenger, Double> cpReleaseFrac     = new HashMap<>();
    private final Map<Passenger, Double> ticketReleaseAbsSec = new HashMap<>();
    private final Map<Passenger, Double> cpReleaseAbsSec     = new HashMap<>();
    private final Map<Passenger, Integer> ticketReleaseSecond = new HashMap<>();
    private final Map<Passenger, Integer> cpReleaseSecond     = new HashMap<>();

    // Optional debug counters
    private final Map<Integer, Integer> lastCompletedTicketCount = new HashMap<>();
    private final Map<Integer, Integer> lastCompletedCPCount     = new HashMap<>();

    private final Map<Integer, Integer> nearestCheckpointByTicket = new HashMap<>();

    // ==========================================================
    // Passenger footprint (LINES + TRANSIT) — source of truth is FloorplanProject
    // ==========================================================
    private double lineLenFt = 2.0;
    private double lineWidFt = 1.5;

    // Holdrooms footprint
    private double holdLenFt = 2.0;
    private double holdWidFt = 1.5;
    private boolean holdFootprintCustom = false;

    private boolean showFootprints = false;

    // cached px (recomputed from scale each paint)
    private int lineLenPx = 12, lineWidPx = 12, holdLenPx = 12, holdWidPx = 12;

    private int paintCurStep = 0;
    private LocalTime paintStartTime = null;
    private int paintStartMinutesOfDay = 0;
    private int paintBoardingCloseOffsetMin = 20;
    private final Map<Flight, Integer> departureIntervalByFlight = new HashMap<>();
    private final Map<Flight, Integer> boardingCloseIntervalByFlight = new HashMap<>();

    // ======== SPEC-1: Queue sliding & service visuals ========
    private static final long SLIDE_MS = 400L;  // 0.40s per slot
    private static final long NUDGE_MS = 200L;  // 0.20s queue-front -> checkpoint-area nudge

    private double nowAbsForRender = 0.0;       // current absolute seconds for this paint pass

    // Tween for in-queue passengers sliding forward when front leaves
    private static final class Tween {
        double startAbs, endAbs; // in absolute seconds
        Point from, to;
    }
    private final Map<Passenger, Tween> queueTweens = new HashMap<>();
    private static double easeCubicInOut(double t){
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return (t < 0.5) ? 4*t*t*t : 1 - Math.pow(-2*t+2, 3)/2.0;
    }

    // Per-lane last visible lists (to detect removals and create tweens)
    private final Map<Integer, List<Passenger>> lastTicketVisible = new HashMap<>();
    private final Map<Integer, List<Point>>     lastTicketSlots   = new HashMap<>();
    private final Map<Integer, List<Passenger>> lastCPVisible     = new HashMap<>();
    private final Map<Integer, List<Point>>     lastCPSlots       = new HashMap<>();

    // Service visuals for checkpoint service dwell between tStart and doneAbs
    private static final class ServiceVisual {
        Passenger p;
        int laneIdx;
        double startAbs;   // service start (tStart = doneAbs - serviceSec)
        double doneAbs;    // service completion (from engine getCheckpointDoneAbsSecond)
        Point from;        // queue-front point (start of nudge)
        Point to;          // cp anchor point (inside CHECKPOINT_AREA)
    }
    private final Map<Passenger, ServiceVisual> serviceVisuals = new HashMap<>();

    public FloorplanSimulationPanel(FloorplanProject projectCopy, SimulationEngine engine) {
        this.project = projectCopy;
        this.engine = engine;

        // Pull line footprint from project at startup, and mirror into hold unless customized
        syncLineFootprintFromProject(true);

        rebuildBindings();

        setBackground(Color.WHITE);
        setFocusable(true);

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (isPanStart(e)) {
                    isPanning = true;
                    lastMouse = e.getPoint();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                isPanning = false;
                lastMouse = null;
                setCursor(Cursor.getDefaultCursor());
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (!isPanning || lastMouse == null) return;
                Point cur = e.getPoint();
                panX += (cur.x - lastMouse.x);
                panY += (cur.y - lastMouse.y);
                lastMouse = cur;
                clampPan();
                repaint();
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                double old = zoom;
                double factor = (e.getWheelRotation() < 0) ? 1.12 : (1.0 / 1.12);
                zoom = Math.max(0.15, Math.min(6.0, zoom * factor));
                // zoom around cursor
                Point p = e.getPoint();
                double sx = (p.x - panX) / old;
                double sy = (p.y - panY) / old;
                panX = p.x - sx * zoom;
                panY = p.y - sy * zoom;
                clampPan();
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);

        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { clampPan(); repaint(); }
            @Override public void componentShown(ComponentEvent e)   { clampPan(); repaint(); }
        });

        ensureCurrentStepMapping();
    }

    // ==========================================================
    // Public API (called from SimulationFrame toolbar)
    // ==========================================================

    /** Playback step seconds (UI slider), range-clamped 1..300. */
    public void setPlaybackStepSeconds(int s) { displayStepSeconds = Math.max(1, Math.min(300, s)); }
    public int getPlaybackStepSeconds() { return displayStepSeconds; }

    /** Exposed for clock label in the frame. */
    public int getSecondsIntoInterval() { return Math.max(0, secondsIntoInterval); }
    public double getStepAlpha01() { return stepAlpha01; }

    /** One-shot helper each UI timer tick to advance by the chosen playback step. */
    public void tickPlaybackStep() { advanceBySeconds(displayStepSeconds); }

    /**
     * Advance UI clock by totalSec (seconds). Engine steps only when a full interval is consumed.
     * Visual staggering is re-scheduled at each new engine interval.
     */
    public void advanceBySeconds(int totalSec) {
        if (engine == null) return;
        int remain = Math.max(0, totalSec);
        while (remain > 0) {
            int intervalSeconds = getIntervalSecondsSafe();
            ensureCurrentStepMapping();
            int leftInThisInterval = intervalSeconds - secondsIntoInterval;

            if (remain < leftInThisInterval) {
                secondsIntoInterval += remain;
                absoluteSeconds += remain;
                remain = 0;
                resetSubSecondClock();
            } else {
                // consume to end of interval
                secondsIntoInterval += leftInThisInterval; // reaches boundary
                absoluteSeconds += leftInThisInterval;
                remain -= leftInThisInterval;

                // normalize and advance engine once
                secondsIntoInterval = 0;
                engineComputeNextInterval();

                int newStep = engineCurrentInterval();
                stepStartAbsSec.put(newStep, absoluteSeconds);
                stepIntervalSec.put(newStep, getIntervalSecondsSafe());

                // prune old step mappings to prevent memory creep
                pruneStepMaps();

                // re-schedule visual staggering for the new engine interval
                scheduleStaggerIfNeeded(true);
                resetSubSecondClock();

                // clear stale per-lane caches that depend on queue membership
                // (tweens expire naturally; lists must follow engine snapshots)
                lastTicketVisible.clear();
                lastTicketSlots.clear();
                lastCPVisible.clear();
                lastCPSlots.clear();
            }
        }
        repaint();
    }

    /** Rewind by seconds; uses engine snapshots at full-interval boundaries. */
    public void rewindBySeconds(int totalSec) {
        if (engine == null) return;
        int remain = Math.max(0, totalSec);
        while (remain > 0) {
            int intervalSeconds = getIntervalSecondsSafe();
            if (secondsIntoInterval > 0) {
                int take = Math.min(secondsIntoInterval, remain);
                secondsIntoInterval -= take;
                absoluteSeconds -= take;
                remain -= take;
                resetSubSecondClock();
            } else {
                if (engineCanRewind()) {
                    // We're at a boundary; move back one engine interval
                    engineRewindOneInterval();
                    int prevStep = engineCurrentInterval();
                    int prevInterval = intervalSecondsAtStep(prevStep);
                    if (prevInterval <= 0) prevInterval = intervalSeconds; // fallback

                    // Land at end of previous interval visually
                    secondsIntoInterval = prevInterval;

                    // Reconstruct prev step start if needed and repopulate mapping (in case it was pruned)
                    long prevStart = Math.max(0L, absoluteSeconds - prevInterval);
                    stepStartAbsSec.put(prevStep, prevStart);
                    stepIntervalSec.put(prevStep, prevInterval);
                    pruneStepMaps();

                    absoluteSeconds = prevStart + prevInterval;

                    scheduleStaggerIfNeeded(true);
                    resetSubSecondClock();

                    // clear motion caches (they'll rebuild on next paint)
                    queueTweens.clear();
                    serviceVisuals.clear();
                    lastTicketVisible.clear();
                    lastTicketSlots.clear();
                    lastCPVisible.clear();
                    lastCPSlots.clear();
                } else {
                    break; // cannot go back further
                }
            }
        }
        int intervalSeconds = getIntervalSecondsSafe();
        if (secondsIntoInterval == intervalSeconds) secondsIntoInterval = intervalSeconds - 1;
        repaint();
    }

    // Legacy footprint aliases
    public void setPassengerFootprintFeet(double lengthFt, double widthFt) { setLineFootprintFeet(lengthFt, widthFt); }
    public void setPassengerSizeFeet(double lengthFt, double widthFt) { setPassengerFootprintFeet(lengthFt, widthFt); }
    public void setPassengerDimsFeet(double lengthFt, double widthFt) { setPassengerFootprintFeet(lengthFt, widthFt); }

    /** Lines/Transit footprint setter. */
    public void setLineFootprintFeet(double lengthFt, double widthFt) {
        if (!(Double.isFinite(lengthFt) && lengthFt > 0)) return;
        if (!(Double.isFinite(widthFt) && widthFt > 0)) return;
        double len = clamp(lengthFt, 0.25, 30.0);
        double wid = clamp(widthFt, 0.25, 30.0);
        if (project != null) {
            try { project.setPassengerLengthFeet(len); } catch (Throwable ignored) { }
            try { project.setPassengerWidthFeet(wid); } catch (Throwable ignored) { }
        }
        lineLenFt = len;
        lineWidFt = wid;
        if (!holdFootprintCustom) { holdLenFt = lineLenFt; holdWidFt = lineWidFt; }
        repaint();
    }

    /** Holdroom footprint setter. */
    public void setHoldFootprintFeet(double lengthFt, double widthFt) {
        if (!(Double.isFinite(lengthFt) && lengthFt > 0)) return;
        if (!(Double.isFinite(widthFt) && widthFt > 0)) return;
        double len = clamp(lengthFt, 0.25, 30.0);
        double wid = clamp(widthFt, 0.25, 30.0);
        holdFootprintCustom = true;
        holdLenFt = len; holdWidFt = wid;
        if (project != null) {
            bestEffortInvoke(project, "setHoldPassengerLengthFeet", new Class<?>[]{double.class}, new Object[]{len});
            bestEffortInvoke(project, "setHoldPassengerWidthFeet",  new Class<?>[]{double.class}, new Object[]{wid});
            bestEffortInvoke(project, "setHoldroomPassengerLengthFeet", new Class<?>[]{double.class}, new Object[]{len});
            bestEffortInvoke(project, "setHoldroomPassengerWidthFeet",  new Class<?>[]{double.class}, new Object[]{wid});
        }
        repaint();
    }
    public void setHoldroomFootprintFeet(double lengthFt, double widthFt) { setHoldFootprintFeet(lengthFt, widthFt); }

    public void setPassengerFootprintFeet(double lineLen, double lineWid, double holdLen, double holdWid) {
        setLineFootprintFeet(lineLen, lineWid);
        setHoldFootprintFeet(holdLen, holdWid);
    }

    public double getPassengerLengthFeet() { syncLineFootprintFromProject(false); return lineLenFt; }
    public double getPassengerWidthFeet()  { syncLineFootprintFromProject(false); return lineWidFt; }
    public double getHoldPassengerLengthFeet() { return holdLenFt; }
    public double getHoldPassengerWidthFeet()  { return holdWidFt; }

    public void setShowPassengerFootprints(boolean show) { showFootprints = show; repaint(); }
    public boolean isShowPassengerFootprints() { return showFootprints; }

    public void setEngine(SimulationEngine engine) {
        this.engine = engine;
        secondsIntoInterval = 0;
        absoluteSeconds = 0L;
        lastScheduledEngineInterval = -1;
        resetSubSecondClock();

        ticketCarry.clear(); cpCarry.clear(); ticketStagger.clear(); cpStagger.clear();
        ticketReleaseFrac.clear(); cpReleaseFrac.clear(); ticketReleaseAbsSec.clear(); cpReleaseAbsSec.clear();
        ticketReleaseSecond.clear(); cpReleaseSecond.clear();
        lastCompletedTicketCount.clear(); lastCompletedCPCount.clear();
        stepStartAbsSec.clear(); stepIntervalSec.clear();

        queueTweens.clear();
        serviceVisuals.clear();
        lastTicketVisible.clear();
        lastTicketSlots.clear();
        lastCPVisible.clear();
        lastCPSlots.clear();

        ensureCurrentStepMapping();
        repaint();
    }

    public void setProject(FloorplanProject projectCopy) {
        this.project = projectCopy;
        syncLineFootprintFromProject(true);
        rebuildBindings();
        clampPan();
        repaint();
    }

    public void setSlotSpacingPx(int px) { this.slotSpacingPx = Math.max(0, px); repaint(); }
    public void setPathStridePx(int px) { this.pathStridePx = Math.max(1, px); rebuildBindings(); repaint(); }
    public void resetView() { zoom = 1.0; panX = 0; panY = 0; clampPan(); repaint(); }

    /** Forward walk-speed to engine; visuals are time-based via absolute seconds. */
    public void setWalkSpeedMps(double mps) {
        double clamped = (Double.isFinite(mps) ? Math.max(0.20, Math.min(3.50, mps)) : 1.34);
        if (engine != null) {
            try { Method m = engine.getClass().getMethod("setWalkSpeedMps", double.class); m.invoke(engine, clamped); }
            catch (Throwable ignored) { }
        }
    }

    // ==========================================================
    // Internal: footprint sync
    // ==========================================================

    private void syncLineFootprintFromProject(boolean mirrorToHoldIfNotCustom) {
        if (project == null) return;
        try {
            double len = project.getPassengerLengthFeet();
            double wid = project.getPassengerWidthFeet();
            if (Double.isFinite(len) && len > 0) lineLenFt = clamp(len, 0.25, 30.0);
            if (Double.isFinite(wid) && wid > 0) lineWidFt = clamp(wid, 0.25, 30.0);
        } catch (Throwable ignored) { }
        if (mirrorToHoldIfNotCustom && !holdFootprintCustom) { holdLenFt = lineLenFt; holdWidFt = lineWidFt; }
    }

    private static boolean bestEffortInvoke(Object target, String methodName, Class<?>[] sig, Object[] args) {
        if (target == null) return false;
        try { Method m = target.getClass().getMethod(methodName, sig); m.invoke(target, args); return true; }
        catch (Throwable ignored) { return false; }
    }

    // ==========================================================
    // Internal: pan/zoom and timers
    // ==========================================================

    private static boolean isPanStart(MouseEvent e) {
        return SwingUtilities.isLeftMouseButton(e)
                || SwingUtilities.isMiddleMouseButton(e)
                || SwingUtilities.isRightMouseButton(e)
                || e.isShiftDown();
    }

    private void clampPan() {
        BufferedImage img = (project == null) ? null : project.getFloorplanImage();
        if (img == null) return;
        int viewW = getWidth(), viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        double imgW = img.getWidth() * zoom;
        double imgH = img.getHeight() * zoom;
        double margin = PAN_MARGIN_PX;
        if (imgW <= viewW) { panX = (viewW - imgW) / 2.0; }
        else { double minX = viewW - imgW - margin, maxX = margin; panX = clamp(panX, minX, maxX); }
        if (imgH <= viewH) { panY = (viewH - imgH) / 2.0; }
        else { double minY = viewH - imgH - margin, maxY = margin; panY = clamp(panY, minY, maxY); }
    }

    private static double clamp(double v, double lo, double hi) { return (v < lo) ? lo : (v > hi ? hi : v); }
    private static int clampInt(int v, int lo, int hi) { return (v < lo) ? lo : (v > hi ? hi : v); }
    private static double clamp01(double v) { return (v < 0) ? 0 : (v > 1 ? 1 : v); }
    private static double clampFracInsideInterval(double t, int intervalSeconds) {
        double eps = 0.5 / Math.max(1, intervalSeconds); // 0.5s worth
        if (t < eps) t = eps; if (t > 1.0 - eps) t = 1.0 - eps; return t;
    }

    @Override public void addNotify() { super.addNotify(); ensureAnimTimer(); clampPan(); }
    @Override public void removeNotify() { if (animTimer != null) animTimer.stop(); super.removeNotify(); }

    private void ensureAnimTimer() {
        if (animTimer != null) return;
        animTimer = new javax.swing.Timer(ANIM_FPS_MS, e -> {
            if (!isShowing() || engine == null) return;
            int intervalSeconds = getIntervalSecondsSafe();
            double sub = Math.max(0.0, Math.min(0.999, (System.nanoTime() - subSecondStartNanos) / 1_000_000_000.0));
            stepAlpha01 = (intervalSeconds <= 0) ? 0.0 : clamp01((secondsIntoInterval + sub) / (double) intervalSeconds);
            repaint();
        });
        animTimer.setCoalesce(true);
        animTimer.start();
    }

    private void resetSubSecondClock() { subSecondStartNanos = System.nanoTime(); }

    private void rebuildBindings() {
        nearestCheckpointByTicket.clear();
        if (project == null) { bindings = null; pathCache = null; return; }
        bindings = new FloorplanBindings(project);
        pathCache = new PathCache(bindings.getMask(), pathStridePx, true);
    }

    // ==========================================================
    // Feet → Pixels conversion
    // ==========================================================

    private void recomputeFootprintPx() {
        syncLineFootprintFromProject(false);
        double pxPerFoot = pixelsPerFoot();
        lineLenPx = Math.max(2, (int) Math.round(lineLenFt * pxPerFoot));
        lineWidPx = Math.max(2, (int) Math.round(lineWidFt * pxPerFoot));
        holdLenPx = Math.max(2, (int) Math.round(holdLenFt * pxPerFoot));
        holdWidPx = Math.max(2, (int) Math.round(holdWidFt * pxPerFoot));
    }

    private double pixelsPerFoot() {
        double mPerPx = readProjectDouble(project, "getMetersPerPixel", "metersPerPixel");
        if (Double.isFinite(mPerPx) && mPerPx > 0) return 0.3048 / mPerPx;
        double dpi = readProjectDouble(project, "getDpi", "dpi");
        if (Double.isFinite(dpi) && dpi > 0) return dpi * 12.0;
        return 10.0; // last resort
    }
    private static double readProjectDouble(Object target, String getterName, String fieldName) {
        if (target == null) return Double.NaN;
        try { Method m = target.getClass().getMethod(getterName); Object out = m.invoke(target); if (out instanceof Number) return ((Number) out).doubleValue(); }
        catch (Throwable ignored) { }
        try { Field f = target.getClass().getDeclaredField(fieldName); f.setAccessible(true); Object out = f.get(target); if (out instanceof Number) return ((Number) out).doubleValue(); }
        catch (Throwable ignored) { }
        return Double.NaN;
    }

    private double metersPerPixelExact() {
        double mPerPx = readProjectDouble(project, "getMetersPerPixel", "metersPerPixel");
        if (Double.isFinite(mPerPx) && mPerPx > 0) return mPerPx;
        double pxPerFoot = pixelsPerFoot();
        if (pxPerFoot > 0) return 0.3048 / pxPerFoot;
        return 0.05; // ~5 cm/px fallback
    }

    private double getWalkSpeedMpsSafe() {
        double def = 1.34;
        if (engine == null) return def;
        try { Method m = engine.getClass().getMethod("getWalkSpeedMps"); Object out = m.invoke(engine); if (out instanceof Number) {
            double v = ((Number) out).doubleValue(); if (Double.isFinite(v) && v >= 0.20 && v <= 3.50) return v; } }
        catch (Throwable ignored) { }
        return def;
    }

    // ==========================================================
    // Paint
    // ==========================================================

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            BufferedImage img = (project == null) ? null : project.getFloorplanImage();
            if (img == null) { g2.setColor(Color.DARK_GRAY); g2.drawString("No floorplan image loaded.", 20, 20); return; }

            recomputeFootprintPx();

            AffineTransform at = new AffineTransform();
            at.translate(panX, panY); at.scale(zoom, zoom); g2.setTransform(at);
            g2.drawImage(img, 0, 0, null);

            if (engine == null || bindings == null || pathCache == null) { drawZones(g2); return; }

            // Ensure staggering prepared for the current engine interval
            scheduleStaggerIfNeeded(false);

            int curStep = engineCurrentInterval();
            double nowAbs = stepStartAbsSeconds(curStep) + secondsIntoInterval
                    + Math.max(0.0, Math.min(0.999, (System.nanoTime() - subSecondStartNanos) / 1_000_000_000.0));
            this.nowAbsForRender = nowAbs;

            paintCurStep = curStep;
            rebuildFlightTimeCaches();

            // Pending maps are ABSOLUTE-SECOND keyed
            Map<Integer, List<Passenger>> pendingToTicket = readPendingMap(engine,
                    new String[]{"getPendingToTicket", "getPendingToTC", "getPendingToCounter"},
                    new String[]{"pendingToTicket", "pendingToTC", "pendingToCounter"});

            Map<Integer, List<Passenger>> pendingToCP = readPendingMap(engine,
                    new String[]{"getPendingToCP", "getPendingToCheckpoint"},
                    new String[]{"pendingToCP", "pendingToCheckpoint"});

            Map<Integer, List<Passenger>> pendingToHold = readPendingMap(engine,
                    new String[]{"getPendingToHold", "getPendingToHoldroom"},
                    new String[]{"pendingToHold", "pendingToHoldroom"});

            Set<Passenger> inTransitToTicket = collectAll(pendingToTicket);
            Set<Passenger> inTransitToCP     = collectAll(pendingToCP);
            Set<Passenger> inTransitToHold   = collectAll(pendingToHold);

            List<LinkedList<Passenger>> completedTicketLines = engineGetCompletedTicketLines();
            List<LinkedList<Passenger>> completedCheckpointLines = engineGetCompletedCheckpointLines();

            Map<Passenger, Integer> ticketDoneOf     = indexOf(completedTicketLines);
            Map<Passenger, Integer> checkpointDoneOf = indexOf(completedCheckpointLines);

            // Prepare checkpoint service visuals (scan pendingToHold to ensure dwell represented even if queue scan missed it)
            seedServiceVisualsFromPendingToHold(pendingToHold, nowAbs);

            // ------ DRAW ORDER (queues -> service -> walkers -> staging/hold) ------

            // 3) Ticket queues — hide when absolute release time has passed (unchanged semantics)
            List<LinkedList<Passenger>> ticketLines = engineGetTicketLines();
            for (int c = 0; c < ticketLines.size(); c++) {
                Point anchor = bindings.getTicketAnchor(c);
                Polygon poly = bindings.getTicketQueuePolygon(c);

                Set<Passenger> skipTransit = new HashSet<>();
                skipTransit.addAll(inTransitToTicket);
                skipTransit.addAll(inTransitToCP);

                List<Passenger> src = ticketLines.get(c);
                List<Passenger> visible = new ArrayList<>(src.size());

                long curStart = stepStartAbsSeconds(curStep);
                int len = intervalSecondsAtStep(curStep);
                long curEnd = curStart + Math.max(1, len);

                for (Passenger p : src) {
                    if (p == null) continue;
                    Integer doneAbs = engineGetTicketDoneAbsSecond(p);
                    boolean completesThisInterval = doneAbs != null && doneAbs >= curStart && doneAbs < curEnd;
                    if (completesThisInterval) {
                        Double abs = null;
                        Integer sec = ticketReleaseSecond.get(p);
                        if (sec != null) abs = curStart + (sec + 0.5);
                        if (abs == null) abs = ticketReleaseAbsSec.get(p);
                        if (abs != null && nowAbs >= abs) continue; // peeled off
                    }
                    visible.add(p);
                }

                List<Point> slots = buildQueueSlots(poly, anchor, lineWidPx, lineLenPx, slotSpacingPx, visible.size());
                applyQueueSlideTweens(lastTicketVisible, lastTicketSlots, c, visible, slots, nowAbs);
                drawQueue(g2, visible, slots, anchor, skipTransit, false);
            }

            // 5) Checkpoint queues — NEW: remove passengers at service START (tStart = doneAbs - serviceSec)
            List<LinkedList<Passenger>> cpLines = engineGetCheckpointLines();
            List<CheckpointConfig> cpCfgsForPaint = getCheckpointConfigs(); // for service seconds
            for (int c = 0; c < cpLines.size(); c++) {
                Point anchor = bindings.getCheckpointAnchor(c);
                Polygon poly = bindings.getCheckpointQueuePolygon(c);

                List<Passenger> src = cpLines.get(c);
                List<Passenger> visible = new ArrayList<>(src.size());

                double serviceSec = serviceSecondsForLane(cpCfgsForPaint, c);
                Point queueFront = firstQueueSlot(poly, anchor, lineWidPx, lineLenPx);

                for (Passenger p : src) {
                    if (p == null) continue;
                    Integer doneAbs = engineGetCheckpointDoneAbsSecond(p);
                    if (doneAbs != null && serviceSec < Double.POSITIVE_INFINITY) {
                        double tStart = doneAbs - serviceSec;
                        if (nowAbs >= tStart && nowAbs < doneAbs) {
                            // Begin/continue service visualization
                            ensureServiceVisual(p, c, tStart, doneAbs, queueFront, anchor);
                            continue; // not visible in queue while in service
                        }
                    }
                    visible.add(p);
                }

                List<Point> slots = buildQueueSlots(poly, anchor, lineWidPx, lineLenPx, slotSpacingPx, visible.size());
                applyQueueSlideTweens(lastCPVisible, lastCPSlots, c, visible, slots, nowAbs);
                drawQueue(g2, visible, slots, anchor, inTransitToHold, false);
            }

            // Draw service passengers at checkpoint anchors / nudge into CHECKPOINT_AREA
            drawCheckpointServiceVisuals(g2, nowAbs);

            // 0/1/2) WALKING (spawn->ticket, to checkpoint, to hold)
            drawTransitSpawnToTicket(g2, pendingToTicket, nowAbs);
            drawTransitToCheckpoint(g2, pendingToCP, nowAbs, ticketDoneOf);
            drawTransitToHoldroom(g2, pendingToHold, nowAbs, checkpointDoneOf);

            // 4) completed ticket staging
            for (int c = 0; c < completedTicketLines.size(); c++) {
                Point anchor = bindings.getTicketAnchor(c);
                List<Passenger> visible = engineGetVisibleCompletedTicketLine(c, completedTicketLines);
                drawStagingAtAnchor(g2, visible, anchor, inTransitToCP, false);
            }

            // 6) completed checkpoint staging
            for (int c = 0; c < completedCheckpointLines.size(); c++) {
                Point anchor = bindings.getCheckpointAnchor(c);
                drawStagingAtAnchor(g2, completedCheckpointLines.get(c), anchor, inTransitToHold, false);
            }

            // 7) hold rooms
            List<LinkedList<Passenger>> holdLines = engineGetHoldroomLines();
            for (int h = 0; h < holdLines.size(); h++) {
                Point anchor = bindings.getHoldroomAnchor(h);
                Polygon poly = bindings.getHoldroomAreaPolygon(h);
                List<Point> slots = buildHoldroomSlots(poly, anchor, holdWidPx, holdLenPx, slotSpacingPx, holdLines.get(h).size());
                drawQueue(g2, holdLines.get(h), slots, anchor, Collections.emptySet(), true);
            }

            drawZones(g2);
        } finally { g2.dispose(); }
    }

    // ==========================================================
    // SPEC-1 helpers: service visuals & queue sliding
    // ==========================================================

    /** Compute service seconds for a checkpoint lane based on ratePerHour; Infinity if lane closed/missing. */
    private double serviceSecondsForLane(List<CheckpointConfig> cpCfgs, int laneIdx) {
        if (cpCfgs == null || laneIdx < 0 || laneIdx >= cpCfgs.size() || cpCfgs.get(laneIdx) == null) return Double.POSITIVE_INFINITY;
        double perHour = Math.max(0.0, cpCfgs.get(laneIdx).getRatePerHour());
        if (!(perHour > 0)) return Double.POSITIVE_INFINITY;
        return 3600.0 / perHour;
    }

    /** First (front) queue slot for a polygon/anchor combo. */
    private Point firstQueueSlot(Polygon poly, Point anchor, int rectW, int rectH) {
        List<Point> one = buildQueueSlots(poly, anchor, rectW, rectH, slotSpacingPx, 1);
        return (one != null && !one.isEmpty()) ? one.get(0) : anchor;
    }

    /** Ensure a service visual exists for passenger p on lane c. */
    private void ensureServiceVisual(Passenger p, int laneIdx, double startAbs, double doneAbs, Point from, Point toAnchor) {
        ServiceVisual sv = serviceVisuals.get(p);
        if (sv == null) {
            sv = new ServiceVisual();
            sv.p = p;
            sv.laneIdx = laneIdx;
            sv.startAbs = startAbs;
            sv.doneAbs = doneAbs;
            sv.from = (from != null) ? new Point(from) : toAnchor;
            sv.to = (toAnchor != null) ? new Point(toAnchor) : toAnchor;
            serviceVisuals.put(p, sv);
        } else {
            // keep updated doneAbs/startAbs in case of rewind/changes
            sv.startAbs = startAbs;
            sv.doneAbs = doneAbs;
        }
    }

    /** Scan pendingToHold to (re)create service visuals for any pax currently in service. */
    private void seedServiceVisualsFromPendingToHold(Map<Integer, List<Passenger>> pendingToHold, double nowAbs) {
        List<CheckpointConfig> cpCfgs = getCheckpointConfigs();
        double maxSvc = 0.0;
        if (cpCfgs != null) {
            for (int i = 0; i < cpCfgs.size(); i++) {
                maxSvc = Math.max(maxSvc, serviceSecondsForLane(cpCfgs, i));
            }
        }
        int window = (int) Math.ceil(Math.min(3600, Math.max(1.0, maxSvc))); // cap window defensively
        if (pendingToHold == null || pendingToHold.isEmpty()) return;

        // For each doneAbs in a small forward window, if now in [tStart, doneAbs) create a service visual
        int startKey = (int) Math.floor(nowAbs);
        for (int k = startKey; k <= startKey + window; k++) {
            List<Passenger> list = pendingToHold.get(k);
            if (list == null) continue;
            for (Passenger p : list) {
                if (p == null) continue;
                int cpIdx = passengerIndex(p,
                        new String[]{"getAssignedCheckpointIndex", "getCheckpointIndex", "getTargetCheckpointIndex", "getChosenCheckpointIndex"},
                        new String[]{"assignedCheckpointIndex", "checkpointIndex", "targetCheckpointIndex", "chosenCheckpointIndex"},
                        0);
                double svcSec = serviceSecondsForLane(cpCfgs, cpIdx);
                if (!(svcSec < Double.POSITIVE_INFINITY)) continue;
                double tStart = k - svcSec;
                if (nowAbs >= tStart && nowAbs < k) {
                    Point anchor = bindings.getCheckpointAnchor(cpIdx);
                    Polygon poly = bindings.getCheckpointQueuePolygon(cpIdx);
                    Point front = firstQueueSlot(poly, anchor, lineWidPx, lineLenPx);
                    ensureServiceVisual(p, cpIdx, tStart, k, front, anchor);
                }
            }
        }

        // Drop any stale service visuals whose doneAbs < now
        serviceVisuals.entrySet().removeIf(e -> nowAbs >= e.getValue().doneAbs + 1e-6);
    }

    /** Draw checkpoint service visuals: nudge then dwell at anchor; remove finished ones. */
    private void drawCheckpointServiceVisuals(Graphics2D g2, double nowAbs) {
        if (serviceVisuals.isEmpty()) return;
        List<Passenger> toRemove = new ArrayList<>();
        for (ServiceVisual sv : serviceVisuals.values()) {
            if (sv == null || sv.p == null || sv.to == null) continue;
            if (nowAbs >= sv.doneAbs) { toRemove.add(sv.p); continue; }

            Point pos;
            double nudgeEnd = sv.startAbs + (NUDGE_MS / 1000.0);
            if (nowAbs < sv.startAbs) {
                // not yet started (should be in queue still, but guard)
                pos = (sv.from != null) ? sv.from : sv.to;
            } else if (nowAbs < nudgeEnd && sv.from != null) {
                double t = (nowAbs - sv.startAbs) / (NUDGE_MS / 1000.0);
                t = easeCubicInOut(clamp01(t));
                pos = lerpPoint(sv.from, sv.to, t);
            } else {
                pos = sv.to;
            }

            // Draw passenger at pos (footprint off; ring pulse to imply "in service")
            drawPassengerWithFootprint(g2, sv.p, pos, lineWidPx, lineLenPx);
            // simple ring
            double phase = ((nowAbs - sv.startAbs) % 1.0); // 1s pulse
            int ringR = Math.max(6, iconRadiusForFootprint(lineWidPx, lineLenPx) + 4 + (int) Math.round(3 * phase));
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(new Color(0, 0, 0, 60));
            g2.drawOval(pos.x - ringR, pos.y - ringR, ringR * 2, ringR * 2);
        }
        for (Passenger p : toRemove) serviceVisuals.remove(p);
    }

    /** Create/update tweens based on lane's previous vs current visible lists. */
    private void applyQueueSlideTweens(Map<Integer, List<Passenger>> lastVisibleMap,
                                       Map<Integer, List<Point>> lastSlotsMap,
                                       int laneIdx,
                                       List<Passenger> visibleNow,
                                       List<Point> slotsNow,
                                       double nowAbs) {
        List<Passenger> prev = lastVisibleMap.getOrDefault(laneIdx, Collections.emptyList());
        List<Point> prevSlots = lastSlotsMap.getOrDefault(laneIdx, Collections.emptyList());

        if (!prev.isEmpty() && !visibleNow.isEmpty() && !prevSlots.isEmpty()) {
            // For each pax that remained in the queue, if index decreased => create tween from old slot to new slot
            Map<Passenger, Integer> prevIndex = new IdentityHashMap<>();
            for (int i = 0; i < prev.size(); i++) prevIndex.put(prev.get(i), i);
            for (int j = 0; j < visibleNow.size(); j++) {
                Passenger p = visibleNow.get(j);
                Integer pi = prevIndex.get(p);
                if (pi == null) continue; // newly arrived or previously not tracked
                if (j < pi && pi < prevSlots.size() && j < slotsNow.size()) {
                    Point from = prevSlots.get(pi);
                    Point to = slotsNow.get(j);
                    if (from != null && to != null) {
                        int slotsMoved = Math.max(1, pi - j);
                        double dur = (SLIDE_MS / 1000.0) * slotsMoved;

                        Tween t = queueTweens.get(p);
                        if (t == null || nowAbs >= t.endAbs - 1e-6) {
                            t = new Tween();
                            t.startAbs = nowAbs;
                            t.endAbs = nowAbs + dur;
                            t.from = new Point(from);
                            t.to = new Point(to);
                            queueTweens.put(p, t);
                        } else {
                            // If active tween exists, retarget destination if needed
                            t.to = new Point(to);
                            t.endAbs = Math.max(t.endAbs, nowAbs + 0.001);
                        }
                    }
                }
            }
        }

        // Update last snapshots
        lastVisibleMap.put(laneIdx, new ArrayList<>(visibleNow));
        lastSlotsMap.put(laneIdx, (slotsNow == null) ? Collections.emptyList() : new ArrayList<>(slotsNow));

        // Expire finished tweens for pax no longer in any slot list
        queueTweens.entrySet().removeIf(e -> (nowAbs >= e.getValue().endAbs + 1e-6));
    }

    private static Point lerpPoint(Point a, Point b, double t){
        if (a == null) return (b == null ? null : new Point(b));
        if (b == null) return new Point(a);
        int x = (int) Math.round(a.x + (b.x - a.x) * t);
        int y = (int) Math.round(a.y + (b.y - a.y) * t);
        return new Point(x, y);
    }

    // ==========================================================
    // Visual staggering helpers
    // ==========================================================

    /** Build per-passenger release schedules for the CURRENT interval if needed. */
    private void scheduleStaggerIfNeeded(boolean forceReschedule) {
        if (engine == null) return;
        int ci = engineCurrentInterval();
        if (!forceReschedule && ci == lastScheduledEngineInterval) return;
        lastScheduledEngineInterval = ci;

        ensureCurrentStepMapping();
        long stepStartSec = stepStartAbsSeconds(ci);
        int intervalSeconds = intervalSecondsAtStep(ci);
        long stepEndSec = stepStartSec + Math.max(1, intervalSeconds);

        // Clear per-passenger maps for the new interval
        ticketReleaseFrac.clear(); cpReleaseFrac.clear();
        ticketReleaseAbsSec.clear(); cpReleaseAbsSec.clear();
        ticketReleaseSecond.clear(); cpReleaseSecond.clear();

        // ---- Gather config rates ----
        List<TicketCounterConfig> ticketCfgs = getTicketConfigs();
        List<CheckpointConfig>    cpCfgs     = getCheckpointConfigs();

        // ---- Determine who starts leaving lines this interval (by absolute done seconds) ----
        Map<Integer, List<Passenger>> pendingToCP = readPendingMap(engine,
                new String[]{"getPendingToCP", "getPendingToCheckpoint"},
                new String[]{"pendingToCP", "pendingToCheckpoint"});

        Map<Integer, List<Passenger>> pendingToHold = readPendingMap(engine,
                new String[]{"getPendingToHold", "getPendingToHoldroom"},
                new String[]{"pendingToHold", "pendingToHoldroom"});

        // Group by ticket lane: in-person pax whose ticketDoneAbs ∈ [stepStartSec, stepEndSec)
        Map<Integer, List<Passenger>> perTicketLaneThisInterval = new HashMap<>();
        if (pendingToCP != null) {
            for (List<Passenger> list : pendingToCP.values()) {
                for (Passenger p : safeList(list)) {
                    if (p == null || !isInPerson(p)) continue;
                    Integer done = engineGetTicketDoneAbsSecond(p);
                    if (done == null || done < stepStartSec || done >= stepEndSec) continue;
                    int ticketIdx = passengerIndex(p,
                            new String[]{"getAssignedTicketCounterIndex", "getTicketCounterIndex", "getTargetTicketCounterIndex", "getChosenTicketCounterIndex"},
                            new String[]{"assignedTicketCounterIndex", "ticketCounterIndex", "targetTicketCounterIndex", "chosenTicketCounterIndex"},
                            0);
                    perTicketLaneThisInterval.computeIfAbsent(ticketIdx, k -> new ArrayList<>()).add(p);
                }
            }
        }

        // Group by checkpoint lane: pax whose checkpointDoneAbs ∈ [stepStartSec, stepEndSec)
        Map<Integer, List<Passenger>> perCPLaneThisInterval = new HashMap<>();
        if (pendingToHold != null) {
            for (List<Passenger> list : pendingToHold.values()) {
                for (Passenger p : safeList(list)) {
                    if (p == null) continue;
                    Integer done = engineGetCheckpointDoneAbsSecond(p);
                    if (done == null || done < stepStartSec || done >= stepEndSec) continue;
                    int cpIdx = passengerIndex(p,
                            new String[]{"getAssignedCheckpointIndex", "getCheckpointIndex", "getTargetCheckpointIndex", "getChosenCheckpointIndex"},
                            new String[]{"assignedCheckpointIndex", "checkpointIndex", "targetCheckpointIndex", "chosenCheckpointIndex"},
                            0);
                    perCPLaneThisInterval.computeIfAbsent(cpIdx, k -> new ArrayList<>()).add(p);
                }
            }
        }

        // interval-length aware minutes/hours (double)
        double intervalMinutes = intervalSeconds / 60.0;
        double intervalHours   = intervalSeconds / 3600.0;

        // ---- Build/assign release times per Ticket lane ----
        for (Map.Entry<Integer, List<Passenger>> entry : perTicketLaneThisInterval.entrySet()) {
            int lane = entry.getKey();
            List<Passenger> pax = entry.getValue();

            double ratePerMin = (ticketCfgs != null && lane < safeSize(ticketCfgs) && ticketCfgs.get(lane) != null)
                    ? Math.max(0.0, ticketCfgs.get(lane).getRate()) : 0.0; // pax/min
            double R = ratePerMin * intervalMinutes; // expected completions this interval
            double L = ticketCarry.getOrDefault(lane, 0.0);

            double[] tkExpected = computeTkArray(L, R);
            List<Double> tkFinal = reconcileSchedule(tkExpected, pax.size(), L, R);

            ticketCarry.put(lane, Math.max(0.0, L + R - pax.size()));
            double lanePhase = lanePhaseFrac(lane, ci, intervalSeconds);

            boolean[] used = new boolean[Math.max(1, intervalSeconds)];
            double prevT = 0.0;
            for (int i = 0; i < pax.size() && i < tkFinal.size(); i++) {
                Passenger p = pax.get(i);
                if (p == null) continue;
                double t = clamp01(tkFinal.get(i) + lanePhase);
                if (i > 0 && t <= prevT) t = Math.min(1.0 - 1e-6, prevT + Math.max(1e-6, 1.0 / (intervalSeconds * 10.0)));
                t = clampFracInsideInterval(t, intervalSeconds); prevT = t;
                ticketReleaseFrac.put(p, t);
                double abs = stepStartSec + t * intervalSeconds; ticketReleaseAbsSec.put(p, abs);
                int candSec = clampInt((int) Math.floor(t * intervalSeconds), 0, intervalSeconds - 1);
                int sec = reserveUniqueSecond(candSec, used); ticketReleaseSecond.put(p, sec);
            }
            ticketStagger.put(lane, toArray(tkFinal));
        }

        // ---- Build/assign release times per Checkpoint lane ----
        for (Map.Entry<Integer, List<Passenger>> entry : perCPLaneThisInterval.entrySet()) {
            int lane = entry.getKey();
            List<Passenger> pax = entry.getValue();

            double perHour = (cpCfgs != null && lane < safeSize(cpCfgs) && cpCfgs.get(lane) != null)
                    ? Math.max(0.0, cpCfgs.get(lane).getRatePerHour()) : 0.0; // pax/hour
            double R = perHour * intervalHours; // expected completions this interval
            double L = cpCarry.getOrDefault(lane, 0.0);

            double[] tkExpected = computeTkArray(L, R);
            List<Double> tkFinal = reconcileSchedule(tkExpected, pax.size(), L, R);

            cpCarry.put(lane, Math.max(0.0, L + R - pax.size()));
            double lanePhase = lanePhaseFrac(lane, ci, intervalSeconds);

            boolean[] used = new boolean[Math.max(1, intervalSeconds)];
            double prevT = 0.0;
            for (int i = 0; i < pax.size() && i < tkFinal.size(); i++) {
                Passenger p = pax.get(i);
                if (p == null) continue;
                double t = clamp01(tkFinal.get(i) + lanePhase);
                if (i > 0 && t <= prevT) t = Math.min(1.0 - 1e-6, prevT + Math.max(1e-6, 1.0 / (intervalSeconds * 10.0)));
                t = clampFracInsideInterval(t, intervalSeconds); prevT = t;
                cpReleaseFrac.put(p, t);
                double abs = stepStartSec + t * intervalSeconds; cpReleaseAbsSec.put(p, abs);
                int candSec = clampInt((int) Math.floor(t * intervalSeconds), 0, intervalSeconds - 1);
                int sec = reserveUniqueSecond(candSec, used); cpReleaseSecond.put(p, sec);
            }
            cpStagger.put(lane, toArray(tkFinal));
        }
    }

    private static int safeSize(List<?> l) { return (l == null) ? 0 : l.size(); }

    /** Expected t_k from L and R. */
    private static double[] computeTkArray(double L, double R) {
        if (!(R > 0)) return new double[0];
        int n = (int) Math.floor(L + R);
        if (n <= 0) return new double[0];
        ArrayList<Double> list = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            double t = (1.0 - L + k) / R;
            if (t > 0 && t <= 1.0) list.add(t);
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    /** Reconcile expected array against actual count, ensuring strictly increasing (0,1). */
    private static List<Double> reconcileSchedule(double[] tkExpected, int actualCount, double L, double R) {
        List<Double> out = new ArrayList<>(actualCount);
        if (tkExpected != null) for (int i = 0; i < Math.min(actualCount, tkExpected.length); i++) out.add(tkExpected[i]);
        int k = (tkExpected == null) ? 0 : tkExpected.length;
        while (out.size() < actualCount) {
            double t = (R > 0) ? (1.0 - L + k) / R : Double.POSITIVE_INFINITY;
            if (t > 0 && t < 1.0) out.add(t);
            else { // Even spacing fallback
                out.clear(); for (int i = 0; i < actualCount; i++) out.add((i + 1.0) / (actualCount + 1.0)); break;
            }
            k++;
        }
        while (out.size() > actualCount) out.remove(out.size() - 1);
        out.sort(Double::compareTo);
        for (int i = 0; i < out.size(); i++) {
            double v = out.get(i); if (v <= 0) v = 1e-6; if (v > 1) v = 1.0; out.set(i, v);
        }
        return out;
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[(list == null) ? 0 : list.size()];
        if (list != null) for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
        return a;
    }

    private int getIntervalSecondsSafe() {
        if (engine == null) return 60;
        try { Method m = engine.getClass().getMethod("getIntervalSeconds"); Object o = m.invoke(engine); if (o instanceof Number) return Math.max(1, ((Number) o).intValue()); }
        catch (Throwable ignored) { }
        try { Method m = engine.getClass().getMethod("getIntervalMinutes"); Object o = m.invoke(engine); if (o instanceof Number) return Math.max(1, ((Number) o).intValue()) * 60; }
        catch (Throwable ignored) { }
        return 60;
    }

    @SuppressWarnings("unchecked")
    private List<TicketCounterConfig> getTicketConfigs() {
        if (engine == null) return null;
        for (String name : new String[]{"getTicketCounterConfigs", "getTicketConfigs", "getCounterConfigs"}) {
            try { Method m = engine.getClass().getMethod(name); Object out = m.invoke(engine); if (out instanceof List) return (List<TicketCounterConfig>) out; }
            catch (Throwable ignored) { }
        }
        for (String field : new String[]{"ticketCounterConfigs", "ticketConfigs", "counterConfigs"}) {
            try { Field f = engine.getClass().getDeclaredField(field); f.setAccessible(true); Object out = f.get(engine); if (out instanceof List) return (List<TicketCounterConfig>) out; }
            catch (Throwable ignored) { }
        }
        try {
            List<Flight> flights = engineGetFlights();
            Method m = bindings.getClass().getMethod("buildTicketCounterConfigsFromZones", List.class, Object.class, double.class);
            Object out = m.invoke(bindings, flights, null, 1.0);
            if (out instanceof List) return (List<TicketCounterConfig>) out;
        } catch (Throwable ignored) { }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<CheckpointConfig> getCheckpointConfigs() {
        if (engine == null) return null;
        for (String name : new String[]{"getCheckpointConfigs"}) {
            try { Method m = engine.getClass().getMethod(name); Object out = m.invoke(engine); if (out instanceof List) return (List<CheckpointConfig>) out; }
            catch (Throwable ignored) { }
        }
        for (String field : new String[]{"checkpointConfigs", "cpConfigs"}) {
            try { Field f = engine.getClass().getDeclaredField(field); f.setAccessible(true); Object out = f.get(engine); if (out instanceof List) return (List<CheckpointConfig>) out; }
            catch (Throwable ignored) { }
        }
        try {
            Method m = bindings.getClass().getMethod("buildCheckpointConfigsFromZones", Object.class, double.class);
            Object out = m.invoke(bindings, null, 60.0);
            if (out instanceof List) return (List<CheckpointConfig>) out;
        } catch (Throwable ignored) { }
        return null;
    }

    // ==========================================================
    // Queue / Holdroom slot builders
    // ==========================================================

    private List<Point> buildQueueSlots(Polygon poly, Point anchor, int rectW, int rectH, int gapPx, int needed) {
        if (needed <= 0) return Collections.emptyList();
        int stepX = Math.max(2, rectW + Math.max(0, gapPx));
        int stepY = Math.max(2, rectH + Math.max(0, gapPx));
        WalkMask mask = (bindings == null) ? null : bindings.getMask();
        List<Point> base = computeAxisAlignedSlotsFullyInside(poly, mask, stepX, stepY, rectW, rectH);
        base = sortSlotsOutwardFromAnchor(base, poly, anchor);
        if (anchor != null && base.size() > 1) {
            int bestIdx = 0; double bestD = anchor.distanceSq(base.get(0));
            for (int i = 1; i < base.size(); i++) { double d = anchor.distanceSq(base.get(i)); if (d < bestD) { bestD = d; bestIdx = i; } }
            if (bestIdx > 0) { Point best = base.remove(bestIdx); base.add(0, best); }
        }
        return ensureOverflowOutsidePolygon(base, needed, anchor, poly, mask, Math.max(rectW, rectH) + Math.max(0, gapPx));
    }

    private List<Point> buildHoldroomSlots(Polygon poly, Point anchor, int rectW, int rectH, int gapPx, int needed) {
        if (needed <= 0) return Collections.emptyList();
        int stepX = Math.max(2, rectW + Math.max(0, gapPx));
        int stepY = Math.max(2, rectH + Math.max(0, gapPx));
        WalkMask mask = (bindings == null) ? null : bindings.getMask();
        List<Point> base = computeAxisAlignedSlotsFullyInside(poly, mask, stepX, stepY, rectW, rectH);
        base.sort(Comparator.<Point>comparingInt(p -> p.y).thenComparingInt(p -> p.x));
        return ensureOverflowOutsidePolygonHoldroom(base, needed, anchor, poly, mask, Math.max(rectW, rectH) + Math.max(0, gapPx));
    }

    private static List<Point> computeAxisAlignedSlotsFullyInside(Polygon poly, WalkMask mask,
                                                                  int stepX, int stepY,
                                                                  int rectW, int rectH) {
        if (poly == null || poly.npoints < 3) return Collections.emptyList();
        Rectangle r = poly.getBounds();
        if (r.width <= 0 || r.height <= 0) return Collections.emptyList();
        int halfW = Math.max(1, rectW / 2); int halfH = Math.max(1, rectH / 2);
        int x0 = r.x + halfW; int y0 = r.y + halfH; int x1 = (r.x + r.width) - halfW; int y1 = (r.y + r.height) - halfH;
        if (x0 > x1 || y0 > y1) return Collections.emptyList();
        List<Point> out = new ArrayList<>();
        for (int y = y0; y <= y1; y += stepY) {
            for (int x = x0; x <= x1; x += stepX) {
                int cx = x, cy = y;
                if (!poly.contains(cx - halfW + 0.5, cy - halfH + 0.5)) continue;
                if (!poly.contains(cx + halfW - 0.5, cy - halfH + 0.5)) continue;
                if (!poly.contains(cx - halfW + 0.5, cy + halfH - 0.5)) continue;
                if (!poly.contains(cx + halfW - 0.5, cy + halfH - 0.5)) continue;
                if (mask != null) {
                    if (!mask.inBounds(cx, cy) || !mask.isWalkable(cx, cy)) continue;
                    int[][] pts = {{cx - halfW, cy - halfH},{cx + halfW, cy - halfH},{cx - halfW, cy + halfH},{cx + halfW, cy + halfH}};
                    boolean ok = true; for (int[] p : pts) { int px = p[0], py = p[1]; if (!mask.inBounds(px, py) || !mask.isWalkable(px, py)) { ok = false; break; } }
                    if (!ok) continue;
                }
                out.add(new Point(cx, cy));
            }
        }
        return out;
    }

    private static List<Point> sortSlotsOutwardFromAnchor(List<Point> pts, Polygon poly, Point anchor) {
        if (pts == null || pts.isEmpty()) return (pts == null) ? Collections.emptyList() : pts;
        if (anchor == null) return pts;
        Point centroid = polygonCentroid(poly);
        double dx0 = (centroid.x - anchor.x); double dy0 = (centroid.y - anchor.y); double len = Math.hypot(dx0, dy0);
        if (len < 1e-6) { dx0 = 0; dy0 = 1; len = 1; }
        final double dx = dx0 / len; final double dy = dy0 / len; final double px = -dy; final double py = dx;
        List<Point> copy = new ArrayList<>(pts);
        copy.sort((a, b) -> {
            double au = (a.x - anchor.x) * dx + (a.y - anchor.y) * dy;
            double bu = (b.x - anchor.x) * dx + (b.y - anchor.y) * dy;
            if (au < bu) return -1; if (au > bu) return 1;
            double av = (a.x - anchor.x) * px + (a.y - anchor.y) * py;
            double bv = (b.x - anchor.x) * px + (b.y - anchor.y) * py;
            return Double.compare(av, bv);
        });
        return copy;
    }

    private List<Point> ensureOverflowOutsidePolygon(List<Point> base, int needed, Point anchor, Polygon poly, WalkMask mask, int spacingDist) {
        List<Point> out = new ArrayList<>(); if (base != null) out.addAll(base); if (out.size() >= needed) return out;
        Point centroid = polygonCentroid(poly);
        double dx = (centroid.x - (anchor != null ? anchor.x : centroid.x));
        double dy = (centroid.y - (anchor != null ? anchor.y : centroid.y));
        double len = Math.hypot(dx, dy); if (len < 1e-6) { dx = 0; dy = 1; len = 1; } dx /= len; dy /= len;
        Point p = out.isEmpty() ? (anchor != null ? anchor : centroid) : out.get(out.size() - 1);
        int guard = 0; while (poly != null && poly.contains(p.x + 0.5, p.y + 0.5) && guard++ < 500) {
            p = new Point((int) Math.round(p.x + dx * spacingDist), (int) Math.round(p.y + dy * spacingDist)); }
        while (out.size() < needed) {
            Point cand = p; if (mask != null && (!mask.inBounds(cand.x, cand.y) || !mask.isWalkable(cand.x, cand.y))) cand = snapToWalkableStatic(mask, cand, 4);
            out.add(cand);
            p = new Point((int) Math.round(p.x + dx * spacingDist), (int) Math.round(p.y + dy * spacingDist));
        }
        return out;
    }

    private List<Point> ensureOverflowOutsidePolygonHoldroom(List<Point> base, int needed, Point anchor, Polygon poly, WalkMask mask, int spacingDist) {
        List<Point> out = new ArrayList<>(); if (base != null) out.addAll(base); if (out.size() >= needed) return out;
        Point centroid = polygonCentroid(poly);
        double dx = ((anchor != null ? anchor.x : centroid.x) - centroid.x);
        double dy = ((anchor != null ? anchor.y : centroid.y) - centroid.y);
        double len = Math.hypot(dx, dy); if (len < 1e-6) { dx = 0; dy = 1; len = 1; } dx /= len; dy /= len;
        Point p = (anchor != null) ? new Point(anchor) : centroid;
        int guard = 0; while (poly != null && poly.contains(p.x + 0.5, p.y + 0.5) && guard++ < 500) {
            p = new Point((int) Math.round(p.x + dx * spacingDist), (int) Math.round(p.y + dy * spacingDist)); }
        while (out.size() < needed) {
            Point cand = p; if (mask != null && (!mask.inBounds(cand.x, cand.y) || !mask.isWalkable(cand.x, cand.y))) cand = snapToWalkableStatic(mask, cand, 4);
            out.add(cand);
            p = new Point((int) Math.round(p.x + dx * spacingDist), (int) Math.round(p.y + dy * spacingDist));
        }
        return out;
    }

    private static Point polygonCentroid(Polygon poly) {
        if (poly == null || poly.npoints < 1) return new Point(0, 0);
        double cx = 0, cy = 0; for (int i = 0; i < poly.npoints; i++) { cx += poly.xpoints[i]; cy += poly.ypoints[i]; }
        cx /= poly.npoints; cy /= poly.npoints; return new Point((int) Math.round(cx), (int) Math.round(cy));
    }

    private static Point snapToWalkableStatic(WalkMask mask, Point p, int stridePx) {
        if (mask == null || p == null) return p;
        Point s = PathCache.snapToNearestWalkable(mask, p, Math.max(1, stridePx), 240);
        return (s != null) ? s : p;
    }

    // ==========================================================
    // Transit drawing (absolute-time based)
    // ==========================================================

    private static class TransitItem {
        final Passenger passenger;
        final Point a; // start anchor
        final Point b; // end anchor
        final double arriveAbsSec; // ABS second when the engine schedules arrival to the target
        Double startAbsSec;        // computed visual start time (arrive - pathTime)
        TransitItem(Passenger p, Point a, Point b, double arriveAbsSec, Double startAbsSec) {
            this.passenger = p; this.a = a; this.b = b; this.arriveAbsSec = arriveAbsSec; this.startAbsSec = startAbsSec;
        }
    }

    private void drawTransitSpawnToTicket(Graphics2D g2, Map<Integer, List<Passenger>> pendingToTicket, double nowAbsSec) {
        if (pendingToTicket == null || pendingToTicket.isEmpty()) return;
        Point spawn = getSpawnAnchorFallback(); if (spawn == null) return;
        Map<String, List<TransitItem>> groups = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Passenger>> e : pendingToTicket.entrySet()) {
            int arriveAbs = e.getKey();
            for (Passenger p : safeList(e.getValue())) {
                if (p == null || !shouldRenderPassengerNow(p)) continue;
                int ticketIdx = passengerIndex(p,
                        new String[]{"getAssignedTicketCounterIndex", "getTicketCounterIndex", "getTargetTicketCounterIndex", "getChosenTicketCounterIndex"},
                        new String[]{"assignedTicketCounterIndex", "ticketCounterIndex", "targetTicketCounterIndex", "chosenTicketCounterIndex"},
                        -1);
                if (ticketIdx < 0) {
                    Integer tt = engineGetTargetTicketLineFor(p); if (tt != null) ticketIdx = tt;
                }
                if (ticketIdx < 0) ticketIdx = 0;
                Point b = bindings.getTicketAnchor(ticketIdx);
                String key = "S->T" + ticketIdx;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new TransitItem(p, spawn, b, arriveAbs, null));
            }
        }
        drawTransitGroups(g2, groups, false, nowAbsSec);
    }

    private void drawTransitToCheckpoint(Graphics2D g2, Map<Integer, List<Passenger>> pendingToCP, double nowAbsSec,
                                         Map<Passenger, Integer> ticketDoneOf) {
        if (pendingToCP == null || pendingToCP.isEmpty()) return;
        Map<String, List<TransitItem>> groups = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Passenger>> e : pendingToCP.entrySet()) {
            int arriveAbs = e.getKey();
            for (Passenger p : safeList(e.getValue())) {
                if (p == null || !shouldRenderPassengerNow(p)) continue;
                boolean online = !isInPerson(p);
                Point a; Double startAbs = null;
                int ticketIdx = -1;
                if (online) {
                    a = getSpawnAnchorFallback();
                } else {
                    ticketIdx = passengerIndex(p,
                            new String[]{"getAssignedTicketCounterIndex", "getTicketCounterIndex", "getTargetTicketCounterIndex", "getChosenTicketCounterIndex"},
                            new String[]{"assignedTicketCounterIndex", "ticketCounterIndex", "targetTicketCounterIndex", "chosenTicketCounterIndex"},
                            -1);
                    if (ticketIdx < 0) ticketIdx = ticketDoneOf.getOrDefault(p, 0);
                    a = bindings.getTicketAnchor(ticketIdx);
                    // Prefer per-passenger gate if this was released this interval
                    int cur = engineCurrentInterval();
                    long stepStart = stepStartAbsSeconds(cur);
                    int len = intervalSecondsAtStep(cur);
                    long stepEnd = stepStart + Math.max(1, len);
                    Integer doneAbs = engineGetTicketDoneAbsSecond(p);
                    if (doneAbs != null && doneAbs >= stepStart && doneAbs < stepEnd) {
                        Integer secGate = ticketReleaseSecond.get(p);
                        if (secGate != null) startAbs = (double) (stepStart + (secGate + 0.5));
                        if (startAbs == null) startAbs = ticketReleaseAbsSec.get(p);
                    }
                }
                int cpIdx = passengerIndex(p,
                        new String[]{"getAssignedCheckpointIndex", "getCheckpointIndex", "getTargetCheckpointIndex", "getChosenCheckpointIndex"},
                        new String[]{"assignedCheckpointIndex", "checkpointIndex", "targetCheckpointIndex", "chosenCheckpointIndex"},
                        -1);
                if (cpIdx < 0) { Integer tgt = engineGetTargetCheckpointLineFor(p); if (tgt != null) cpIdx = tgt; }
                Point b; int effectiveCpIdx;
                if (cpIdx >= 0) { effectiveCpIdx = cpIdx; b = bindings.getCheckpointAnchor(cpIdx); }
                else { effectiveCpIdx = getNearestCheckpointIndexForTicket(online ? -1 : ticketIdx, a); b = bindings.getCheckpointAnchor(effectiveCpIdx); }
                String key = (online ? "S" : ("T" + ticketIdx)) + "->C" + effectiveCpIdx;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new TransitItem(p, a, b, arriveAbs, startAbs));
            }
        }
        drawTransitGroups(g2, groups, false, nowAbsSec);
    }

    private void drawTransitToHoldroom(Graphics2D g2, Map<Integer, List<Passenger>> pendingToHold, double nowAbsSec,
                                       Map<Passenger, Integer> checkpointDoneOf) {
        if (pendingToHold == null || pendingToHold.isEmpty()) return;
        Map<String, List<TransitItem>> groups = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Passenger>> e : pendingToHold.entrySet()) {
            int arriveAbs = e.getKey();
            for (Passenger p : safeList(e.getValue())) {
                if (p == null || !shouldRenderPassengerNow(p)) continue;
                int cpIdx = passengerIndex(p,
                        new String[]{"getAssignedCheckpointIndex", "getCheckpointIndex", "getTargetCheckpointIndex", "getChosenCheckpointIndex"},
                        new String[]{"assignedCheckpointIndex", "checkpointIndex", "targetCheckpointIndex", "chosenCheckpointIndex"},
                        -1);
                if (cpIdx < 0) cpIdx = checkpointDoneOf.getOrDefault(p, 0);
                Point a = bindings.getCheckpointAnchor(cpIdx);
                int holdIdx = passengerIndex(p,
                        new String[]{"getAssignedHoldRoomIndex", "getAssignedHoldroomIndex", "getHoldRoomIndex", "getHoldroomIndex", "getTargetHoldRoomIndex"},
                        new String[]{"assignedHoldRoomIndex", "assignedHoldroomIndex", "holdRoomIndex", "holdroomIndex", "targetHoldRoomIndex"},
                        0);
                Point b = bindings.getHoldroomAnchor(holdIdx);
                Double startAbs = null;
                // Prefer per-passenger gate if this was released this interval
                int cur = engineCurrentInterval();
                long stepStart = stepStartAbsSeconds(cur);
                int len = intervalSecondsAtStep(cur);
                long stepEnd = stepStart + Math.max(1, len);
                Integer doneAbs = engineGetCheckpointDoneAbsSecond(p);
                if (doneAbs != null && doneAbs >= stepStart && doneAbs < stepEnd) {
                    Integer secGate = cpReleaseSecond.get(p);
                    if (secGate != null) startAbs = (double) (stepStart + (secGate + 0.5));
                    if (startAbs == null) startAbs = cpReleaseAbsSec.get(p);
                }
                String key = "C" + cpIdx + "->H" + holdIdx;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new TransitItem(p, a, b, arriveAbs, startAbs));
            }
        }
        drawTransitGroups(g2, groups, false, nowAbsSec);
    }

    private void drawTransitGroups(Graphics2D g2, Map<String, List<TransitItem>> groups, boolean useHoldFootprint, double nowAbsSec) {
        if (groups == null || groups.isEmpty()) return;
        final int rectW = useHoldFootprint ? holdWidPx : lineWidPx;
        final int rectH = useHoldFootprint ? holdLenPx : lineLenPx;
        final int spacingDist = Math.max(rectW, rectH) + Math.max(0, slotSpacingPx);

        for (List<TransitItem> items : groups.values()) {
            if (items == null || items.isEmpty()) continue;
            Point a = items.get(0).a; Point b = items.get(0).b; if (a == null || b == null) continue;
            Point aa = snapToWalkable(a); Point bb = snapToWalkable(b);
            List<Point> path = pathCache.path(aa, bb);
            double totalLenPx = (path != null && path.size() >= 2) ? PathCache.polylineLengthPixels(path) : aa.distance(bb);

            // Convert path length to time in SECONDS
            double sec = (totalLenPx * metersPerPixelExact()) / Math.max(0.20, getWalkSpeedMpsSafe());
            // Ensure visible span
            double minSec = 0.15 * Math.max(1, getIntervalSecondsSafe());
            sec = Math.max(minSec, sec);

            class D { final TransitItem item; final double rawDist; D(TransitItem it, double d){ item = it; rawDist = d;} }
            List<D> list = new ArrayList<>();
            for (TransitItem it : items) {
                double hardEnd = it.arriveAbsSec - 0.5; // arrive just before scheduled second
                double startAbs = (it.startAbsSec != null) ? it.startAbsSec : (it.arriveAbsSec - sec);
                double endAbs   = Math.min(startAbs + sec, hardEnd);
                endAbs = Math.max(startAbs + 1e-6, endAbs);
                double t01 = frac01(nowAbsSec, startAbs, endAbs);
                double d = Math.max(0.0, Math.min(1.0, t01)) * Math.max(1.0, totalLenPx);
                list.add(new D(it, d));
            }

            // sort front-to-back (largest distance first), stabilize
            list.sort((x, y) -> {
                int c = Double.compare(y.rawDist, x.rawDist); if (c != 0) return c;
                return Integer.compare(System.identityHashCode(x.item.passenger), System.identityHashCode(y.item.passenger));
            });

            double prev = Double.POSITIVE_INFINITY;
            for (D d : list) {
                double adj = d.rawDist;
                if (prev != Double.POSITIVE_INFINITY) adj = Math.min(adj, prev - spacingDist);
                prev = adj;
                Point pos = (path != null && path.size() >= 2) ? pointAlongDistanceExtended(path, adj)
                                                               : pointAlongDistanceExtendedLine(aa, bb, adj);
                drawPassengerWithFootprint(g2, d.item.passenger, pos, rectW, rectH);
            }
        }
    }

    private static Point pointAlongDistanceExtendedLine(Point a, Point b, double dist) {
        if (a == null && b == null) return null; if (a == null) return b; if (b == null) return a;
        double dx = b.x - a.x, dy = b.y - a.y; double len = Math.hypot(dx, dy); if (len < 1e-6) return new Point(a);
        double ux = dx / len, uy = dy / len; int x = (int) Math.round(a.x + ux * dist); int y = (int) Math.round(a.y + uy * dist);
        return new Point(x, y);
    }

    private static Point pointAlongDistanceExtended(List<Point> path, double dist) {
        if (path == null || path.size() < 2) return null;
        double total = 0.0; for (int i = 1; i < path.size(); i++) total += path.get(i - 1).distance(path.get(i));
        if (total < 1e-6) return new Point(path.get(0));
        if (dist < 0) { Point p0 = path.get(0), p1 = path.get(1); double dx = p1.x - p0.x, dy = p1.y - p0.y; double len = Math.hypot(dx, dy); if (len < 1e-6) return new Point(p0); double ux = dx/len, uy = dy/len; return new Point((int) Math.round(p0.x + ux * dist), (int) Math.round(p0.y + uy * dist)); }
        if (dist > total) { Point p0 = path.get(path.size()-2), p1 = path.get(path.size()-1); double dx = p1.x - p0.x, dy = p1.y - p0.y; double len = Math.hypot(dx, dy); if (len < 1e-6) return new Point(p1); double ux = dx/len, uy = dy/len; double extra = dist - total; return new Point((int) Math.round(p1.x + ux * extra), (int) Math.round(p1.y + uy * extra)); }
        double remaining = dist;
        for (int i = 1; i < path.size(); i++) {
            Point p0 = path.get(i - 1), p1 = path.get(i); double seg = p0.distance(p1);
            if (remaining <= seg) { double t = (seg < 1e-6) ? 0.0 : (remaining / seg); int x = (int) Math.round(p0.x + (p1.x - p0.x) * t); int y = (int) Math.round(p0.y + (p1.y - p0.y) * t); return new Point(x, y); }
            remaining -= seg;
        }
        return new Point(path.get(path.size() - 1));
    }

    private Point snapToWalkable(Point p) { if (p == null || bindings == null) return p; WalkMask m = bindings.getMask(); if (m == null) return p; Point s = PathCache.snapToNearestWalkable(m, p, Math.max(1, pathStridePx), 240); return (s != null) ? s : p; }

    private int getNearestCheckpointIndexForTicket(int ticketIdx, Point fromAnchor) {
        if (bindings == null) return 0; int n = safeInt(() -> bindings.checkpointCount(), 0); if (n <= 0) return 0;
        Integer cachedIdx = nearestCheckpointByTicket.get(ticketIdx); if (cachedIdx != null && cachedIdx >= 0 && cachedIdx < n) return cachedIdx;
        Point from = (fromAnchor != null) ? fromAnchor : bindings.getCheckpointAnchor(0); if (from == null) return 0;
        int bestI = 0; double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) { Point c = bindings.getCheckpointAnchor(i); if (c == null) continue; List<Point> path = pathCache.path(from, c); double score = (path != null && path.size() >= 2) ? PathCache.polylineLengthPixels(path) : from.distance(c); if (score < bestScore) { bestScore = score; bestI = i; } }
        nearestCheckpointByTicket.put(ticketIdx, bestI); return bestI;
    }

    private static double frac01(double now, double start, double end) {
        if (end <= start) return 1.0; double t = (now - start) / (end - start); if (t < 0) return 0.0; if (t > 1) return 1.0; return t;
    }

    private static int reserveUniqueSecond(int candidate, boolean[] used) {
        int n = used.length; if (n <= 0) return 0; int c = clampInt(candidate, 0, n - 1); if (!used[c]) { used[c] = true; return c; }
        for (int d = 1; d < n; d++) { int r = c + d; if (r < n && !used[r]) { used[r] = true; return r; } int l = c - d; if (l >= 0 && !used[l]) { used[l] = true; return l; } }
        return c; // worst case: tie
    }

    private static double lanePhaseFrac(int lane, int interval, int intervalSeconds) {
        if (intervalSeconds <= 0) return 0.0;
        long seed = 1469598103934665603L; seed ^= (long) lane * 1099511628211L; seed ^= (long) interval * 1402946736689701973L;
        seed ^= (seed >>> 33); seed *= 0xff51afd7ed558ccdL; seed ^= (seed >>> 33); seed *= 0xc4ceb9fe1a85ec53L; seed ^= (seed >>> 33);
        double u = ((seed >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
        double maxShift = 0.5 / Math.max(1, intervalSeconds); // ±0.5 sec worth
        return (u - 0.5) * 2.0 * maxShift;
    }

    // ----------------- Zone rendering -----------------

    private void drawZones(Graphics2D g2) {
        if (project == null) return; java.util.List<sim.floorplan.model.Zone> zones = project.getZones(); if (zones == null) return;
        g2.setStroke(new BasicStroke(2f));
        for (sim.floorplan.model.Zone z : zones) {
            if (z == null || z.getType() == null) continue;
            if (z.getType().hasArea() && z.getArea() != null && z.getArea().npoints >= 3) { g2.setColor(new Color(0, 0, 0, 90)); g2.drawPolygon(z.getArea()); }
            if (z.getType().hasAnchor() && z.getAnchor() != null) { Point a = z.getAnchor(); g2.setColor(new Color(0, 0, 0, 180)); g2.fillOval(a.x - 4, a.y - 4, 8, 8); }
        }
    }

    private Point getSpawnAnchorFallback() {
        try { Method m = bindings.getClass().getMethod("getSpawnAnchor"); Object out = m.invoke(bindings); if (out instanceof Point) return (Point) out; }
        catch (Throwable ignored) { }
        if (project != null && project.getZones() != null) {
            for (sim.floorplan.model.Zone z : project.getZones()) {
                if (z == null || z.getType() == null) continue;
                try { if ("SPAWN".equals(z.getType().name()) && z.getAnchor() != null) return z.getAnchor(); }
                catch (Throwable ignored) { }
            }
        }
        return null;
    }

    // ----------------- Pending map reflection helpers -----------------

    @SuppressWarnings("unchecked")
    private static Map<Integer, List<Passenger>> readPendingMap(SimulationEngine engine, String[] getterNames, String[] fieldNames) {
        if (engine == null) return Collections.emptyMap();
        if (getterNames != null) {
            for (String getterName : getterNames) {
                try { Method m = engine.getClass().getMethod(getterName); Object out = m.invoke(engine); if (out instanceof Map) return (Map<Integer, List<Passenger>>) out; }
                catch (Throwable ignored) { }
            }
        }
        if (fieldNames != null) {
            for (String fieldName : fieldNames) {
                try { Field f = engine.getClass().getDeclaredField(fieldName); f.setAccessible(true); Object out = f.get(engine); if (out instanceof Map) return (Map<Integer, List<Passenger>>) out; }
                catch (Throwable ignored) { }
            }
        }
        return Collections.emptyMap();
    }

    // ----------------- Queue + passenger drawing -----------------

    private void drawQueue(Graphics2D g2,
                           List<Passenger> passengers,
                           List<Point> slots,
                           Point fallbackAnchor,
                           Set<Passenger> skip,
                           boolean useHoldFootprint) {
        if (passengers == null) return;
        final int rectW = useHoldFootprint ? holdWidPx : lineWidPx;
        final int rectH = useHoldFootprint ? holdLenPx : lineLenPx;
        int i = 0;
        for (Passenger p : passengers) {
            if (p == null) continue; if (!shouldRenderPassengerNow(p)) continue; if (skip != null && skip.contains(p)) continue;
            Point pos = null;
            // If tween active, use tweened position; else use slot i
            Tween tween = queueTweens.get(p);
            if (tween != null && tween.from != null && tween.to != null) {
                double t = clamp01((nowAbsForRender - tween.startAbs) / Math.max(1e-6, (tween.endAbs - tween.startAbs)));
                t = easeCubicInOut(t);
                pos = lerpPoint(tween.from, tween.to, t);
            }
            if (pos == null) {
                if (slots != null && i < slots.size()) pos = slots.get(i);
                if (pos == null) pos = fallbackAnchor;
            }
            drawPassengerWithFootprint(g2, p, pos, rectW, rectH); i++;
        }
    }

    private void drawStagingAtAnchor(Graphics2D g2,
                                     List<Passenger> passengers,
                                     Point anchor,
                                     Set<Passenger> skip,
                                     boolean useHoldFootprint) {
        if (passengers == null || anchor == null) return;
        final int rectW = useHoldFootprint ? holdWidPx : lineWidPx;
        final int rectH = useHoldFootprint ? holdLenPx : lineLenPx;
        int spacingX = Math.max(2, rectW + Math.max(0, slotSpacingPx));
        int spacingY = Math.max(2, rectH + Math.max(0, slotSpacingPx));
        int cols = 6; int k = 0;
        for (Passenger p : passengers) {
            if (p == null) continue; if (!shouldRenderPassengerNow(p)) continue; if (skip != null && skip.contains(p)) continue;
            int row = k / cols; int col = k % cols; int ox = (col - cols / 2) * spacingX; int oy = (row + 1) * spacingY; Point pos = new Point(anchor.x + ox, anchor.y + oy);
            drawPassengerWithFootprint(g2, p, pos, rectW, rectH); k++;
        }
    }

    private void drawPassengerWithFootprint(Graphics2D g2, Passenger p, Point pos, int rectW, int rectH) {
        if (p == null || pos == null) return;
        if (showFootprints) {
            int x = pos.x - rectW / 2; int y = pos.y - rectH / 2; g2.setColor(new Color(0, 0, 0, 40)); g2.fillRect(x, y, rectW, rectH);
            g2.setColor(new Color(0, 0, 0, 90)); g2.setStroke(new BasicStroke(1.0f)); g2.drawRect(x, y, rectW, rectH);
        }
        int r = iconRadiusForFootprint(rectW, rectH); drawPassenger(g2, p, pos, r);
    }

    private static int iconRadiusForFootprint(int rectW, int rectH) {
        int m = Math.max(2, Math.min(rectW, rectH)); int r = (int) Math.floor(m * 0.35); return Math.max(2, r);
    }

    private void drawPassenger(Graphics2D g2, Passenger p, Point pos, int size) {
        if (p == null || pos == null) return;
        Flight f = safeFlight(p); Flight.ShapeType shape = (f == null) ? Flight.ShapeType.CIRCLE : f.getShape();
        boolean inPerson = isInPerson(p); boolean missed = isMissed(p);
        Color fill = inPerson ? new Color(30, 120, 255, 210) : new Color(30, 30, 30, 200); if (missed) fill = new Color(200, 0, 0, 220);
        g2.setColor(fill); drawShapeSafe(g2, shape, pos.x, pos.y, size);
        g2.setColor(new Color(255, 255, 255, 200)); g2.setStroke(new BasicStroke(1.2f)); drawShapeOutlineSafe(g2, shape, pos.x, pos.y, size);
    }

    // ----------------- Missed visibility policy -----------------

    private void rebuildFlightTimeCaches() {
        departureIntervalByFlight.clear(); boardingCloseIntervalByFlight.clear(); if (engine == null) return;
        List<Flight> flights = engineGetFlights(); if (flights == null || flights.isEmpty()) return;
        int arrivalSpanMin = engineInt(engine, 0, "getArrivalSpan", "getArrivalSpanMinutes", "arrivalSpanMinutes");
        LocalTime firstDep = flights.stream().filter(Objects::nonNull).map(Flight::getDepartureTime).filter(Objects::nonNull).min(LocalTime::compareTo).orElse(LocalTime.MIDNIGHT);
        paintStartTime = firstDep.minusMinutes(Math.max(0, arrivalSpanMin)); paintStartMinutesOfDay = timeToMinutesOfDay(paintStartTime);
        paintBoardingCloseOffsetMin = engineInt(engine, 20, "getBoardingCloseMinutes", "getBoardingCloseOffsetMinutes", "boardingCloseMinutes");
        for (Flight f : flights) {
            if (f == null || f.getDepartureTime() == null) continue;
            int depInterval = minutesFromStartTo(paintStartMinutesOfDay, f.getDepartureTime()); departureIntervalByFlight.put(f, depInterval);
            int closeInterval = Math.max(0, depInterval - Math.max(0, paintBoardingCloseOffsetMin)); boardingCloseIntervalByFlight.put(f, closeInterval);
        }
    }

    private boolean shouldRenderPassengerNow(Passenger p) {
        if (p == null) return false; if (!isMissed(p)) return true;
        Flight f = safeFlight(p); if (f == null) return true; Integer dep = departureIntervalByFlight.get(f); Integer close = boardingCloseIntervalByFlight.get(f); if (dep == null || close == null) return true;
        int holdEntry = passengerHoldEntryStep(p, -1); boolean reachedHold = holdEntry >= 0; int vanishAt = reachedHold ? dep : close; return paintCurStep < vanishAt;
    }

    private static int timeToMinutesOfDay(LocalTime t) { if (t == null) return 0; return t.getHour() * 60 + t.getMinute(); }
    private static int minutesFromStartTo(int startMinutesOfDay, LocalTime target) {
        int tgt = timeToMinutesOfDay(target); int d = tgt - startMinutesOfDay; if (d < 0) d += 24 * 60; return d;
    }

    // ----------------- Passenger reflection helpers -----------------

    private static Flight safeFlight(Passenger p) {
        if (p == null) return null; try { Method m = p.getClass().getMethod("getFlight"); Object out = m.invoke(p); if (out instanceof Flight) return (Flight) out; } catch (Throwable ignored) { } return null;
    }

    private static boolean isInPerson(Passenger p) {
        if (p == null) return false;
        try { Method m = p.getClass().getMethod("isInPerson"); Object out = m.invoke(p); if (out instanceof Boolean) return (Boolean) out; } catch (Throwable ignored) {}
        try { Method m = p.getClass().getMethod("getInPerson"); Object out = m.invoke(p); if (out instanceof Boolean) return (Boolean) out; } catch (Throwable ignored) {}
        Boolean f = tryFieldBool(p, "inPerson", "isInPerson"); return f != null && f;
    }

    private static boolean isMissed(Passenger p) {
        if (p == null) return false;
        try { Method m = p.getClass().getMethod("isMissed"); Object out = m.invoke(p); if (out instanceof Boolean) return (Boolean) out; } catch (Throwable ignored) {}
        try { Method m = p.getClass().getMethod("getMissed"); Object out = m.invoke(p); if (out instanceof Boolean) return (Boolean) out; } catch (Throwable ignored) {}
        Boolean f = tryFieldBool(p, "missed", "isMissed"); return f != null && f;
    }

    private static int passengerArrivalStep(Passenger p, int fallback) {
        Integer v = tryInvokeInt(p, "getArrivalMinute", "getArrivalInterval", "getArrivalStep", "getSpawnMinute", "getSpawnInterval"); if (v != null && v >= 0) return v;
        Integer f = tryFieldInt(p, "arrivalMinute", "arrivalInterval", "arrivalStep", "spawnMinute", "spawnInterval"); if (f != null && f >= 0) return f; return Math.max(0, fallback - 1);
    }

    private static int passengerHoldEntryStep(Passenger p, int fallback) {
        Integer v = tryInvokeInt(p, "getHoldRoomEntryMinute", "getHoldroomEntryMinute", "getHoldEntryMinute"); if (v != null && v >= 0) return v;
        Integer f = tryFieldInt(p, "holdRoomEntryMinute", "holdroomEntryMinute", "holdEntryMinute"); if (f != null && f >= 0) return f; return fallback;
    }

    private static int passengerIndex(Passenger p, String[] methodNames, String[] fieldNames, int fallback) {
        if (p == null) return fallback; if (methodNames != null) for (String n : methodNames) { Integer v = tryInvokeInt(p, n); if (v != null && v >= 0) return v; }
        if (fieldNames != null) for (String f : fieldNames) { Integer v = tryFieldInt(p, f); if (v != null && v >= 0) return v; } return fallback;
    }

    private static Integer tryInvokeInt(Object target, String... methodNames) {
        if (target == null || methodNames == null) return null;
        for (String name : methodNames) {
            try { Method m = target.getClass().getMethod(name); Class<?> rt = m.getReturnType(); if (rt == int.class || rt == Integer.class) { Object out = m.invoke(target); return (out == null) ? null : ((Number) out).intValue(); } }
            catch (Throwable ignored) { }
        }
        return null;
    }

    private static Integer tryFieldInt(Object target, String... fieldNames) {
        if (target == null || fieldNames == null) return null;
        for (String fn : fieldNames) {
            try { Field f = target.getClass().getDeclaredField(fn); f.setAccessible(true); Object out = f.get(target); if (out instanceof Number) return ((Number) out).intValue(); }
            catch (Throwable ignored) { }
        }
        return null;
    }

    private static Boolean tryFieldBool(Object target, String... fieldNames) {
        if (target == null || fieldNames == null) return null;
        for (String fn : fieldNames) {
            try { Field f = target.getClass().getDeclaredField(fn); f.setAccessible(true); Object out = f.get(target); if (out instanceof Boolean) return (Boolean) out; }
            catch (Throwable ignored) { }
        }
        return null;
    }

    private static int engineInt(SimulationEngine eng, int fallback, String... methodOrFieldNames) {
        if (eng == null || methodOrFieldNames == null) return fallback;
        for (String name : methodOrFieldNames) {
            if (name == null || name.trim().isEmpty()) continue;
            try { Method m = eng.getClass().getMethod(name); Object out = m.invoke(eng); if (out instanceof Number) return ((Number) out).intValue(); }
            catch (Throwable ignored) { }
            try { Field f = eng.getClass().getDeclaredField(name); f.setAccessible(true); Object out = f.get(eng); if (out instanceof Number) return ((Number) out).intValue(); }
            catch (Throwable ignored) { }
        }
        return fallback;
    }

    // ==== Engine reflection wrappers for ABS-SECOND getters ====

    private Integer engineGetTicketDoneAbsSecond(Passenger p) {
        if (engine == null || p == null) return null;
        try { Method m = engine.getClass().getMethod("getTicketDoneAbsSecond", Passenger.class); Object out = m.invoke(engine, p); if (out instanceof Number) return ((Number) out).intValue(); }
        catch (Throwable ignored) { }
        // Fallback: minute + optional second on Passenger
        Integer min = tryInvokeInt(p, "getTicketCompletionMinute", "getTicketCompleteMinute", "getTicketDoneMinute", "getTicketCompletionInterval");
        Integer sec = tryInvokeInt(p, "getTicketCompletionSecond", "getTicketDoneSecond");
        if (min != null && min >= 0) return min * 60 + Math.max(0, (sec == null ? 0 : sec));
        return null;
    }

    private Integer engineGetCheckpointDoneAbsSecond(Passenger p) {
        if (engine == null || p == null) return null;
        try { Method m = engine.getClass().getMethod("getCheckpointDoneAbsSecond", Passenger.class); Object out = m.invoke(engine, p); if (out instanceof Number) return ((Number) out).intValue(); }
        catch (Throwable ignored) { }
        Integer min = tryInvokeInt(p, "getCheckpointCompletionMinute", "getCheckpointCompleteMinute", "getCheckpointDoneMinute", "getCheckpointCompletionInterval");
        Integer sec = tryInvokeInt(p, "getCheckpointCompletionSecond", "getCheckpointDoneSecond");
        if (min != null && min >= 0) return min * 60 + Math.max(0, (sec == null ? 0 : sec));
        return null;
    }

    // ----------------- Shapes -----------------

    private void drawShapeSafe(Graphics2D g2, Flight.ShapeType s, int cx, int cy, int r) {
        String name = (s == null) ? "CIRCLE" : s.name();
        switch (name) {
            case "TRIANGLE": { Polygon p = new Polygon(new int[]{cx, cx - r, cx + r}, new int[]{cy - r, cy + r, cy + r}, 3); g2.fillPolygon(p); break; }
            case "SQUARE": g2.fillRect(cx - r, cy - r, 2 * r, 2 * r); break;
            case "PENTAGON": g2.fillPolygon(regularPolygon(cx, cy, r, 5)); break;
            case "HEXAGON": g2.fillPolygon(regularPolygon(cx, cy, r, 6)); break;
            case "OCTAGON": g2.fillPolygon(regularPolygon(cx, cy, r, 8)); break;
            default: g2.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        }
    }

    private void drawShapeOutlineSafe(Graphics2D g2, Flight.ShapeType s, int cx, int cy, int r) {
        String name = (s == null) ? "CIRCLE" : s.name();
        switch (name) {
            case "TRIANGLE": { Polygon p = new Polygon(new int[]{cx, cx - r, cx + r}, new int[]{cy - r, cy + r, cy + r}, 3); g2.drawPolygon(p); break; }
            case "SQUARE": g2.drawRect(cx - r, cy - r, 2 * r, 2 * r); break;
            case "PENTAGON": g2.drawPolygon(regularPolygon(cx, cy, r, 5)); break;
            case "HEXAGON": g2.drawPolygon(regularPolygon(cx, cy, r, 6)); break;
            case "OCTAGON": g2.drawPolygon(regularPolygon(cx, cy, r, 8)); break;
            default: g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
        }
    }

    private static Polygon regularPolygon(int cx, int cy, int r, int n) {
        int[] xs = new int[n]; int[] ys = new int[n];
        for (int i = 0; i < n; i++) { double a = -Math.PI / 2 + i * (2 * Math.PI / n); xs[i] = cx + (int) Math.round(Math.cos(a) * r); ys[i] = cy + (int) Math.round(Math.sin(a) * r); }
        return new Polygon(xs, ys, n);
    }

    // ==========================================================
    // Engine reflection wrappers
    // ==========================================================

    private int engineCurrentInterval() {
        if (engine == null) return 0;
        try { Method m = engine.getClass().getMethod("getCurrentInterval"); Object out = m.invoke(engine); if (out instanceof Number) return ((Number) out).intValue(); }
        catch (Throwable ignored) { }
        try { Method m = engine.getClass().getMethod("getCurrentStep"); Object out = m.invoke(engine); if (out instanceof Number) return ((Number) out).intValue(); }
        catch (Throwable ignored) { }
        return 0;
    }

    private void engineComputeNextInterval() {
        if (engine == null) return;
        try { Method m = engine.getClass().getMethod("computeNextInterval"); m.invoke(engine); return; } catch (Throwable ignored) { }
        try { Method m = engine.getClass().getMethod("nextInterval"); m.invoke(engine); } catch (Throwable ignored) { }
    }

    private boolean engineCanRewind() {
        if (engine == null) return false;
        try { Method m = engine.getClass().getMethod("canRewind"); Object out = m.invoke(engine); if (out instanceof Boolean) return (Boolean) out; } catch (Throwable ignored) { }
        return false;
    }

    private void engineRewindOneInterval() {
        if (engine == null) return;
        try { Method m = engine.getClass().getMethod("rewindOneInterval"); m.invoke(engine); return; } catch (Throwable ignored) { }
        try { Method m = engine.getClass().getMethod("rewindOneStep"); m.invoke(engine); } catch (Throwable ignored) { }
    }

    @SuppressWarnings("unchecked")
    private List<Flight> engineGetFlights() {
        if (engine == null) return Collections.emptyList();
        try { Method m = engine.getClass().getMethod("getFlights"); Object out = m.invoke(engine); if (out instanceof List) return (List<Flight>) out; } catch (Throwable ignored) { }
        try { Field f = engine.getClass().getDeclaredField("flights"); f.setAccessible(true); Object out = f.get(engine); if (out instanceof List) return (List<Flight>) out; } catch (Throwable ignored) { }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<LinkedList<Passenger>> engineGetTicketLines() {
        List<?> out = engineGetListVia(engine, new String[]{"getTicketLines", "getTicketCounterLines"}, new String[]{"ticketLines", "ticketCounterLines"});
        return castLines(out);
    }

    @SuppressWarnings("unchecked")
    private List<LinkedList<Passenger>> engineGetCheckpointLines() {
        List<?> out = engineGetListVia(engine, new String[]{"getCheckpointLines", "getCPQueueLines"}, new String[]{"checkpointLines", "cpLines"});
        return castLines(out);
    }

    @SuppressWarnings("unchecked")
    private List<LinkedList<Passenger>> engineGetHoldroomLines() {
        List<?> out = engineGetListVia(engine, new String[]{"getHoldRoomLines", "getHoldroomLines"}, new String[]{"holdRoomLines", "holdroomLines"});
        return castLines(out);
    }

    @SuppressWarnings("unchecked")
    private List<LinkedList<Passenger>> engineGetCompletedTicketLines() {
        List<?> out = engineGetListVia(engine, new String[]{"getCompletedTicketLines"}, new String[]{"completedTicketLines"});
        return castLines(out);
    }

    @SuppressWarnings("unchecked")
    private List<LinkedList<Passenger>> engineGetCompletedCheckpointLines() {
        List<?> out = engineGetListVia(engine, new String[]{"getCompletedCheckpointLines"}, new String[]{"completedCheckpointLines"});
        return castLines(out);
    }

    private static List<?> engineGetListVia(Object target, String[] getters, String[] fields) {
        if (target == null) return Collections.emptyList();
        if (getters != null) for (String g : getters) {
            try { Method m = target.getClass().getMethod(g); Object out = m.invoke(target); if (out instanceof List) return (List<?>) out; }
            catch (Throwable ignored) { }
        }
        if (fields != null) for (String fName : fields) {
            try { Field f = target.getClass().getDeclaredField(fName); f.setAccessible(true); Object out = f.get(target); if (out instanceof List) return (List<?>) out; }
            catch (Throwable ignored) { }
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<LinkedList<Passenger>> castLines(List<?> maybeLines) {
        if (maybeLines == null) return Collections.emptyList();
        try { return (List<LinkedList<Passenger>>) maybeLines; } catch (Throwable ignored) { return Collections.emptyList(); }
    }

    private List<Passenger> engineGetVisibleCompletedTicketLine(int lane, List<LinkedList<Passenger>> completedTicketLinesFallback) {
        if (engine != null) {
            try { Method m = engine.getClass().getMethod("getVisibleCompletedTicketLine", int.class); Object out = m.invoke(engine, lane); if (out instanceof List) {
                @SuppressWarnings("unchecked") List<Passenger> list = (List<Passenger>) out; return list; } }
            catch (Throwable ignored) { }
        }
        if (completedTicketLinesFallback != null && lane >= 0 && lane < completedTicketLinesFallback.size()) return completedTicketLinesFallback.get(lane);
        return Collections.emptyList();
    }

    private Integer engineGetTargetTicketLineFor(Passenger p) {
        if (engine == null || p == null) return null;
        try { Method m = engine.getClass().getMethod("getTargetTicketLineFor", Passenger.class); Object out = m.invoke(engine, p); if (out instanceof Integer) return (Integer) out; if (out instanceof Number) return ((Number) out).intValue(); }
        catch (Throwable ignored) { }
        return null;
    }

    private Integer engineGetTargetCheckpointLineFor(Passenger p) {
        if (engine == null || p == null) return null;
        try { Method m = engine.getClass().getMethod("getTargetCheckpointLineFor", Passenger.class); Object out = m.invoke(engine, p); if (out instanceof Integer) return (Integer) out; if (out instanceof Number) return ((Number) out).intValue(); }
        catch (Throwable ignored) { }
        return null;
    }

    // ==========================================================
    // Utilities
    // ==========================================================

    private static <T> List<T> safeList(List<T> list) { return (list == null) ? Collections.emptyList() : list; }

    private static Set<Passenger> collectAll(Map<Integer, List<Passenger>> m) {
        Set<Passenger> s = new HashSet<>(); if (m == null) return s; for (List<Passenger> list : m.values()) { if (list == null) continue; for (Passenger p : list) if (p != null) s.add(p); } return s;
    }

    private static Map<Passenger, Integer> indexOf(List<LinkedList<Passenger>> lines) {
        Map<Passenger, Integer> map = new HashMap<>(); if (lines == null) return map; for (int i = 0; i < lines.size(); i++) { List<Passenger> lane = lines.get(i); if (lane == null) continue; for (Passenger p : lane) { if (p != null && !map.containsKey(p)) map.put(p, i); } } return map;
    }

    private interface IntSupplierWithThrow { int get() throws Exception; }
    private static int safeInt(IntSupplierWithThrow s, int fallback) { try { return s.get(); } catch (Throwable t) { return fallback; } }

    // ===== Absolute-time helpers =====

    private void ensureCurrentStepMapping() {
        int ci = engineCurrentInterval(); int intervalSeconds = getIntervalSecondsSafe(); long start = Math.max(0L, absoluteSeconds - secondsIntoInterval);
        stepStartAbsSec.put(ci, start); stepIntervalSec.put(ci, intervalSeconds);
        pruneStepMaps();
    }

    private void pruneStepMaps() {
        long cutoffAbs = Math.max(0L, absoluteSeconds - STEP_RETENTION_SEC);
        int cur = engineCurrentInterval();
        stepStartAbsSec.entrySet().removeIf(e -> e.getKey() != cur && e.getValue() < cutoffAbs);
        stepIntervalSec.keySet().removeIf(step -> !stepStartAbsSec.containsKey(step));
    }

    private int intervalSecondsAtStep(int step) {
        Integer v = stepIntervalSec.get(step); return (v != null && v > 0) ? v : getIntervalSecondsSafe();
    }

    private long stepStartAbsSeconds(int step) {
        Long v = stepStartAbsSec.get(step); if (v != null) return v; if (step == engineCurrentInterval()) return Math.max(0L, absoluteSeconds - secondsIntoInterval);
        return (long) step * Math.max(1, getIntervalSecondsSafe()); // conservative fallback
    }
}
