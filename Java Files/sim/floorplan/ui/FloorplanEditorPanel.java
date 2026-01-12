package sim.floorplan.ui;

import sim.floorplan.io.FloorplanProjectIO;
import sim.floorplan.io.PdfFloorplanImporter;
import sim.floorplan.mask.AutoMaskGenerator;
import sim.floorplan.model.FloorplanProject;
import sim.floorplan.model.WalkMask;
import sim.floorplan.model.Zone;
import sim.floorplan.model.ZoneType;
import sim.floorplan.path.AStarRouter;
import sim.floorplan.sim.FloorplanTravelTimeProvider;
import sim.floorplan.sim.TravelTimeProvider;
import sim.model.ArrivalCurveConfig;
import sim.model.Flight;
import sim.service.SimulationEngine;
import sim.ui.CheckpointConfig;
import sim.ui.DataTableFrame;
import sim.ui.HoldRoomConfig;
import sim.ui.SimulationFrame;
import sim.ui.TicketCounterConfig;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;

public class FloorplanEditorPanel extends JPanel {

    private final FloorplanCanvas canvas = new FloorplanCanvas();

    // ✅ inspector for per-zone metadata
    private final ZoneInspectorPanel inspector = new ZoneInspectorPanel();

    // Project state (Milestone 4)
    private final FloorplanProject project = new FloorplanProject();
    private boolean locked = false;
    private List<String> lastValidationErrors = new ArrayList<>();

    // PDF controls
    private final JButton uploadBtn = new JButton("Upload PDF");
    private final JSpinner pageSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
    private final JComboBox<Integer> dpiCombo = new JComboBox<>(new Integer[]{150, 200, 300});
    private final JButton renderBtn = new JButton("Render");

    // ✅ Tier 2: scale UI
    private final JSpinner metersPerPixelSpinner = new JSpinner(new SpinnerNumberModel(0.05, 0.000001, 10.0, 0.001));
    private final JToggleButton measureScaleBtn = new JToggleButton("Measure Scale");

    // scale measure state
    private Point scaleA = null;

    // Auto-mask controls
    private final JSlider thresholdSlider = new JSlider(0, 255, 200);
    private final JCheckBox autoThrToggle = new JCheckBox("Auto Thr (Otsu)", false);
    private final JSpinner inflateSpinner = new JSpinner(new SpinnerNumberModel(6, 0, 60, 1));

    // outside removal controls
    private final JCheckBox removeOutsideToggle = new JCheckBox("Remove Outside", true);
    private final JSpinner sealGapsSpinner = new JSpinner(new SpinnerNumberModel(14, 0, 80, 1));

    private final JButton autoMaskBtn = new JButton("Auto Mask");

    // Tools (mask)
    private final JToggleButton selectToolBtn = new JToggleButton("Select");
    private final JToggleButton panToolBtn = new JToggleButton("Pan");
    private final JToggleButton walkToolBtn = new JToggleButton("Paint Walkable");
    private final JToggleButton blockToolBtn = new JToggleButton("Paint Blocked");
    private final JToggleButton polyFillBtn = new JToggleButton("Poly Fill Walkable");

    // ✅ Test Route tool controls
    private final JToggleButton testRouteBtn = new JToggleButton("Test Route");
    private final JSpinner routeStrideSpinner = new JSpinner(new SpinnerNumberModel(4, 1, 40, 1));
    private final JButton clearRouteBtn = new JButton("Clear Route");

    // Tools (zones)
    private final JToggleButton placeSpawnBtn = new JToggleButton("Place Spawn");
    private final JToggleButton placeTicketBtn = new JToggleButton("Place Ticket");
    private final JToggleButton placeCheckpointBtn = new JToggleButton("Place Checkpoint");
    private final JToggleButton placeHoldroomBtn = new JToggleButton("Place Holdroom");

    private final JToggleButton drawTicketQueueBtn = new JToggleButton("Draw Ticket Queue");
    private final JToggleButton drawCheckpointQueueBtn = new JToggleButton("Draw Checkpoint Queue");
    private final JToggleButton drawCheckpointAreaBtn = new JToggleButton("Draw Checkpoint Area"); // NEW
    private final JToggleButton drawHoldroomAreaBtn = new JToggleButton("Draw Holdroom Area");

    private final JButton deleteSelectedBtn = new JButton("Delete Selected");
    private final JButton validateLockBtn = new JButton("Validate & Lock");
    private final JButton unlockBtn = new JButton("Unlock (Edit)");

    // ✅ Save/Load FloorplanProject
    private final JButton saveProjectBtn = new JButton("Save Project");
    private final JButton loadProjectBtn = new JButton("Load Project");

    private final JSpinner brushSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 120, 1));
    private final JButton resetViewBtn = new JButton("Reset View");
    private final JCheckBox overlayToggle = new JCheckBox("Mask Overlay", true);

    private final JLabel statusLabel = new JLabel("No project loaded.");
    private final JLabel helpLabel = new JLabel(" ");

    private File currentPdf;
    private BufferedImage currentImage;

    /**
     * ✅ IMPORTANT:
     * Keep exactly ONE runtime WalkMask instance during editing.
     * Canvas edits it. Validation/routing must see the same instance via project.getMask().
     */
    private WalkMask currentMask;

    private Zone selectedZone;

    // ✅ Test route state
    private Point routeStart = null;
    private Point routeEnd = null;
    private List<Point> routePath = null;
    private SwingWorker<List<Point>, Void> routeWorker = null;

    // ==========================================================
    // ✅ FLOORPLAN SIM (Editor side)
    // ==========================================================
    // NOTE: The "Floorplan Sim Settings..." BUTTON has been REMOVED per your request.
    // Any floorplan sim parameters should be edited in your Floorplan Simulation tab (separate UI).
    private final JButton startFloorplanSimBtn = new JButton("Start Floorplan Simulation");

    // Floorplan simulation state (independent backing model)
    // (Your Floorplan Simulation tab should write into this state.)
    private final FloorplanSimState floorplanSimState = new FloorplanSimState();

    public FloorplanEditorPanel() {
        super(new BorderLayout(10, 10));

        add(buildControlsNorth(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottomStatus(), BorderLayout.SOUTH);

        dpiCombo.setSelectedItem(200);

        thresholdSlider.setPaintTicks(true);
        thresholdSlider.setPaintLabels(true);
        thresholdSlider.setMajorTickSpacing(50);
        thresholdSlider.setMinorTickSpacing(10);
        thresholdSlider.setPreferredSize(new Dimension(220, thresholdSlider.getPreferredSize().height));

        // default tool
        panToolBtn.setSelected(true);
        canvas.setTool(FloorplanCanvas.Tool.PAN);
        canvas.setBrushRadiusPx(((Number) brushSpinner.getValue()).intValue());

        // ✅ wire canvas -> editor on mask edits
        canvas.setOnMaskEdited(this::onMaskEdited);

        // inspector callback
        inspector.setOnZoneChanged(() -> {
            syncZonesToCanvas();
            updateStatusSelection();
        });

        // wire canvas callbacks
        canvas.setOnPointAction((tool, pt) -> handlePointTool(tool, pt));
        canvas.setOnPolygonFinished((tool, poly) -> handlePolygonTool(tool, poly));
        canvas.setOnSelectionChanged(z -> {
            selectedZone = z;
            inspector.setZone(z);
            updateStatusSelection();
        });
        canvas.setOnDeleteRequested(this::deleteSelected);

        hookEvents();
        updateHelp();
        syncZonesToCanvas();
        setEditingEnabled(true);

        // init scale into project
        project.setMetersPerPixel(((Number) metersPerPixelSpinner.getValue()).doubleValue());
    }

    /** Fix “buttons cut off”: wrap controls in a horizontal scroller, split into rows. */
    private JComponent buildControlsNorth() {
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        controls.add(buildRowPdf());
        controls.add(buildRowMask());
        controls.add(buildRowToolsMask());
        controls.add(buildRowToolsZones());

        JScrollPane scroller = new JScrollPane(
                controls,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        scroller.setBorder(BorderFactory.createEmptyBorder());
        scroller.getHorizontalScrollBar().setUnitIncrement(16);
        return scroller;
    }

    private JComponent buildRowPdf() {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        r.add(uploadBtn);

        r.add(new JLabel("Page:"));
        r.add(pageSpinner);

        r.add(new JLabel("DPI:"));
        r.add(dpiCombo);

        r.add(renderBtn);
        r.add(Box.createHorizontalStrut(12));
        r.add(overlayToggle);

        // ✅ scale
        r.add(Box.createHorizontalStrut(14));
        r.add(new JLabel("Scale (m/px):"));
        metersPerPixelSpinner.setMaximumSize(new Dimension(110, 28));
        r.add(metersPerPixelSpinner);
        r.add(measureScaleBtn);

        return r;
    }

    private JComponent buildRowMask() {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));

        r.add(new JLabel("Threshold:"));
        r.add(thresholdSlider);
        r.add(autoThrToggle);

        r.add(Box.createHorizontalStrut(10));
        r.add(new JLabel("Inflate(px):"));
        r.add(inflateSpinner);

        r.add(Box.createHorizontalStrut(10));
        r.add(removeOutsideToggle);

        r.add(new JLabel("Seal gaps(px):"));
        r.add(sealGapsSpinner);

        r.add(autoMaskBtn);
        return r;
    }

    private JComponent buildRowToolsMask() {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));

        ButtonGroup tools = getUnifiedToolGroup();
        tools.add(selectToolBtn);
        tools.add(panToolBtn);
        tools.add(walkToolBtn);
        tools.add(blockToolBtn);
        tools.add(polyFillBtn);
        tools.add(testRouteBtn);
        tools.add(measureScaleBtn);

        r.add(new JLabel("Tools:"));
        r.add(selectToolBtn);
        r.add(panToolBtn);
        r.add(walkToolBtn);
        r.add(blockToolBtn);
        r.add(polyFillBtn);

        r.add(Box.createHorizontalStrut(10));
        r.add(testRouteBtn);
        r.add(new JLabel("Stride(px):"));
        r.add(routeStrideSpinner);
        r.add(clearRouteBtn);

        r.add(Box.createHorizontalStrut(12));
        r.add(new JLabel("Brush(px):"));
        r.add(brushSpinner);

        r.add(resetViewBtn);
        return r;
    }

    private JComponent buildRowToolsZones() {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));

        ButtonGroup tools = getUnifiedToolGroup();
        tools.add(placeSpawnBtn);
        tools.add(placeTicketBtn);
        tools.add(placeCheckpointBtn);
        tools.add(placeHoldroomBtn);

        tools.add(drawTicketQueueBtn);
        tools.add(drawCheckpointQueueBtn);
        tools.add(drawCheckpointAreaBtn);   // NEW
        tools.add(drawHoldroomAreaBtn);

        r.add(new JLabel("Zones:"));
        r.add(placeSpawnBtn);
        r.add(placeTicketBtn);
        r.add(placeCheckpointBtn);
        r.add(placeHoldroomBtn);

        r.add(Box.createHorizontalStrut(8));
        r.add(drawTicketQueueBtn);
        r.add(drawCheckpointQueueBtn);
        r.add(drawCheckpointAreaBtn);       // NEW
        r.add(drawHoldroomAreaBtn);

        r.add(Box.createHorizontalStrut(12));
        r.add(deleteSelectedBtn);

        r.add(Box.createHorizontalStrut(12));
        r.add(validateLockBtn);
        r.add(unlockBtn);

        r.add(Box.createHorizontalStrut(12));
        r.add(saveProjectBtn);
        r.add(loadProjectBtn);

        // ✅ Floorplan sim launch (settings button removed)
        r.add(Box.createHorizontalStrut(16));
        r.add(startFloorplanSimBtn);

        return r;
    }

    // One unified group so only one tool is active at a time
    private ButtonGroup unifiedToolGroup;
    private ButtonGroup getUnifiedToolGroup() {
        if (unifiedToolGroup == null) unifiedToolGroup = new ButtonGroup();
        return unifiedToolGroup;
    }

    private JComponent buildCenter() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(BorderFactory.createTitledBorder("Floorplan Preview"));

        canvas.setPreferredSize(new Dimension(900, 600));

        JScrollPane scroller = new JScrollPane(canvas,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroller, inspector);
        split.setResizeWeight(0.80);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);

        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.78));

        wrap.add(split, BorderLayout.CENTER);
        return wrap;
    }

    private JComponent buildBottomStatus() {
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        helpLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.add(statusLabel);
        bottom.add(Box.createVerticalStrut(4));
        bottom.add(helpLabel);
        return bottom;
    }

    private void hookEvents() {
        overlayToggle.addActionListener(e -> canvas.setOverlayEnabled(overlayToggle.isSelected()));

        // Tool switching
        selectToolBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.SELECT); updateHelp(); });
        panToolBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.PAN); updateHelp(); });

        walkToolBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.PAINT_WALKABLE); updateHelp(); });
        blockToolBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.PAINT_BLOCKED); updateHelp(); });
        polyFillBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.POLY_FILL_WALKABLE); updateHelp(); });

        testRouteBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.TEST_ROUTE); updateHelp(); });
        measureScaleBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.MEASURE_SCALE); updateHelp(); });

        placeSpawnBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.PLACE_SPAWN); updateHelp(); });
        placeTicketBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.PLACE_TICKET_COUNTER); updateHelp(); });
        placeCheckpointBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.PLACE_CHECKPOINT); updateHelp(); });
        placeHoldroomBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.PLACE_HOLDROOM); updateHelp(); });

        drawTicketQueueBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.DRAW_TICKET_QUEUE); updateHelp(); });
        drawCheckpointQueueBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.DRAW_CHECKPOINT_QUEUE); updateHelp(); });
        drawCheckpointAreaBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.DRAW_CHECKPOINT_AREA); updateHelp(); }); // NEW
        drawHoldroomAreaBtn.addActionListener(e -> { scaleA = null; canvas.setTool(FloorplanCanvas.Tool.DRAW_HOLDROOM_AREA); updateHelp(); });

        brushSpinner.addChangeListener(e -> canvas.setBrushRadiusPx(((Number) brushSpinner.getValue()).intValue()));
        resetViewBtn.addActionListener(e -> canvas.resetView());

        clearRouteBtn.addActionListener(e -> clearRoute());

        // scale changes apply immediately to project
        metersPerPixelSpinner.addChangeListener(e -> {
            double v = ((Number) metersPerPixelSpinner.getValue()).doubleValue();
            project.setMetersPerPixel(v);
            statusLabel.setText("Scale set: " + v + " m/px");
        });

        uploadBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select floorplan PDF");
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

            int res = chooser.showOpenDialog(FloorplanEditorPanel.this);
            if (res == JFileChooser.APPROVE_OPTION) {
                currentPdf = chooser.getSelectedFile();
                statusLabel.setText("Selected PDF: " + currentPdf.getName() + " (click Render)");
            }
        });

        renderBtn.addActionListener(e -> doRender());

        autoMaskBtn.addActionListener(e -> {
            if (currentImage == null) {
                JOptionPane.showMessageDialog(this, "Render a page first.", "Auto Mask", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (locked) {
                JOptionPane.showMessageDialog(this, "Unlock to edit mask.", "Locked", JOptionPane.WARNING_MESSAGE);
                return;
            }
            rebuildMaskFromControls();
        });

        deleteSelectedBtn.addActionListener(e -> deleteSelected());
        validateLockBtn.addActionListener(e -> validateAndLock());

        unlockBtn.addActionListener(e -> {
            if (!locked) return;
            int ok = JOptionPane.showConfirmDialog(this,
                    "Unlocking allows edits. This may invalidate the floorplan.\n\nUnlock now?",
                    "Unlock Floorplan",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (ok == JOptionPane.OK_OPTION) unlockForEditing();
        });

        saveProjectBtn.addActionListener(e -> doSaveProject());
        loadProjectBtn.addActionListener(e -> doLoadProject());

        // ✅ Start floorplan sim (settings button removed)
        startFloorplanSimBtn.addActionListener(e -> startFloorplanSimulation());
    }

    private void updateHelp() {
        FloorplanCanvas.Tool t = canvas.getTool();

        if (t == FloorplanCanvas.Tool.POLY_FILL_WALKABLE) {
            helpLabel.setText("Poly Fill: click points, double-click/Enter/right-click to close & fill. Backspace=undo, Esc=cancel. Right-drag pans.");
        } else if (t == FloorplanCanvas.Tool.DRAW_TICKET_QUEUE
                || t == FloorplanCanvas.Tool.DRAW_CHECKPOINT_QUEUE
                || t == FloorplanCanvas.Tool.DRAW_CHECKPOINT_AREA
                || t == FloorplanCanvas.Tool.DRAW_HOLDROOM_AREA) {
            helpLabel.setText("Draw Area: click points, double-click/Enter/right-click to close. Backspace=undo, Esc=cancel. Select matching anchor first.");
        } else if (t == FloorplanCanvas.Tool.PLACE_SPAWN
                || t == FloorplanCanvas.Tool.PLACE_TICKET_COUNTER
                || t == FloorplanCanvas.Tool.PLACE_CHECKPOINT
                || t == FloorplanCanvas.Tool.PLACE_HOLDROOM) {
            helpLabel.setText("Place Anchor: left-click to place (snaps to nearest walkable if needed). Use Select to choose anchors. Right-drag pans, wheel zoom.");
        } else if (t == FloorplanCanvas.Tool.TEST_ROUTE) {
            helpLabel.setText("Test Route: click START then END. A* runs on the walk mask and draws the path. Larger stride = faster but less precise.");
        } else if (t == FloorplanCanvas.Tool.MEASURE_SCALE) {
            helpLabel.setText("Measure Scale: click point A then point B. You’ll be prompted for meters; scale (m/px) will be set.");
        } else if (t == FloorplanCanvas.Tool.SELECT) {
            helpLabel.setText("Select: click an anchor (near dot) or click inside an area polygon. Delete key removes selected. Right-drag pans.");
        } else if (t == FloorplanCanvas.Tool.PAINT_WALKABLE || t == FloorplanCanvas.Tool.PAINT_BLOCKED) {
            helpLabel.setText("Brush: left-drag to paint. Right-drag to pan. Mouse wheel zoom.");
        } else {
            helpLabel.setText("Pan: right-drag (or Pan tool). Mouse wheel zoom.");
        }
    }

    private void doRender() {
        List<String> errors = new ArrayList<>();
        if (currentPdf == null) errors.add("No PDF selected. Click 'Upload PDF' first.");

        Integer dpi = (Integer) dpiCombo.getSelectedItem();
        if (dpi == null) errors.add("DPI not selected.");
        int pageIndex = ((Number) pageSpinner.getValue()).intValue();

        if (!errors.isEmpty()) {
            showErrors(errors);
            return;
        }

        try {
            BufferedImage img = PdfFloorplanImporter.renderPage(currentPdf, pageIndex, dpi);
            currentImage = img;

            canvas.setImage(currentImage);

            // reset lock + zones on new render (coords likely changed)
            locked = false;
            selectedZone = null;
            inspector.setZone(null);
            lastValidationErrors = new ArrayList<>();
            if (project.getZones() != null) project.getZones().clear();

            clearRoute();

            // reset scale measurement overlay state
            scaleA = null;
            canvas.clearMeasureSegment();

            // update project metadata
            project.setPdfFile(currentPdf);
            project.setPageIndex(pageIndex);
            project.setDpi(dpi);
            project.setFloorplanImage(currentImage);

            rebuildMaskFromControls();

            canvas.setOverlayEnabled(overlayToggle.isSelected());
            syncZonesToCanvas();
            canvas.setLocked(false);
            setEditingEnabled(true);

            statusLabel.setText("Rendered: " + currentPdf.getName()
                    + " | page " + pageIndex + " | " + dpi + " DPI | mask/zones editable");

        } catch (Exception ex) {
            ex.printStackTrace();
            errors.add("Failed to render PDF: " + ex.getMessage());
            showErrors(errors);
        }
    }

    /**
     * When rebuilding mask, keep project.getMask() pointing at the same instance canvas edits.
     */
    private void rebuildMaskFromControls() {
        int thr = thresholdSlider.getValue();
        int inflatePx = ((Number) inflateSpinner.getValue()).intValue();
        int sealPx = ((Number) sealGapsSpinner.getValue()).intValue();

        try {
            AutoMaskGenerator.Params p = new AutoMaskGenerator.Params();
            p.threshold = thr;
            p.autoThreshold = autoThrToggle.isSelected();
            p.inflatePx = inflatePx;
            p.removeOutside = removeOutsideToggle.isSelected();
            p.sealGapsPx = sealPx;

            currentMask = AutoMaskGenerator.generate(currentImage, p);
            canvas.setMask(currentMask);

            // ✅ CRITICAL: no copy here
            project.setMask(currentMask);

            clearRoute();

            statusLabel.setText("Mask ready | thr " + (p.autoThreshold ? "AUTO" : thr)
                    + " | inflate " + inflatePx + "px | removeOutside=" + p.removeOutside
                    + " | sealGaps " + sealPx + "px");
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "Auto-mask failed: " + ex.getMessage(),
                    "Auto Mask",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showErrors(List<String> errors) {
        JOptionPane.showMessageDialog(
                this,
                String.join("\n", errors),
                "Floorplan Editor",
                JOptionPane.WARNING_MESSAGE
        );
    }

    // ==========================================================
    // Save / Load FloorplanProject
    // ==========================================================

    private void doSaveProject() {
        try {
            List<String> pre = validateProject();
            if (!pre.isEmpty()) {
                showErrors(pre);
                return;
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save Floorplan Project (*.fsp)");
            chooser.setSelectedFile(new File("floorplan.fsp"));

            int res = chooser.showSaveDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) return;

            project.setPdfFile(currentPdf);
            project.setPageIndex(((Number) pageSpinner.getValue()).intValue());
            project.setDpi((Integer) dpiCombo.getSelectedItem());
            project.setFloorplanImage(currentImage);

            project.setMask(currentMask);
            project.setMetersPerPixel(((Number) metersPerPixelSpinner.getValue()).doubleValue());

            FloorplanProjectIO.saveToFile(project.copy(), chooser.getSelectedFile());
            statusLabel.setText("✅ Saved project: " + chooser.getSelectedFile().getName());

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "Save failed: " + ex.getMessage(),
                    "Save Project",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doLoadProject() {
        try {
            if (locked) {
                int ok = JOptionPane.showConfirmDialog(
                        this,
                        "A floorplan is currently LOCKED.\n\nLoading another project will unlock and replace it.\n\nContinue?",
                        "Load Project",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (ok != JOptionPane.OK_OPTION) return;
                locked = false;
                canvas.setLocked(false);
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Load Floorplan Project (*.fsp)");

            int res = chooser.showOpenDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) return;

            FloorplanProject loaded = FloorplanProjectIO.loadFromFile(chooser.getSelectedFile());

            currentPdf = loaded.getPdfFile();
            currentImage = loaded.getFloorplanImage();
            currentMask = loaded.getMask();

            project.setPdfFile(currentPdf);
            project.setPageIndex(loaded.getPageIndex());
            project.setDpi(loaded.getDpi());
            project.setFloorplanImage(currentImage);
            project.setMask(currentMask);

            if (project.getZones() != null) project.getZones().clear();
            if (loaded.getZones() != null && project.getZones() != null) project.getZones().addAll(loaded.getZones());

            project.setMetersPerPixel(loaded.getMetersPerPixel());
            metersPerPixelSpinner.setValue(loaded.getMetersPerPixel());

            pageSpinner.setValue(loaded.getPageIndex());
            if (loaded.getDpi() != null) dpiCombo.setSelectedItem(loaded.getDpi());

            canvas.setImage(currentImage);
            canvas.setMask(currentMask);
            canvas.setOverlayEnabled(overlayToggle.isSelected());

            clearRoute();

            scaleA = null;
            canvas.clearMeasureSegment();

            selectedZone = null;
            canvas.setSelectedZone(null);
            inspector.setZone(null);
            syncZonesToCanvas();

            locked = false;
            lastValidationErrors = new ArrayList<>();
            canvas.setLocked(false);
            setEditingEnabled(true);

            panToolBtn.setSelected(true);
            canvas.setTool(FloorplanCanvas.Tool.PAN);
            updateHelp();

            statusLabel.setText("✅ Loaded project: " + chooser.getSelectedFile().getName()
                    + (currentPdf != null ? (" | PDF link: " + currentPdf.getName()) : " | (PDF path not found; image/mask loaded)"));

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "Load failed: " + ex.getMessage(),
                    "Load Project",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // ==========================================================
    // Canvas -> editor handlers
    // ==========================================================

    private void onMaskEdited(String reason) {
        if (locked) {
            locked = false;
            canvas.setLocked(false);
            setEditingEnabled(true);
        }

        project.setMask(currentMask);
        lastValidationErrors = new ArrayList<>();
        clearRoute();
        canvas.repaint();

        if (reason != null && !reason.isEmpty()) {
            statusLabel.setText("Mask edited: " + reason + " (re-Validate & Lock before sim)");
        } else {
            statusLabel.setText("Mask edited (re-Validate & Lock before sim)");
        }
    }

    private void handlePointTool(FloorplanCanvas.Tool tool, Point imgPt) {
        if (currentMask == null || currentImage == null) return;
        if (imgPt == null) return;

        if (imgPt.x < 0 || imgPt.y < 0 || imgPt.x >= currentImage.getWidth() || imgPt.y >= currentImage.getHeight()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }

        // Measure scale works even when locked
        if (tool == FloorplanCanvas.Tool.MEASURE_SCALE) {
            handleMeasureScaleClick(imgPt);
            return;
        }

        // Test route works even when locked
        if (tool == FloorplanCanvas.Tool.TEST_ROUTE) {
            handleTestRouteClick(imgPt);
            return;
        }

        if (locked) return;

        ZoneType type = null;

        if (tool == FloorplanCanvas.Tool.PLACE_SPAWN) type = ZoneType.SPAWN;
        if (tool == FloorplanCanvas.Tool.PLACE_TICKET_COUNTER) type = ZoneType.TICKET_COUNTER;
        if (tool == FloorplanCanvas.Tool.PLACE_CHECKPOINT) type = ZoneType.CHECKPOINT;
        if (tool == FloorplanCanvas.Tool.PLACE_HOLDROOM) type = ZoneType.HOLDROOM;

        if (type == null) return;

        // snap anchor to nearest walkable
        Point placePt = imgPt;
        if (!currentMask.isWalkable(placePt.x, placePt.y)) {
            Point snapped = AStarRouter.snapToNearestWalkable(currentMask, placePt, 2, 160);
            if (snapped != null) {
                placePt = snapped;
            } else {
                Toolkit.getDefaultToolkit().beep();
                statusLabel.setText("Anchor must be on GREEN walkable pixel (no nearby walkable found).");
                return;
            }
        }

        // spawn is single; placing again moves existing instead of creating S2
        if (type == ZoneType.SPAWN) {
            Zone existingSpawn = findFirstAnchorOfType(ZoneType.SPAWN);
            if (existingSpawn != null) {
                existingSpawn.setAnchor(placePt);
                selectedZone = existingSpawn;
                canvas.setSelectedZone(existingSpawn);
                inspector.setZone(existingSpawn);
                syncZonesToCanvas();
                statusLabel.setText("Moved SPAWN " + safeId(existingSpawn) + " to (" + placePt.x + "," + placePt.y + ")");
                return;
            }
        }

        String id = nextAnchorIdFor(type);

        Zone z = Zone.anchorZone(id, type, placePt);

        // sensible defaults for per-zone variability (reflection so we don't hard-depend on Zone fields)
        if (type == ZoneType.TICKET_COUNTER) {
            Double cur = readDouble(z, "getTicketRatePerMinute", "getTicketRatePerMin", "getRatePerMinute", "getRatePerMin");
            if (cur == null || !(cur > 0)) {
                writeDouble(z, 1.0, "setTicketRatePerMinute", "setTicketRatePerMin", "setRatePerMinute", "setRatePerMin");
            }
        }
        if (type == ZoneType.CHECKPOINT) {
            Double cur = readDouble(z, "getCheckpointRatePerHour", "getRatePerHour");
            if (cur == null || !(cur > 0)) {
                writeDouble(z, 180.0, "setCheckpointRatePerHour", "setRatePerHour");
            }
        }

        if (project.getZones() != null) project.getZones().add(z);

        selectedZone = z;
        canvas.setSelectedZone(z);
        inspector.setZone(z);
        syncZonesToCanvas();

        statusLabel.setText("Placed " + type.getLabel() + " " + id + " at (" + placePt.x + "," + placePt.y + ")");
    }

    private void handleMeasureScaleClick(Point imgPt) {
        if (scaleA == null) {
            scaleA = imgPt;
            canvas.setMeasureSegment(scaleA, scaleA);
            statusLabel.setText("Scale: point A set at (" + imgPt.x + "," + imgPt.y + "). Click point B.");
            return;
        }

        Point scaleB = imgPt;
        double px = scaleA.distance(scaleB);
        if (px < 2.0) {
            Toolkit.getDefaultToolkit().beep();
            statusLabel.setText("Scale: points too close. Click farther apart.");
            return;
        }

        String s = JOptionPane.showInputDialog(
                this,
                String.format("Pixel distance: %.1f px\n\nEnter the real-world distance between these points (meters):", px),
                "Set Scale",
                JOptionPane.QUESTION_MESSAGE
        );
        if (s == null) {
            scaleA = null;
            return;
        }

        double meters;
        try {
            meters = Double.parseDouble(s.trim());
        } catch (Exception ex) {
            Toolkit.getDefaultToolkit().beep();
            statusLabel.setText("Scale not set (invalid meters).");
            scaleA = null;
            return;
        }

        if (!(Double.isFinite(meters) && meters > 0)) {
            Toolkit.getDefaultToolkit().beep();
            statusLabel.setText("Scale not set (meters must be > 0).");
            scaleA = null;
            return;
        }

        double mPerPx = meters / px;
        metersPerPixelSpinner.setValue(mPerPx);
        project.setMetersPerPixel(mPerPx);

        canvas.setMeasureSegment(scaleA, scaleB);
        statusLabel.setText(String.format("✅ Scale set: %.6f m/px (%.1f px = %.3f m)", mPerPx, px, meters));

        scaleA = null;
    }

    private void handleTestRouteClick(Point imgPt) {
        if (currentMask == null || currentImage == null) return;

        int stride = ((Number) routeStrideSpinner.getValue()).intValue();

        if (routeStart == null || (routeStart != null && routeEnd != null)) {

            Point s = AStarRouter.snapToNearestWalkable(currentMask, imgPt, stride, 240);
            if (s == null) {
                Toolkit.getDefaultToolkit().beep();
                statusLabel.setText("❌ No walkable pixel near that START point. Click nearer GREEN or adjust mask.");
                return;
            }

            routeStart = s;
            routeEnd = null;
            routePath = null;
            cancelRouteWorker();
            canvas.setTestRoute(routeStart, null, null);

            statusLabel.setText("Route START set at (" + routeStart.x + "," + routeStart.y + "). Click an end point.");
            return;
        }

        Point e = AStarRouter.snapToNearestWalkable(currentMask, imgPt, stride, 240);
        if (e == null) {
            Toolkit.getDefaultToolkit().beep();
            statusLabel.setText("❌ No walkable pixel near that END point. Click nearer GREEN or adjust mask.");
            return;
        }

        Point s2 = AStarRouter.snapToNearestWalkable(currentMask, routeStart, stride, 240);
        if (s2 == null) {
            Toolkit.getDefaultToolkit().beep();
            statusLabel.setText("❌ START is no longer near walkable (mask/stride changed). Click a new START.");
            return;
        }

        routeStart = s2;
        routeEnd = e;

        routePath = null;
        canvas.setTestRoute(routeStart, routeEnd, null);

        statusLabel.setText("Routing (A*)... stride=" + stride + "px");

        cancelRouteWorker();

        routeWorker = new SwingWorker<>() {
            @Override
            protected List<Point> doInBackground() {
                return AStarRouter.findPath(
                        currentMask,
                        routeStart,
                        routeEnd,
                        stride,
                        2_000_000,
                        true
                );
            }

            @Override
            protected void done() {
                if (isCancelled()) return;
                try {
                    List<Point> path = get();
                    routePath = path;

                    if (path == null || path.size() < 2) {
                        canvas.setTestRoute(routeStart, routeEnd, null);
                        statusLabel.setText("❌ No path found. Try higher inflate / lower stride / fix mask gaps.");
                    } else {
                        canvas.setTestRoute(routeStart, routeEnd, path);
                        statusLabel.setText("✅ Path found: " + path.size() + " pts (stride=" + stride + ")");
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    canvas.setTestRoute(routeStart, routeEnd, null);
                    statusLabel.setText("❌ Routing failed: " + ex.getMessage());
                }
            }
        };

        routeWorker.execute();
    }

    private void cancelRouteWorker() {
        if (routeWorker != null) {
            routeWorker.cancel(true);
            routeWorker = null;
        }
    }

    private void clearRoute() {
        cancelRouteWorker();
        routeStart = null;
        routeEnd = null;
        routePath = null;
        canvas.clearTestRoute();
    }

    private void handlePolygonTool(FloorplanCanvas.Tool tool, Polygon poly) {
        if (locked) return;
        if (poly == null || poly.npoints < 3) return;

        if (selectedZone == null || selectedZone.getType() == null || !selectedZone.getType().hasAnchor()) {
            Toolkit.getDefaultToolkit().beep();
            JOptionPane.showMessageDialog(this,
                    "Select an anchor (Ticket/Checkpoint/Holdroom) first, then draw its area.",
                    "No Anchor Selected",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        Zone anchor = selectedZone;

        ZoneType areaType = null;
        String areaId = null;

        if (tool == FloorplanCanvas.Tool.DRAW_TICKET_QUEUE) {
            if (anchor.getType() != ZoneType.TICKET_COUNTER) {
                warnWrongAnchor("Ticket Counter");
                return;
            }
            areaType = ZoneType.TICKET_QUEUE_AREA;
            areaId = anchor.getId() + "_QUEUE";
        } else if (tool == FloorplanCanvas.Tool.DRAW_CHECKPOINT_QUEUE) {
            if (anchor.getType() != ZoneType.CHECKPOINT) {
                warnWrongAnchor("Checkpoint");
                return;
            }
            areaType = ZoneType.CHECKPOINT_QUEUE_AREA;
            areaId = anchor.getId() + "_QUEUE";
        } else if (tool == FloorplanCanvas.Tool.DRAW_CHECKPOINT_AREA) { // NEW
            if (anchor.getType() != ZoneType.CHECKPOINT) {
                warnWrongAnchor("Checkpoint");
                return;
            }
            areaType = ZoneType.CHECKPOINT_AREA;
            areaId = anchor.getId() + "_AREA";
        } else if (tool == FloorplanCanvas.Tool.DRAW_HOLDROOM_AREA) {
            if (anchor.getType() != ZoneType.HOLDROOM) {
                warnWrongAnchor("Holdroom");
                return;
            }
            areaType = ZoneType.HOLDROOM_AREA;
            areaId = anchor.getId() + "_AREA";
        } else {
            return;
        }

        Zone existing = findZone(areaId, areaType);
        if (existing != null) {
            existing.setArea(poly);
        } else {
            Zone area = Zone.areaZone(areaId, areaType, poly);
            if (project.getZones() != null) project.getZones().add(area);
        }

        selectedZone = anchor;
        canvas.setSelectedZone(anchor);
        inspector.setZone(anchor);

        syncZonesToCanvas();
        statusLabel.setText("Set " + areaType.getLabel() + " for " + areaId);
    }

    private void warnWrongAnchor(String expected) {
        Toolkit.getDefaultToolkit().beep();
        JOptionPane.showMessageDialog(this,
                "This draw tool requires a selected " + expected + " anchor.\n\n" +
                        "Use Select and click the correct anchor dot first.",
                "Wrong Anchor Selected",
                JOptionPane.WARNING_MESSAGE);
    }

    private Zone findZone(String id, ZoneType type) {
        if (project.getZones() == null) return null;
        for (Zone z : project.getZones()) {
            if (z == null) continue;
            if (z.getType() == type && id != null && id.equals(z.getId())) return z;
        }
        return null;
    }

    private static String safeId(Zone z) {
        if (z == null) return "(null)";
        String id = z.getId();
        if (id == null || id.trim().isEmpty()) return "(no id)";
        return id.trim();
    }

    private Zone findFirstAnchorOfType(ZoneType t) {
        if (project.getZones() == null) return null;
        for (Zone z : project.getZones()) {
            if (z != null && z.getType() == t) return z;
        }
        return null;
    }

    private String nextAnchorIdFor(ZoneType type) {
        String prefix;
        if (type == ZoneType.SPAWN) prefix = "S";
        else if (type == ZoneType.TICKET_COUNTER) prefix = "T";
        else if (type == ZoneType.CHECKPOINT) prefix = "C";
        else if (type == ZoneType.HOLDROOM) prefix = "H";
        else prefix = "Z";

        Set<Integer> used = new HashSet<>();
        if (project.getZones() != null) {
            for (Zone z : project.getZones()) {
                if (z == null) continue;
                if (z.getType() != type) continue;

                String id = (z.getId() == null) ? "" : z.getId().trim();
                if (!id.startsWith(prefix)) continue;

                String rest = id.substring(prefix.length()).trim();
                if (rest.isEmpty()) continue;

                boolean digits = true;
                for (int i = 0; i < rest.length(); i++) {
                    if (!Character.isDigit(rest.charAt(i))) { digits = false; break; }
                }
                if (!digits) continue;

                try { used.add(Integer.parseInt(rest)); } catch (Exception ignored) {}
            }
        }

        int n = 1;
        while (used.contains(n)) n++;
        return prefix + n;
    }

    private void deleteSelected() {
        if (locked) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        if (selectedZone == null) return;

        Zone z = selectedZone;
        selectedZone = null;
        canvas.setSelectedZone(null);
        inspector.setZone(null);

        if (z.getType() == ZoneType.TICKET_COUNTER) {
            removeZoneById(z.getId() + "_QUEUE", ZoneType.TICKET_QUEUE_AREA);
        } else if (z.getType() == ZoneType.CHECKPOINT) {
            removeZoneById(z.getId() + "_QUEUE", ZoneType.CHECKPOINT_QUEUE_AREA);
            removeZoneById(z.getId() + "_AREA", ZoneType.CHECKPOINT_AREA); // NEW: also remove checkpoint area
        } else if (z.getType() == ZoneType.HOLDROOM) {
            removeZoneById(z.getId() + "_AREA", ZoneType.HOLDROOM_AREA);
        }

        if (project.getZones() != null) project.getZones().remove(z);
        syncZonesToCanvas();
        statusLabel.setText("Deleted: " + z);
    }

    private void removeZoneById(String id, ZoneType t) {
        Zone target = findZone(id, t);
        if (target != null && project.getZones() != null) project.getZones().remove(target);
    }

    private void syncZonesToCanvas() {
        canvas.setZones(project.getZones());
        canvas.repaint();
    }

    private void updateStatusSelection() {
        if (selectedZone == null) return;
        statusLabel.setText("Selected: " + selectedZone);
    }

    // ==========================================================
    // Validate + Lock API
    // ==========================================================

    public boolean validateAndLock() {
        if (locked) return true;

        List<String> pre = validateProject();
        if (!pre.isEmpty()) {
            showErrors(pre);
            return false;
        }

        project.setMask(currentMask);
        project.setMetersPerPixel(((Number) metersPerPixelSpinner.getValue()).doubleValue());

        lastValidationErrors = project.validate();
        if (!lastValidationErrors.isEmpty()) {
            showErrors(lastValidationErrors);
            return false;
        }

        int stride = ((Number) routeStrideSpinner.getValue()).intValue();
        List<String> conn = sim.floorplan.path.FloorplanConnectivity.check(project, stride, true);
        if (!conn.isEmpty()) {
            showErrors(conn);
            return false;
        }

        locked = true;
        canvas.setLocked(true);
        setEditingEnabled(false);

        statusLabel.setText("✅ Floorplan validated & LOCKED.");
        return true;
    }

    public boolean isLocked() { return locked; }

    public List<String> getLastValidationErrors() { return new ArrayList<>(lastValidationErrors); }

    private void unlockForEditing() {
        locked = false;
        canvas.setLocked(false);
        setEditingEnabled(true);
        statusLabel.setText("Unlocked. Edits allowed (re-validate before starting).");
    }

    private void setEditingEnabled(boolean enabled) {
        resetViewBtn.setEnabled(true);
        overlayToggle.setEnabled(true);

        metersPerPixelSpinner.setEnabled(true);
        measureScaleBtn.setEnabled(true);

        testRouteBtn.setEnabled(true);
        routeStrideSpinner.setEnabled(true);
        clearRouteBtn.setEnabled(true);

        // mask tools
        walkToolBtn.setEnabled(enabled);
        blockToolBtn.setEnabled(enabled);
        polyFillBtn.setEnabled(enabled);

        // zone tools
        placeSpawnBtn.setEnabled(enabled);
        placeTicketBtn.setEnabled(enabled);
        placeCheckpointBtn.setEnabled(enabled);
        placeHoldroomBtn.setEnabled(enabled);

        drawTicketQueueBtn.setEnabled(enabled);
        drawCheckpointQueueBtn.setEnabled(enabled);
        drawCheckpointAreaBtn.setEnabled(enabled); // NEW
        drawHoldroomAreaBtn.setEnabled(enabled);

        deleteSelectedBtn.setEnabled(enabled);

        // generators
        autoMaskBtn.setEnabled(enabled);
        thresholdSlider.setEnabled(enabled);
        autoThrToggle.setEnabled(enabled);
        inflateSpinner.setEnabled(enabled);
        removeOutsideToggle.setEnabled(enabled);
        sealGapsSpinner.setEnabled(enabled);

        validateLockBtn.setEnabled(enabled);
        unlockBtn.setEnabled(!enabled);

        // save/load always
        saveProjectBtn.setEnabled(true);
        loadProjectBtn.setEnabled(true);

        // inspector read-only when locked
        inspector.setLocked(!enabled);

        // ✅ when locked, allow SELECT/PAN/TEST_ROUTE/MEASURE_SCALE without forcing PAN
        if (!enabled) {
            FloorplanCanvas.Tool t = canvas.getTool();
            if (t != FloorplanCanvas.Tool.TEST_ROUTE
                    && t != FloorplanCanvas.Tool.MEASURE_SCALE
                    && t != FloorplanCanvas.Tool.SELECT
                    && t != FloorplanCanvas.Tool.PAN) {
                panToolBtn.setSelected(true);
                canvas.setTool(FloorplanCanvas.Tool.PAN);
            }
            updateHelp();
        }
    }

    // ==========================================================
    // Required API
    // ==========================================================

    public boolean hasValidProject() {
        return validateProject().isEmpty();
    }

    public List<String> validateProject() {
        List<String> errs = new ArrayList<>();
        if (currentImage == null) errs.add("No rendered/loaded image. Render a PDF page or Load Project.");
        if (currentMask == null) errs.add("No mask generated/loaded.");
        return errs;
    }

    public FloorplanProject getProjectCopy() {
        project.setMask(currentMask);
        project.setFloorplanImage(currentImage);
        project.setPdfFile(currentPdf);
        project.setPageIndex(((Number) pageSpinner.getValue()).intValue());
        project.setDpi((Integer) dpiCombo.getSelectedItem());
        project.setMetersPerPixel(((Number) metersPerPixelSpinner.getValue()).doubleValue());
        return project.copy();
    }

    public void setMask(WalkMask mask) {
        if (locked) {
            JOptionPane.showMessageDialog(this, "Unlock to edit mask.", "Locked", JOptionPane.WARNING_MESSAGE);
            return;
        }
        this.currentMask = mask;
        canvas.setMask(mask);

        project.setMask(mask);

        clearRoute();
        statusLabel.setText(statusLabel.getText() + " | mask updated");
    }

    // ==========================================================
    // ✅ FLOORPLAN SIM: start (settings button removed)
    // ==========================================================

    private void startFloorplanSimulation() {
        // Must validate & lock before sim
        boolean ok = validateAndLock();
        if (!ok) return;

        // Frozen copy used for routing/sim
        FloorplanProject fpCopy = getProjectCopy();

        // Basic required anchors
        int nSpawn = countAnchorZones(fpCopy, ZoneType.SPAWN);
        int nTickets = countAnchorZones(fpCopy, ZoneType.TICKET_COUNTER);
        int nCps = countAnchorZones(fpCopy, ZoneType.CHECKPOINT);
        int nHolds = countAnchorZones(fpCopy, ZoneType.HOLDROOM);

        if (nSpawn <= 0 || nCps <= 0 || nHolds <= 0) {
            JOptionPane.showMessageDialog(this,
                    "Floorplan simulation requires at least:\n" +
                            "- 1 Spawn anchor\n" +
                            "- 1 Checkpoint anchor\n" +
                            "- 1 Holdroom anchor\n\n" +
                            "Ticket counters are optional (online passengers can skip them).\n" +
                            "Please place anchors and Validate & Lock.",
                    "Floorplan Missing Required Anchors",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Ensure floorplan sim state has the right holdroom row count (1 per holdroom anchor)
        ensureHoldRoomCountInState(nHolds);

        // Flights (edited ONLY in Floorplan Simulation tab)
        List<Flight> flights = new ArrayList<>(floorplanSimState.flights);
        if (flights.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please add at least one Flight in the Floorplan Simulation tab (Flight Schedule section).",
                    "No Flights",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Percent in person / walk speed (also edited in Floorplan Simulation tab)
        double percentInPerson = clampDouble(floorplanSimState.percentInPerson, 0.0, 1.0);
        double walkSpeedMps = clampDouble(floorplanSimState.walkSpeedMps, 0.2, 6.0);

        // Arrival curve (edited ONLY in Floorplan Simulation tab)
        ArrivalCurveConfig curveCfg = (floorplanSimState.arrivalCurve == null)
                ? ArrivalCurveConfig.legacyDefault()
                : floorplanSimState.arrivalCurve;
        curveCfg.validateAndClamp();

        // For floorplan sim: treat curve window as up to 240 minutes (4 hours)
        int arrivalSpan = Math.max(240,
                curveCfg.isLegacyMode()
                        ? ArrivalCurveConfig.DEFAULT_WINDOW_START
                        : curveCfg.getWindowStartMinutesBeforeDeparture());

        int intervalMinutes = 1; // 1-minute ticks

        // Ticket counter + checkpoint configs FROM ZONES (separate from blank canvas)
        List<TicketCounterConfig> counters = buildTicketCounterConfigsFromZones(flights, 1.0);
        List<CheckpointConfig> checkpoints = buildCheckpointConfigsFromZones(120.0);

        // Holdrooms mapping (separate from blank canvas)
        List<HoldRoomConfig> holdRooms = buildHoldRoomsSizedToAnchors(nHolds, floorplanSimState.holdRooms);

        // Travel provider from floorplan routing
        TravelTimeProvider provider = new FloorplanTravelTimeProvider(fpCopy, walkSpeedMps);

        // Legacy delays are 0 in floorplan mode (routing determines time)
        int transitDelay = 0;
        int holdDelay = 0;

        try {
            // Build table engine (precompute)
            SimulationEngine tableEngine = createEngine(
                    percentInPerson,
                    counters,
                    checkpoints,
                    arrivalSpan,
                    intervalMinutes,
                    transitDelay,
                    holdDelay,
                    flights,
                    holdRooms
            );
            tableEngine.setArrivalCurveConfig(curveCfg);
            tableEngine.setTravelTimeProvider(provider);
            tableEngine.runAllIntervals();

            // Build sim engine (interactive stepping)
            SimulationEngine simEngine = createEngine(
                    percentInPerson,
                    counters,
                    checkpoints,
                    arrivalSpan,
                    intervalMinutes,
                    transitDelay,
                    holdDelay,
                    flights,
                    holdRooms
            );
            simEngine.setArrivalCurveConfig(curveCfg);
            simEngine.setTravelTimeProvider(provider);

            // Open windows
            new DataTableFrame(tableEngine).setVisible(true);
            new SimulationFrame(simEngine, fpCopy).setVisible(true);

        } catch (Exception ex) {
            ex.printStackTrace();
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            JTextArea area = new JTextArea(sw.toString(), 20, 60);
            area.setEditable(false);
            JOptionPane.showMessageDialog(this,
                    new JScrollPane(area),
                    "Floorplan Simulation Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void ensureHoldRoomCountInState(int nHolds) {
        int n = Math.max(0, nHolds);
        while (floorplanSimState.holdRooms.size() < n) {
            floorplanSimState.holdRooms.add(new HoldRoomConfig(floorplanSimState.holdRooms.size() + 1));
        }
        while (floorplanSimState.holdRooms.size() > n) {
            floorplanSimState.holdRooms.remove(floorplanSimState.holdRooms.size() - 1);
        }
        // Fix IDs if needed (defensive)
        for (int i = 0; i < floorplanSimState.holdRooms.size(); i++) {
            HoldRoomConfig c = floorplanSimState.holdRooms.get(i);
            if (c == null) {
                floorplanSimState.holdRooms.set(i, new HoldRoomConfig(i + 1));
            }
        }
    }

    private static int countAnchorZones(FloorplanProject fp, ZoneType t) {
        if (fp == null || fp.getZones() == null) return 0;
        int c = 0;
        for (Zone z : fp.getZones()) {
            if (z != null && z.getType() == t) c++;
        }
               return c;
    }

    private static double clampDouble(double v, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private static List<HoldRoomConfig> buildHoldRoomsSizedToAnchors(int desired, List<HoldRoomConfig> ui) {
        List<HoldRoomConfig> out = new ArrayList<>();
        for (int i = 0; i < desired; i++) {
            HoldRoomConfig cfg = new HoldRoomConfig(i + 1);
            if (ui != null && i < ui.size() && ui.get(i) != null) {
                HoldRoomConfig src = ui.get(i);
                cfg.setAllowedFlightNumbers(src.getAllowedFlightNumbers());
                cfg.setWalkSecondsFromCheckpoint(src.getWalkSecondsFromCheckpoint());
            }
            out.add(cfg);
        }
        return out;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private SimulationEngine createEngine(
            double percentInPerson,
            List<TicketCounterConfig> counters,
            List<CheckpointConfig> checkpoints,
            int arrivalSpan,
            int interval,
            int transitDelay,
            int holdDelay,
            List<Flight> flights,
            List<HoldRoomConfig> holdRooms
    ) throws Exception {

        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 9
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])
                    && List.class.isAssignableFrom(p[2])
                    && p[3] == int.class
                    && p[4] == int.class
                    && p[5] == int.class
                    && p[6] == int.class
                    && List.class.isAssignableFrom(p[7])
                    && List.class.isAssignableFrom(p[8])) {
                return (SimulationEngine) c.newInstance(
                        percentInPerson, counters, checkpoints,
                        arrivalSpan, interval, transitDelay, holdDelay,
                        flights, holdRooms
                );
            }
        }

        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 8
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])
                    && List.class.isAssignableFrom(p[2])
                    && p[3] == int.class
                    && p[4] == int.class
                    && p[5] == int.class
                    && List.class.isAssignableFrom(p[6])
                    && List.class.isAssignableFrom(p[7])) {
                return (SimulationEngine) c.newInstance(
                        percentInPerson, counters, checkpoints,
                        arrivalSpan, interval, transitDelay,
                        flights, holdRooms
                );
            }
        }

        throw new IllegalStateException("No compatible SimulationEngine constructor found.");
    }

    // ==========================================================
    // Helpers: build configs from zones (reflection-safe)
    // ==========================================================

    public List<TicketCounterConfig> buildTicketCounterConfigsFromZones(List<Flight> flights, double fallbackRatePerMin) {
        List<Zone> tickets = new ArrayList<>();
        if (project.getZones() != null) {
            for (Zone z : project.getZones()) {
                if (z != null && z.getType() == ZoneType.TICKET_COUNTER) tickets.add(z);
            }
        }
        tickets.sort(Comparator.comparingInt(FloorplanEditorPanel::idNumberOrMax)
                .thenComparing(z -> z.getId() == null ? "" : z.getId()));

        Map<String, Flight> byNum = new HashMap<>();
        if (flights != null) {
            for (Flight f : flights) {
                if (f != null && f.getFlightNumber() != null) byNum.put(f.getFlightNumber().trim(), f);
            }
        }

        List<TicketCounterConfig> out = new ArrayList<>();
        int id = 1;
        for (Zone z : tickets) {
            double rate = Double.NaN;

            Double perMin = readDouble(z,
                    "getTicketRatePerMinute", "getTicketRatePerMin",
                    "getRatePerMinute", "getRatePerMin",
                    "getServiceRatePerMinute", "getServiceRatePerMin"
            );
            Double perHour = readDouble(z,
                    "getTicketRatePerHour", "getRatePerHour", "getServiceRatePerHour"
            );

            if (perMin != null && perMin > 0) rate = perMin;
            else if (perHour != null && perHour > 0) rate = perHour / 60.0;

            if (!(rate > 0)) rate = fallbackRatePerMin;

            Set<Flight> allowed = new HashSet<>();
            Set<String> allowedNums = readStringSet(z,
                    "getAllowedFlightNumbers", "getAllowedFlights", "getAllowedFlightNums", "getAllowedFlightIds"
            );
            if (allowedNums != null && !allowedNums.isEmpty()) {
                for (String num : allowedNums) {
                    if (num == null) continue;
                    Flight f = byNum.get(num.trim());
                    if (f != null) allowed.add(f);
                }
            }

            out.add(new TicketCounterConfig(id++, rate, allowed));
        }
        return out;
    }

    public List<CheckpointConfig> buildCheckpointConfigsFromZones(double fallbackRatePerHour) {
        List<Zone> cps = new ArrayList<>();
        if (project.getZones() != null) {
            for (Zone z : project.getZones()) {
                if (z != null && z.getType() == ZoneType.CHECKPOINT) cps.add(z);
            }
        }
        cps.sort(Comparator.comparingInt(FloorplanEditorPanel::idNumberOrMax)
                .thenComparing(z -> z.getId() == null ? "" : z.getId()));

        List<CheckpointConfig> out = new ArrayList<>();
        int id = 1;
        for (Zone z : cps) {
            double perHour = Double.NaN;

            Double zPerHour = readDouble(z,
                    "getCheckpointRatePerHour", "getRatePerHour", "getServiceRatePerHour"
            );
            Double zPerMin = readDouble(z,
                    "getCheckpointRatePerMinute", "getCheckpointRatePerMin",
                    "getRatePerMinute", "getRatePerMin", "getServiceRatePerMinute", "getServiceRatePerMin"
            );

            if (zPerHour != null && zPerHour > 0) perHour = zPerHour;
            else if (zPerMin != null && zPerMin > 0) perHour = zPerMin * 60.0;

            if (!(perHour > 0)) perHour = fallbackRatePerHour;

            CheckpointConfig cfg = new CheckpointConfig(id++);
            cfg.setRatePerHour(perHour);
            out.add(cfg);
        }
        return out;
    }

    private static int idNumberOrMax(Zone z) {
        if (z == null || z.getId() == null) return Integer.MAX_VALUE;
        String id = z.getId().trim();
        int i = id.length() - 1;
        while (i >= 0 && Character.isDigit(id.charAt(i))) i--;
        if (i == id.length() - 1) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(id.substring(i + 1));
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    // ==========================================================
    // Reflection helpers
    // ==========================================================

    private static Double readDouble(Object target, String... methodNames) {
        Object o = readObject(target, methodNames);
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Set<String> readStringSet(Object target, String... methodNames) {
        Object o = readObject(target, methodNames);
        if (o == null) return Collections.emptySet();

        LinkedHashSet<String> out = new LinkedHashSet<>();

        if (o instanceof Collection) {
            for (Object it : (Collection<?>) o) {
                if (it == null) continue;
                String s = String.valueOf(it).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }

        if (o.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < n; i++) {
                Object it = java.lang.reflect.Array.get(o, i);
                if (it == null) continue;
                String s = String.valueOf(it).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }

        String s = String.valueOf(o).trim();
        if (!s.isEmpty()) {
            for (String part : s.split("[,;\\n\\t ]+")) {
                String p = part.trim();
                if (!p.isEmpty()) out.add(p);
            }
        }
        return out;
    }

    private static boolean writeDouble(Object target, double value, String... setterNames) {
        if (target == null) return false;
        for (String name : setterNames) {
            try {
                Method m = target.getClass().getMethod(name, double.class);
                m.setAccessible(true);
                m.invoke(target, value);
                return true;
            } catch (Exception ignored) { }
            try {
                Method m = target.getClass().getMethod(name, Double.class);
                m.setAccessible(true);
                m.invoke(target, value);
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static Object readObject(Object target, String... methodNames) {
        if (target == null) return null;
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ==========================================================
    // ✅ Floorplan Sim state (backing model)
    // ==========================================================

    private static class FloorplanSimState {
        // IMPORTANT: Flights + Arrival Curve are edited in the Floorplan Simulation TAB (not in this editor panel).
        final List<Flight> flights = new ArrayList<>();

        double percentInPerson = 0.4;
        double walkSpeedMps = 1.34;

        ArrivalCurveConfig arrivalCurve = defaultFloorplanCurve();

        // Holdrooms mapping: one per holdroom anchor (id 1..n), empty allowed set means ALL flights
        final List<HoldRoomConfig> holdRooms = new ArrayList<>();
    }

    private static ArrivalCurveConfig defaultFloorplanCurve() {
        ArrivalCurveConfig cfg = ArrivalCurveConfig.legacyDefault();
        try {
            cfg.setLegacyMode(false);
            cfg.setBoardingCloseMinutesBeforeDeparture(20);
            cfg.setWindowStartMinutesBeforeDeparture(240);
            cfg.setPeakMinutesBeforeDeparture(70);
            cfg.setLeftSigmaMinutes(18);
            cfg.setRightSigmaMinutes(14);
            cfg.setLateClampEnabled(true);
            cfg.setLateClampMinutesBeforeDeparture(30);
            cfg.validateAndClamp();
        } catch (Throwable ignored) { }
        return cfg;
    }
}
