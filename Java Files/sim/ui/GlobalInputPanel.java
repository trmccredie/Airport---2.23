package sim.ui;


import javax.swing.*;
import java.awt.*;


public class GlobalInputPanel extends JPanel {
   private final JTextField percentInPersonField;
   private final JTextField arrivalSpanField;
   private final JTextField transitDelayField;
   private final JTextField intervalField;


   public GlobalInputPanel() {
       setLayout(new GridLayout(4, 2, 5, 5));


       percentInPersonField = addLabeledField("% In Person (0-1):");
       arrivalSpanField     = addLabeledField("Arrival Span (min):");
       transitDelayField    = addLabeledField("Transit Delay (min):");
       intervalField        = addLabeledField("Interval (min):");


       // defaults
       percentInPersonField.setText("0.4");
       arrivalSpanField.setText("120");
       transitDelayField.setText("2");


       // your current design keeps interval fixed at 1
       intervalField.setText("1");
       intervalField.setEditable(false);
       intervalField.setFocusable(false);
       intervalField.setBackground(new Color(200, 200, 200));
       intervalField.setForeground(Color.BLACK);
   }


   private JTextField addLabeledField(String label) {
       add(new JLabel(label));
       JTextField field = new JTextField();
       add(field);
       return field;
   }


   public double getPercentInPerson() {
       return Double.parseDouble(percentInPersonField.getText());
   }


   public int getArrivalSpanMinutes() {
       return Integer.parseInt(arrivalSpanField.getText());
   }


   public int getTransitDelayMinutes() {
       return Integer.parseInt(transitDelayField.getText());
   }


   /** Always returns 1 (per your current UI design). */
   public int getIntervalMinutes() {
       return 1;
   }
}
