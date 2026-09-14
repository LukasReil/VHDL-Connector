package vhdlconnector.gui;

import vhdlconnector.export.VhdlExporter;
import vhdlconnector.io.ProjectIO;
import vhdlconnector.model.Instance;
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

public class MainFrame extends JFrame implements WorkspacePanel.Listener, CanvasPanel.Listener {

    private Project project = new Project();
    private File currentProjectFile;
    private boolean dirty = false;

    private final WorkspacePanel workspacePanel = new WorkspacePanel();
    private final CanvasPanel canvasPanel = new CanvasPanel();
    private final JLabel statusLabel = new JLabel(" ");

    private final VhdlEntityParser parser = new VhdlEntityParser();
    private final ProjectIO projectIO = new ProjectIO();

    public MainFrame() {
        super("VHDL Connector");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        workspacePanel.setListener(this);
        canvasPanel.setListener(this);
        canvasPanel.setProject(project);
        workspacePanel.setWorkspaceRoot(project.workspaceRoot != null ? new File(project.workspaceRoot) : null, project);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspacePanel, new JScrollPane(canvasPanel));
        split.setDividerLocation(260);

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
        fileMenu.add(menuItem("Open Workspace Folder...", this::openWorkspaceFolder));
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
        workspacePanel.setWorkspaceRoot(null, project);
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
            workspacePanel.setWorkspaceRoot(project.workspaceRoot != null ? new File(project.workspaceRoot) : null, project);
            updateTitle();
            status("Opened " + currentProjectFile.getName());
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Failed to open project:\n" + ex.getMessage(), "Open Project", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Opens a folder as the project's workspace: the left panel becomes a browsable file
     *  tree rooted there (see WorkspacePanel), replacing the old up-front bulk import - the
     *  user instantiates entities from it on demand instead of the whole folder being scanned
     *  and parsed immediately. */
    private void openWorkspaceFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Open Workspace Folder");
        if (project.workspaceRoot != null) chooser.setCurrentDirectory(new File(project.workspaceRoot));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File folder = chooser.getSelectedFile();
        project.workspaceRoot = folder.getAbsolutePath();
        workspacePanel.setWorkspaceRoot(folder, project);
        dirty = true;
        updateTitle();
        status("Opened workspace folder: " + folder.getAbsolutePath());
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

    /** Dispatches on extension the same way the old per-file importers did: a .vho is a
     *  Vivado IP instantiation template (component declaration + port map template, not a
     *  full entity - see VhdlEntityParser.parseVhoFile), anything else is parsed as a normal
     *  entity declaration. */
    private List<VhdlEntity> parseWorkspaceFile(File f) throws IOException {
        return f.getName().toLowerCase().endsWith(".vho") ? parser.parseVhoFile(f) : parser.parseFile(f);
    }

    /** Merges freshly parsed entities into the library, prompting on a name collision with
     *  an existing (presumably different) entity - same conflict handling the old bulk
     *  importer used. Returns only the entities actually merged in (a declined overwrite is
     *  left out). */
    private List<VhdlEntity> mergeIntoLibrary(List<VhdlEntity> parsed) {
        List<VhdlEntity> merged = new ArrayList<>();
        for (VhdlEntity e : parsed) {
            if (project.library.containsKey(e.name)) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "Entity '" + e.name + "' already exists in the library. Overwrite it?",
                        "Entity conflict", JOptionPane.YES_NO_OPTION);
                if (choice != JOptionPane.YES_OPTION) continue;
            }
            project.addEntity(e);
            merged.add(e);
        }
        return merged;
    }

    /** If a file declared more than one entity/component, asks which one to act on next
     *  (placing on canvas, or previewing). Returns null if the user cancelled. */
    private VhdlEntity chooseEntity(List<VhdlEntity> candidates, String verb) {
        if (candidates.size() == 1) return candidates.get(0);
        String[] names = candidates.stream().map(e -> e.name).toArray(String[]::new);
        String chosen = (String) JOptionPane.showInputDialog(this,
                "This file declares multiple entities. Which one do you want to " + verb + "?",
                "Select Entity", JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
        if (chosen == null) return null;
        for (VhdlEntity e : candidates) if (e.name.equals(chosen)) return e;
        return null;
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
                "You have unsaved changes. Save them before exiting?", "Unsaved Changes",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.CANCEL_OPTION) {
            return false;
        }
        if (choice == JOptionPane.YES_OPTION) {
            saveProject();
            return !dirty; // if still dirty, user cancelled save dialog
        }

        // user chose "No" - discard changes
        return true;
    }

    private void updateTitle() {
        String name = currentProjectFile != null ? currentProjectFile.getName() : "Untitled";
        setTitle("VHDL Connector - " + name + (dirty ? " *" : ""));
    }

    private void status(String msg) {
        statusLabel.setText(msg);
    }

    // ---------------- WorkspacePanel.Listener ----------------

    @Override
    public void onAddToCanvasRequested(File file) {
        List<VhdlEntity> parsed;
        try {
            parsed = parseWorkspaceFile(file);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to parse " + file.getName() + ":\n" + ex.getMessage(),
                    "Parse Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (parsed.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No entity/component declaration found in:\n" + file.getName(),
                    "Add to Canvas", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<VhdlEntity> merged = mergeIntoLibrary(parsed);
        if (merged.isEmpty()) {
            status("No entity added from " + file.getName() + ".");
            return;
        }
        workspacePanel.setProject(project);
        dirty = true;
        updateTitle();
        VhdlEntity toPlace = chooseEntity(merged, "place");
        if (toPlace == null) {
            status("Merged " + merged.size() + " entit" + (merged.size() == 1 ? "y" : "ies") + " from "
                    + file.getName() + " into the library; none placed.");
            return;
        }
        canvasPanel.addInstanceAtDefaultPosition(toPlace.name);
        status("Instantiated '" + toPlace.name + "' from " + file.getName() + ".");
        // merging may have overwritten an existing entity with a different port set; this
        // immediately cleans up any resulting stale connection on other instances of it
        // (and reports it, taking over the status line above) rather than leaving it
        // silently blocking a pin until some unrelated canvas action happens to trigger
        // the same cleanup.
        canvasPanel.layoutChanged();
    }

    @Override
    public void onViewRequested(File file) {
        List<VhdlEntity> parsed;
        try {
            parsed = parseWorkspaceFile(file);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to parse " + file.getName() + ":\n" + ex.getMessage(),
                    "Parse Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (parsed.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No entity/component declaration found in:\n" + file.getName(),
                    "View Entity", JOptionPane.WARNING_MESSAGE);
            return;
        }
        VhdlEntity toShow = chooseEntity(parsed, "view");
        if (toShow != null) Dialogs.showEntitySummary(this, toShow);
    }

    @Override
    public void onReloadRequested(File file) {
        String abs = file.getAbsolutePath();
        VhdlEntity old = null;
        for (VhdlEntity e : project.library.values()) {
            if (abs.equals(e.sourceFile)) { old = e; break; }
        }
        if (old == null) return; // "Reload from Disk" is disabled in this case; nothing to do
        reloadEntityFromDisk(old);
    }

    /** Re-parses an already-in-the-library entity from its recorded source file and swaps it
     *  in under the same name, so existing instances keep their position, label, and generic
     *  overrides (an override for a generic that no longer exists after the edit is dropped).
     *  Used by "Reload from Disk" in the workspace tree's context menu. */
    private void reloadEntityFromDisk(VhdlEntity old) {
        String entityName = old.name;
        File f = new File(old.sourceFile);
        if (!f.isFile()) {
            JOptionPane.showMessageDialog(this, "Source file no longer exists:\n" + old.sourceFile,
                    "Cannot Reload", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            List<VhdlEntity> parsed = parseWorkspaceFile(f);
            VhdlEntity reloaded = null;
            for (VhdlEntity e : parsed) {
                if (e.name.equalsIgnoreCase(entityName)) { reloaded = e; break; }
            }
            if (reloaded == null) {
                JOptionPane.showMessageDialog(this,
                        "Entity '" + entityName + "' was not found in:\n" + f.getName() +
                                "\nIt may have been renamed or removed in the source file.",
                        "Cannot Reload", JOptionPane.WARNING_MESSAGE);
                return;
            }
            final VhdlEntity newEntity = reloaded;
            project.library.put(entityName, newEntity);
            for (Instance inst : project.instances) {
                if (inst.entityName.equals(entityName)) {
                    inst.genericOverrides.keySet().removeIf(key -> newEntity.getGeneric(key) == null);
                }
            }
            workspacePanel.setProject(project);
            dirty = true;
            updateTitle();
            status("Reloaded entity '" + entityName + "' from " + f.getName() + ".");
            // a reload may have dropped or renamed a port that existing instances were
            // wired to; this immediately cleans up any resulting stale connection (and
            // reports it, taking over the status line above) the same way a fresh merge does.
            canvasPanel.layoutChanged();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Failed to reload '" + entityName + "':\n" + ex.getMessage(),
                    "Reload Failed", JOptionPane.ERROR_MESSAGE);
        }
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
