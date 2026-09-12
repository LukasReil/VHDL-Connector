package vhdlconnector.gui;

import vhdlconnector.model.Project;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Left-hand panel: lists the VHDL entities currently loaded into the project's library. */
public class LibraryPanel extends JPanel {

    public interface Listener {
        void onImportRequested();
        void onAddToCanvasRequested(String entityName);
        void onRemoveRequested(String entityName);
        void onViewRequested(String entityName);
    }

    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> list = new JList<>(listModel);
    private Listener listener;

    public LibraryPanel() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setPreferredSize(new Dimension(220, 0));

        add(new JLabel("Entity Library"), BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 1, 2, 2));
        JButton importBtn = new JButton("Import VHDL File...");
        JButton addBtn = new JButton("Add to Canvas");
        JButton viewBtn = new JButton("View Entity");
        JButton removeBtn = new JButton("Remove from Library");

        importBtn.addActionListener(e -> { if (listener != null) listener.onImportRequested(); });
        addBtn.addActionListener(e -> withSelection(name -> listener.onAddToCanvasRequested(name)));
        viewBtn.addActionListener(e -> withSelection(name -> listener.onViewRequested(name)));
        removeBtn.addActionListener(e -> withSelection(name -> listener.onRemoveRequested(name)));
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) withSelection(name -> listener.onAddToCanvasRequested(name));
            }
        });

        buttons.add(importBtn);
        buttons.add(addBtn);
        buttons.add(viewBtn);
        buttons.add(removeBtn);
        add(buttons, BorderLayout.SOUTH);
    }

    private interface StringConsumer { void accept(String s); }

    private void withSelection(StringConsumer c) {
        String sel = list.getSelectedValue();
        if (sel != null && listener != null) c.accept(sel);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void refresh(Project project) {
        String sel = list.getSelectedValue();
        listModel.clear();
        List<String> names = new java.util.ArrayList<>(project.library.keySet());
        java.util.Collections.sort(names);
        for (String n : names) listModel.addElement(n);
        if (sel != null && names.contains(sel)) list.setSelectedValue(sel, true);
    }
}
