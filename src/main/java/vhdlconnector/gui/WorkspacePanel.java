package vhdlconnector.gui;

import vhdlconnector.model.Project;
import vhdlconnector.model.VhdlEntity;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Left-hand panel: a lazily-loaded filesystem tree rooted at the currently open workspace
 *  folder. Every file is shown - nothing is pre-filtered by folder role, since simulation
 *  sources etc. simply never get double-clicked rather than needing to be hidden. The user
 *  browses to whatever .vhd/.vhdl entity file or .vho IP instantiation template they want and
 *  instantiates it on demand (double-click, or the right-click menu), instead of the whole
 *  workspace being bulk-imported up front. A .ecd file (Entity Connection Diagram - the
 *  per-diagram project file, opened as its own tab) is a third, distinct file type: double-click
 *  or right-click -> Open to load it as a tab, and right-click a folder for "New .ecd File..."
 *  to create one there.
 *
 *  Every directory the user has actually expanded is watched (java.nio.file.WatchService) for
 *  files/folders appearing or disappearing, so the tree reflects changes made outside the app -
 *  by Vivado, git, a text editor, etc. - without a manual "Refresh". Unexpanded folders don't
 *  need watching: they always list() fresh the moment they're first opened. A folder that isn't
 *  expanded yet (still showing the loading placeholder) is left untouched by a refresh, and an
 *  expanded folder's own expanded subfolders keep their state across a refresh of an ancestor -
 *  only what actually changed on disk is added, removed, or re-fetched. */
public class WorkspacePanel extends JPanel {

    public interface Listener {
        void onAddToCanvasRequested(File file);
        void onViewRequested(File file);
        void onReloadRequested(File file);
        void onOpenDiagramRequested(File ecdFile);
        void onNewDiagramRequested(File targetFolder, String chosenFileName);
        void onExportRequested(File ecdFile);
    }

    /** Sentinel child placed under every not-yet-expanded directory node so its expand arrow
     *  shows without having to list() the directory (and every directory beneath it,
     *  recursively) up front - a workspace can be large, and most of it is never opened. */
    private static final Object LOADING_PLACEHOLDER = new Object();

    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("(no workspace)");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final JTree tree = new JTree(treeModel);
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JLabel emptyLabel = new JLabel("", SwingConstants.CENTER);
    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TREE = "tree";

    private Listener listener;
    private Project project; // only consulted to decide whether "Reload from Disk" applies to a given file

    // Filesystem auto-refresh: one watch per expanded directory node. watchService is null
    // (feature silently unavailable, manual Refresh still works) if the platform can't provide
    // one. pendingRefresh + refreshDebounceTimer coalesce a burst of events (e.g. many files
    // changing at once) into a single tree update per settled directory instead of one per event.
    private WatchService watchService;
    private final Map<WatchKey, DefaultMutableTreeNode> watchKeyToNode = new HashMap<>();
    private final Map<DefaultMutableTreeNode, WatchKey> nodeToWatchKey = new HashMap<>();
    private final Set<DefaultMutableTreeNode> pendingRefresh = new HashSet<>();
    private final Timer refreshDebounceTimer = new Timer(250, e -> flushPendingRefreshes());

    public WorkspacePanel() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setPreferredSize(new Dimension(260, 0));

        add(new JLabel("Workspace"), BorderLayout.NORTH);

        emptyLabel.setForeground(new Color(130, 130, 130));
        JPanel empty = new JPanel(new BorderLayout());
        empty.add(emptyLabel, BorderLayout.CENTER);
        showNoWorkspaceMessage(null);

        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new WorkspaceCellRenderer());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) {
                Object last = event.getPath().getLastPathComponent();
                if (last instanceof DefaultMutableTreeNode) lazyLoad((DefaultMutableTreeNode) last);
            }
            @Override public void treeWillCollapse(TreeExpansionEvent event) { }
        });
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2) return;
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                File f = fileAt(path);
                if (f == null || !f.isFile() || listener == null) return;
                if (isDiagramFile(f)) listener.onOpenDiagramRequested(f);
                else if (isEntityFile(f)) listener.onAddToCanvasRequested(f);
            }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
        });

        cardPanel.add(empty, CARD_EMPTY);
        cardPanel.add(new JScrollPane(tree), CARD_TREE);
        cards.show(cardPanel, CARD_EMPTY);
        add(cardPanel, BorderLayout.CENTER);

        refreshDebounceTimer.setRepeats(false);
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Thread watchThread = new Thread(this::watchLoop, "workspace-fs-watch");
            watchThread.setDaemon(true);
            watchThread.start();
        } catch (IOException ex) {
            watchService = null; // auto-refresh unavailable on this platform; manual Refresh still works
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Opens (or re-opens) the tree at the given folder. Pass null (or a path that no longer
     *  exists on disk - e.g. a diagram's recorded workspaceRoot on a machine where that folder
     *  moved) to fall back to the empty-state card; this is deliberately silent, not an error
     *  dialog, since it can legitimately happen whenever a .ecd/.json from elsewhere is opened. */
    public void setWorkspaceRoot(File root, Project project) {
        this.project = project;
        unregisterAllWatches();
        if (root == null || !root.isDirectory()) {
            rootNode.setUserObject("(no workspace)");
            rootNode.removeAllChildren();
            treeModel.reload();
            showNoWorkspaceMessage(root);
            cards.show(cardPanel, CARD_EMPTY);
            return;
        }
        rootNode.setUserObject(root);
        loadChildren(rootNode);
        treeModel.reload();
        tree.expandPath(new TreePath(rootNode.getPath()));
        cards.show(cardPanel, CARD_TREE);
    }

    /** The library (or an entity's sourceFile) may have changed - e.g. after a reload, or the
     *  user switching to a different open diagram tab - independently of the workspace folder
     *  itself; call this so "Reload from Disk" enablement (computed fresh whenever the popup is
     *  built) stays accurate for whichever diagram is currently active. Does not touch the tree
     *  structure, since switching tabs never adds/removes files on disk. */
    public void setProject(Project project) {
        this.project = project;
    }

    /** Re-reads the given folder's children from disk - e.g. right after a new .ecd file was
     *  created inside it, so it appears in the tree without a manual "Refresh". Purely a
     *  tree-display refresh, no library/project side effects; a no-op if that folder isn't
     *  currently present in the tree (not yet expanded, or under a different workspace root). */
    public void refreshFolder(File folder) {
        refreshNodeFor(folder);
    }

    private void showNoWorkspaceMessage(File missingRoot) {
        if (missingRoot == null) {
            emptyLabel.setText("<html><center>No workspace open.<br><br>File &rarr; Open Workspace Folder...<br>to get started.</center></html>");
        } else {
            emptyLabel.setText("<html><center>Workspace folder no longer found:<br>" + missingRoot.getAbsolutePath()
                    + "<br><br>File &rarr; Open Workspace Folder...<br>to pick a folder.</center></html>");
        }
    }

    private void lazyLoad(DefaultMutableTreeNode node) {
        if (isUnloadedPlaceholder(node)) loadChildren(node);
    }

    private static boolean isUnloadedPlaceholder(DefaultMutableTreeNode node) {
        return node.getChildCount() == 1 && ((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject() == LOADING_PLACEHOLDER;
    }

    private static List<File> sortEntries(File[] kids) {
        List<File> sorted = new ArrayList<>(java.util.Arrays.asList(kids));
        sorted.sort(Comparator.<File, Boolean>comparing(f -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase()));
        return sorted;
    }

    /** Cold (re)load of a directory node's children - discards whatever was there before, so
     *  any previously-expanded grandchildren collapse back to lazy. Used for the initial load
     *  of a just-expanded (placeholder) node, where there's nothing worth preserving yet. */
    private void loadChildren(DefaultMutableTreeNode node) {
        node.removeAllChildren();
        Object uo = node.getUserObject();
        File dir = uo instanceof File ? (File) uo : null;
        if (dir != null) {
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (File f : sortEntries(kids)) {
                    DefaultMutableTreeNode child = new DefaultMutableTreeNode(f);
                    if (f.isDirectory()) child.add(new DefaultMutableTreeNode(LOADING_PLACEHOLDER));
                    node.add(child);
                }
            }
            registerWatch(node, dir);
        }
        treeModel.nodeStructureChanged(node);
    }

    /** Re-reads an already-loaded directory node's children, reusing the same child TreeNode
     *  instances for entries that are still present (so JTree keeps their expansion/loaded state
     *  intact - refreshing a folder never collapses an already-open subfolder just because a
     *  sibling changed) and only adding/removing nodes for what actually changed on disk. A
     *  not-yet-expanded (placeholder) node is left alone - it'll list() fresh the moment it's
     *  first opened regardless, so there's nothing to reconcile. */
    private void refreshChildrenPreservingState(DefaultMutableTreeNode node) {
        Object uo = node.getUserObject();
        File dir = uo instanceof File ? (File) uo : null;
        if (dir == null || !dir.isDirectory() || isUnloadedPlaceholder(node)) return;
        File[] kids = dir.listFiles();
        if (kids == null) return; // dir just became inaccessible; leave the tree showing its last known state

        Map<String, DefaultMutableTreeNode> existingByPath = new HashMap<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (child.getUserObject() instanceof File) {
                existingByPath.put(((File) child.getUserObject()).getAbsolutePath(), child);
            }
        }

        Set<String> stillPresent = new HashSet<>();
        node.removeAllChildren();
        for (File f : sortEntries(kids)) {
            String path = f.getAbsolutePath();
            stillPresent.add(path);
            DefaultMutableTreeNode child = existingByPath.get(path);
            if (child == null) {
                child = new DefaultMutableTreeNode(f);
                if (f.isDirectory()) child.add(new DefaultMutableTreeNode(LOADING_PLACEHOLDER));
            }
            node.add(child);
        }
        for (Map.Entry<String, DefaultMutableTreeNode> entry : existingByPath.entrySet()) {
            if (!stillPresent.contains(entry.getKey())) unregisterWatch(entry.getValue());
        }
        treeModel.nodeStructureChanged(node);
    }

    private void maybeShowPopup(java.awt.event.MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) return;
        tree.setSelectionPath(path);
        File f = fileAt(path);
        if (f == null) return;
        JPopupMenu menu = buildPopup(f);
        if (menu != null) menu.show(tree, e.getX(), e.getY());
    }

    private JPopupMenu buildPopup(File f) {
        JPopupMenu menu = new JPopupMenu();
        if (f.isDirectory()) {
            JMenuItem newDiagram = new JMenuItem("New .ecd File...");
            newDiagram.addActionListener(a -> promptNewDiagram(f));
            JMenuItem refresh = new JMenuItem("Refresh");
            refresh.addActionListener(a -> refreshNodeFor(f));
            menu.add(newDiagram);
            menu.add(refresh);
            return menu;
        }
        if (isDiagramFile(f)) {
            JMenuItem open = new JMenuItem("Open");
            open.addActionListener(a -> { if (listener != null) listener.onOpenDiagramRequested(f); });
            JMenuItem export = new JMenuItem("Export as VHDL");
            export.addActionListener(a -> { if (listener != null) listener.onExportRequested(f); });
            menu.add(open);
            menu.add(export);
            return menu;
        }
        if (!isEntityFile(f)) return null;
        JMenuItem add = new JMenuItem("Add to Canvas");
        add.addActionListener(a -> { if (listener != null) listener.onAddToCanvasRequested(f); });
        JMenuItem view = new JMenuItem("View Entity");
        view.addActionListener(a -> { if (listener != null) listener.onViewRequested(f); });
        JMenuItem reload = new JMenuItem("Reload from Disk");
        reload.setEnabled(isReloadable(f));
        reload.addActionListener(a -> { if (listener != null) listener.onReloadRequested(f); });
        menu.add(add);
        menu.add(view);
        menu.add(reload);
        return menu;
    }

    /** Prompts for a filename and, once it's confirmed not to already exist in that folder,
     *  hands off actually creating+opening the file to the listener (MainFrame owns Project/
     *  ProjectIO and tab management, not this panel). */
    private void promptNewDiagram(File folder) {
        Object result = JOptionPane.showInputDialog(this, "File name:", "New .ecd File",
                JOptionPane.PLAIN_MESSAGE, null, null, "diagram.ecd");
        if (result == null) return;
        String name = result.toString().trim();
        if (name.isEmpty()) return;
        if (!name.toLowerCase().endsWith(".ecd")) name = name + ".ecd";
        File target = new File(folder, name);
        if (target.exists()) {
            JOptionPane.showMessageDialog(this, "A file named '" + name + "' already exists in this folder.",
                    "New .ecd File", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (listener != null) listener.onNewDiagramRequested(folder, name);
    }

    private boolean isReloadable(File f) {
        if (project == null) return false;
        String abs = f.getAbsolutePath();
        for (VhdlEntity e : project.library.values()) {
            if (abs.equals(e.sourceFile)) return true;
        }
        return false;
    }

    private void refreshNodeFor(File dir) {
        DefaultMutableTreeNode node = findNode(rootNode, dir);
        if (node == null) return;
        if (isUnloadedPlaceholder(node)) loadChildren(node);
        else refreshChildrenPreservingState(node);
    }

    // ---------------- filesystem auto-refresh ----------------

    private void registerWatch(DefaultMutableTreeNode node, File dir) {
        if (watchService == null || nodeToWatchKey.containsKey(node)) return;
        try {
            WatchKey key = dir.toPath().register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
            watchKeyToNode.put(key, node);
            nodeToWatchKey.put(node, key);
        } catch (IOException ex) {
            // can't watch this directory (permissions, unsupported filesystem, ...) - the tree
            // still works, it just won't auto-refresh for changes made under it
        }
    }

    private void unregisterWatch(DefaultMutableTreeNode node) {
        WatchKey key = nodeToWatchKey.remove(node);
        if (key != null) {
            key.cancel();
            watchKeyToNode.remove(key);
        }
    }

    private void unregisterAllWatches() {
        for (WatchKey key : watchKeyToNode.keySet()) key.cancel();
        watchKeyToNode.clear();
        nodeToWatchKey.clear();
        pendingRefresh.clear();
    }

    /** Runs on a dedicated daemon thread for the panel's lifetime, blocking on watchService.take()
     *  between filesystem events. Never touches Swing state directly - every reaction is handed
     *  to the EDT via invokeLater, same as any other background-thread-to-UI handoff. */
    private void watchLoop() {
        while (true) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException ex) {
                return;
            }
            key.pollEvents(); // contents don't matter - a refresh just re-lists the directory
            boolean valid = key.reset();
            SwingUtilities.invokeLater(() -> handleWatchSignal(key, valid));
        }
    }

    private void handleWatchSignal(WatchKey key, boolean stillValid) {
        DefaultMutableTreeNode node = watchKeyToNode.get(key);
        if (node == null) return; // stale signal for a watch that's since been torn down (e.g. workspace switched)
        if (!stillValid) {
            unregisterWatch(node);
            if (node == rootNode) setWorkspaceRoot(null, project); // the workspace folder itself vanished
            return;
        }
        pendingRefresh.add(node);
        refreshDebounceTimer.restart();
    }

    private void flushPendingRefreshes() {
        for (DefaultMutableTreeNode node : pendingRefresh) refreshChildrenPreservingState(node);
        pendingRefresh.clear();
    }

    private DefaultMutableTreeNode findNode(DefaultMutableTreeNode node, File target) {
        Object uo = node.getUserObject();
        if (uo instanceof File && ((File) uo).getAbsolutePath().equals(target.getAbsolutePath())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode found = findNode((DefaultMutableTreeNode) node.getChildAt(i), target);
            if (found != null) return found;
        }
        return null;
    }

    private static File fileAt(TreePath path) {
        Object uo = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return uo instanceof File ? (File) uo : null;
    }

    private static boolean isEntityFile(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".vhd") || n.endsWith(".vhdl") || n.endsWith(".vho");
    }

    private static boolean isDiagramFile(File f) {
        return f.getName().toLowerCase().endsWith(".ecd");
    }

    private static final class WorkspaceCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                        boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            Object uo = ((DefaultMutableTreeNode) value).getUserObject();
            if (uo instanceof File) {
                File f = (File) uo;
                boolean isRoot = value == tree.getModel().getRoot();
                setText(isRoot ? f.getAbsolutePath() : f.getName());
                if (f.isFile() && isDiagramFile(f)) {
                    setForeground(new Color(40, 110, 210));
                    setFont(getFont().deriveFont(Font.BOLD));
                } else if (f.isFile() && !isEntityFile(f)) {
                    setForeground(new Color(160, 160, 160));
                } else {
                    setForeground(UIManager.getColor("Tree.textForeground"));
                }
            } else if (uo == LOADING_PLACEHOLDER) {
                setText("Loading...");
                setForeground(new Color(160, 160, 160));
            } else {
                setText(String.valueOf(uo));
            }
            return this;
        }
    }
}
