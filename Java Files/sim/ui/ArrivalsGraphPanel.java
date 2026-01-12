package sim.ui;


import sim.service.SimulationEngine;


import javax.swing.*;
import java.awt.*;


public class ArrivalsGraphPanel extends JPanel {
   private final SimulationEngine engine;


   private int viewedInterval = 0;
   private int maxComputed = 0;


   private static final int PAD_L = 50;
   private static final int PAD_R = 20;
   private static final int PAD_T = 20;
   private static final int PAD_B = 40;


   public ArrivalsGraphPanel(SimulationEngine engine) {
       this.engine = engine;
       setBackground(Color.WHITE);
       setPreferredSize(new Dimension(800, 260));
       syncWithEngine();
   }


   public void syncWithEngine() {
       if (engine == null) return;
       maxComputed = Math.max(0, engine.getMaxComputedInterval());
       // keep marker sensible
       viewedInterval = clamp(viewedInterval, 0, Math.max(maxComputed, engine.getTotalIntervals()));
       repaint();
   }


   public void setViewedInterval(int interval) {
       viewedInterval = Math.max(0, interval);
       repaint();
   }


   @Override
   protected void paintComponent(Graphics g) {
       super.paintComponent(g);
       if (engine == null) return;


       int w = getWidth();
       int h = getHeight();
       if (w <= 0 || h <= 0) return;


       Graphics2D g2 = (Graphics2D) g.create();
       g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);


       int plotL = PAD_L;
       int plotR = w - PAD_R;
       int plotT = PAD_T;
       int plotB = h - PAD_B;


       // border
       g2.setColor(new Color(220, 220, 220));
       g2.drawRect(plotL, plotT, plotR - plotL, plotB - plotT);


       // title + dynamic span label
       g2.setColor(Color.DARK_GRAY);
       g2.drawString("Arrivals per Interval (arrivalSpan=" + engine.getArrivalSpan() + " min, interval=" + engine.getInterval() + " min)",
               plotL, 14);


       int n = Math.max(1, maxComputed + 1);


       // y max
       int yMax = 1;
       for (int i = 0; i <= maxComputed; i++) {
           yMax = Math.max(yMax, engine.getTotalArrivalsAtInterval(i));
       }


       // polyline
       g2.setColor(new Color(40, 120, 200));
       int prevX = -1, prevY = -1;


       for (int i = 0; i <= maxComputed; i++) {
           int v = engine.getTotalArrivalsAtInterval(i);


           double tx = (n <= 1) ? 0.0 : (i / (double) (n - 1));
           int x = plotL + (int) Math.round(tx * (plotR - plotL));


           double frac = v / (double) yMax;
           int y = plotB - (int) Math.round(frac * (plotB - plotT));


           if (prevX >= 0) g2.drawLine(prevX, prevY, x, y);
           prevX = x;
           prevY = y;
       }


       // viewed interval marker (purple)
       int vi = clamp(viewedInterval, 0, Math.max(maxComputed, 0));
       int vx = plotL + (int) Math.round(((n <= 1) ? 0.0 : (vi / (double) (n - 1))) * (plotR - plotL));
       g2.setColor(new Color(160, 80, 200));
       g2.drawLine(vx, plotT, vx, plotB);


       // current interval marker (red)
       int ci = clamp(engine.getCurrentInterval(), 0, Math.max(maxComputed, 0));
       int cx = plotL + (int) Math.round(((n <= 1) ? 0.0 : (ci / (double) (n - 1))) * (plotR - plotL));
       g2.setColor(new Color(220, 80, 80));
       g2.drawLine(cx, plotT, cx, plotB);


       // bottom labels
       g2.setColor(Color.DARK_GRAY);
       g2.drawString("0", plotL - 6, plotB + 18);
       g2.drawString(String.valueOf(maxComputed), plotR - 10, plotB + 18);
       g2.drawString("Viewed=" + viewedInterval + "   Current=" + engine.getCurrentInterval(),
               plotL, h - 10);


       g2.dispose();
   }


   private static int clamp(int v, int lo, int hi) {
       return Math.max(lo, Math.min(hi, v));
   }
}
