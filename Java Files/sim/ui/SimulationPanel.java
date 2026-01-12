package sim.ui;

import sim.model.Passenger;
import sim.model.Flight.ShapeType;
import sim.service.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class SimulationPanel extends JPanel {

    // Keep your “3 rows, 15 cols” requirement
    private static final int ROWS = 3;
    private static final int COLS = 15;

    private static final int MARGIN_X = 18;
    private static final int MARGIN_Y = 50;
    private static final int STATION_GAP_PX = 80;

    // Cell size clamps (dynamic scaling will pick within these)
    private static final int MIN_CELL = 6;
    private static final int MAX_CELL = 20;

    private final SimulationEngine engine;

    // scroll offsets (in “columns”)
    private final int[] ticketQueuedOffsets;
    private final int[] ticketServedOffsets;
    private final int[] checkpointQueuedOffsets;
    private final int[] checkpointServedOffsets;

    // click-to-inspect support
    private final List<Rectangle> clickableAreas = new ArrayList<>();
    private final List<Passenger> clickablePassengers = new ArrayList<>();

    // Drag state
    private boolean dragging = false;
    private boolean draggingQueued;   // true=queued knob, false=served knob
    private boolean draggingTicket;   // true=ticket line, false=checkpoint line
    private int dragLine = -1;
    private int initialMouseX;
    private int initialOffset;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public SimulationPanel(SimulationEngine engine) {
        this.engine = engine;
        setFocusable(true);

        this.ticketQueuedOffsets     = new int[engine.getTicketLines().size()];
        this.ticketServedOffsets     = new int[engine.getTicketLines().size()];
        this.checkpointQueuedOffsets = new int[engine.getCheckpointLines().size()];
        this.checkpointServedOffsets = new int[engine.getCheckpointLines().size()];

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { handlePress(e); }
            @Override public void mouseReleased(MouseEvent e) { dragging = false; }
            @Override public void mouseDragged(MouseEvent e)  { handleDrag(e); }
            @Override public void mouseClicked(MouseEvent e)  { handleClick(e); }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    // ==========================================================
    // Layout helper
    // ==========================================================

    private static final class LayoutInfo {
        final int w, h;
        final int cellW;
        final int boxSize;
        final int gridW;
        final int gridH;
        final int trackH;
        final int top, bottom;
        final int leftCenterX, rightCenterX;

        LayoutInfo(int w, int h, int cellW, int boxSize, int gridW, int gridH, int trackH,
                   int top, int bottom, int leftCenterX, int rightCenterX) {
            this.w = w;
            this.h = h;
            this.cellW = cellW;
            this.boxSize = boxSize;
            this.gridW = gridW;
            this.gridH = gridH;
            this.trackH = trackH;
            this.top = top;
            this.bottom = bottom;
            this.leftCenterX = leftCenterX;
            this.rightCenterX = rightCenterX;
        }
    }

    /**
     * IMPORTANT:
     * Do NOT name this method `layout()` because java.awt.Container already has `layout() : void`
     * and Java cannot overload only by return type. That’s why your IDE underlined "Layout".
     */
    private LayoutInfo calcLayout() {
        int w = getWidth();
        int h = getHeight();

        // one station = queued grid + box + served grid = (2*COLS + ROWS) cells wide
        int stationCells = (2 * COLS + ROWS);
        int totalCells = Math.max(1, stationCells * 2); // two stations

        int availablePx = w - 2 * MARGIN_X - STATION_GAP_PX;
        availablePx = Math.max(1, availablePx);

        int cellW = availablePx / totalCells;
        cellW = clamp(cellW, MIN_CELL, MAX_CELL);

        int boxSize = ROWS * cellW;
        int gridW = COLS * cellW;
        int gridH = ROWS * cellW;
        int trackH = Math.max(4, cellW / 2);

        int stationW = stationCells * cellW;

        int leftCenterX  = MARGIN_X + stationW / 2;
        int rightCenterX = w - MARGIN_X - stationW / 2;

        int top = MARGIN_Y;
        int bottom = Math.max(top + boxSize + 40, h - MARGIN_Y);

        return new LayoutInfo(w, h, cellW, boxSize, gridW, gridH, trackH, top, bottom, leftCenterX, rightCenterX);
    }

    // ==========================================================
    // Mouse / Drag
    // ==========================================================

    private void handlePress(MouseEvent e) {
        LayoutInfo L = calcLayout();
        int mx = e.getX(), my = e.getY();

        clampOffsetsToCurrentSizes();

        // Ticket scrollbar knobs
        int ticketLinesCount = engine.getTicketLines().size();
        int spaceT = (ticketLinesCount > 1) ? (L.bottom - L.top) / (ticketLinesCount - 1) : 0;

        for (int i = 0; i < ticketLinesCount; i++) {
            int centerY = (spaceT > 0) ? (L.top + i * spaceT) : (L.h / 2);

            List<Passenger> q = engine.getTicketLines().get(i);
            if (hitKnob(mx, my, L, L.leftCenterX, centerY, true, q.size(), ticketQueuedOffsets[i])) {
                dragging = true;
                draggingQueued = true;
                draggingTicket = true;
                dragLine = i;
                initialMouseX = mx;
                initialOffset = ticketQueuedOffsets[i];
                return;
            }

            List<Passenger> s = engine.getVisibleCompletedTicketLine(i);
            if (hitKnob(mx, my, L, L.leftCenterX, centerY, false, s.size(), ticketServedOffsets[i])) {
                dragging = true;
                draggingQueued = false;
                draggingTicket = true;
                dragLine = i;
                initialMouseX = mx;
                initialOffset = ticketServedOffsets[i];
                return;
            }
        }

        // Checkpoint scrollbar knobs
        int cpLinesCount = engine.getCheckpointLines().size();
        int spaceC = (cpLinesCount > 1) ? (L.bottom - L.top) / (cpLinesCount - 1) : 0;

        for (int i = 0; i < cpLinesCount; i++) {
            int centerY = (spaceC > 0) ? (L.top + i * spaceC) : (L.h / 2);

            List<Passenger> q = engine.getCheckpointLines().get(i);
            if (hitKnob(mx, my, L, L.rightCenterX, centerY, true, q.size(), checkpointQueuedOffsets[i])) {
                dragging = true;
                draggingQueued = true;
                draggingTicket = false;
                dragLine = i;
                initialMouseX = mx;
                initialOffset = checkpointQueuedOffsets[i];
                return;
            }

            List<Passenger> s = engine.getCompletedCheckpointLines().get(i);
            if (hitKnob(mx, my, L, L.rightCenterX, centerY, false, s.size(), checkpointServedOffsets[i])) {
                dragging = true;
                draggingQueued = false;
                draggingTicket = false;
                dragLine = i;
                initialMouseX = mx;
                initialOffset = checkpointServedOffsets[i];
                return;
            }
        }
    }

    private void handleDrag(MouseEvent e) {
        if (!dragging) return;

        LayoutInfo L = calcLayout();
        int dx = e.getX() - initialMouseX;
        int deltaCols = dx / Math.max(1, L.cellW);

        int line = dragLine;
        if (line < 0) return;

        if (draggingTicket) {
            if (draggingQueued) {
                int fullCols = fullCols(engine.getTicketLines().get(line).size());
                ticketQueuedOffsets[line] = clamp(initialOffset + deltaCols, 0, Math.max(0, fullCols - COLS));
            } else {
                int fullCols = fullCols(engine.getVisibleCompletedTicketLine(line).size());
                ticketServedOffsets[line] = clamp(initialOffset + deltaCols, 0, Math.max(0, fullCols - COLS));
            }
        } else {
            if (draggingQueued) {
                int fullCols = fullCols(engine.getCheckpointLines().get(line).size());
                checkpointQueuedOffsets[line] = clamp(initialOffset + deltaCols, 0, Math.max(0, fullCols - COLS));
            } else {
                int fullCols = fullCols(engine.getCompletedCheckpointLines().get(line).size());
                checkpointServedOffsets[line] = clamp(initialOffset + deltaCols, 0, Math.max(0, fullCols - COLS));
            }
        }

        repaint();
    }

    private void handleClick(MouseEvent e) {
        Point pt = e.getPoint();
        for (int i = 0; i < clickableAreas.size(); i++) {
            if (clickableAreas.get(i).contains(pt)) {
                Passenger p = clickablePassengers.get(i);
                if (p == null || p.getFlight() == null) return;

                LocalTime arrivalTime = null;

                LocalTime gs = tryGetGlobalStart(engine);
                if (gs != null && p.getArrivalMinute() >= 0) {
                    arrivalTime = gs.plusMinutes(p.getArrivalMinute());
                }

                if (arrivalTime == null) {
                    LocalTime start = p.getFlight().getDepartureTime().minusMinutes(engine.getArrivalSpan());
                    arrivalTime = start.plusMinutes(p.getArrivalMinute());
                }

                String msg = "Flight: " + p.getFlight().getFlightNumber()
                        + "\nArrived: " + arrivalTime.format(TIME_FMT);

                JOptionPane.showMessageDialog(this, msg, "Passenger Info", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }
    }

    private static LocalTime tryGetGlobalStart(SimulationEngine engine) {
        if (engine == null) return null;

        try {
            Method m = engine.getClass().getMethod("getGlobalStartTime");
            Object out = m.invoke(engine);
            if (out instanceof LocalTime) return (LocalTime) out;
        } catch (Throwable ignored) {}

        try {
            Field f = engine.getClass().getDeclaredField("globalStart");
            f.setAccessible(true);
            Object out = f.get(engine);
            if (out instanceof LocalTime) return (LocalTime) out;
        } catch (Throwable ignored) {}

        return null;
    }

    // ==========================================================
    // Paint
    // ==========================================================

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        clickableAreas.clear();
        clickablePassengers.clear();

        LayoutInfo L = calcLayout();
        clampOffsetsToCurrentSizes();

        // Ticket lines
        int tLines = engine.getTicketLines().size();
        int spaceT = (tLines > 1) ? (L.bottom - L.top) / (tLines - 1) : 0;

        for (int i = 0; i < tLines; i++) {
            int centerY = (spaceT > 0) ? (L.top + i * spaceT) : (L.h / 2);
            drawStationLine(g, L, true, i, L.leftCenterX, centerY);
        }

        // Checkpoint lines
        int cLines = engine.getCheckpointLines().size();
        int spaceC = (cLines > 1) ? (L.bottom - L.top) / (cLines - 1) : 0;

        for (int i = 0; i < cLines; i++) {
            int centerY = (spaceC > 0) ? (L.top + i * spaceC) : (L.h / 2);
            drawStationLine(g, L, false, i, L.rightCenterX, centerY);
        }
    }

    private void drawStationLine(Graphics g, LayoutInfo L, boolean isTicket, int lineIdx, int centerX, int centerY) {
        int boxX = centerX - L.boxSize / 2;
        int boxY = centerY - L.boxSize / 2;

        g.setColor(Color.BLACK);
        g.drawRect(boxX, boxY, L.boxSize, L.boxSize);

        int gridTopY = boxY + (L.boxSize - L.gridH) / 2;

        // queued (left of box)
        List<Passenger> queued = isTicket
                ? engine.getTicketLines().get(lineIdx)
                : engine.getCheckpointLines().get(lineIdx);

        g.setColor(Color.YELLOW);
        int startXQueued = boxX - L.cellW;
        drawGridWindow(g, queued, startXQueued, gridTopY, L.cellW, L.cellW, ROWS,
                isTicket ? ticketQueuedOffsets[lineIdx] : checkpointQueuedOffsets[lineIdx]);

        drawScrollbarIfNeeded(g, L, boxX, boxY, true, queued.size(),
                isTicket ? ticketQueuedOffsets[lineIdx] : checkpointQueuedOffsets[lineIdx]);

        // served (right of box)
        List<Passenger> served = isTicket
                ? engine.getVisibleCompletedTicketLine(lineIdx)
                : engine.getCompletedCheckpointLines().get(lineIdx);

        g.setColor(Color.GREEN);
        int startXServed = boxX + L.boxSize + (COLS - 1) * L.cellW;
        drawGridWindow(g, served, startXServed, gridTopY, L.cellW, L.cellW, ROWS,
                isTicket ? ticketServedOffsets[lineIdx] : checkpointServedOffsets[lineIdx]);

        drawScrollbarIfNeeded(g, L, boxX, boxY, false, served.size(),
                isTicket ? ticketServedOffsets[lineIdx] : checkpointServedOffsets[lineIdx]);
    }

    private void drawScrollbarIfNeeded(Graphics g, LayoutInfo L, int boxX, int boxY, boolean queuedSide, int listSize, int offset) {
        int fullCols = fullCols(listSize);
        if (fullCols <= COLS) return;

        int trackW = COLS * L.cellW;
        int trackY = boxY + (L.boxSize - L.gridH) / 2 + L.gridH + 2;

        int trackX;
        if (queuedSide) {
            int startXQueued = boxX - L.cellW;
            trackX = startXQueued - (COLS - 1) * L.cellW;
        } else {
            int startXServed = boxX + L.boxSize + (COLS - 1) * L.cellW;
            trackX = startXServed - (COLS - 1) * L.cellW;
        }

        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(trackX, trackY, trackW, L.trackH);

        int knobW = (int) Math.max(12, Math.round((COLS / (double) fullCols) * trackW));
        int denom = Math.max(1, fullCols - COLS);
        int knobX = trackX + (int) Math.round((offset / (double) denom) * (trackW - knobW));

        g.setColor(Color.DARK_GRAY);
        g.fillRect(knobX, trackY, knobW, L.trackH);
    }

    private boolean hitKnob(int mx, int my, LayoutInfo L, int centerX, int centerY,
                            boolean queuedSide, int listSize, int offset) {

        int fullCols = fullCols(listSize);
        if (fullCols <= COLS) return false;

        int boxX = centerX - L.boxSize / 2;
        int boxY = centerY - L.boxSize / 2;

        int trackW = COLS * L.cellW;
        int trackY = boxY + (L.boxSize - L.gridH) / 2 + L.gridH + 2;

        int trackX;
        if (queuedSide) {
            int startXQueued = boxX - L.cellW;
            trackX = startXQueued - (COLS - 1) * L.cellW;
        } else {
            int startXServed = boxX + L.boxSize + (COLS - 1) * L.cellW;
            trackX = startXServed - (COLS - 1) * L.cellW;
        }

        int knobW = (int) Math.max(12, Math.round((COLS / (double) fullCols) * trackW));
        int denom = Math.max(1, fullCols - COLS);
        int knobX = trackX + (int) Math.round((offset / (double) denom) * (trackW - knobW));

        Rectangle knob = new Rectangle(knobX, trackY, knobW, L.trackH);
        return knob.contains(mx, my);
    }

    private void drawGridWindow(Graphics g,
                                List<Passenger> list,
                                int startX,
                                int startY,
                                int cellW,
                                int cellH,
                                int rows,
                                int offsetCols) {
        if (list == null) return;

        int size = list.size();
        int fullCols = fullCols(size);
        int maxOffset = Math.max(0, fullCols - COLS);
        int offset = clamp(offsetCols, 0, maxOffset);

        for (int idx = 0; idx < size; idx++) {
            Passenger p = list.get(idx);
            if (p == null || p.getFlight() == null) continue;

            int row = idx % rows;
            int col = idx / rows;

            int rel = col - offset;
            if (rel < 0 || rel >= COLS) continue;

            int x = startX - rel * cellW;
            int y = startY + row * cellH;

            drawShape(g, p.getFlight().getShape(), x, y, cellW, cellH);
            recordClickable(x, y, cellW, cellH, p);
        }
    }

    private void drawShape(Graphics g, ShapeType type, int x, int y, int w, int h) {
        ShapePainter.paintShape(g, type, x, y, w, h, Color.BLACK);
    }

    private void recordClickable(int x, int y, int w, int h, Passenger p) {
        if (p == null) return;
        clickableAreas.add(new Rectangle(x, y, w, h));
        clickablePassengers.add(p);
    }

    // ==========================================================
    // Offset clamping + helpers
    // ==========================================================

    private void clampOffsetsToCurrentSizes() {
        for (int i = 0; i < ticketQueuedOffsets.length; i++) {
            int fullCols = fullCols(engine.getTicketLines().get(i).size());
            ticketQueuedOffsets[i] = clamp(ticketQueuedOffsets[i], 0, Math.max(0, fullCols - COLS));
        }
        for (int i = 0; i < ticketServedOffsets.length; i++) {
            int fullCols = fullCols(engine.getVisibleCompletedTicketLine(i).size());
            ticketServedOffsets[i] = clamp(ticketServedOffsets[i], 0, Math.max(0, fullCols - COLS));
        }
        for (int i = 0; i < checkpointQueuedOffsets.length; i++) {
            int fullCols = fullCols(engine.getCheckpointLines().get(i).size());
            checkpointQueuedOffsets[i] = clamp(checkpointQueuedOffsets[i], 0, Math.max(0, fullCols - COLS));
        }
        for (int i = 0; i < checkpointServedOffsets.length; i++) {
            int fullCols = fullCols(engine.getCompletedCheckpointLines().get(i).size());
            checkpointServedOffsets[i] = clamp(checkpointServedOffsets[i], 0, Math.max(0, fullCols - COLS));
        }
    }

    private int fullCols(int size) {
        if (size <= 0) return 0;
        return (size + ROWS - 1) / ROWS;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}