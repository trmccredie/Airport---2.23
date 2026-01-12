package sim.ui;


import sim.model.Flight;


import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.time.LocalTime;
import java.util.List;


public class FlightTablePanel extends JPanel {
   private final JTable table;
   private final FlightTableModel model;


   // ✅ instance counter so Floorplan and Blank Canvas don't "share" numbering
   private int flightCounter = 1;


   public FlightTablePanel() {
       setLayout(new BorderLayout());


       model = new FlightTableModel();
       table = new JTable(model);


       // Optional: makes the shape column a bit nicer visually
       table.setRowHeight(Math.max(22, table.getRowHeight()));


       // Shape editor (uses your actual enum: CIRCLE/TRIANGLE/SQUARE/DIAMOND/HEXAGON/STAR)
       // NOTE: your model previously used col 4 for ShapeType; keep that consistent here.
       TableColumn shapeCol = table.getColumnModel().getColumn(4);
       JComboBox<Flight.ShapeType> combo = new JComboBox<>(Flight.ShapeType.values());


       // Friendly labels matching ShapePainter's supported shapes
       combo.setRenderer(new DefaultListCellRenderer() {
           @Override
           public Component getListCellRendererComponent(
                   JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
           ) {
               super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
               if (value instanceof Flight.ShapeType) {
                   setText(shapeLabel((Flight.ShapeType) value));
               } else if (value == null) {
                   setText("");
               } else {
                   setText(String.valueOf(value));
               }
               return this;
           }
       });


       shapeCol.setCellEditor(new DefaultCellEditor(combo));


       // Render the friendly label in the table cells as well
       shapeCol.setCellRenderer(new DefaultTableCellRenderer() {
           @Override
           protected void setValue(Object value) {
               if (value instanceof Flight.ShapeType) {
                   setText(shapeLabel((Flight.ShapeType) value));
               } else if (value == null) {
                   setText("");
               } else {
                   super.setValue(value);
               }
           }
       });


       add(new JScrollPane(table), BorderLayout.CENTER);


       JButton addBtn = new JButton("Add Flight");
       JButton removeBtn = new JButton("Remove Flight");


       addBtn.addActionListener(e -> model.addFlight(
               new Flight(
                       String.valueOf(flightCounter++),
                       LocalTime.now().withSecond(0).withNano(0),
                       180,
                       0.85,
                       Flight.ShapeType.CIRCLE
               )
       ));


       removeBtn.addActionListener(e -> {
           int sel = table.getSelectedRow();
           if (sel >= 0) model.removeFlight(sel);
       });


       JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
       btnPanel.add(addBtn);
       btnPanel.add(removeBtn);
       add(btnPanel, BorderLayout.SOUTH);
   }


   private static String shapeLabel(Flight.ShapeType t) {
       if (t == null) return "";
       // Match ShapePainter exactly
       switch (t) {
           case CIRCLE:   return "CIRCLE";
           case TRIANGLE: return "TRIANGLE";
           case SQUARE:   return "SQUARE";
           case DIAMOND:  return "DIAMOND";
           case HEXAGON:  return "HEXAGON";
           case STAR:     return "STAR";
           default:       return t.name();
       }
   }


   public List<Flight> getFlights() {
       return model.getFlights();
   }
}


