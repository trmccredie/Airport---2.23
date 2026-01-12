package sim.ui;


import javax.swing.*;
import java.awt.*;


public class FloorplanGlobalInputPanel extends JPanel {
   private final JTextField percentInPersonField;
   private final JTextField arrivalSpanField;
   private final JTextField walkSpeedField;
   private final JTextField intervalField;


   public FloorplanGlobalInputPanel() {
       setLayout(new GridLayout(4, 2, 5, 5));


       percentInPersonField = addLabeledField("% In Person (0-1):");
       arrivalSpanField     = addLabeledField("Arrival Span (min):");
       walkSpeedField       = addLabeledField("Walk Speed (m/s):");
       intervalField        = addLabeledField("Interval (min):");


       percentInPersonField.setText("0.4");
       arrivalSpanField.setText("120");
       walkSpeedField.setText("1.34");


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


   public int getIntervalMinutes() {
       return 1;
   }


   public double getWalkSpeedMps() {
       double v = 1.34;
       try {
           v = Double.parseDouble(walkSpeedField.getText().trim());
       } catch (Exception ignored) {}
       if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) v = 1.34;
       return Math.max(0.2, Math.min(6.0, v));
   }
}


