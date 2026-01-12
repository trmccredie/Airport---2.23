// TicketCounterPanel.java
package sim.ui;




import sim.model.Flight;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;




/**
* Panel for adding/removing ticket counters with per-counter rates and flight restrictions.
*/
public class TicketCounterPanel extends JPanel {
   private final JTable table;
   private final TicketCounterTableModel model;
   private final JButton addBtn;
   private final JButton removeBtn;




   public TicketCounterPanel(List<Flight> flights) {
       super(new BorderLayout());
       model = new TicketCounterTableModel(flights);
       table = new JTable(model);




       // Column 2 (Available Flights) uses a button editor to show checkbox dialog
       table.getColumnModel().getColumn(2)
           .setCellEditor(new AvailableFlightsCellEditor(flights));




       add(new JScrollPane(table), BorderLayout.CENTER);




       addBtn = new JButton("Add Counter");
       removeBtn = new JButton("Remove Counter");




       addBtn.addActionListener(e -> model.addCounter());
       removeBtn.addActionListener(e -> {
           int sel = table.getSelectedRow();
           if (sel >= 0) model.removeCounter(sel);
       });




       JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
       btnPanel.add(addBtn);
       btnPanel.add(removeBtn);
       add(btnPanel, BorderLayout.SOUTH);
   }
   public List<TicketCounterConfig> getCounters() {
       return model.getCounters();
       }
}


