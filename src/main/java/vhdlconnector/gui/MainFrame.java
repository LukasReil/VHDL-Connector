package vhdlconnector.gui;

import vhdlconnector.export.VhdlExporter;
import vhdlconnector.io.ProjectIO;
import vhdlconnector.model.Project;
import vhdlconnector.model.VhdlEntity;
import vhdlconnector.parser.VhdlEntityParser;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

public class MainFrame extends JFrame implements LibraryPanel.Listener, CanvasPanel.Listener {

    private Project project = new Project();
    private File currentProjectFile;
    private boolean dirty = false;

    private final LibraryPanel libraryPanel = new LibraryPanel();
    private final CanvasPanel canvasPanel = new CanvasPanel();
    private final JLabel statusLabel = new JLabel(" ");

    private final VhdlEntityParser parser = new VhdlEntityParser();
    private final ProjectIO projectIO = new ProjectIO();

    public MainFrame() {
        super("VHDL Connector");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        libraryPanel.setListener(this);
        canvasPanel.setListener(this);
        canvasPanel.setProject(project);
        libraryPanel.refresh(project);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, libraryPanel, new JScrollPane(canvasPanel));
        split.setDividerLocation(220);

        setJMenuBar(buildMenuBar());

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        statusBar.add(statusLabel, BorderLayout.WEST);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { exit(); }
        });

        updateTitle();
    }

    // ---------------- menu ----------------

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        JMenu fileMenu = new JMenu("File");
        fileMenu.add(menuItem("New Project", this::newProject, KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcutMask)));
        fileMenu.add(menuItem("Open Project...", this::openProject, KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcutMask)));
        fileMenu.add(menuItem("Save Project", this::saveProject, KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcutMask)));
        fileMenu.add(menuItem("Save Project As...", this::saveProjectAs, KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcutMask | InputEvent.SHIFT_DOWN_MASK)));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Import VHDL File...", this::importVhdlFiles));
        fileMenu.add(menuItem("Import VHDL Folder...", this::importFolder));
        fileMenu.add(menuItem("Import IP Folder...", this::importIpFolder));
        fileMenu.add(menuItem("Export VHDL...", this::exportVhdl));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Exit", this::exit));

        JMenu editMenu = new JMenu("Edit");
        editMenu.add(menuItem("Set Top Entity Name...", this::setTopEntityName));
        editMenu.add(menuItem("Auto-Connect...", canvasPanel::showAutoConnectDialog));

        bar.add(fileMenu);
        bar.add(editMenu);
        return bar;
    }

    private JMenuItem menuItem(String label, Runnable action) {
        return menuItem(label, action, null);
    }

    private JMenuItem menuItem(String label, Runnable action, KeyStroke accelerator) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        if (accelerator != null) item.setAccelerator(accelerator);
        return item;
    }

    // ---------------- project lifecycle ----------------

    private void newProject() {
        if (!confirmDiscardIfDirty()) return;
        project = new Project();
        currentProjectFile = null;
        dirty = false;
        canvasPanel.setProject(project);
        libraryPanel.refresh(project);
        updateTitle();
        status("New project created.");
    }

    private void openProject() {
        if (!confirmDiscardIfDirty()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("VHDL Connector Project (*.json)", "json"));
        if (currentProjectFile != null) chooser.setCurrentDirectory(currentProjectFile.getParentFile());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            project = projectIO.load(chooser.getSelectedFile());
            currentProjectFile = chooser.getSelectedFile();
            dirty = false;
            canvasPanel.setProject(project);
            libraryPanel.refresh(project);
            updateTitle();
            status("Opened " + currentProjectFile.getName());
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Failed to open project:\n" + ex.getMessage(), "Open Project", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveProject() {
        if (currentProjectFile == null) {
            saveProjectAs();
            return;
        }
        doSave(currentProjectFile);
    }

    private void saveProjectAs() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("VHDL Connector Project (*.json)", "json"));
        if (currentProjectFile != null) chooser.setSelectedFile(currentProjectFile);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".json")) f = new File(f.getParentFile(), f.getName() + ".json");
        doSave(f);
    }

    private void doSave(File f) {
        try {
            projectIO.save(project, f);
            currentProjectFile = f;
            dirty = false;
            updateTitle();
            status("Saved " + f.getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to save project:\n" + ex.getMessage(), "Save Project", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Recursively collects every file under folder whose name ends with one of the given
     *  (lowercase) extensions. Shared by the VHDL-folder and IP-folder imports. */
    private ArrayList<File> findFilesRecursively(File folder, String... lowerCaseExtensions) {
        ArrayList<File> found = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files == null) return found;
        for (File f : files) {
            if (f.isDirectory()) {
                found.addAll(findFilesRecursively(f, lowerCaseExtensions));
            } else {
                String lower = f.getName().toLowerCase();
                for (String ext : lowerCaseExtensions) {
                    if (lower.endsWith(ext)) { found.add(f); break; }
                }
            }
        }
        return found;
    }

    private void importFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select VHDL Folder");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File folder = chooser.getSelectedFile();
        ArrayList<File> vhdlFiles = findFilesRecursively(folder, ".vhd", ".vhdl");
        if (vhdlFiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No VHDL files found in the selected folder.", "Import Folder", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        importFiles(vhdlFiles.toArray(new File[0]), parser::parseFile);
    }

    /** Recursively imports Vivado-generated IP: an "IP folder" contains one subfolder per
     *  core, each with a .vho instantiation template (component declaration + port map
     *  template) rather than a full entity declaration - see VhdlEntityParser.parseVhoFile. */
    private void importIpFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select IP Folder");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File folder = chooser.getSelectedFile();
        ArrayList<File> vhoFiles = findFilesRecursively(folder, ".vho");
        if (vhoFiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No .vho instantiation templates found in the selected folder.", "Import IP Folder", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        importFiles(vhoFiles.toArray(new File[0]), parser::parseVhoFile);
    }

    private void importVhdlFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("VHDL Source (*.vhd, *.vhdl)", "vhd", "vhdl"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        importFiles(chooser.getSelectedFiles(), parser::parseFile);
    }

    private interface EntityFileParser {
        List<VhdlEntity> parse(File f) throws IOException;
    }

    private void importFiles(File[] files, EntityFileParser fileParser) {
        int imported = 0;
        StringBuilder errors = new StringBuilder();
        for (File f : files) {
            try {
                List<VhdlEntity> entities = fileParser.parse(f);
                if (entities.isEmpty()) {
                    errors.append(f.getName()).append(": no entity/component declaration found\n");
                    continue;
                }
                for (VhdlEntity e : entities) {
                    if (project.library.containsKey(e.name)) {
                        int choice = JOptionPane.showConfirmDialog(this,
                                "Entity '" + e.name + "' already exists in the library. Overwrite it?",
                                "Entity conflict", JOptionPane.YES_NO_OPTION);
                        if (choice != JOptionPane.YES_OPTION) continue;
                    }
                    project.addEntity(e);
                    imported++;
                }
            } catch (IOException ex) {
                errors.append(f.getName()).append(": ").append(ex.getMessage()).append('\n');
            }
        }
        libraryPanel.refresh(project);
        if (imported > 0) { dirty = true; updateTitle(); }
        if (errors.length() > 0) {
            JOptionPane.showMessageDialog(this, errors.toString(), "Import Warnings", JOptionPane.WARNING_MESSAGE);
        }
        status("Imported " + imported + " entity/entities.");
    }

    private void exportVhdl() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("VHDL Source (*.vhd)", "vhd"));
        chooser.setSelectedFile(new File(project.topEntityName + ".vhd"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".vhd") && !f.getName().toLowerCase().endsWith(".vhdl")) {
            f = new File(f.getParentFile(), f.getName() + ".vhd");
        }
        VhdlExporter exporter = new VhdlExporter();
        VhdlExporter.Result result = exporter.generate(project);
        try {
            java.nio.file.Files.write(f.toPath(), result.vhdl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            status("Exported " + f.getName() + (result.warnings.isEmpty() ? "" : (" (" + result.warnings.size() + " warning(s))")));
            if (!result.warnings.isEmpty()) {
                JOptionPane.showMessageDialog(this, String.join("\n", result.warnings), "Export Warnings", JOptionPane.WARNING_MESSAGE);
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to export VHDL:\n" + ex.getMessage(), "Export VHDL", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void setTopEntityName() {
        String name = Dialogs.promptString(this, "Top Entity Name", "Name of the generated entity:", project.topEntityName);
        if (name != null && !name.trim().isEmpty()) {
            project.topEntityName = name.trim();
            dirty = true;
            updateTitle();
        }
    }

    private void exit() {
        if (!confirmDiscardIfDirty()) return;
        dispose();
        System.exit(0);
    }

    private boolean confirmDiscardIfDirty() {
        if (!dirty) return true;
        int choice = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes. Discard them?", "Unsaved Changes",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private void updateTitle() {
        String name = currentProjectFile != null ? currentProjectFile.getName() : "Untitled";
        setTitle("VHDL Connector - " + name + (dirty ? " *" : ""));
    }

    private void status(String msg) {
        statusLabel.setText(msg);
    }

    // ---------------- LibraryPanel.Listener ----------------

    @Override
    public void onImportRequested() {
        importVhdlFiles();
    }

    @Override
    public void onAddToCanvasRequested(String entityName) {
        canvasPanel.addInstanceAtDefaultPosition(entityName);
        dirty = true;
        updateTitle();
    }

    @Override
    public void onRemoveRequested(String entityName) {
        boolean inUse = project.instances.stream().anyMatch(i -> i.entityName.equals(entityName));
        if (inUse) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Entity '" + entityName + "' is used by one or more instances on the canvas.\n" +
                            "Removing it will also delete those instances and their connections. Continue?",
                    "Remove Entity", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
            List<String> ids = project.instances.stream().filter(i -> i.entityName.equals(entityName))
                    .map(i -> i.id).collect(java.util.stream.Collectors.toList());
            for (String id : ids) project.removeInstance(id);
        }
        project.library.remove(entityName);
        libraryPanel.refresh(project);
        canvasPanel.layoutChanged();
        dirty = true;
        updateTitle();
        status("Removed entity '" + entityName + "' from library.");
    }

    @Override
    public void onViewRequested(String entityName) {
        VhdlEntity e = project.library.get(entityName);
        if (e != null) Dialogs.showEntitySummary(this, e);
    }

    // ---------------- CanvasPanel.Listener ----------------

    @Override
    public void onProjectChanged() {
        dirty = true;
        updateTitle();
    }

    @Override
    public void onStatusMessage(String msg) {
        status(msg);
    }
}
