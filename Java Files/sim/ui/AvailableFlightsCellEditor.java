// AvailableFlightsCellEditor.java
package sim.ui;




import sim.model.Flight;




import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.TableCellEditor;




import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;




import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;




/**
* TableCellEditor that allows selecting a subset of flights via checkboxes.
*/
public class AvailableFlightsCellEditor extends AbstractCellEditor implements TableCellEditor {
   private final JButton button = new JButton();
   private Set<Flight> selectedFlights = new HashSet<>();
   private final List<Flight> allFlights;
   private JDialog dialog;




   public AvailableFlightsCellEditor(List<Flight> allFlights) {
       this.allFlights = allFlights;
       button.addActionListener(e -> showSelectionDialog());
   }




   @Override
   public Object getCellEditorValue() {
       return selectedFlights;
   }




   @Override
   public Component getTableCellEditorComponent(JTable table,
                                               Object value,
                                               boolean isSelected,
                                               int row,
                                               int column) {
       if (value instanceof Set) {
           //noinspection unchecked
           selectedFlights = new HashSet<>((Set<Flight>) value);
       } else {
           selectedFlights = new HashSet<>();
       }
       button.setText(selectedFlights.isEmpty()
                       ? "All flights"
                       : String.valueOf(selectedFlights.size()));
       return button;
   }




   private void showSelectionDialog() {
       Frame owner = JOptionPane.getFrameForComponent(button);
       dialog = new JDialog(owner, "Select Allowed Flights", true);
       dialog.setLayout(new BorderLayout());




       JPanel listPanel = new JPanel(new GridLayout(0, 1));
       Map<Flight, JCheckBox> checkMap = new LinkedHashMap<>();
       JCheckBox allBox = new JCheckBox("All flights");
       allBox.setSelected(selectedFlights.isEmpty());
       allBox.addActionListener(ev -> {
           boolean sel = allBox.isSelected();
           checkMap.values().forEach(cb -> {
               cb.setEnabled(!sel);
               if (sel) cb.setSelected(false);
           });
       });
       listPanel.add(allBox);




       for (Flight f : allFlights) {
           JCheckBox cb = new JCheckBox(f.getFlightNumber());
           cb.setSelected(selectedFlights.contains(f));
           checkMap.put(f, cb);
           listPanel.add(cb);
       }




       dialog.add(new JScrollPane(listPanel), BorderLayout.CENTER);




       JPanel btnPanel = new JPanel();
       JButton ok = new JButton("OK");
       ok.addActionListener(ev -> {
           if (allBox.isSelected()) {
               selectedFlights.clear();
           } else {
               selectedFlights.clear();
               checkMap.forEach((flight, cb) -> {
                   if (cb.isSelected()) selectedFlights.add(flight);
               });
           }
           button.setText(selectedFlights.isEmpty()
                           ? "All flights"
                           : String.valueOf(selectedFlights.size()));
           dialog.dispose();
           fireEditingStopped();
       });
       btnPanel.add(ok);




       JButton cancel = new JButton("Cancel");
       cancel.addActionListener(ev -> {
           dialog.dispose();
           fireEditingCanceled();
       });
       btnPanel.add(cancel);




       dialog.add(btnPanel, BorderLayout.SOUTH);
       dialog.pack();
       dialog.setLocationRelativeTo(button);
       dialog.setVisible(true);
   }
}
