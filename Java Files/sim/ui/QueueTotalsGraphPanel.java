package sim.ui;


import sim.service.SimulationEngine;


import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;


import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;


/**
* Live-updating 3-line graph:
*  - Total passengers waiting in ALL ticket counter lines
*  - Total passengers waiting in ALL checkpoint lines
*  - Total passengers in ALL hold rooms
*
* Designed to be updated from SimulationFrame via any of these calls:
*  setCurrentInterval(i), setInterval(i), setDisplayedInterval(i), goToInterval(i),
*  onIntervalChanged(i), updateForInterval(i)
*  plus optional setMaxComputedInterval(max) / setTotalIntervals(total) / refresh().
*/
public class QueueTotalsGraphPanel extends JPanel {


   private final SimulationEngine engine;


   private final XYSeries ticketSeries =
           new XYSeries("Ticket Counter Lines", /*autoSort*/ true, /*allowDuplicateX*/ false);
   private final XYSeries checkpointSeries =
           new XYSeries("Checkpoint Lines", /*autoSort*/ true, /*allowDuplicateX*/ false);
   private final XYSeries holdRoomSeries =
           new XYSeries("Hold Rooms", /*autoSort*/ true, /*allowDuplicateX*/ false);


   private final XYSeriesCollection dataset = new XYSeriesCollection();


   private JFreeChart chart;
   private XYPlot plot;
   private ValueMarker currentMarker;


   private int currentInterval = 0;
   private int maxComputedInterval = 0;
   private int totalIntervals = 0;


   private int lastBuiltUpTo = -1;


   // -------- Constructors (multiple signatures for reflection compatibility) --------


   /** Preferred constructor */
   public QueueTotalsGraphPanel(SimulationEngine engine) {
       this(engine, null, null);
   }


   /** Compatibility constructor (ignored params, but useful if you instantiate like ArrivalsGraphPanel) */
   public QueueTotalsGraphPanel(SimulationEngine engine, LocalTime startTime) {
       this(engine, startTime, null);
   }


   /** Compatibility constructor (ignored params, but useful if you instantiate like ArrivalsGraphPanel) */
   public QueueTotalsGraphPanel(SimulationEngine engine, LocalTime startTime, DateTimeFormatter fmt) {
       super(new BorderLayout());
       this.engine = engine;


       if (engine == null) {
           add(makeFallback("Queue totals graph could not be loaded (engine was null)."), BorderLayout.CENTER);
           return;
       }


       initChart();
       add(new ChartPanel(chart), BorderLayout.CENTER);


       // Initial draw (interval 0)
       this.maxComputedInterval = engine.getMaxComputedInterval();
       this.totalIntervals = engine.getTotalIntervals();
       rebuildSeriesIfNeeded(true);
       updateMarker();
   }


   /** No-arg fallback constructor (in case reflection tries it) */
   public QueueTotalsGraphPanel() {
       super(new BorderLayout());
       this.engine = null;
       add(makeFallback("Queue totals graph panel could not be loaded."), BorderLayout.CENTER);
   }


   private static JComponent makeFallback(String msg) {
       JLabel label = new JLabel(msg);
       label.setHorizontalAlignment(SwingConstants.CENTER);
       JPanel p = new JPanel(new BorderLayout());
       p.add(label, BorderLayout.CENTER);
       return p;
   }


   // -------- Public update API (SimulationFrame calls these via reflection) --------


   public void setCurrentInterval(int interval) {
       this.currentInterval = Math.max(0, interval);
       refresh();
   }


   public void setInterval(int interval) { setCurrentInterval(interval); }
   public void setDisplayedInterval(int interval) { setCurrentInterval(interval); }
   public void goToInterval(int interval) { setCurrentInterval(interval); }
   public void onIntervalChanged(int interval) { setCurrentInterval(interval); }
   public void updateForInterval(int interval) { setCurrentInterval(interval); }


   public void setMaxComputedInterval(int max) {
       this.maxComputedInterval = Math.max(0, max);
       refresh();
   }


   public void setMaxInterval(int max) { setMaxComputedInterval(max); }


   public void setTotalIntervals(int total) {
       this.totalIntervals = Math.max(0, total);
       // no automatic refresh required, but harmless:
       refresh();
   }


   public void rebuild() { refresh(); }


   public void refresh() {
       if (engine == null) return;


       // Make sure we run on EDT so JFreeChart + Swing are happy
       if (!SwingUtilities.isEventDispatchThread()) {
           SwingUtilities.invokeLater(this::refresh);
           return;
       }


       // If caller didn't set these, pull from engine (safe)
       this.maxComputedInterval = Math.max(this.maxComputedInterval, engine.getMaxComputedInterval());
       if (this.totalIntervals <= 0) this.totalIntervals = engine.getTotalIntervals();


       rebuildSeriesIfNeeded(false);
       updateMarker();


       revalidate();
       repaint();
   }


   // -------- Chart setup + rendering --------


   private void initChart() {
       dataset.addSeries(ticketSeries);
       dataset.addSeries(checkpointSeries);
       dataset.addSeries(holdRoomSeries);


       chart = ChartFactory.createXYLineChart(
               "Queue Totals by Interval",
               "Interval",
               "Passengers",
               dataset,
               PlotOrientation.VERTICAL,
               true,
               true,
               false
       );


       plot = chart.getXYPlot();


       // Use line-only renderer (no shapes) for cleaner look
       XYLineAndShapeRenderer r = new XYLineAndShapeRenderer(true, false);
       plot.setRenderer(r);


       // Force distinct colors (user asked explicitly)
       r.setSeriesPaint(0, new Color(31, 119, 180)); // blue-ish
       r.setSeriesPaint(1, new Color(214, 39, 40));  // red-ish
       r.setSeriesPaint(2, new Color(44, 160, 44));  // green-ish
   }


   private void rebuildSeriesIfNeeded(boolean force) {
       int targetMax = Math.max(0, maxComputedInterval);


       // If we haven't built yet, or we need to extend, or force rebuild
       if (!force && targetMax == lastBuiltUpTo) {
           // still might need marker/range updates
           updateDomainRange(targetMax);
           return;
       }


       // If targetMax decreased (rare), rebuild everything
       if (force || targetMax < lastBuiltUpTo) {
           ticketSeries.clear();
           checkpointSeries.clear();
           holdRoomSeries.clear();
           lastBuiltUpTo = -1;
       }


       // Incrementally add points up to targetMax
       for (int i = lastBuiltUpTo + 1; i <= targetMax; i++) {
           int t = engine.getTicketQueuedAtInterval(i);
           int c = engine.getCheckpointQueuedAtInterval(i);
           int h = engine.getHoldRoomTotalAtInterval(i);


           ticketSeries.add(i, t);
           checkpointSeries.add(i, c);
           holdRoomSeries.add(i, h);
       }


       lastBuiltUpTo = targetMax;
       updateDomainRange(targetMax);
   }


   private void updateDomainRange(int maxX) {
       if (plot == null) return;


       // keep some sensible range even early on
       int right = Math.max(1, maxX);
       plot.getDomainAxis().setRange(0, right);
   }


   private void updateMarker() {
       if (plot == null) return;


       int markerX = currentInterval;
       if (maxComputedInterval > 0) markerX = Math.min(markerX, maxComputedInterval);


       if (currentMarker != null) {
           plot.removeDomainMarker(currentMarker);
       }


       currentMarker = new ValueMarker(markerX);
       currentMarker.setStroke(new BasicStroke(2f));
       currentMarker.setPaint(new Color(0, 0, 0, 140)); // semi-transparent black


       plot.addDomainMarker(currentMarker);
   }
}
