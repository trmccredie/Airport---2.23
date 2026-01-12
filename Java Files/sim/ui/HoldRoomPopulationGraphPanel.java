package sim.ui;


import sim.model.Passenger;
import sim.service.SimulationEngine;


import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;


/**
* Tab panel that lets the user choose a specific physical hold room (button per room)
* and view a live-updating per-interval line graph of that room's population.
*
* X-axis uses the same interval indexing pattern as the rest of your UI:
*  - interval 0 = initial state (before any simulateInterval ran) -> 0 passengers
*  - interval i>=1 uses historyHoldRooms.get(i-1)
*/
public class HoldRoomPopulationGraphPanel extends JPanel {
   private final SimulationEngine engine;


   private final JPanel buttonListPanel;
   private final JScrollPane buttonScroll;


   // NEW: title label above the graph (prevents overlap with borders/paint)
   private final JLabel graphTitleLabel;


   private final LineGraphPanel graphPanel;


   private int selectedRoomIndex = 0;


   // For consistent marker behavior with other graphs
   private int viewedInterval = 0;
   private int maxComputedInterval = 0;
   private int totalIntervals = 0;


   // Track how many rooms we last built buttons for
   private int lastRoomCount = -1;


   // ---------------- NEW: Summary stats panel (shown ONLY after simulation completes) ----------------
   private static final int SQFT_PER_PERSON = 5;


   private final JPanel statsPanel;
   private final JLabel statsStatusLabel;
   private final JLabel maxPeopleValueLabel;
   private final JLabel areaValueLabel;


   public HoldRoomPopulationGraphPanel(SimulationEngine engine) {
       super(new BorderLayout(8, 8));
       this.engine = engine;


       buttonListPanel = new JPanel();
       buttonListPanel.setLayout(new BoxLayout(buttonListPanel, BoxLayout.Y_AXIS));


       buttonScroll = new JScrollPane(
               buttonListPanel,
               JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
               JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
       );
       buttonScroll.setBorder(BorderFactory.createTitledBorder("Select Hold Room"));
       buttonScroll.setPreferredSize(new Dimension(220, 200));


       // --- Graph area (title label + graph) ---
       graphTitleLabel = new JLabel("Hold Room — population per interval");
       graphTitleLabel.setFont(graphTitleLabel.getFont().deriveFont(Font.BOLD, 13f));
       graphTitleLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));


       graphPanel = new LineGraphPanel();
       // IMPORTANT: remove titled border to prevent overlap with any drawn text
       graphPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));


       JPanel graphContainer = new JPanel(new BorderLayout());
       graphContainer.add(graphTitleLabel, BorderLayout.NORTH);
       graphContainer.add(graphPanel, BorderLayout.CENTER);


       // ---------------- NEW: Stats panel to the RIGHT of the graph ----------------
       statsPanel = new JPanel();
       statsPanel.setLayout(new BoxLayout(statsPanel, BoxLayout.Y_AXIS));
       statsPanel.setBorder(BorderFactory.createTitledBorder("Hold Room Summary"));
       statsPanel.setPreferredSize(new Dimension(230, 200));


       statsStatusLabel = new JLabel("Complete the simulation to see final stats.");
       statsStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);


       JLabel maxLbl = new JLabel("Max people at once:");
       maxLbl.setAlignmentX(Component.LEFT_ALIGNMENT);


       maxPeopleValueLabel = new JLabel("—");
       maxPeopleValueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);


       JLabel areaLbl = new JLabel("Required area (sq ft):");
       areaLbl.setAlignmentX(Component.LEFT_ALIGNMENT);


       areaValueLabel = new JLabel("—");
       areaValueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);


       statsPanel.add(statsStatusLabel);
       statsPanel.add(Box.createVerticalStrut(10));
       statsPanel.add(maxLbl);
       statsPanel.add(maxPeopleValueLabel);
       statsPanel.add(Box.createVerticalStrut(8));
       statsPanel.add(areaLbl);
       statsPanel.add(areaValueLabel);


       // Put graph + stats side-by-side
       JPanel graphAndStats = new JPanel(new BorderLayout(8, 8));
       graphAndStats.add(graphContainer, BorderLayout.CENTER);
       graphAndStats.add(statsPanel, BorderLayout.EAST);


       add(buttonScroll, BorderLayout.WEST);
       add(graphAndStats, BorderLayout.CENTER);


       rebuildButtonsIfNeeded();
       syncWithEngine();
   }


   // --------- API mirroring the other graph tabs ----------


   public void setMaxComputedInterval(int maxComputedInterval) {
       this.maxComputedInterval = Math.max(0, maxComputedInterval);
   }


   public void setTotalIntervals(int totalIntervals) {
       this.totalIntervals = Math.max(0, totalIntervals);
   }


   public void setCurrentInterval(int currentInterval) {
       setViewedInterval(currentInterval);
   }


   public void setViewedInterval(int interval) {
       this.viewedInterval = Math.max(0, interval);
       graphPanel.setMarkerInterval(this.viewedInterval);
       graphPanel.repaint();
   }


   /**
    * Call each refresh tick (like arrivalsGraphPanel.syncWithEngine()).
    * Rebuilds series from engine history and updates the graph.
    */
   public void syncWithEngine() {
       rebuildButtonsIfNeeded();


       List<Integer> series = buildRoomSeries(selectedRoomIndex);
       String roomLabel = getRoomLabel(selectedRoomIndex);


       // NEW: title is handled by JLabel above the graph (no overlap)
       graphTitleLabel.setText(roomLabel + " — population per interval");


       graphPanel.setData(series, maxComputedInterval, totalIntervals);
       graphPanel.setMarkerInterval(viewedInterval);
       graphPanel.repaint();


       // NEW: summary stats (only once simulation is done)
       updateStats(series);
   }


   // --------- Internal helpers ----------


   private void rebuildButtonsIfNeeded() {
       int roomCount = (engine.getHoldRoomConfigs() == null) ? 0 : engine.getHoldRoomConfigs().size();
       if (roomCount == lastRoomCount) return;


       lastRoomCount = roomCount;
       buttonListPanel.removeAll();


       if (roomCount <= 0) {
           JLabel none = new JLabel("No hold rooms.");
           none.setAlignmentX(Component.LEFT_ALIGNMENT);
           buttonListPanel.add(none);
           buttonListPanel.revalidate();
           buttonListPanel.repaint();


           // Also reset stats panel
           statsStatusLabel.setText("No hold rooms.");
           maxPeopleValueLabel.setText("—");
           areaValueLabel.setText("—");
           return;
       }


       ButtonGroup group = new ButtonGroup();


       // Clamp selected index if room count shrank
       selectedRoomIndex = Math.max(0, Math.min(selectedRoomIndex, roomCount - 1));


       for (int i = 0; i < roomCount; i++) {
           final int idx = i;


           JToggleButton btn = new JToggleButton(getRoomLabel(i));
           btn.setAlignmentX(Component.LEFT_ALIGNMENT);


           if (i == selectedRoomIndex) btn.setSelected(true);


           btn.addActionListener(e -> {
               selectedRoomIndex = idx;
               syncWithEngine();
           });


           group.add(btn);
           buttonListPanel.add(btn);
           buttonListPanel.add(Box.createVerticalStrut(6));
       }


       buttonListPanel.revalidate();
       buttonListPanel.repaint();
   }


   private String getRoomLabel(int roomIdx) {
       // Best-effort label: prefer config id if present, else "Hold Room N"
       try {
           HoldRoomConfig cfg = engine.getHoldRoomConfigs().get(roomIdx);
           if (cfg != null) {
               try {
                   int id = cfg.getId();
                   return "Hold Room " + id;
               } catch (Exception ignored) { }
           }
       } catch (Exception ignored) { }
       return "Hold Room " + (roomIdx + 1);
   }


   /**
    * Build a series where index = interval.
    *  - interval 0 => 0
    *  - interval i>=1 => historyHoldRooms.get(i-1).get(roomIdx).size()
    *
    * We build up to maxComputedInterval (not totalIntervals), since that's what's available.
    */
   private List<Integer> buildRoomSeries(int roomIdx) {
       int maxX = Math.max(0, maxComputedInterval);


       List<Integer> out = new ArrayList<>(maxX + 1);


       // interval 0 = initial state (empty rooms)
       out.add(0);


       List<List<List<Passenger>>> hist = engine.getHistoryHoldRooms();
       int histSize = (hist == null) ? 0 : hist.size();


       for (int interval = 1; interval <= maxX; interval++) {
           int step = interval - 1; // history index
           int count = 0;


           if (step >= 0 && step < histSize) {
               List<List<Passenger>> roomsAtStep = hist.get(step);
               if (roomsAtStep != null && roomIdx >= 0 && roomIdx < roomsAtStep.size()) {
                   List<Passenger> room = roomsAtStep.get(roomIdx);
                   count = (room == null) ? 0 : room.size();
               }
           }


           out.add(count);
       }


       return out;
   }


   /**
    * Updates the stats panel.
    * Only shows final values when the simulation is complete (i.e., we've computed all intervals).
    */
   private void updateStats(List<Integer> series) {
       // Treat "done" only when totalIntervals is known and we've computed through it.
       boolean done = (totalIntervals > 0) && (maxComputedInterval >= totalIntervals);


       if (!done) {
           statsStatusLabel.setText("Complete the simulation to see final stats.");
           maxPeopleValueLabel.setText("—");
           areaValueLabel.setText("—");
           return;
       }


       int max = 0;
       if (series != null) {
           for (Integer v : series) {
               if (v != null && v > max) max = v;
           }
       }


       int area = max * SQFT_PER_PERSON;


       statsStatusLabel.setText("Final (entire run):");
       maxPeopleValueLabel.setText(String.format("%,d", max));
       areaValueLabel.setText(String.format("%,d", area));
   }


   // --------- Simple custom line graph ----------


   private static final class LineGraphPanel extends JPanel {
       private List<Integer> data = new ArrayList<>();
       private int markerInterval = 0;
       private int maxComputed = 0;
       private int total = 0;


       public void setData(List<Integer> data, int maxComputedInterval, int totalIntervals) {
           this.data = (data == null) ? new ArrayList<>() : data;
           this.maxComputed = Math.max(0, maxComputedInterval);
           this.total = Math.max(0, totalIntervals);
       }


       public void setMarkerInterval(int markerInterval) {
           this.markerInterval = Math.max(0, markerInterval);
       }


       @Override
       protected void paintComponent(Graphics g0) {
           super.paintComponent(g0);
           Graphics2D g = (Graphics2D) g0.create();
           try {
               g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);


               int w = getWidth();
               int h = getHeight();


               int padL = 55;
               int padR = 20;
               int padT = 16;  // smaller now that title is outside
               int padB = 40;


               int plotX = padL;
               int plotY = padT;
               int plotW = Math.max(1, w - padL - padR);
               int plotH = Math.max(1, h - padT - padB);


               // Determine maxY
               int maxY = 1;
               for (Integer v : data) {
                   if (v != null) maxY = Math.max(maxY, v);
               }


               // Axes
               g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
               g.drawLine(plotX, plotY, plotX, plotY + plotH);
               g.drawLine(plotX, plotY + plotH, plotX + plotW, plotY + plotH);


               // Y labels (0, maxY)
               g.drawString("0", plotX - 18, plotY + plotH + 4);
               g.drawString(String.valueOf(maxY), plotX - 30, plotY + 10);


               // X labels (0, maxComputed, total)
               g.drawString("0", plotX - 2, plotY + plotH + 18);
               g.drawString(String.valueOf(maxComputed), plotX + plotW - 20, plotY + plotH + 18);
               if (total > maxComputed) {
                   g.drawString("Total: " + total, plotX + 6, plotY + plotH + 18);
               }


               if (data.size() < 2) return;


               // Map points: x in [0..maxComputed]
               int n = data.size(); // should be maxComputed+1
               int maxX = Math.max(1, n - 1);


               int prevX = plotX;
               int prevY = plotY + plotH - (int) ((data.get(0) / (double) maxY) * plotH);


               for (int i = 1; i < n; i++) {
                   int x = plotX + (int) ((i / (double) maxX) * plotW);
                   int v = (data.get(i) == null) ? 0 : data.get(i);
                   int y = plotY + plotH - (int) ((v / (double) maxY) * plotH);


                   g.drawLine(prevX, prevY, x, y);


                   prevX = x;
                   prevY = y;
               }


               // Marker (viewed interval)
               int marker = Math.max(0, Math.min(markerInterval, maxX));
               int mx = plotX + (int) ((marker / (double) maxX) * plotW);
               g.drawLine(mx, plotY, mx, plotY + plotH);


               // Marker label
               int labelX = Math.min(mx + 6, plotX + plotW - 90); // avoid running off right edge
               g.drawString("Interval " + markerInterval, labelX, plotY + 14);


           } finally {
               g.dispose();
           }
       }
   }
}


