package sim.ui;


import javax.swing.*;
import java.awt.*;
import java.util.List;


/**
* Panel for adding/removing checkpoints with per-checkpoint processing rates (passengers/hr).
* No per-flight restrictions.
*/
public class CheckpointPanel extends JPanel {
   private final JTable table;
   private final CheckpointTableModel model;
   private final JButton addBtn;
   private final JButton removeBtn;


   public CheckpointPanel() {
       super(new BorderLayout());
       model = new CheckpointTableModel();
       table = new JTable(model);


       add(new JScrollPane(table), BorderLayout.CENTER);


       addBtn = new JButton("Add Checkpoint");
       removeBtn = new JButton("Remove Checkpoint");


       addBtn.addActionListener(e -> model.addCheckpoint());
       removeBtn.addActionListener(e -> {
           int sel = table.getSelectedRow();
           if (sel >= 0) model.removeCheckpoint(sel);
       });


       JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
       btnPanel.add(addBtn);
       btnPanel.add(removeBtn);
       add(btnPanel, BorderLayout.SOUTH);
   }


   public List<CheckpointConfig> getCheckpoints() {
       return model.getCheckpoints();
   }
}


