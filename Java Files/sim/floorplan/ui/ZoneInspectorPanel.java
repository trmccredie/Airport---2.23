package sim.floorplan.ui;

import sim.floorplan.model.Zone;
import sim.floorplan.model.ZoneType;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashSet;
import java.util.Set;

public class ZoneInspectorPanel extends JPanel {
    private Zone zone;
    private boolean locked = false;

    private Runnable onZoneChanged;

    private final JLabel header = new JLabel("No zone selected");

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    private static final String CARD_NONE = "none";
    private static final String CARD_TICKET = "ticket";
    private static final String CARD_CHECKPOINT = "checkpoint";
    private static final String CARD_HOLDROOM = "holdroom";
    private static final String CARD_OTHER = "other";

    // Ticket card
    private final JTextField ticketRatePerMinField = new JTextField();
    private final JTextField ticketAllowedFlightsField = new JTextField();
    private final JButton ticketApplyBtn = new JButton("Apply");

    // Checkpoint card
    private final JTextField checkpointRatePerHourField = new JTextField();
    private final JButton checkpointApplyBtn = new JButton("Apply");

    // Holdroom card
    private final JTextField holdAllowedFlightsField = new JTextField();
    private final JButton holdApplyBtn = new JButton("Apply");

    // Other
    private final JTextArea otherInfo = new JTextArea();

    public ZoneInspectorPanel() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createTitledBorder("Zone Inspector"));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        add(header, BorderLayout.NORTH);

        cardPanel.add(buildNoneCard(), CARD_NONE);
        cardPanel.add(buildTicketCard(), CARD_TICKET);
        cardPanel.add(buildCheckpointCard(), CARD_CHECKPOINT);
        cardPanel.add(buildHoldroomCard(), CARD_HOLDROOM);
        cardPanel.add(buildOtherCard(), CARD_OTHER);

        add(cardPanel, BorderLayout.CENTER);

        setZone(null);
        setLocked(false);
    }

    public void setOnZoneChanged(Runnable r) {
        this.onZoneChanged = r;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        updateEnabledState();
    }

    public void setZone(Zone z) {
        this.zone = z;

        if (zone == null) {
            header.setText("No zone selected");
            cards.show(cardPanel, CARD_NONE);
            updateEnabledState();
            return;
        }

        ZoneType t = zone.getType();
        String id = zone.getId();
        header.setText((t == null ? "Zone" : t.getLabel()) + (id == null ? "" : (" (" + id + ")")));

        if (t == ZoneType.TICKET_COUNTER) {
            double r = zone.hasTicketRatePerMinute() ? zone.getTicketRatePerMinute() : Double.NaN;
            ticketRatePerMinField.setText(Double.isNaN(r) ? "" : trimNice(r));
            ticketAllowedFlightsField.setText(String.join(", ", zone.getAllowedFlightNumbers()));
            cards.show(cardPanel, CARD_TICKET);

        } else if (t == ZoneType.CHECKPOINT) {
            double r = zone.hasCheckpointRatePerHour() ? zone.getCheckpointRatePerHour() : Double.NaN;
            checkpointRatePerHourField.setText(Double.isNaN(r) ? "" : trimNice(r));
            cards.show(cardPanel, CARD_CHECKPOINT);

        } else if (t == ZoneType.HOLDROOM) {
            holdAllowedFlightsField.setText(String.join(", ", zone.getAllowedFlightNumbers()));
            cards.show(cardPanel, CARD_HOLDROOM);

        } else {
            otherInfo.setText(buildOtherInfo(zone));
            otherInfo.setCaretPosition(0);
            cards.show(cardPanel, CARD_OTHER);
        }

        updateEnabledState();
    }

    private JComponent buildNoneCard() {
        JTextArea a = new JTextArea("Select a zone in the editor to view/edit per-zone settings.");
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setEditable(false);
        a.setOpaque(false);
        return a;
    }

    private JComponent buildTicketCard() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        p.add(label("Ticket counter rate (passengers / minute):"));
        p.add(ticketRatePerMinField);
        p.add(Box.createVerticalStrut(10));

        p.add(label("Allowed flights (comma-separated flight numbers). Leave blank = accepts ALL:"));
        p.add(ticketAllowedFlightsField);
        p.add(Box.createVerticalStrut(10));

        ticketApplyBtn.addActionListener(e -> applyTicket());
        p.add(ticketApplyBtn);

        return p;
    }

    private JComponent buildCheckpointCard() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        p.add(label("Checkpoint rate (passengers / hour):"));
        p.add(checkpointRatePerHourField);
        p.add(Box.createVerticalStrut(10));

        checkpointApplyBtn.addActionListener(e -> applyCheckpoint());
        p.add(checkpointApplyBtn);

        return p;
    }

    private JComponent buildHoldroomCard() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        p.add(label("Allowed flights (comma-separated flight numbers). Leave blank = accepts ALL:"));
        p.add(holdAllowedFlightsField);
        p.add(Box.createVerticalStrut(10));

        holdApplyBtn.addActionListener(e -> applyHoldroom());
        p.add(holdApplyBtn);

        return p;
    }

    private JComponent buildOtherCard() {
        otherInfo.setEditable(false);
        otherInfo.setLineWrap(true);
        otherInfo.setWrapStyleWord(true);
        otherInfo.setBackground(new Color(245, 245, 245));
        return new JScrollPane(otherInfo);
    }

    private JLabel label(String s) {
        JLabel l = new JLabel(s);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private void updateEnabledState() {
        boolean editable = (zone != null) && !locked;

        ticketRatePerMinField.setEnabled(editable);
        ticketAllowedFlightsField.setEnabled(editable);
        ticketApplyBtn.setEnabled(editable);

        checkpointRatePerHourField.setEnabled(editable);
        checkpointApplyBtn.setEnabled(editable);

        holdAllowedFlightsField.setEnabled(editable);
        holdApplyBtn.setEnabled(editable);
    }

    private void applyTicket() {
        if (zone == null || zone.getType() != ZoneType.TICKET_COUNTER) return;

        Double rate = parseNullableDouble(ticketRatePerMinField.getText());
        if (rate != null && rate < 0) {
            Toolkit.getDefaultToolkit().beep();
            JOptionPane.showMessageDialog(this, "Rate must be >= 0.", "Invalid Rate", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (rate == null) zone.setTicketRatePerMinute(Double.NaN);
        else zone.setTicketRatePerMinute(rate);

        zone.setAllowedFlightNumbers(parseCsv(ticketAllowedFlightsField.getText()));
        fireChanged();
    }

    private void applyCheckpoint() {
        if (zone == null || zone.getType() != ZoneType.CHECKPOINT) return;

        Double rate = parseNullableDouble(checkpointRatePerHourField.getText());
        if (rate != null && rate < 0) {
            Toolkit.getDefaultToolkit().beep();
            JOptionPane.showMessageDialog(this, "Rate must be >= 0.", "Invalid Rate", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (rate == null) zone.setCheckpointRatePerHour(Double.NaN);
        else zone.setCheckpointRatePerHour(rate);

        fireChanged();
    }

    private void applyHoldroom() {
        if (zone == null || zone.getType() != ZoneType.HOLDROOM) return;

        zone.setAllowedFlightNumbers(parseCsv(holdAllowedFlightsField.getText()));
        fireChanged();
    }

    private void fireChanged() {
        setZone(zone);
        if (onZoneChanged != null) onZoneChanged.run();
    }

    private static Double parseNullableDouble(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try { return Double.parseDouble(t); }
        catch (Exception e) { return null; }
    }

    private static Set<String> parseCsv(String s) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (s == null) return out;

        String[] parts = s.split("[,;\\n]");
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String trimNice(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        long iv = (long) v;
        if (Math.abs(v - iv) < 1e-9) return String.valueOf(iv);
        String s = String.valueOf(v);
        if (s.contains(".")) {
            while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String buildOtherInfo(Zone z) {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: ").append(z.getType() == null ? "?" : z.getType().getLabel()).append("\n");
        sb.append("ID: ").append(z.getId() == null ? "?" : z.getId()).append("\n\n");

        if (z.getAnchor() != null) {
            sb.append("Anchor: (").append(z.getAnchor().x).append(", ").append(z.getAnchor().y).append(")\n");
        }
        if (z.getArea() != null && z.getArea().npoints >= 3) {
            sb.append("Area points: ").append(z.getArea().npoints).append("\n");
        }

        sb.append("\nNo editable per-zone settings for this zone type yet.");
        return sb.toString();
    }
} 