package vhdlconnector.gui;

import vhdlconnector.model.Direction;
import vhdlconnector.model.ExternalPort;
import vhdlconnector.model.GenericParam;
import vhdlconnector.model.Port;
import vhdlconnector.model.PortGroup;
import vhdlconnector.model.VhdlEntity;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small modal form helpers, built with plain Swing components (no external deps). */
public final class Dialogs {
    private Dialogs() {}

    private static final String TYPE_STD_LOGIC = "std_logic";
    private static final String TYPE_STD_LOGIC_VECTOR = "std_logic_vector";
    private static final String TYPE_AXI_STREAM = "AXI4-Stream";
    private static final String TYPE_CUSTOM = "Custom...";

    public static String promptString(Component parent, String title, String label, String initial) {
        return (String) JOptionPane.showInputDialog(parent, label, title, JOptionPane.PLAIN_MESSAGE, null, null, initial);
    }

    /** Shows a form to create/edit a single, ordinary (non AXI-Stream) external port.
     *  Returns a singleton list, a multi-element list if the user switches the type to
     *  AXI4-Stream, or null if cancelled. */
    public static List<ExternalPort> promptExternalPort(Component parent, ExternalPort existing) {
        return promptExternalPort(parent, existing, null, existing != null ? existing.x : 40, existing != null ? existing.y : 40);
    }

    /** Shows a form to create a brand new external port at the given canvas position. */
    public static List<ExternalPort> promptNewExternalPort(Component parent, double x, double y) {
        return promptExternalPort(parent, null, null, x, y);
    }

    /** Shows a form to reconfigure an existing detected AXI-Stream external interface. */
    public static List<ExternalPort> promptAxiStreamGroup(Component parent, PortGroup existingGroup, double x, double y) {
        return promptExternalPort(parent, null, existingGroup, x, y);
    }

    /** Core implementation: exactly one of existingSingle/existingGroup should be non-null
     *  when editing, both null when creating a new port. Returns the resulting list of
     *  ExternalPort objects (one for std_logic/vector/custom, several for AXI4-Stream), or
     *  null if the dialog was cancelled. */
    private static List<ExternalPort> promptExternalPort(Component parent, ExternalPort existingSingle,
                                                           PortGroup existingGroup, double x, double y) {
        boolean editing = existingSingle != null || existingGroup != null;

        String initialName = existingGroup != null ? existingGroup.name : existingSingle != null ? existingSingle.name : "port_name";
        JTextField nameField = new JTextField(initialName, 18);

        JComboBox<String> typeBox = new JComboBox<>(new String[]{TYPE_STD_LOGIC, TYPE_STD_LOGIC_VECTOR, TYPE_AXI_STREAM, TYPE_CUSTOM});

        CardLayout cards = new CardLayout();
        JPanel cardPanel = new JPanel(cards);

        // -- std_logic card --
        JComboBox<Direction> dirBoxSimple = new JComboBox<>(new Direction[]{Direction.IN, Direction.OUT, Direction.INOUT});
        JPanel stdLogicCard = new JPanel(new GridLayout(0, 2, 6, 6));
        stdLogicCard.add(new JLabel("Direction:"));
        stdLogicCard.add(dirBoxSimple);

        // -- std_logic_vector card --
        JComboBox<Direction> dirBoxVec = new JComboBox<>(new Direction[]{Direction.IN, Direction.OUT, Direction.INOUT});
        JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(8, 1, 4096, 1));
        JPanel vectorCard = new JPanel(new GridLayout(0, 2, 6, 6));
        vectorCard.add(new JLabel("Direction:"));
        vectorCard.add(dirBoxVec);
        vectorCard.add(new JLabel("Width (bits):"));
        vectorCard.add(widthSpinner);

        // -- AXI4-Stream card --
        JComboBox<String> roleBox = new JComboBox<>(new String[]{"Slave (data comes IN)", "Master (data goes OUT)"});
        JSpinner dataWidthSpinner = new JSpinner(new SpinnerNumberModel(32, 1, 4096, 1));
        JCheckBox tlastBox = new JCheckBox("TLAST");
        JCheckBox tkeepBox = new JCheckBox("TKEEP");
        JCheckBox tstrbBox = new JCheckBox("TSTRB");
        JCheckBox tidBox = new JCheckBox("TID");
        JSpinner tidWidthSpinner = new JSpinner(new SpinnerNumberModel(4, 1, 256, 1));
        JCheckBox tdestBox = new JCheckBox("TDEST");
        JSpinner tdestWidthSpinner = new JSpinner(new SpinnerNumberModel(4, 1, 256, 1));
        JCheckBox tuserBox = new JCheckBox("TUSER");
        JSpinner tuserWidthSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 4096, 1));
        tidWidthSpinner.setEnabled(false);
        tdestWidthSpinner.setEnabled(false);
        tuserWidthSpinner.setEnabled(false);
        tidBox.addActionListener(a -> tidWidthSpinner.setEnabled(tidBox.isSelected()));
        tdestBox.addActionListener(a -> tdestWidthSpinner.setEnabled(tdestBox.isSelected()));
        tuserBox.addActionListener(a -> tuserWidthSpinner.setEnabled(tuserBox.isSelected()));

        JLabel previewLabel = new JLabel(" ");
        previewLabel.setFont(previewLabel.getFont().deriveFont(Font.ITALIC, 11f));

        JPanel axiCard = new JPanel();
        axiCard.setLayout(new BoxLayout(axiCard, BoxLayout.Y_AXIS));
        JPanel rolePanel = new JPanel(new GridLayout(0, 2, 6, 6));
        rolePanel.add(new JLabel("Role:"));
        rolePanel.add(roleBox);
        rolePanel.add(new JLabel("Data width (bits):"));
        rolePanel.add(dataWidthSpinner);
        JPanel togglePanel = new JPanel(new GridLayout(0, 2, 4, 4));
        togglePanel.add(tlastBox); togglePanel.add(new JLabel());
        togglePanel.add(tkeepBox); togglePanel.add(new JLabel());
        togglePanel.add(tstrbBox); togglePanel.add(new JLabel());
        togglePanel.add(tidBox); togglePanel.add(tidWidthSpinner);
        togglePanel.add(tdestBox); togglePanel.add(tdestWidthSpinner);
        togglePanel.add(tuserBox); togglePanel.add(tuserWidthSpinner);
        axiCard.add(rolePanel);
        axiCard.add(Box.createVerticalStrut(6));
        axiCard.add(new JLabel("TVALID and TREADY are always included. Optional signals:"));
        axiCard.add(togglePanel);
        axiCard.add(Box.createVerticalStrut(6));
        axiCard.add(previewLabel);

        // -- custom card --
        JComboBox<Direction> dirBoxCustom = new JComboBox<>(new Direction[]{Direction.IN, Direction.OUT, Direction.INOUT});
        JTextField customTypeField = new JTextField(TYPE_STD_LOGIC, 18);
        JPanel customCard = new JPanel(new GridLayout(0, 2, 6, 6));
        customCard.add(new JLabel("Direction:"));
        customCard.add(dirBoxCustom);
        customCard.add(new JLabel("Type:"));
        customCard.add(customTypeField);

        cardPanel.add(stdLogicCard, TYPE_STD_LOGIC);
        cardPanel.add(vectorCard, TYPE_STD_LOGIC_VECTOR);
        cardPanel.add(axiCard, TYPE_AXI_STREAM);
        cardPanel.add(customCard, TYPE_CUSTOM);
        typeBox.addActionListener(a -> cards.show(cardPanel, (String) typeBox.getSelectedItem()));

        Runnable updatePreview = () -> {
            String prefix = normalizeAxisPrefix(nameField.getText().trim());
            StringBuilder sb = new StringBuilder(prefix).append("_tdata, ").append(prefix).append("_tvalid, ").append(prefix).append("_tready");
            if (tlastBox.isSelected()) sb.append(", ").append(prefix).append("_tlast");
            if (tkeepBox.isSelected()) sb.append(", ").append(prefix).append("_tkeep");
            if (tstrbBox.isSelected()) sb.append(", ").append(prefix).append("_tstrb");
            if (tidBox.isSelected()) sb.append(", ").append(prefix).append("_tid");
            if (tdestBox.isSelected()) sb.append(", ").append(prefix).append("_tdest");
            if (tuserBox.isSelected()) sb.append(", ").append(prefix).append("_tuser");
            previewLabel.setText("<html>Ports: " + sb + "</html>");
        };
        DocumentListener liveUpdate = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updatePreview.run(); }
            public void removeUpdate(DocumentEvent e) { updatePreview.run(); }
            public void changedUpdate(DocumentEvent e) { updatePreview.run(); }
        };
        nameField.getDocument().addDocumentListener(liveUpdate);
        for (JCheckBox cb : new JCheckBox[]{tlastBox, tkeepBox, tstrbBox, tidBox, tdestBox, tuserBox}) {
            cb.addActionListener(a -> updatePreview.run());
        }

        // pre-fill from whatever we're editing (if anything)
        if (existingGroup != null) {
            typeBox.setSelectedItem(TYPE_AXI_STREAM);
            AxiConfig cfg = AxiConfig.fromGroup(existingGroup);
            roleBox.setSelectedIndex(cfg.master ? 1 : 0);
            dataWidthSpinner.setValue(cfg.dataWidth);
            tlastBox.setSelected(cfg.tlast);
            tkeepBox.setSelected(cfg.tkeep);
            tstrbBox.setSelected(cfg.tstrb);
            tidBox.setSelected(cfg.tid); tidWidthSpinner.setValue(cfg.tidWidth); tidWidthSpinner.setEnabled(cfg.tid);
            tdestBox.setSelected(cfg.tdest); tdestWidthSpinner.setValue(cfg.tdestWidth); tdestWidthSpinner.setEnabled(cfg.tdest);
            tuserBox.setSelected(cfg.tuser); tuserWidthSpinner.setValue(cfg.tuserWidth); tuserWidthSpinner.setEnabled(cfg.tuser);
            cards.show(cardPanel, TYPE_AXI_STREAM);
        } else if (existingSingle != null) {
            Integer vecWidth = parseVectorWidth(existingSingle.type);
            if (existingSingle.type.trim().equalsIgnoreCase(TYPE_STD_LOGIC)) {
                typeBox.setSelectedItem(TYPE_STD_LOGIC);
                dirBoxSimple.setSelectedItem(existingSingle.direction);
                cards.show(cardPanel, TYPE_STD_LOGIC);
            } else if (vecWidth != null) {
                typeBox.setSelectedItem(TYPE_STD_LOGIC_VECTOR);
                dirBoxVec.setSelectedItem(existingSingle.direction);
                widthSpinner.setValue(vecWidth);
                cards.show(cardPanel, TYPE_STD_LOGIC_VECTOR);
            } else {
                typeBox.setSelectedItem(TYPE_CUSTOM);
                dirBoxCustom.setSelectedItem(existingSingle.direction);
                customTypeField.setText(existingSingle.type);
                cards.show(cardPanel, TYPE_CUSTOM);
            }
        } else {
            typeBox.setSelectedItem(TYPE_STD_LOGIC);
            cards.show(cardPanel, TYPE_STD_LOGIC);
        }
        updatePreview.run();

        JPanel top = new JPanel(new GridLayout(0, 2, 6, 6));
        top.add(new JLabel(existingGroup != null ? "Interface name:" : "Name:"));
        top.add(nameField);
        top.add(new JLabel("Type:"));
        top.add(typeBox);

        JPanel main = new JPanel(new BorderLayout(8, 8));
        main.add(top, BorderLayout.NORTH);
        main.add(cardPanel, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(parent, main,
                editing ? "Edit External Port" : "New External Port",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        String name = nameField.getText().trim();
        if (name.isEmpty()) return null;
        String selectedType = (String) typeBox.getSelectedItem();

        List<ExternalPort> out = new ArrayList<>();
        if (TYPE_STD_LOGIC.equals(selectedType)) {
            out.add(new ExternalPort(name, (Direction) dirBoxSimple.getSelectedItem(), TYPE_STD_LOGIC, x, y));
        } else if (TYPE_STD_LOGIC_VECTOR.equals(selectedType)) {
            int width = (Integer) widthSpinner.getValue();
            out.add(new ExternalPort(name, (Direction) dirBoxVec.getSelectedItem(), vecType(width), x, y));
        } else if (TYPE_AXI_STREAM.equals(selectedType)) {
            boolean master = roleBox.getSelectedIndex() == 1;
            out.addAll(buildAxiPorts(name, master, (Integer) dataWidthSpinner.getValue(),
                    tlastBox.isSelected(), tkeepBox.isSelected(), tstrbBox.isSelected(),
                    tidBox.isSelected(), (Integer) tidWidthSpinner.getValue(),
                    tdestBox.isSelected(), (Integer) tdestWidthSpinner.getValue(),
                    tuserBox.isSelected(), (Integer) tuserWidthSpinner.getValue(),
                    x, y));
        } else {
            String customType = customTypeField.getText().trim();
            if (customType.isEmpty()) customType = TYPE_STD_LOGIC;
            out.add(new ExternalPort(name, (Direction) dirBoxCustom.getSelectedItem(), customType, x, y));
        }
        return out;
    }

    private static List<ExternalPort> buildAxiPorts(String rawName, boolean master, int dataWidth,
                                                      boolean tlast, boolean tkeep, boolean tstrb,
                                                      boolean tid, int tidWidth,
                                                      boolean tdest, int tdestWidth,
                                                      boolean tuser, int tuserWidth,
                                                      double x, double y) {
        String prefix = normalizeAxisPrefix(rawName);
        Direction dataDir = master ? Direction.OUT : Direction.IN;
        Direction readyDir = master ? Direction.IN : Direction.OUT;
        int keepStrbWidth = Math.max(1, (dataWidth + 7) / 8);

        List<ExternalPort> ports = new ArrayList<>();
        ports.add(new ExternalPort(prefix + "_tdata", dataDir, vecType(dataWidth), x, y));
        ports.add(new ExternalPort(prefix + "_tvalid", dataDir, TYPE_STD_LOGIC, x, y));
        ports.add(new ExternalPort(prefix + "_tready", readyDir, TYPE_STD_LOGIC, x, y));
        if (tlast) ports.add(new ExternalPort(prefix + "_tlast", dataDir, TYPE_STD_LOGIC, x, y));
        if (tkeep) ports.add(new ExternalPort(prefix + "_tkeep", dataDir, vecType(keepStrbWidth), x, y));
        if (tstrb) ports.add(new ExternalPort(prefix + "_tstrb", dataDir, vecType(keepStrbWidth), x, y));
        if (tid) ports.add(new ExternalPort(prefix + "_tid", dataDir, vecType(tidWidth), x, y));
        if (tdest) ports.add(new ExternalPort(prefix + "_tdest", dataDir, vecType(tdestWidth), x, y));
        if (tuser) ports.add(new ExternalPort(prefix + "_tuser", dataDir, vecType(tuserWidth), x, y));
        return ports;
    }

    /** Ensures the interface prefix ends in "_axis" or "_axi" so AxiStreamDetector will
     *  actually recognize the generated ports as one bundled interface. */
    private static String normalizeAxisPrefix(String raw) {
        String base = raw.isEmpty() ? "m_axis" : raw;
        String lower = base.toLowerCase();
        if (lower.endsWith("_axis") || lower.endsWith("_axi")) return base;
        return base + "_axis";
    }

    private static String vecType(int width) {
        return width <= 1 ? TYPE_STD_LOGIC : (TYPE_STD_LOGIC_VECTOR + "(" + (width - 1) + " downto 0)");
    }

    private static final Pattern VECTOR_WIDTH_PATTERN = Pattern.compile(
            "std_logic_vector\\s*\\(\\s*(\\d+)\\s+downto\\s+0\\s*\\)", Pattern.CASE_INSENSITIVE);

    private static Integer parseVectorWidth(String type) {
        Matcher m = VECTOR_WIDTH_PATTERN.matcher(type.trim());
        return m.find() ? Integer.parseInt(m.group(1)) + 1 : null;
    }

    private static int widthOf(Port p, int fallback) {
        if (p == null) return fallback;
        if (p.type.trim().equalsIgnoreCase(TYPE_STD_LOGIC)) return 1;
        Integer w = parseVectorWidth(p.type);
        return w != null ? w : fallback;
    }

    /** Reverse-engineers the dialog's AXI4-Stream fields from an already-detected group,
     *  so editing an existing interface starts from its current configuration. */
    private static final class AxiConfig {
        boolean master;
        int dataWidth;
        boolean tlast, tkeep, tstrb, tid, tdest, tuser;
        int tidWidth, tdestWidth, tuserWidth;

        static AxiConfig fromGroup(PortGroup g) {
            AxiConfig cfg = new AxiConfig();
            cfg.master = g.isMaster();
            cfg.dataWidth = widthOf(g.signals.get("DATA"), 32);
            cfg.tlast = g.signals.containsKey("LAST");
            cfg.tkeep = g.signals.containsKey("KEEP");
            cfg.tstrb = g.signals.containsKey("STRB");
            cfg.tid = g.signals.containsKey("ID");
            cfg.tidWidth = widthOf(g.signals.get("ID"), 4);
            cfg.tdest = g.signals.containsKey("DEST");
            cfg.tdestWidth = widthOf(g.signals.get("DEST"), 4);
            cfg.tuser = g.signals.containsKey("USER");
            cfg.tuserWidth = widthOf(g.signals.get("USER"), 1);
            return cfg;
        }
    }

    /** Shows a form to edit generic overrides for an instance. Returns null if cancelled. */
    public static Map<String, String> promptGenericOverrides(Component parent, VhdlEntity entity, Map<String, String> current) {
        if (entity.generics.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "This entity has no generics.", "Generics", JOptionPane.INFORMATION_MESSAGE);
            return current;
        }
        JPanel panel = new JPanel(new GridLayout(0, 2, 6, 6));
        Map<String, JTextField> fields = new LinkedHashMap<>();
        for (GenericParam g : entity.generics) {
            String val = current.getOrDefault(g.name, g.defaultValue != null ? g.defaultValue : "");
            JTextField field = new JTextField(val, 12);
            fields.put(g.name, field);
            panel.add(new JLabel(g.name + " (" + g.type + "):"));
            panel.add(field);
        }
        int result = JOptionPane.showConfirmDialog(parent, panel, "Generic Map", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        Map<String, String> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, JTextField> e : fields.entrySet()) {
            String v = e.getValue().getText().trim();
            if (!v.isEmpty()) overrides.put(e.getKey(), v);
        }
        return overrides;
    }

    public static void showEntitySummary(Component parent, VhdlEntity entity) {
        StringBuilder sb = new StringBuilder();
        sb.append("entity ").append(entity.name).append(" is\n");
        if (!entity.generics.isEmpty()) {
            sb.append("  generic (\n");
            for (GenericParam g : entity.generics) sb.append("    ").append(g).append(";\n");
            sb.append("  );\n");
        }
        sb.append("  port (\n");
        for (var p : entity.ports) sb.append("    ").append(p).append(";\n");
        sb.append("  );\n");
        sb.append("end entity;\n");
        if (entity.sourceFile != null) sb.append("\nSource: ").append(entity.sourceFile);

        JTextArea area = new JTextArea(sb.toString(), 20, 50);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JOptionPane.showMessageDialog(parent, new JScrollPane(area), "Entity: " + entity.name, JOptionPane.PLAIN_MESSAGE);
    }
}
