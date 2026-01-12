package sim.ui;


import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;


/**
* Table model for configuring checkpoints:
* Columns: [Checkpoint #, Rate (passengers/hr)]
*/
public class CheckpointTableModel extends AbstractTableModel {
   private final String[] columns = {
           "Checkpoint #", "Rate (passengers/hr)"
   };


   private final List<CheckpointConfig> checkpoints = new ArrayList<>();


   @Override
   public int getRowCount() {
       return checkpoints.size();
   }


   @Override
   public int getColumnCount() {
       return columns.length;
   }


   @Override
   public String getColumnName(int col) {
       return columns[col];
   }


   @Override
   public Class<?> getColumnClass(int col) {
       return (col == 0) ? Integer.class : Double.class;
   }


   @Override
   public boolean isCellEditable(int row, int col) {
       // Only rate is editable
       return col == 1;
   }


   @Override
   public Object getValueAt(int row, int col) {
       CheckpointConfig cfg = checkpoints.get(row);
       if (col == 0) return cfg.getId();
       return cfg.getRatePerHour();
   }


   @Override
   public void setValueAt(Object val, int row, int col) {
       if (col != 1) return;


       CheckpointConfig cfg = checkpoints.get(row);


       double rateHr = 0.0;
       if (val instanceof Number) {
           rateHr = ((Number) val).doubleValue();
       } else if (val != null) {
           try { rateHr = Double.parseDouble(val.toString().trim()); }
           catch (Exception ignored) { return; }
       }


       cfg.setRatePerHour(rateHr);
       fireTableCellUpdated(row, col);
   }


   public void addCheckpoint() {
       int id = checkpoints.size() + 1;
       checkpoints.add(new CheckpointConfig(id));
       fireTableRowsInserted(checkpoints.size() - 1, checkpoints.size() - 1);
   }


   public void removeCheckpoint(int idx) {
       if (idx < 0 || idx >= checkpoints.size()) return;
       checkpoints.remove(idx);
       for (int i = 0; i < checkpoints.size(); i++) {
           checkpoints.get(i).setId(i + 1);
       }
       fireTableDataChanged();
   }


   public List<CheckpointConfig> getCheckpoints() {
       return new ArrayList<>(checkpoints);
   }
}


