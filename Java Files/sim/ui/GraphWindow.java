package sim.ui;

import sim.service.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/**
 * GraphWindow
 *
 * New behavior:
 *  - When constructed with SimulationEngine, shows all in-app graph panels in tabs.
 *  - When constructed with (title, heldUpData), shows a simple legacy bar chart (no JFreeChart).
 *
 * This avoids graphs taking space inside SimulationFrame.
 */
public class GraphWindow extends JFrame {

    // Engine-based graphs mode
    private final SimulationEngine engine;
    private ArrivalsGraphPanel arrivalsGraphPanel;
    private QueueTotalsGraphPanel queueTotalsGraphPanel;
    private HoldRoomPopulationGraphPanel holdRoomPopulationGraphPanel;
    private ArrivalCurveUsedPanel arrivalCurveUsedPanel;

    private int viewedInterval = 0;

    // Legacy mode
    private final Map<Integer, Integer> legacyHeldUps;

    // -----------------------------
    // NEW: Engine graphs window
    // -----------------------------
    public GraphWindow(SimulationEngine engine) {
        super("Graphs");
        this.engine = engine;
        this.legacyHeldUps = null;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JTabbedPane tabs = new JTabbedPane();

        arrivalsGraphPanel = new ArrivalsGraphPanel(engine);
        JPanel arrivalsTab = new JPanel(new BorderLayout());
        arrivalsTab.add(arrivalsGraphPanel, BorderLayout.CENTER);
        tabs.addTab("Arrivals", arrivalsTab);

        queueTotalsGraphPanel = new QueueTotalsGraphPanel(engine);
        JPanel queueTotalsTab = new JPanel(new BorderLayout());
        queueTotalsTab.add(queueTotalsGraphPanel, BorderLayout.CENTER);
        tabs.addTab("Queue Totals", queueTotalsTab);

        holdRoomPopulationGraphPanel = new HoldRoomPopulationGraphPanel(engine);
        JPanel holdRoomsTab = new JPanel(new BorderLayout());
        holdRoomsTab.add(holdRoomPopulationGraphPanel, BorderLayout.CENTER);
        tabs.addTab("Hold Rooms", holdRoomsTab);

        arrivalCurveUsedPanel = new ArrivalCurveUsedPanel(engine);
        JPanel curveTab = new JPanel(new BorderLayout());
        curveTab.add(arrivalCurveUsedPanel, BorderLayout.CENTER);
        tabs.addTab("Curve (Used)", curveTab);

        add(tabs, BorderLayout.CENTER);

        setSize(980, 620);
        setLocationRelativeTo(null);

        // initialize state (clamp to computed history if needed)
        int cur = (engine != null) ? Math.max(0, engine.getCurrentInterval()) : 0;
        int max = (engine != null) ? Math.max(0, engine.getMaxComputedInterval()) : 0;
        setViewedInterval(Math.min(cur, max));
        updateFromEngine();
    }

    // -----------------------------
    // LEGACY: kept for compatibility
    // -----------------------------
    public GraphWindow(String title, Map<Integer, Integer> heldUpData) {
        super(title);
        this.engine = null;
        this.legacyHeldUps = heldUpData;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        add(new LegacyBarChartPanel(heldUpData), BorderLayout.CENTER);

        setSize(800, 500);
        setLocationRelativeTo(null);
    }

    // -----------------------------
    // Called by SimulationFrame
    // -----------------------------
    public void setViewedInterval(int interval) {
        this.viewedInterval = Math.max(0, interval);

        if (arrivalsGraphPanel != null) arrivalsGraphPanel.setViewedInterval(this.viewedInterval);
        if (queueTotalsGraphPanel != null) queueTotalsGraphPanel.setCurrentInterval(this.viewedInterval);
        if (holdRoomPopulationGraphPanel != null) holdRoomPopulationGraphPanel.setViewedInterval(this.viewedInterval);
        if (arrivalCurveUsedPanel != null) arrivalCurveUsedPanel.setViewedInterval(this.viewedInterval);

        setTitle("Graphs — Interval " + this.viewedInterval);
        repaint();
    }

    public void updateFromEngine() {
        if (engine == null) return;

        int maxComputed = Math.max(0, engine.getMaxComputedInterval());
        int total = Math.max(0, engine.getTotalIntervals());
        int current = Math.max(0, engine.getCurrentInterval());

        // Clamp viewed interval within currently computed range
        if (viewedInterval > maxComputed) {
            setViewedInterval(maxComputed);
        }

        // Arrivals
        if (arrivalsGraphPanel != null) {
            arrivalsGraphPanel.setViewedInterval(viewedInterval);
            arrivalsGraphPanel.syncWithEngine();
        }

        // Curve used
        if (arrivalCurveUsedPanel != null) {
            arrivalCurveUsedPanel.setViewedInterval(viewedInterval);
            arrivalCurveUsedPanel.syncWithEngine();
        }

        // Queue totals (uses optional served arrays via engine accessors inside the panel)
        if (queueTotalsGraphPanel != null) {
            queueTotalsGraphPanel.setMaxComputedInterval(maxComputed);
            queueTotalsGraphPanel.setTotalIntervals(total);
            queueTotalsGraphPanel.setCurrentInterval(viewedInterval);
        }

        // Hold room populations
        if (holdRoomPopulationGraphPanel != null) {
            holdRoomPopulationGraphPanel.setMaxComputedInterval(maxComputed);
            holdRoomPopulationGraphPanel.setTotalIntervals(total);
            holdRoomPopulationGraphPanel.setCurrentInterval(current);
            holdRoomPopulationGraphPanel.setViewedInterval(viewedInterval);
            holdRoomPopulationGraphPanel.syncWithEngine();
        }
    }

    // -----------------------------
    // Tiny “no-deps” bar chart
    // -----------------------------
    private static final class LegacyBarChartPanel extends JPanel {
        private final Map<Integer, Integer> data;

        LegacyBarChartPanel(Map<Integer, Integer> data) {
            this.data = data;
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (data == null || data.isEmpty()) {
                g.setColor(Color.GRAY);
                g.drawString("No data.", 20, 20);
                return;
            }

            int w = getWidth();
            int h = getHeight();

            int padL = 50, padR = 20, padT = 30, padB = 50;
            int chartW = Math.max(1, w - padL - padR);
            int chartH = Math.max(1, h - padT - padB);

            int maxV = 1;
            for (int v : data.values()) maxV = Math.max(maxV, v);

            int n = data.size();
            int barW = Math.max(1, chartW / n);

            // axes
            g.setColor(Color.BLACK);
            g.drawLine(padL, padT, padL, padT + chartH);
            g.drawLine(padL, padT + chartH, padL + chartW, padT + chartH);

            int i = 0;
            for (Map.Entry<Integer, Integer> e : data.entrySet()) {
                int interval = e.getKey();
                int v = e.getValue();

                int bh = (int) Math.round((v / (double) maxV) * (chartH - 5));
                int x = padL + i * barW + 2;
                int y = padT + chartH - bh;

                g.setColor(new Color(120, 160, 220));
                g.fillRect(x, y, Math.max(1, barW - 4), bh);

                g.setColor(Color.DARK_GRAY);
                if (n <= 30 || (i % 5 == 0)) {
                    g.drawString(String.valueOf(interval), x, padT + chartH + 15);
                }

                i++;
            }

            g.setColor(Color.BLACK);
            g.drawString("Held Ups by Interval", padL, 18);
        }
    }
}
