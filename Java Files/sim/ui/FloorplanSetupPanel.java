package sim.ui;


import sim.floorplan.model.FloorplanProject;
import sim.floorplan.model.Zone;
import sim.floorplan.model.ZoneType;
import sim.floorplan.sim.FloorplanTravelTimeProvider;
import sim.floorplan.sim.TravelTimeProvider;
import sim.model.ArrivalCurveConfig;
import sim.model.Flight;
import sim.service.SimulationEngine;


import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;


public class FloorplanSetupPanel extends JPanel {


   private static final double DEFAULT_TICKET_RATE_PER_MIN = 1.0;     // 60/hr
   private static final double DEFAULT_CHECKPOINT_RATE_PER_HR = 120.0;


   private final FloorplanGlobalInputPanel globalInputPanel = new FloorplanGlobalInputPanel();
   private final FlightTablePanel flightTablePanel = new FlightTablePanel();
   private final ArrivalCurveEditorPanel arrivalCurvePanel =
           new ArrivalCurveEditorPanel(ArrivalCurveConfig.legacyDefault());


   private final JLabel statusLabel = new JLabel("No validated floorplan loaded yet.");
   private final JButton startBtn = new JButton("Start Floorplan Simulation");


   private FloorplanProject floorplanProjectCopy;


   public FloorplanSetupPanel() {
       super(new BorderLayout());
       setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));


       startBtn.addActionListener(e -> onStartFloorplanSimulation());
       startBtn.setEnabled(false);


       JPanel top = new JPanel(new BorderLayout(8, 8));
       top.add(globalInputPanel, BorderLayout.NORTH);


       statusLabel.setForeground(new Color(90, 90, 90));
       top.add(statusLabel, BorderLayout.SOUTH);


       add(top, BorderLayout.NORTH);


       JTabbedPane tabs = new JTabbedPane();
       tabs.addTab("Flights (Floorplan)", flightTablePanel);
       tabs.addTab("Arrivals Curve (Floorplan)", arrivalCurvePanel);


       // reminder panel (so it’s obvious where rates/mappings live)
       JTextArea hint = new JTextArea(
               "Floorplan rates/mappings come ONLY from Zone metadata:\n" +
               "- Ticket Counter: rate + allowed flights\n" +
               "- Checkpoint: rate\n" +
               "- Holdroom: allowed flights\n\n" +
               "Edit these in: Floorplan Editor → select zone → Zone Inspector.\n" +
               "Blank-canvas counters/checkpoints/hold rooms do NOT affect floorplan."
       );
       hint.setEditable(false);
       hint.setOpaque(false);
       hint.setLineWrap(true);
       hint.setWrapStyleWord(true);
       hint.setBorder(BorderFactory.createEmptyBorder(10, 2, 2, 2));


       JPanel center = new JPanel(new BorderLayout());
       center.add(tabs, BorderLayout.CENTER);
       center.add(hint, BorderLayout.SOUTH);


       add(center, BorderLayout.CENTER);


       JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
       bottom.add(startBtn);
       add(bottom, BorderLayout.SOUTH);
   }


   public void setFloorplanProjectCopy(FloorplanProject copy) {
       this.floorplanProjectCopy = (copy == null) ? null : copy.copy();


       boolean usable = isUsableFloorplan(this.floorplanProjectCopy);
       startBtn.setEnabled(usable);


       if (!usable) {
           statusLabel.setText("Invalid floorplan: needs mask + zones (and required anchors).");
       } else {
           int sp = countZones(ZoneType.SPAWN);
           int tc = countZones(ZoneType.TICKET_COUNTER);
           int cp = countZones(ZoneType.CHECKPOINT);
           int hr = countZones(ZoneType.HOLDROOM);
           statusLabel.setText("Loaded validated floorplan. Anchors: spawn=" + sp + ", ticket=" + tc +
                   ", checkpoint=" + cp + ", holdroom=" + hr);
       }
   }


   private int countZones(ZoneType t) {
       if (floorplanProjectCopy == null || floorplanProjectCopy.getZones() == null) return 0;
       int n = 0;
       for (Zone z : floorplanProjectCopy.getZones()) {
           if (z != null && z.getType() == t) n++;
       }
       return n;
   }


   private static boolean isUsableFloorplan(FloorplanProject fp) {
       if (fp == null) return false;
       if (fp.getMask() == null) return false;
       if (fp.getZones() == null || fp.getZones().isEmpty()) return false;


       boolean hasSpawn = false, hasCheckpoint = false, hasHold = false;
       for (Zone z : fp.getZones()) {
           if (z == null || z.getType() == null) continue;
           if (z.getType() == ZoneType.SPAWN && z.getAnchor() != null) hasSpawn = true;
           if (z.getType() == ZoneType.CHECKPOINT && z.getAnchor() != null) hasCheckpoint = true;
           if (z.getType() == ZoneType.HOLDROOM && z.getAnchor() != null) hasHold = true;
       }
       return hasSpawn && hasCheckpoint && hasHold;
   }


   private void onStartFloorplanSimulation() {
       if (!isUsableFloorplan(floorplanProjectCopy)) {
           JOptionPane.showMessageDialog(this,
                   "Floorplan simulation is locked until a validated floorplan is loaded.",
                   "No Valid Floorplan",
                   JOptionPane.WARNING_MESSAGE);
           return;
       }


       if (flightTablePanel.getFlights().isEmpty()) {
           JOptionPane.showMessageDialog(this,
                   "Please add at least one floorplan flight before starting.",
                   "No Flights Defined",
                   JOptionPane.WARNING_MESSAGE);
           return;
       }


       try {
           double percentInPerson = globalInputPanel.getPercentInPerson();
           if (percentInPerson < 0 || percentInPerson > 1) {
               throw new IllegalArgumentException("Percent in person must be between 0 and 1");
           }


           int baseArrivalSpan = globalInputPanel.getArrivalSpanMinutes();
           int interval = globalInputPanel.getIntervalMinutes(); // fixed 1


           double walkSpeedMps = globalInputPanel.getWalkSpeedMps();
           if (!(walkSpeedMps > 0)) {
               JOptionPane.showMessageDialog(this,
                       "Walk speed must be > 0.",
                       "Invalid Walk Speed",
                       JOptionPane.WARNING_MESSAGE);
               return;
           }


           List<Flight> flights = flightTablePanel.getFlights();


           ArrivalCurveConfig curveCfg = arrivalCurvePanel.getConfigCopy();
           curveCfg.validateAndClamp();


           int curveStart = curveCfg.isLegacyMode()
                   ? ArrivalCurveConfig.DEFAULT_WINDOW_START
                   : curveCfg.getWindowStartMinutesBeforeDeparture();


           int effectiveArrivalSpan = Math.max(Math.max(0, baseArrivalSpan), Math.max(0, curveStart));


           // Build configs ONLY from zones (no blank-canvas panels involved)
           List<Zone> ticketZones = zonesOfType(ZoneType.TICKET_COUNTER);
           List<Zone> checkpointZones = zonesOfType(ZoneType.CHECKPOINT);
           List<Zone> holdZones = zonesOfType(ZoneType.HOLDROOM);


           if (checkpointZones.isEmpty() || holdZones.isEmpty()) {
               JOptionPane.showMessageDialog(this,
                       "Floorplan simulation requires at least:\n" +
                               "- 1 Checkpoint anchor\n" +
                               "- 1 Holdroom anchor\n",
                       "Missing Required Zones",
                       JOptionPane.WARNING_MESSAGE);
               return;
           }


           // If in-person > 0, require at least one ticket counter anchor
           if (percentInPerson > 0.0 && ticketZones.isEmpty()) {
               JOptionPane.showMessageDialog(this,
                       "Percent In Person > 0 requires at least 1 Ticket Counter anchor.",
                       "Missing Ticket Counters",
                       JOptionPane.WARNING_MESSAGE);
               return;
           }


           List<TicketCounterConfig> counters = buildTicketCountersFromZones(ticketZones, flights);
           List<CheckpointConfig> checkpoints = buildCheckpointsFromZones(checkpointZones);
           List<HoldRoomConfig> holdRooms = buildHoldRoomsFromZones(holdZones);


           // Floorplan travel times come from provider; pass legacy delays as 0 to avoid double counting
           int transitDelay = 0;
           int holdDelay = 0;


           TravelTimeProvider provider = new FloorplanTravelTimeProvider(floorplanProjectCopy.copy(), walkSpeedMps);


           SimulationEngine tableEngine = new SimulationEngine(
                   percentInPerson, counters, checkpoints,
                   effectiveArrivalSpan, interval, transitDelay, holdDelay,
                   flights, holdRooms
           );
           tableEngine.setArrivalCurveConfig(curveCfg);
           tableEngine.setTravelTimeProvider(provider);
           tableEngine.runAllIntervals();


           SimulationEngine simEngine = new SimulationEngine(
                   percentInPerson, counters, checkpoints,
                   effectiveArrivalSpan, interval, transitDelay, holdDelay,
                   flights, holdRooms
           );
           simEngine.setArrivalCurveConfig(curveCfg);
           simEngine.setTravelTimeProvider(provider);


           new DataTableFrame(tableEngine).setVisible(true);


           // show floorplan sim
           new SimulationFrame(simEngine, floorplanProjectCopy.copy()).setVisible(true);


       } catch (Exception ex) {
           ex.printStackTrace();
           JTextArea area = new JTextArea(String.valueOf(ex), 16, 60);
           area.setEditable(false);
           JOptionPane.showMessageDialog(this,
                   new JScrollPane(area),
                   "Floorplan Simulation Error",
                   JOptionPane.ERROR_MESSAGE);
       }
   }


   private List<Zone> zonesOfType(ZoneType type) {
       List<Zone> out = new ArrayList<>();
       if (floorplanProjectCopy == null || floorplanProjectCopy.getZones() == null) return out;
       for (Zone z : floorplanProjectCopy.getZones()) {
           if (z != null && z.getType() == type) out.add(z);
       }
       return out;
   }


   private List<TicketCounterConfig> buildTicketCountersFromZones(List<Zone> zones, List<Flight> flights) {
       List<TicketCounterConfig> out = new ArrayList<>();
       int id = 1;


       for (Zone z : zones) {
           TicketCounterConfig cfg = new TicketCounterConfig(id++);
           double rate = (z.hasTicketRatePerMinute() ? z.getTicketRatePerMinute() : DEFAULT_TICKET_RATE_PER_MIN);
           cfg.setRate(Math.max(0.0, rate));


           Set<String> allowedNums = z.getAllowedFlightNumbers();
           if (allowedNums == null || allowedNums.isEmpty()) {
               // empty => accept ALL
               cfg.setAllowedFlights(Collections.emptySet());
           } else {
               Set<Flight> allowed = new LinkedHashSet<>();
               for (Flight f : flights) {
                   if (f != null && allowedNums.contains(f.getFlightNumber())) allowed.add(f);
               }
               cfg.setAllowedFlights(allowed);
           }


           out.add(cfg);
       }


       return out;
   }


   private List<CheckpointConfig> buildCheckpointsFromZones(List<Zone> zones) {
       List<CheckpointConfig> out = new ArrayList<>();
       int id = 1;


       for (Zone z : zones) {
           CheckpointConfig cfg = new CheckpointConfig(id++);
           double rate = (z.hasCheckpointRatePerHour() ? z.getCheckpointRatePerHour() : DEFAULT_CHECKPOINT_RATE_PER_HR);
           cfg.setRatePerHour(Math.max(0.0, rate));
           out.add(cfg);
       }


       return out;
   }


   private List<HoldRoomConfig> buildHoldRoomsFromZones(List<Zone> zones) {
       List<HoldRoomConfig> out = new ArrayList<>();
       int id = 1;


       for (Zone z : zones) {
           // Your HoldRoomTableModel already uses this constructor:
           HoldRoomConfig cfg = new HoldRoomConfig(id++, 0);


           // reuse the same allowedFlightNumbers metadata field (empty => accept ALL)
           cfg.setAllowedFlightNumbers(z.getAllowedFlightNumbers());


           // floorplan travel time provider controls walking time, so this is fine at 0
           cfg.setWalkTime(0, 0);


           out.add(cfg);
       }


       return out;
   }
}


