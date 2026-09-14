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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Left-hand panel: a lazily-loaded filesystem tree rooted at the currently open workspace
 *  folder. Every file is shown - nothing is pre-filtered by folder role, since simulation
 *  sources etc. simply never get double-clicked rather than needing to be hidden. The user
 *  browses to whatever .vhd/.vhdl entity file or .vho IP instantiation template they want and
 *  instantiates it on demand (double-click, or the right-click menu), instead of the whole
 *  workspace being bulk-imported up front. A .ecd file (Entity Connection Diagram - the
 *  per-diagram project file, opened as its own tab) is a third, distinct file type: double-click
 *  or right-click -> Open to load it as a tab, and right-click a folder for "New .ecd File..."
 *  to create one there. */
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
        if (node.getChildCount() == 1 && ((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject() == LOADING_PLACEHOLDER) {
            loadChildren(node);
        }
    }

    private void loadChildren(DefaultMutableTreeNode node) {
        node.removeAllChildren();
        Object uo = node.getUserObject();
        File dir = uo instanceof File ? (File) uo : null;
        if (dir != null) {
            File[] kids = dir.listFiles();
            if (kids != null) {
                List<File> sorted = new ArrayList<>(java.util.Arrays.asList(kids));
                sorted.sort(Comparator.<File, Boolean>comparing(f -> !f.isDirectory())
                        .thenComparing(f -> f.getName().toLowerCase()));
                for (File f : sorted) {
                    DefaultMutableTreeNode child = new DefaultMutableTreeNode(f);
                    if (f.isDirectory()) child.add(new DefaultMutableTreeNode(LOADING_PLACEHOLDER));
                    node.add(child);
                }
            }
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
        if (node != null) loadChildren(node);
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
