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

/** Every diagram is a .ecd (Entity Connection Diagram) file that must live somewhere inside
 *  an open workspace folder - created via the workspace tree's "New .ecd File..." and opened
 *  by double-clicking it there, never as a free-floating in-memory scratch project - and each
 *  open diagram gets its own tab (DiagramTab) with its own Project/CanvasPanel, so several can
 *  be worked on side by side. The one exception is "Open Project... (.json)", kept purely to
 *  open project files saved before this change; ProjectIO's format is identical either way, so
 *  it opens into a tab exactly like a .ecd does. The workspace tree itself is shared/global
 *  across all tabs, not per-tab. */
public class MainFrame extends JFrame implements WorkspacePanel.Listener {

    /** One open diagram: its own Project + CanvasPanel + backing file + dirty flag, plus the
     *  export path remembered after the first "Export VHDL..." this session so later exports
     *  on this tab don't prompt again. Implements CanvasPanel.Listener itself (rather than
     *  MainFrame doing it for a single global project) so canvas edits mark exactly this tab
     *  dirty and retitle exactly this tab, never some other open one. */
    private final class DiagramTab implements CanvasPanel.Listener {
        final Project project;
        final CanvasPanel canvasPanel = new CanvasPanel();
        File file; // always non-null once the tab exists - tabs can't exist without a backing file
        boolean dirty;
        File lastExportPath;

        DiagramTab(Project project, File file) {
            this.project = project;
            this.file = file;
            canvasPanel.setListener(this);
            canvasPanel.setProject(project);
        }

        @Override
        public void onProjectChanged() {
            dirty = true;
            refreshTabTitle(this);
        }

        @Override
        public void onStatusMessage(String msg) {
            status(msg);
        }
    }

    private final List<DiagramTab> tabs = new ArrayList<>();
    private final JTabbedPane tabbedPane = new JTabbedPane();
    private final CardLayout canvasCards = new CardLayout();
    private final JPanel canvasCardPanel = new JPanel(canvasCards);
    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TABS = "tabs";

    /** The folder currently browsed in the workspace tree - shared across all tabs, and
     *  independent of any individual tab's Project.workspaceRoot (which just remembers, for
     *  portability, which workspace a given .ecd was created under). */
    private File workspaceFolder;

    private final WorkspacePanel workspacePanel = new WorkspacePanel();
    private final JLabel statusLabel = new JLabel(" ");

    private final VhdlEntityParser parser = new VhdlEntityParser();
    private final ProjectIO projectIO = new ProjectIO();

    public MainFrame() {
        super("VHDL Connector");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        workspacePanel.setListener(this);
        workspacePanel.setWorkspaceRoot(null, null);

        JLabel emptyCanvasLabel = new JLabel(
                "<html><center>Open a workspace folder, then create or open a .ecd diagram file<br>to get started.</center></html>",
                SwingConstants.CENTER);
        emptyCanvasLabel.setForeground(new Color(130, 130, 130));
        JPanel emptyCanvasPanel = new JPanel(new BorderLayout());
        emptyCanvasPanel.add(emptyCanvasLabel, BorderLayout.CENTER);
        canvasCardPanel.add(emptyCanvasPanel, CARD_EMPTY);
        canvasCardPanel.add(tabbedPane, CARD_TABS);
        canvasCards.show(canvasCardPanel, CARD_EMPTY);

        tabbedPane.addChangeListener(e -> {
            DiagramTab tab = activeTab();
            workspacePanel.setProject(tab != null ? tab.project : null);
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspacePanel, canvasCardPanel);
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
    }

    // ---------------- menu ----------------

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        JMenu fileMenu = new JMenu("File");
        fileMenu.add(menuItem("Open Project... (.json)", this::openProject, KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcutMask)));
        fileMenu.add(menuItem("Save", this::saveProject, KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcutMask)));
        fileMenu.add(menuItem("Save As...", this::saveProjectAs, KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcutMask | InputEvent.SHIFT_DOWN_MASK)));
        fileMenu.add(menuItem("Close Tab", this::closeActiveTab, KeyStroke.getKeyStroke(KeyEvent.VK_W, shortcutMask)));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Open Workspace Folder...", this::openWorkspaceFolder));
        fileMenu.add(menuItem("Export VHDL...", this::exportVhdl));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Exit", this::exit));

        JMenu editMenu = new JMenu("Edit");
        editMenu.add(menuItem("Set Top Entity Name...", this::setTopEntityName));
        editMenu.add(menuItem("Auto-Connect...", this::showAutoConnectDialog));

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

    // ---------------- tab management ----------------

    private DiagramTab activeTab() {
        int idx = tabbedPane.getSelectedIndex();
        return idx >= 0 && idx < tabs.size() ? tabs.get(idx) : null;
    }

    /** Opens `file` as a new tab, or just switches to it if a tab for that same file (compared
     *  canonically) is already open - never a duplicate tab for one file. If no workspace is
     *  currently browsed and this project remembers one that still exists on disk, adopts it
     *  (mirrors the old single-project behavior of restoring the workspace on open, without
     *  surprising the user by yanking the tree out from under an already-open workspace). */
    private void openTab(Project project, File file) {
        for (DiagramTab t : tabs) {
            if (sameFile(t.file, file)) {
                tabbedPane.setSelectedIndex(tabs.indexOf(t));
                return;
            }
        }
        DiagramTab tab = new DiagramTab(project, file);
        tabs.add(tab);
        tabbedPane.addTab(file.getName(), new JScrollPane(tab.canvasPanel));
        tabbedPane.setSelectedIndex(tabs.size() - 1);
        refreshTabTitle(tab);
        updateEmptyState();
        if (workspaceFolder == null && project.workspaceRoot != null && new File(project.workspaceRoot).isDirectory()) {
            workspaceFolder = new File(project.workspaceRoot);
            workspacePanel.setWorkspaceRoot(workspaceFolder, tab.project);
        } else {
            workspacePanel.setProject(tab.project);
        }
    }

    private void refreshTabTitle(DiagramTab tab) {
        int idx = tabs.indexOf(tab);
        if (idx < 0) return;
        tabbedPane.setTitleAt(idx, tab.file.getName() + (tab.dirty ? " *" : ""));
    }

    private void updateEmptyState() {
        canvasCards.show(canvasCardPanel, tabs.isEmpty() ? CARD_EMPTY : CARD_TABS);
    }

    private void closeActiveTab() {
        DiagramTab tab = activeTab();
        if (tab != null) closeTab(tab);
    }

    /** Closes one tab after the same unsaved-changes confirmation used everywhere else.
     *  Returns false if the user cancelled (tab stays open) - used by exit() to stop closing
     *  the remaining tabs the moment one close is cancelled. */
    private boolean closeTab(DiagramTab tab) {
        int idx = tabs.indexOf(tab);
        if (idx < 0) return true;
        if (tabbedPane.getSelectedIndex() != idx) tabbedPane.setSelectedIndex(idx);
        if (!confirmDiscardIfDirty(tab)) return false;
        tabs.remove(idx);
        tabbedPane.remove(idx);
        updateEmptyState();
        return true;
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException ex) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    // ---------------- project lifecycle ----------------

    /** Legacy path: opens an old-format project .json (predating the .ecd/workspace-tree
     *  workflow) as a new tab. Nothing is discarded/replaced by this - unlike the pre-tabs
     *  "Open Project", it can no longer clobber whatever else is already open. */
    private void openProject() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("VHDL Connector Project (*.json)", "json"));
        if (workspaceFolder != null) chooser.setCurrentDirectory(workspaceFolder);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        try {
            Project project = projectIO.load(f);
            openTab(project, f);
            status("Opened " + f.getName());
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Failed to open project:\n" + ex.getMessage(), "Open Project", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Opens a folder as the workspace: the left panel becomes a browsable file tree rooted
     *  there (see WorkspacePanel), shared by every open tab. Also stamps it onto the active
     *  tab's Project.workspaceRoot (if any tab is active) so that saving it remembers the
     *  association, the same way opening a workspace used to behave before there were tabs. */
    private void openWorkspaceFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Open Workspace Folder");
        if (workspaceFolder != null) chooser.setCurrentDirectory(workspaceFolder);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File folder = chooser.getSelectedFile();
        workspaceFolder = folder;
        DiagramTab tab = activeTab();
        if (tab != null) {
            tab.project.workspaceRoot = folder.getAbsolutePath();
            tab.dirty = true;
            refreshTabTitle(tab);
        }
        workspacePanel.setWorkspaceRoot(folder, tab != null ? tab.project : null);
        status("Opened workspace folder: " + folder.getAbsolutePath());
    }

    private void saveProject() {
        DiagramTab tab = activeTab();
        if (tab == null) { status("Open or create a diagram first."); return; }
        if (tab.file == null) { saveProjectAs(); return; } // defensive; a tab always has a backing file today
        doSave(tab, tab.file);
    }

    private void saveProjectAs() {
        DiagramTab tab = activeTab();
        if (tab == null) { status("Open or create a diagram first."); return; }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Entity Connection Diagram (*.ecd)", "ecd"));
        chooser.setSelectedFile(tab.file);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        String lower = f.getName().toLowerCase();
        if (!lower.endsWith(".ecd") && !lower.endsWith(".json")) f = new File(f.getParentFile(), f.getName() + ".ecd");
        doSave(tab, f);
    }

    private void doSave(DiagramTab tab, File f) {
        try {
            projectIO.save(tab.project, f);
            tab.file = f;
            tab.dirty = false;
            refreshTabTitle(tab);
            status("Saved " + f.getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to save diagram:\n" + ex.getMessage(), "Save Diagram", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Dispatches on extension the same way the old per-file importers did: a .vho is a
     *  Vivado IP instantiation template (component declaration + port map template, not a
     *  full entity - see VhdlEntityParser.parseVhoFile), anything else is parsed as a normal
     *  entity declaration. */
    private List<VhdlEntity> parseWorkspaceFile(File f) throws IOException {
        return f.getName().toLowerCase().endsWith(".vho") ? parser.parseVhoFile(f) : parser.parseFile(f);
    }

    /** Merges freshly parsed entities into the given tab's library, prompting on a name
     *  collision with an existing (presumably different) entity - same conflict handling the
     *  old bulk importer used. Returns only the entities actually merged in (a declined
     *  overwrite is left out). */
    private List<VhdlEntity> mergeIntoLibrary(Project project, List<VhdlEntity> parsed) {
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

    /** First export on a tab prompts (defaulted next to that tab's file) and remembers the
     *  chosen path; every export after that on the same tab writes straight there, silently. */
    private void exportVhdl() {
        DiagramTab tab = activeTab();
        if (tab == null) { status("Open or create a diagram first."); return; }
        File target = tab.lastExportPath;
        if (target == null) {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("VHDL Source (*.vhd)", "vhd"));
            File defaultDir = tab.file.getAbsoluteFile().getParentFile();
            if (defaultDir != null) {
                chooser.setCurrentDirectory(defaultDir);
                chooser.setSelectedFile(new File(defaultDir, tab.project.topEntityName + ".vhd"));
            } else {
                chooser.setSelectedFile(new File(tab.project.topEntityName + ".vhd"));
            }
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = chooser.getSelectedFile();
            String lower = f.getName().toLowerCase();
            if (!lower.endsWith(".vhd") && !lower.endsWith(".vhdl")) f = new File(f.getParentFile(), f.getName() + ".vhd");
            target = f;
            tab.lastExportPath = f;
        }
        doExport(tab, target);
    }

    private void doExport(DiagramTab tab, File f) {
        VhdlExporter exporter = new VhdlExporter();
        VhdlExporter.Result result = exporter.generate(tab.project);
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
        DiagramTab tab = activeTab();
        if (tab == null) { status("Open or create a diagram first."); return; }
        String name = Dialogs.promptString(this, "Top Entity Name", "Name of the generated entity:", tab.project.topEntityName);
        if (name != null && !name.trim().isEmpty()) {
            tab.project.topEntityName = name.trim();
            tab.dirty = true;
            refreshTabTitle(tab);
        }
    }

    private void showAutoConnectDialog() {
        DiagramTab tab = activeTab();
        if (tab == null) { status("Open or create a diagram first."); return; }
        tab.canvasPanel.showAutoConnectDialog();
    }

    private void exit() {
        for (DiagramTab tab : new ArrayList<>(tabs)) {
            if (!closeTab(tab)) return;
        }
        dispose();
        System.exit(0);
    }

    private boolean confirmDiscardIfDirty(DiagramTab tab) {
        if (!tab.dirty) return true;
        int choice = JOptionPane.showConfirmDialog(this,
                "'" + tab.file.getName() + "' has unsaved changes. Save them before closing?", "Unsaved Changes",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.CANCEL_OPTION) {
            return false;
        }
        if (choice == JOptionPane.YES_OPTION) {
            doSave(tab, tab.file);
            return !tab.dirty; // if still dirty, user cancelled the save dialog
        }

        // user chose "No" - discard changes
        return true;
    }

    private void status(String msg) {
        statusLabel.setText(msg);
    }

    // ---------------- WorkspacePanel.Listener ----------------

    @Override
    public void onAddToCanvasRequested(File file) {
        DiagramTab tab = activeTab();
        if (tab == null) { status("Open or create a diagram first."); return; }
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
        List<VhdlEntity> merged = mergeIntoLibrary(tab.project, parsed);
        if (merged.isEmpty()) {
            status("No entity added from " + file.getName() + ".");
            return;
        }
        workspacePanel.setProject(tab.project);
        tab.dirty = true;
        refreshTabTitle(tab);
        VhdlEntity toPlace = chooseEntity(merged, "place");
        if (toPlace == null) {
            status("Merged " + merged.size() + " entit" + (merged.size() == 1 ? "y" : "ies") + " from "
                    + file.getName() + " into the library; none placed.");
            return;
        }
        tab.canvasPanel.addInstanceAtDefaultPosition(toPlace.name);
        status("Instantiated '" + toPlace.name + "' from " + file.getName() + ".");
        // merging may have overwritten an existing entity with a different port set; this
        // immediately cleans up any resulting stale connection on other instances of it
        // (and reports it, taking over the status line above) rather than leaving it
        // silently blocking a pin until some unrelated canvas action happens to trigger
        // the same cleanup.
        tab.canvasPanel.layoutChanged();
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
        DiagramTab tab = activeTab();
        if (tab == null) return; // the context-menu item is only enabled when the active tab's library already matched this file
        String abs = file.getAbsolutePath();
        VhdlEntity old = null;
        for (VhdlEntity e : tab.project.library.values()) {
            if (abs.equals(e.sourceFile)) { old = e; break; }
        }
        if (old == null) return; // "Reload from Disk" is disabled in this case; nothing to do
        reloadEntityFromDisk(tab, old);
    }

    @Override
    public void onOpenDiagramRequested(File ecdFile) {
        try {
            Project project = projectIO.load(ecdFile);
            openTab(project, ecdFile);
            status("Opened " + ecdFile.getName());
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Failed to open diagram:\n" + ex.getMessage(), "Open Diagram", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** "Export as VHDL" from the tree's .ecd context menu: opens (or switches to) that
     *  diagram's tab first - if it's already open with unsaved changes, the freshly-loaded
     *  copy is discarded in favor of the open tab (see openTab's dedup-by-file check), so
     *  the in-memory version is always what actually gets exported - then runs the same
     *  dialog-once-then-remember export flow the File menu uses. */
    @Override
    public void onExportRequested(File ecdFile) {
        try {
            Project project = projectIO.load(ecdFile);
            openTab(project, ecdFile);
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Failed to open diagram:\n" + ex.getMessage(), "Export VHDL", JOptionPane.ERROR_MESSAGE);
            return;
        }
        exportVhdl();
    }

    @Override
    public void onNewDiagramRequested(File targetFolder, String chosenFileName) {
        File target = new File(targetFolder, chosenFileName);
        Project project = new Project();
        project.topEntityName = chosenFileName.substring(0, chosenFileName.length() - ".ecd".length());
        if (workspaceFolder != null) project.workspaceRoot = workspaceFolder.getAbsolutePath();
        try {
            projectIO.save(project, target);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to create diagram file:\n" + ex.getMessage(),
                    "New Diagram", JOptionPane.ERROR_MESSAGE);
            return;
        }
        workspacePanel.refreshFolder(targetFolder);
        openTab(project, target);
        status("Created " + target.getName());
    }

    /** Re-parses an already-in-the-library entity from its recorded source file and swaps it
     *  in under the same name, so existing instances keep their position, label, and generic
     *  overrides (an override for a generic that no longer exists after the edit is dropped).
     *  Used by "Reload from Disk" in the workspace tree's context menu. */
    private void reloadEntityFromDisk(DiagramTab tab, VhdlEntity old) {
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
            tab.project.library.put(entityName, newEntity);
            for (Instance inst : tab.project.instances) {
                if (inst.entityName.equals(entityName)) {
                    inst.genericOverrides.keySet().removeIf(key -> newEntity.getGeneric(key) == null);
                }
            }
            workspacePanel.setProject(tab.project);
            tab.dirty = true;
            refreshTabTitle(tab);
            status("Reloaded entity '" + entityName + "' from " + f.getName() + ".");
            // a reload may have dropped or renamed a port that existing instances were
            // wired to; this immediately cleans up any resulting stale connection (and
            // reports it, taking over the status line above) the same way a fresh merge does.
            tab.canvasPanel.layoutChanged();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Failed to reload '" + entityName + "':\n" + ex.getMessage(),
                    "Reload Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

}
