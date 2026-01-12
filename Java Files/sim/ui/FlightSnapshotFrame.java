package sim.ui;


import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;


import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;


public class FlightSnapshotFrame extends JFrame {
   private final SimulationEngine engine;
   private final int step;
   private final int originalInterval;


   /**
    * Legacy constructor: shows snapshot at the most recently closed interval.
    */
   public FlightSnapshotFrame(SimulationEngine engine, Flight flight) {
       this(engine, flight, Math.max(0, engine.getCurrentInterval() - 1));
   }


   /**
    * New full constructor: show snapshot at a specific history step.
    * @param engine the simulation engine (must have runAllIntervals() beforehand)
    * @param flight the flight to filter by
    * @param step   the interval index into the history lists
    */
   public FlightSnapshotFrame(SimulationEngine engine, Flight flight, int step) {
       super("Snapshot — Flight " + flight.getFlightNumber());
       this.engine = engine;
       this.step   = Math.max(0, step);
       this.originalInterval = engine.getCurrentInterval();


       // Bump engine.currentInterval so that the panels pick up the correct history step.
       // (Panels typically render step = engine.getCurrentInterval() - 1)
       try {
           Field curr = SimulationEngine.class.getDeclaredField("currentInterval");
           curr.setAccessible(true);
           curr.setInt(engine, this.step + 1);
       } catch (Exception e) {
           e.printStackTrace();
       }


       // IMPORTANT: restore the engine interval when this window closes
       addWindowListener(new WindowAdapter() {
           @Override
           public void windowClosed(WindowEvent e) {
               restoreInterval();
           }
           @Override
           public void windowClosing(WindowEvent e) {
               restoreInterval();
           }
       });


       initUI(flight);
   }


   private void restoreInterval() {
       try {
           Field curr = SimulationEngine.class.getDeclaredField("currentInterval");
           curr.setAccessible(true);
           curr.setInt(engine, originalInterval);
       } catch (Exception ignored) { }
   }


   private void initUI(Flight flight) {
       setDefaultCloseOperation(DISPOSE_ON_CLOSE);
       setLayout(new BorderLayout(5, 5));


       // --- Compute panel widths exactly as in SimulationFrame ---
       int cellW      = 60 / 3;                    // 3 rows per column
       int boxSize    = 60;
       int gutter     = 30;                        // same gutter
       int padding    = 100;
       int queuedW    = GridRenderer.COLS * cellW; // 15 cols × cellW
       int servedW    = GridRenderer.COLS * cellW;
       int panelWidth = queuedW + boxSize + servedW + padding;


       // --- Ticket panel ---
       List<Rectangle> areas1 = new ArrayList<>();
       List<Passenger> pass1  = new ArrayList<>();
       TicketLinesPanel ticketPanel = new TicketLinesPanel(engine, areas1, pass1, flight);
       ticketPanel.setPreferredSize(
               new Dimension(panelWidth, ticketPanel.getPreferredSize().height)
       );
       JScrollPane ticketScroll = new JScrollPane(
               ticketPanel,
               JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
               JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
       );


       // --- Checkpoint panel ---
       List<Rectangle> areas2 = new ArrayList<>();
       List<Passenger> pass2  = new ArrayList<>();
       CheckpointLinesPanel checkpointPanel = new CheckpointLinesPanel(engine, areas2, pass2, flight);
       checkpointPanel.setPreferredSize(
               new Dimension(panelWidth, checkpointPanel.getPreferredSize().height)
       );
       JScrollPane cpScroll = new JScrollPane(
               checkpointPanel,
               JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
               JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
       );


       // --- Hold-rooms panel ---
       HoldRoomsPanel holdPanel = new HoldRoomsPanel(engine, flight);
       JScrollPane holdScroll = new JScrollPane(
               holdPanel,
               JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
               JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
       );


       // --- Assemble horizontally with gutters ---
       JPanel split = new JPanel();
       split.setLayout(new BoxLayout(split, BoxLayout.X_AXIS));
       split.add(Box.createHorizontalStrut(gutter));
       split.add(ticketScroll);
       split.add(Box.createHorizontalStrut(gutter));
       split.add(cpScroll);
       split.add(Box.createHorizontalStrut(gutter));
       split.add(holdScroll);
       split.add(Box.createHorizontalStrut(gutter));


       // Wrap in outer scrollpane
       JScrollPane centerScroll = new JScrollPane(
               split,
               JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
               JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
       );
       add(centerScroll, BorderLayout.CENTER);


       // --- Close button ---
       JButton close = new JButton("Close");
       close.addActionListener(e -> dispose());
       JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER));
       south.add(close);
       add(south, BorderLayout.SOUTH);


       pack();
       setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
       setVisible(true);
   }
}
