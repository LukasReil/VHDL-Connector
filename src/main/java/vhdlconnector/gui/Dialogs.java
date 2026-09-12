package vhdlconnector.gui;

import vhdlconnector.model.Direction;
import vhdlconnector.model.ExternalPort;
import vhdlconnector.model.GenericParam;
import vhdlconnector.model.VhdlEntity;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small modal form helpers, built with plain Swing components (no external deps). */
public final class Dialogs {
    private Dialogs() {}

    public static String promptString(Component parent, String title, String label, String initial) {
        return (String) JOptionPane.showInputDialog(parent, label, title, JOptionPane.PLAIN_MESSAGE, null, null, initial);
    }

    /** Shows a form to create/edit an external (top-level) port. Returns null if cancelled. */
    public static ExternalPort promptExternalPort(Component parent, ExternalPort existing) {
        JTextField nameField = new JTextField(existing != null ? existing.name : "port_name", 16);
        JComboBox<Direction> dirBox = new JComboBox<>(new Direction[]{Direction.IN, Direction.OUT, Direction.INOUT});
        dirBox.setSelectedItem(existing != null ? existing.direction : Direction.IN);
        JTextField typeField = new JTextField(existing != null ? existing.type : "std_logic", 16);

        JPanel panel = new JPanel(new GridLayout(0, 2, 6, 6));
        panel.add(new JLabel("Name:"));
        panel.add(nameField);
        panel.add(new JLabel("Direction:"));
        panel.add(dirBox);
        panel.add(new JLabel("Type:"));
        panel.add(typeField);

        int result = JOptionPane.showConfirmDialog(parent, panel,
                existing != null ? "Edit External Port" : "New External Port",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        String name = nameField.getText().trim();
        if (name.isEmpty()) return null;
        Direction dir = (Direction) dirBox.getSelectedItem();
        String type = typeField.getText().trim();
        if (type.isEmpty()) type = "std_logic";

        double x = existing != null ? existing.x : 40;
        double y = existing != null ? existing.y : 40;
        return new ExternalPort(name, dir, type, x, y);
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
