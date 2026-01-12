package sim.ui;


import sim.model.Flight;
import sim.service.SimulationEngine;


import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;


/**
* Read-only viewer of the ACTUAL per-minute arrival curve currently inside SimulationEngine.
* - Pulls data from engine.getMinuteArrivalsMap()
* - Lets user select a flight
* - Draws the curve in minutes-before-departure space
* - Highlights the currently viewed interval (timeline scrub or current interval)
*
* This does NOT edit config. It's purely a "what is the engine using?" inspector.
*/
public class ArrivalCurveUsedPanel extends JPanel {
   private static final int PAD_L = 55;
   private static final int PAD_R = 20;
   private static final int PAD_T = 22;
   private static final int PAD_B = 42;


   private final SimulationEngine engine;


   private final JComboBox<Flight> flightBox = new JComboBox<>();
   private final JLabel infoLabel = new JLabel(" ");


   private int viewedInterval = 0; // minutes since global start


   private final LocalTime globalStart; // derived the same way as engine uses


   public ArrivalCurveUsedPanel(SimulationEngine engine) {
       this.engine = engine;


       LocalTime firstDep = engine.getFlights().stream()
               .map(Flight::getDepartureTime)
               .min(LocalTime::compareTo)
               .orElse(LocalTime.MIDNIGHT);
       this.globalStart = firstDep.minusMinutes(engine.getArrivalSpan());


       setLayout(new BorderLayout(8, 6));
       setBackground(Color.WHITE);


       JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
       top.setOpaque(false);


       top.add(new JLabel("Flight:"));
       flightBox.setRenderer(new DefaultListCellRenderer() {
           @Override
           public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                         boolean isSelected, boolean cellHasFocus) {
               super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
               if (value instanceof Flight) {
                   Flight f = (Flight) value;
                   setText(f.getFlightNumber() + "  (dep " + f.getDepartureTime() + ")");
               }
               return this;
           }
       });


       for (Flight f : engine.getFlights()) flightBox.addItem(f);


       flightBox.addActionListener(e -> repaint());


       top.add(flightBox);


       top.add(Box.createHorizontalStrut(12));
       top.add(infoLabel);


       add(top, BorderLayout.NORTH);


       setPreferredSize(new Dimension(900, 240));


       syncWithEngine();
   }


   /** Call this when the timeline scrubber is moving or after a rewind/jump. */
   public void setViewedInterval(int interval) {
       this.viewedInterval = Math.max(0, interval);
       repaint();
   }


   /** Call this after engine advances (or after goToInterval) to refresh labels. */
   public void syncWithEngine() {
       int arrivalSpan = engine.getArrivalSpan();


       // Derive boarding close from data if possible:
       // close = arrivalSpan - perMin.length (typically 20)
       int close = 20;
       Flight f = (Flight) flightBox.getSelectedItem();
       Map<Flight, int[]> m = safeGetMinuteArrivalsMap();
       if (f != null && m != null) {
           int[] arr = m.get(f);
           if (arr != null && arr.length > 0) {
               close = Math.max(0, arrivalSpan - arr.length);
           }
       }


       // Best-effort display of curve mode (via reflection, so this file compiles even if method name differs)
       String mode = tryGetCurveModeString();


       infoLabel.setText("arrivalSpan=" + arrivalSpan + "  close≈" + close + "  " + mode);
       repaint();
   }


   private Map<Flight, int[]> safeGetMinuteArrivalsMap() {
       try {
           return engine.getMinuteArrivalsMap();
       } catch (Exception ex) {
           return null;
       }
   }


   private String tryGetCurveModeString() {
       // If your engine has getArrivalCurveConfigCopy() returning ArrivalCurveConfig, we’ll read legacyMode from it.
       try {
           Method m = engine.getClass().getMethod("getArrivalCurveConfigCopy");
           Object cfg = m.invoke(engine);
           if (cfg != null) {
               Method isLegacy = cfg.getClass().getMethod("isLegacyMode");
               Object out = isLegacy.invoke(cfg);
               boolean legacy = (out instanceof Boolean) && (Boolean) out;
               return legacy ? "mode=LEGACY" : "mode=EDITED";
           }
       } catch (Exception ignored) { }
       return "mode=(unknown)";
   }


   @Override
   protected void paintComponent(Graphics g) {
       super.paintComponent(g);


       int w = getWidth();
       int h = getHeight();
       if (w <= 0 || h <= 0) return;


       Graphics2D g2 = (Graphics2D) g.create();
       g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);


       int plotL = PAD_L;
       int plotR = w - PAD_R;
       int plotT = PAD_T;
       int plotB = h - PAD_B;


       g2.setColor(new Color(225, 225, 225));
       g2.drawRect(plotL, plotT, plotR - plotL, plotB - plotT);


       Flight f = (Flight) flightBox.getSelectedItem();
       if (f == null) {
           g2.dispose();
           return;
       }


       Map<Flight, int[]> map = safeGetMinuteArrivalsMap();
       int[] perMin = (map == null) ? null : map.get(f);


       int arrivalSpan = engine.getArrivalSpan();
       if (perMin == null) perMin = new int[0];


       // Derive boarding close from array length (typically 20)
       int close = Math.max(0, arrivalSpan - perMin.length);
       if (close <= 0) close = 20;


       // Max for scaling
       int max = 1;
       for (int v : perMin) max = Math.max(max, v);


       // Draw curve
       g2.setColor(new Color(40, 120, 200));
       int prevX = -1, prevY = -1;


       for (int i = 0; i < perMin.length; i++) {
           // i=0 corresponds to arrivalSpan minutes before departure
           double minutesBefore = arrivalSpan - (i + 0.5);
           int x = minutesToX(minutesBefore, arrivalSpan, close, plotL, plotR);
           double frac = perMin[i] / (double) max;
           int y = plotB - (int) Math.round(frac * (plotB - plotT));


           if (prevX >= 0) g2.drawLine(prevX, prevY, x, y);
           prevX = x;
           prevY = y;
       }


       // Highlight currently viewed interval (vertical line at "minutes before departure" for that flight)
       int mb = minutesBeforeDepartureAtViewedInterval(f, viewedInterval);
       if (mb >= close && mb <= arrivalSpan) {
           int vx = minutesToX(mb, arrivalSpan, close, plotL, plotR);
           g2.setColor(new Color(200, 60, 60));
           g2.drawLine(vx, plotT, vx, plotB);
           g2.drawString("viewed @ " + mb + " min before", vx + 4, plotT + 14);
       }


       // X ticks
       g2.setColor(Color.DARK_GRAY);
       drawXTicks(g2, plotL, plotR, plotB, arrivalSpan, close);


       // Title
       g2.setColor(Color.DARK_GRAY);
       g2.drawString("Actual per-minute arrivals for " + f.getFlightNumber(), plotL, 14);


       g2.dispose();
   }


   private int minutesBeforeDepartureAtViewedInterval(Flight f, int interval) {
       LocalTime t = globalStart.plusMinutes(Math.max(0, interval));
       long mb = Duration.between(t, f.getDepartureTime()).toMinutes();
       return (int) mb;
   }


   private static int minutesToX(double minutesBefore, int arrivalSpan, int close, int plotL, int plotR) {
       minutesBefore = clamp(minutesBefore, close, arrivalSpan);
       double range = Math.max(1.0, (arrivalSpan - close));
       double t = (arrivalSpan - minutesBefore) / range; // arrivalSpan->0.0, close->1.0
       return plotL + (int) Math.round(t * (plotR - plotL));
   }


   private static void drawXTicks(Graphics2D g2, int plotL, int plotR, int plotB, int arrivalSpan, int close) {
       int[] common = new int[]{240, 210, 180, 150, 120, 90, 60, 30, 20};
       for (int t : common) {
           if (t > arrivalSpan || t < close) continue;
           int x = minutesToX(t, arrivalSpan, close, plotL, plotR);
           g2.drawLine(x, plotB, x, plotB + 6);
           g2.drawString(String.valueOf(t), x - 8, plotB + 20);
       }
       g2.drawString("minutes before departure (" + arrivalSpan + " → " + close + ")", plotL, plotB + 34);
   }


   private static double clamp(double v, double lo, double hi) {
       return Math.max(lo, Math.min(hi, v));
   }
}
