package vhdlconnector.gui;

import vhdlconnector.model.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The block-design canvas: draggable instance blocks and external port pins,
 *  wired together by click-and-drag connections, drawn Vivado-block-design style. */
public class CanvasPanel extends JPanel {

    public interface Listener {
        void onProjectChanged();
        void onStatusMessage(String msg);
    }

    private static final int HEADER_HEIGHT = 34;
    private static final int PIN_SPACING = 20;
    private static final int PIN_TOP_PAD = 14;
    private static final int PIN_BOTTOM_PAD = 10;
    private static final int MIN_BOX_WIDTH = 150;
    private static final double PIN_HIT_RADIUS = 7;
    private static final double EXT_PORT_HIT_RADIUS = 9;   // grab the diamond body/label to move it
    private static final double EXT_WIRE_HIT_RADIUS = 5;   // must click closer to the tip to start a wire
    private static final double CONN_HIT_DIST = 5;

    private Project project;
    private Listener listener;
    private final Map<String, List<Point2D>> routedPaths = new java.util.HashMap<>(); // connection id -> routed polyline
    private final List<Connection> visualConnections = new ArrayList<>(); // one representative per drawn wire (a whole AXI-Stream bundle collapses to one)

    // Entity names whose sourceFile no longer exists on disk, as of the last layoutChanged()/
    // recomputeRoutes() pass - checked there (not on every paint, which would mean a stat()
    // per instance per repaint, including during drag) so it's a plain cached lookup here.
    // Deliberately never used to prune anything - a missing file just gets flagged so the user
    // notices, per an explicit decision that disk hiccups (an unmounted drive, a rename in
    // progress) must never silently delete wiring.
    private final java.util.Set<String> missingSourceEntities = new java.util.HashSet<>();

    // drag state
    private Instance draggingInstance;
    private List<ExternalPort> draggingExternalPorts; // all members of the pin being dragged (1 for a plain port)
    private double dragOffsetX, dragOffsetY;

    private boolean draggingWire;
    private PinTarget wireStart;
    private Point wireCurrentPoint;

    private Object selectedItem; // Instance | ExternalPort | Connection | null
    private final java.util.Set<Instance> selectedInstances = new java.util.LinkedHashSet<>(); // multi-select, for copy/paste

    private final List<ClipboardInstance> clipboardInstances = new ArrayList<>();
    private final List<ClipboardConnection> clipboardConnections = new ArrayList<>();
    private static final double PASTE_OFFSET = 30;

    private OrthogonalRouter router = new OrthogonalRouter();

    public CanvasPanel() {
        setBackground(new Color(250, 250, 252));
        setFocusable(true);
        setPreferredSize(new Dimension(1600, 1000));

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { handlePressed(e); }
            @Override public void mouseDragged(MouseEvent e) { handleDragged(e); }
            @Override public void mouseReleased(MouseEvent e) { handleReleased(e); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);

        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteSelected");
        getActionMap().put("deleteSelected", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { deleteSelected(); }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copySelection");
        getActionMap().put("copySelection", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { copySelection(); }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "pasteClipboard");
        getActionMap().put("pasteClipboard", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { pasteClipboard(); }
        });

        ToolTipManager.sharedInstance().registerComponent(this);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        if (project == null) return null;
        PinTarget pin = hitTestPin(e.getPoint());
        if (pin != null && pin.group != null) {
            StringBuilder sb = new StringBuilder("<html>AXI-Stream ").append(pin.group.isMaster() ? "master" : "slave")
                    .append(" interface <b>").append(pin.group.name).append("</b><br>");
            for (Map.Entry<String, Port> en : pin.group.signals.entrySet()) {
                sb.append(en.getValue().name).append(" (").append(en.getValue().direction.vhdl()).append(")<br>");
            }
            return sb.append("</html>").toString();
        }
        Instance inst = hitTestInstance(e.getPoint());
        if (inst != null) {
            VhdlEntity ent = project.getEntityForInstance(inst);
            if (ent != null && missingSourceEntities.contains(ent.name)) {
                return "<html>Source file missing:<br>" + ent.sourceFile + "</html>";
            }
        }
        return null;
    }

    public void setProject(Project project) {
        this.project = project;
        selectedItem = null;
        selectedInstances.clear();
        draggingInstance = null;
        draggingExternalPorts = null;
        draggingWire = false;
        layoutChanged();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void changed() {
        recomputeRoutes(computeRoutingBounds());
        if (listener != null) listener.onProjectChanged();
        repaint();
    }

    private void status(String msg) {
        if (listener != null) listener.onStatusMessage(msg);
    }

    public void layoutChanged() {
        Rectangle2D bounds = computeRoutingBounds();
        recomputeRoutes(bounds);
        setPreferredSize(new Dimension((int) bounds.getWidth(), (int) bounds.getHeight()));
        revalidate();
        repaint();
    }

    private Rectangle2D computeRoutingBounds() {
        double maxX = 1600, maxY = 1000;
        double minX = 0, minY = 0;
        if (project != null) {
            for (Instance inst : project.instances) {
                InstanceBox box = computeBox(inst);
                maxX = Math.max(maxX, box.x + box.width + 200);
                maxY = Math.max(maxY, box.y + box.height + 200);
                minX = Math.min(minX, box.x - 200);
                minY = Math.min(minY, box.y - 200);
            }
            for (ExternalPort p : project.externalPorts) {
                maxX = Math.max(maxX, p.x + 200);
                maxY = Math.max(maxY, p.y + 200);
                minX = Math.min(minX, p.x - 200);
                minY = Math.min(minY, p.y - 200);
            }
        }
        return new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
    }

    /** Routes every connection on an obstacle-avoiding grid. An AXI-Stream bundle (several
     *  signals wired between the same two interfaces) is routed and drawn as a single wire,
     *  since it's connected/disconnected as one unit - drawing every underlying signal
     *  separately just produced N routes from independent searches that could legitimately
     *  differ (equally-short paths, different tie-breaks), which looked inconsistent even
     *  though they start/end at the same coincident pin. Everything else is grouped by
     *  driver pin so signals fanning out from the same source still share a trunk. */
    private void recomputeRoutes(Rectangle2D bounds) {
        routedPaths.clear();
        visualConnections.clear();
        missingSourceEntities.clear();
        if (project == null) return;

        for (VhdlEntity e : project.library.values()) {
            if (e.sourceFile != null && !new File(e.sourceFile).isFile()) missingSourceEntities.add(e.name);
        }

        int deduped = project.deduplicateConnectionIds();
        int pruned = project.pruneOrphanedConnections();
        if (deduped > 0) {
            status(deduped + " connection" + (deduped == 1 ? "" : "s") + " had a duplicate internal id and "
                    + (deduped == 1 ? "was" : "were") + " reassigned a fresh one (this was hijacking another "
                    + "connection's rendering on-screen; nothing about your wiring changed).");
        } else if (pruned > 0) {
            status(pruned + " stale connection" + (pruned == 1 ? "" : "s")
                    + " referencing a port that no longer exists (e.g. an entity re-imported with different ports) "
                    + (pruned == 1 ? "was" : "were") + " removed.");
        }

        router.reset();
        int fallbackUsed = 0;
        int netId = 0;
        LaneAssigner lanes = new LaneAssigner();

        List<Rectangle2D> obstacles = new ArrayList<>();
        for (Instance inst : project.instances) {
            InstanceBox box = computeBox(inst);
            obstacles.add(new Rectangle2D.Double(box.x, box.y, box.width, box.height));
        }

        java.util.Set<Connection> handled = new java.util.HashSet<>();

        // AXI-Stream bundles: one route stands in for every signal in the bundle. Resolve
        // before marking anything handled - a bundle member that fails to resolve must not
        // take its (perfectly fine) bundle-mates down with it; they still get a chance below.
        for (Connection c : project.connections) {
            if (handled.contains(c)) continue;
            List<Connection> bundle = bundleFor(c);
            if (bundle.size() <= 1) continue;
            Point2D p1 = resolveEndpointPoint(c.a);
            Point2D p2 = resolveEndpointPoint(c.b);
            if (p1 == null || p2 == null) continue;
            handled.addAll(bundle);
            List<Point2D> path = router.route(p1, java.util.Collections.singletonList(p2), obstacles, bounds).get(0);
            // OrthogonalRouter returns an EMPTY list (not null) for a destination it decides
            // is unreachable under its own constraints (e.g. wedged too close to another pin
            // or obstacle to be entered horizontally) - trusting that blindly stored a
            // 0-point "path", which draws nothing while the connection stays fully "connected"
            // in the model. A straight fallback line guarantees this can never go invisible.
            if (path == null || path.size() < 2) { path = java.util.Arrays.asList(p1, p2); fallbackUsed++; }
            path = lanes.deconflict(path, netId++);
            for (Connection member : bundle) routedPaths.put(member.id, path);
            visualConnections.add(bundle.get(0));
        }

        // everything else, grouped by driver pin so fan-outs share a trunk. Only connections
        // whose destination actually resolves are handed to the router; the rest fall through
        // to the fallback pass below rather than being silently dropped or faked.
        Map<Endpoint, List<Connection>> bySource = new java.util.LinkedHashMap<>();
        Map<Connection, Endpoint> destOf = new java.util.HashMap<>();
        for (Connection c : project.connections) {
            if (handled.contains(c)) continue;
            boolean aDrives = project.canDrive(c.a);
            Endpoint src = aDrives ? c.a : c.b;
            Endpoint dst = aDrives ? c.b : c.a;
            bySource.computeIfAbsent(src, k -> new ArrayList<>()).add(c);
            destOf.put(c, dst);
        }

        for (Map.Entry<Endpoint, List<Connection>> e : bySource.entrySet()) {
            Point2D sourcePoint = resolveEndpointPoint(e.getKey());
            if (sourcePoint == null) continue;
            List<Connection> conns = e.getValue();
            List<Point2D> destPoints = new ArrayList<>();
            List<Connection> resolvableConns = new ArrayList<>();
            for (Connection c : conns) {
                Point2D dp = resolveEndpointPoint(destOf.get(c));
                if (dp == null) continue;
                destPoints.add(dp);
                resolvableConns.add(c);
            }
            if (resolvableConns.isEmpty()) continue;
            List<List<Point2D>> paths = router.route(sourcePoint, destPoints, obstacles, bounds);
            int thisNetId = netId++;
            for (int i = 0; i < resolvableConns.size(); i++) {
                // As above: the router reports a destination it can't reach under its own
                // constraints as an empty list rather than null - e.g. two pins on the same
                // instance close enough together that one ends up wedged once the other's
                // stub claims the approach. Two real, resolved endpoints must still always
                // draw as something, even if not the fancy shared-trunk route.
                List<Point2D> path = paths.get(i);
                if (path == null || path.size() < 2) { path = java.util.Arrays.asList(sourcePoint, destPoints.get(i)); fallbackUsed++; }
                // Every destination sharing this source is the same net (that's the whole
                // point of the trunk-sharing above), so they must all be deconflicted under
                // the same netId - otherwise two destinations that legitimately share a
                // trunk would look like a conflict with each other and get needlessly split.
                path = lanes.deconflict(path, thisNetId);
                routedPaths.put(resolvableConns.get(i).id, path);
            }
            visualConnections.addAll(resolvableConns);
            handled.addAll(resolvableConns);
        }

        // Safety net: anything not yet handled (a bundle whose "nice" path failed, or a
        // connection whose shared driver pin couldn't be resolved on its own) still gets a
        // direct fallback line as long as its own two endpoints resolve. A connection with
        // two genuinely valid endpoints must never end up invisible just because of how it
        // happened to get grouped for the fancier routing above.
        for (Connection c : project.connections) {
            if (handled.contains(c)) continue;
            Point2D p1 = resolveEndpointPoint(c.a);
            Point2D p2 = resolveEndpointPoint(c.b);
            if (p1 == null || p2 == null) continue; // genuinely orphaned; pruned on the next pass
            List<Point2D> straight = lanes.deconflict(java.util.Arrays.asList(p1, p2), netId++);
            routedPaths.put(c.id, straight);
            visualConnections.add(c);
        }

        // Surface it when the fallback above actually had to kick in - it means the router
        // judged a destination unreachable under its own layout rules (see OrthogonalRouter's
        // route() contract), which should be rare; a straight line was substituted so the
        // connection stays visible, but if this keeps showing up it's worth reporting with
        // the exact layout that triggers it. Deferred to the (rarer, more actionable) prune
        // message above if both happened in the same pass.
        if (pruned == 0 && fallbackUsed > 0) {
            status(fallbackUsed + " connection" + (fallbackUsed == 1 ? "" : "s")
                    + " fell back to a straight line - the router couldn't fit its usual routed path for "
                    + (fallbackUsed == 1 ? "it" : "them") + " in the current layout.");
        }
    }

    private static final double LANE_GAP = 6.0;

    /** Nudges routed paths apart where two UNRELATED nets happen to run along the exact same
     *  line, so they read as two distinct wires instead of one drawn on top of the other.
     *  OrthogonalRouter's own congestion penalty already discourages this, but it's a soft
     *  cost - if the clean detour is expensive enough (more bends, a longer run), it'll still
     *  pick the overlapping option. This is a separate, purely cosmetic pass on top of
     *  whatever the router decided: it never re-routes anything or knows about obstacles, it
     *  just tracks which (orientation, coordinate, span) a given net has already put a segment
     *  on and, when a later net's segment collides with an earlier net's on the same line,
     *  shifts the later one sideways by inserting a small perpendicular jog around it.
     *
     *  Two segments from the SAME net (an intentional shared trunk - a bundle, or several
     *  destinations fanning out from one driver) are deliberately left overlapping; only a
     *  DIFFERENT net's segment triggers a shift. The very first and last segment of every
     *  path are never touched, since those are the pin's own horizontal approach/exit and
     *  must end exactly on the pin - only interior "trunk" segments are eligible, which is
     *  also exactly where cross-net overlap is most visually noticeable. */
    private static final class LaneAssigner {
        // key "H:<y>" or "V:<x>" -> claims already made on that exact line: {start, end, netId, lane}
        private final Map<String, List<double[]>> occupancy = new java.util.HashMap<>();

        List<Point2D> deconflict(List<Point2D> path, int netId) {
            if (path.size() < 4) return path; // no interior segment exists to shift (see class doc)
            List<Point2D> result = new ArrayList<>(path);
            int segments = result.size() - 1;
            // walk backwards so inserting points never disturbs the indices still to be visited
            for (int k = segments - 2; k >= 1; k--) {
                Point2D pk = result.get(k), pk1 = result.get(k + 1);
                boolean horizontal = Math.abs(pk.getY() - pk1.getY()) < 1e-6;
                boolean vertical = Math.abs(pk.getX() - pk1.getX()) < 1e-6;
                if (!horizontal && !vertical) continue; // shouldn't happen, but never mangle a diagonal
                if (horizontal && Math.abs(pk.getX() - pk1.getX()) < 1e-6) continue; // zero-length, ignore
                if (vertical && Math.abs(pk.getY() - pk1.getY()) < 1e-6) continue;

                String key = horizontal ? ("H:" + Math.round(pk.getY() * 10)) : ("V:" + Math.round(pk.getX() * 10));
                double start = horizontal ? Math.min(pk.getX(), pk1.getX()) : Math.min(pk.getY(), pk1.getY());
                double end = horizontal ? Math.max(pk.getX(), pk1.getX()) : Math.max(pk.getY(), pk1.getY());
                int lane = laneFor(key, start, end, netId);
                if (lane == 0) continue; // first (or only) net on this line - no shift needed

                // Shift AWAY from where the path is coming from, never back toward it: the
                // preceding segment (k-1, k) is perpendicular to this one and already tells us
                // which way the path was travelling when it arrived at pk. Always adding a
                // fixed positive offset ignored this, so a path arriving from the "positive"
                // side got shifted BACKWARD into ground it had just covered - a visible
                // backtrack/notch right where the jog was inserted, which is exactly the kind
                // of stray-looking stub this was meant to avoid, not create.
                Point2D pPrev = result.get(k - 1);
                double sign = horizontal
                        ? (pk.getY() >= pPrev.getY() ? 1.0 : -1.0)
                        : (pk.getX() >= pPrev.getX() ? 1.0 : -1.0);
                double offset = sign * LANE_GAP * lane;
                if (horizontal) {
                    result.add(k + 1, new Point2D.Double(pk1.getX(), pk1.getY() + offset));
                    result.add(k + 1, new Point2D.Double(pk.getX(), pk.getY() + offset));
                } else {
                    result.add(k + 1, new Point2D.Double(pk1.getX() + offset, pk1.getY()));
                    result.add(k + 1, new Point2D.Double(pk.getX() + offset, pk.getY()));
                }
            }
            return result;
        }

        /** Returns which lane (0 = original position, 1/2/3... = progressively offset) this
         *  net should use for a segment on the given line and span, registering the claim so
         *  later calls see it. A net that already claimed an overlapping span on this exact
         *  line (its own earlier destination in the same fan-out, most commonly) reuses its
         *  existing lane rather than being treated as a new conflict. */
        private int laneFor(String key, double start, double end, int netId) {
            List<double[]> claims = occupancy.computeIfAbsent(key, k -> new ArrayList<>());
            int maxLane = -1;
            for (double[] claim : claims) {
                // a strict ">" with a small margin, not "<=": two segments merely touching at
                // a shared corner point (extremely common in orthogonal routing - two nets
                // turning at the same grid intersection is normal, not an overlap) must not
                // count as a conflict, or every path would get needlessly split into jogs.
                boolean overlaps = Math.min(end, claim[1]) - Math.max(start, claim[0]) > 1.0;
                if (!overlaps) continue;
                if ((int) claim[2] == netId) return (int) claim[3];
                maxLane = Math.max(maxLane, (int) claim[3]);
            }
            int lane = maxLane + 1;
            claims.add(new double[]{start, end, netId, lane});
            return lane;
        }
    }

    public void addInstanceAtDefaultPosition(String entityName) {
        if (project == null || project.library.get(entityName) == null) return;
        Rectangle visible = getVisibleRect();
        double x = visible.x + visible.width / 2.0 - 70 + (Math.random() * 40 - 20);
        double y = visible.y + visible.height / 2.0 - 40 + (Math.random() * 40 - 20);
        String id = project.nextInstanceId(entityName);
        Instance inst = new Instance(id, entityName, Math.max(20, x), Math.max(20, y));
        project.instances.add(inst);
        selectedItem = inst;
        layoutChanged();
        changed();
    }

    /** Adds one or more freshly created external ports (a plain port is a singleton list,
     *  an AXI-Stream interface is several), renaming them if needed to avoid collisions. */
    private void addExternalPortsAt(List<ExternalPort> ports) {
        if (project == null || ports.isEmpty()) return;
        ensureUniqueNames(ports);
        project.externalPorts.addAll(ports);
        selectedItem = ports.size() == 1 ? ports.get(0) : null;
        layoutChanged();
        changed();
    }

    /** Replaces an existing external port or AXI-Stream group with a freshly (re)configured
     *  one, preserving connections for any signal name that survives the edit unchanged and
     *  dropping connections for names that disappeared. */
    private void editExternalPorts(List<ExternalPort> oldMembers, List<ExternalPort> newMembers) {
        java.util.Set<String> oldNames = new java.util.HashSet<>();
        for (ExternalPort p : oldMembers) oldNames.add(p.name);
        project.externalPorts.removeIf(p -> oldNames.contains(p.name));

        ensureUniqueNames(newMembers);

        java.util.Set<String> newNames = new java.util.HashSet<>();
        for (ExternalPort p : newMembers) newNames.add(p.name);
        for (String old : oldNames) {
            if (!newNames.contains(old)) project.removeConnectionsTouching(Endpoint.external(old));
        }

        project.externalPorts.addAll(newMembers);
        selectedItem = null;
        layoutChanged();
        changed();
    }

    /** Renames ports so none of their names collide with existing external ports. A
     *  multi-port (AXI-Stream) list is renamed as a whole, keeping every member's shared
     *  prefix in sync, rather than letting individual signals drift out of the bundle. */
    private void ensureUniqueNames(List<ExternalPort> ports) {
        if (ports.size() > 1) {
            String prefix = AxiStreamDetector.prefixOf(ports.get(0).name);
            if (prefix != null) {
                String candidate = prefix;
                int n = 2;
                while (true) {
                    final String cp = candidate;
                    final String base = prefix;
                    boolean collide = ports.stream().anyMatch(p -> project.getExternalPort(cp + p.name.substring(base.length())) != null);
                    if (!collide) break;
                    candidate = prefix + "_" + n++;
                }
                if (!candidate.equals(prefix)) {
                    for (ExternalPort p : ports) p.name = candidate + p.name.substring(prefix.length());
                }
                return;
            }
        }
        for (ExternalPort p : ports) p.name = uniqueExternalName(p.name);
    }

    private String uniqueExternalName(String base) {
        if (project.getExternalPort(base) == null) return base;
        int n = 2;
        while (project.getExternalPort(base + n) != null) n++;
        return base + n;
    }

    // ---------------- geometry ----------------

    /** A single logical connection point on an instance: either one ordinary port,
     *  or a whole detected AXI-Stream interface bundled into one pin. */
    private static class PinInfo {
        Port port;       // non-null for an ordinary (ungrouped) port pin
        PortGroup group; // non-null for a bundled AXI-Stream interface pin
        double x, y;

        static PinInfo forPort(Port p, double x, double y) {
            PinInfo pi = new PinInfo(); pi.port = p; pi.x = x; pi.y = y; return pi;
        }
        static PinInfo forGroup(PortGroup g, double x, double y) {
            PinInfo pi = new PinInfo(); pi.group = g; pi.x = x; pi.y = y; return pi;
        }
        boolean isGroup() { return group != null; }
        String label() { return isGroup() ? group.name.toUpperCase() : port.name; }
    }

    private static class InstanceBox {
        Instance inst;
        VhdlEntity entity;
        double x, y, width, height;
        List<PinInfo> leftPins = new ArrayList<>();
        List<PinInfo> rightPins = new ArrayList<>();
        Map<String, PinInfo> portNameToPin = new java.util.HashMap<>(); // port name -> its (possibly grouped) pin
    }

    private InstanceBox computeBox(Instance inst) {
        InstanceBox box = new InstanceBox();
        box.inst = inst;
        box.entity = project.getEntityForInstance(inst);
        box.x = inst.x;
        box.y = inst.y;

        FontMetrics fm = getFontMetrics(getFont());
        List<Object> leftLogical = new ArrayList<>();  // Port or PortGroup
        List<Object> rightLogical = new ArrayList<>();
        int textWidth = fm.stringWidth(inst.label);

        if (box.entity != null) {
            textWidth = Math.max(textWidth, fm.stringWidth("(" + box.entity.name + ")"));
            List<PortGroup> groups = box.entity.getAxiStreamGroups();
            Map<Port, PortGroup> portToGroup = new java.util.HashMap<>();
            for (PortGroup g : groups) for (Port p : g.signals.values()) portToGroup.put(p, g);
            java.util.Set<PortGroup> emitted = new java.util.HashSet<>();

            for (Port p : box.entity.ports) {
                PortGroup g = portToGroup.get(p);
                if (g != null) {
                    if (!emitted.add(g)) continue;
                    (g.isMaster() ? rightLogical : leftLogical).add(g);
                    textWidth = Math.max(textWidth, fm.stringWidth(g.name.toUpperCase()) * 2 + 20);
                } else {
                    (p.canReceive() && !p.canDrive() ? leftLogical : rightLogical).add(p);
                    textWidth = Math.max(textWidth, fm.stringWidth(p.name) * 2 + 20);
                }
            }
        }
        box.width = Math.max(MIN_BOX_WIDTH, textWidth + 30);
        int maxRows = Math.max(leftLogical.size(), rightLogical.size());
        box.height = HEADER_HEIGHT + PIN_TOP_PAD + Math.max(1, maxRows) * PIN_SPACING + PIN_BOTTOM_PAD;

        for (int i = 0; i < leftLogical.size(); i++) {
            double py = box.y + HEADER_HEIGHT + PIN_TOP_PAD + i * PIN_SPACING + PIN_SPACING / 2.0;
            PinInfo pin = toPinInfo(leftLogical.get(i), box.x, py);
            box.leftPins.add(pin);
            registerPin(box, pin);
        }
        for (int i = 0; i < rightLogical.size(); i++) {
            double py = box.y + HEADER_HEIGHT + PIN_TOP_PAD + i * PIN_SPACING + PIN_SPACING / 2.0;
            PinInfo pin = toPinInfo(rightLogical.get(i), box.x + box.width, py);
            box.rightPins.add(pin);
            registerPin(box, pin);
        }
        return box;
    }

    private PinInfo toPinInfo(Object portOrGroup, double x, double y) {
        return portOrGroup instanceof PortGroup
                ? PinInfo.forGroup((PortGroup) portOrGroup, x, y)
                : PinInfo.forPort((Port) portOrGroup, x, y);
    }

    private void registerPin(InstanceBox box, PinInfo pin) {
        if (pin.isGroup()) {
            for (Port p : pin.group.signals.values()) box.portNameToPin.put(p.name, pin);
        } else {
            box.portNameToPin.put(pin.port.name, pin);
        }
    }

    private Point2D resolveEndpointPoint(Endpoint e) {
        if (e.kind == Endpoint.Kind.EXTERNAL) {
            ExternalPort p = project.getExternalPort(e.portName);
            return p == null ? null : new Point2D.Double(p.x, p.y);
        }
        Instance inst = project.getInstance(e.instanceId);
        if (inst == null) return null;
        InstanceBox box = computeBox(inst);
        PinInfo pin = box.portNameToPin.get(e.portName);
        return pin == null ? null : new Point2D.Double(pin.x, pin.y);
    }

    // ---------------- painting ----------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (project == null) return;
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // connections (routed around obstacles; signals sharing a driver pin share a trunk;
        // an AXI-Stream bundle draws as a single wire rather than one per underlying signal)
        List<Point2D> selectedPath = null;
        for (Connection c : visualConnections) {
            List<Point2D> path = routedPaths.get(c.id);
            if (path == null || path.size() < 2) {
                Point2D p1 = resolveEndpointPoint(c.a);
                Point2D p2 = resolveEndpointPoint(c.b);
                if (p1 == null || p2 == null) continue;
                path = java.util.Arrays.asList(p1, p2);
            }
            boolean sel = c == selectedItem;
            if (sel) {
                selectedPath = path;
                continue;
            }
            if (c.isBus) {
                g2.setColor(new Color(0, 140, 130));
                g2.setStroke(new BasicStroke(2.0f));
            } else {
                g2.setColor(new Color(90, 100, 115));
                g2.setStroke(new BasicStroke(1.6f));
            }
            drawPolyline(g2, path);
        }

        if (selectedPath != null) {
            g2.setColor(new Color(220, 90, 30));
            g2.setStroke(new BasicStroke(2.4f));
            drawPolyline(g2, selectedPath);
        }

        // rubber-band wire being dragged
        if (draggingWire && wireStart != null && wireCurrentPoint != null) {
            Point2D p1 = wireStart.point();
            if (p1 != null) {
                g2.setColor(new Color(30, 120, 220));
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 4}, 0));
                g2.draw(new Line2D.Double(p1, wireCurrentPoint));
            }
        }

        // instances
        for (Instance inst : project.instances) {
            drawInstance(g2, computeBox(inst));
        }

        // external ports
        for (ExternalPinInfo pin : computeExternalPins()) {
            if (pin.group != null) drawExternalGroupPin(g2, pin);
            else drawExternalPort(g2, pin.single);
        }
    }

    private void drawPolyline(Graphics2D g2, List<Point2D> pts) {
        java.awt.geom.GeneralPath path = new java.awt.geom.GeneralPath();
        Point2D first = pts.get(0);
        path.moveTo(first.getX(), first.getY());
        for (int i = 1; i < pts.size(); i++) path.lineTo(pts.get(i).getX(), pts.get(i).getY());
        g2.draw(path);
    }

    private void drawInstance(Graphics2D g2, InstanceBox box) {
        boolean sel = selectedInstances.contains(box.inst);
        boolean missingSource = box.entity != null && missingSourceEntities.contains(box.entity.name);
        RoundRectangle2DHelper.fillRoundRect(g2, box.x, box.y, box.width, box.height, 8, new Color(214, 226, 245));
        if (missingSource) {
            g2.setColor(new Color(200, 60, 40));
            g2.setStroke(new BasicStroke(sel ? 2.2f : 1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{5, 4}, 0));
        } else {
            g2.setColor(sel ? new Color(40, 110, 210) : new Color(120, 135, 160));
            g2.setStroke(new BasicStroke(sel ? 2.2f : 1.2f));
        }
        g2.drawRoundRect((int) box.x, (int) box.y, (int) box.width, (int) box.height, 8, 8);

        g2.setColor(new Color(60, 90, 150));
        g2.fillRoundRect((int) box.x, (int) box.y, (int) box.width, HEADER_HEIGHT, 8, 8);
        g2.fillRect((int) box.x, (int) box.y + HEADER_HEIGHT - 8, (int) box.width, 8);

        g2.setColor(Color.WHITE);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(box.inst.label, (int) (box.x + 8), (int) (box.y + 15));
        Font oldFont = g2.getFont();
        g2.setFont(oldFont.deriveFont(Font.PLAIN, 10f));
        String sub = box.entity != null ? "(" + box.entity.name + ")" : "(missing entity!)";
        g2.drawString(sub, (int) (box.x + 8), (int) (box.y + 28));
        g2.setFont(oldFont);

        if (missingSource) {
            double gx = box.x + box.width - 18, gy = box.y + HEADER_HEIGHT / 2.0;
            g2.setColor(new Color(230, 190, 30));
            java.awt.geom.GeneralPath tri = new java.awt.geom.GeneralPath();
            tri.moveTo(gx, gy - 8);
            tri.lineTo(gx + 7, gy + 6);
            tri.lineTo(gx - 7, gy + 6);
            tri.closePath();
            g2.fill(tri);
            g2.setColor(new Color(90, 70, 0));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(tri);
            g2.setFont(oldFont.deriveFont(Font.BOLD, 10f));
            g2.drawString("!", (float) gx - 2, (float) gy + 4);
            g2.setFont(oldFont);
        }

        g2.setFont(oldFont.deriveFont(Font.BOLD, 11f));
        for (PinInfo pin : box.leftPins) {
            drawPin(g2, pin);
            g2.setColor(new Color(40, 40, 40));
            g2.setFont(oldFont.deriveFont(pin.isGroup() ? Font.BOLD : Font.PLAIN, 11f));
            g2.drawString(pin.label(), (float) (pin.x + 9), (float) (pin.y + 4));
        }
        for (PinInfo pin : box.rightPins) {
            drawPin(g2, pin);
            g2.setColor(new Color(40, 40, 40));
            g2.setFont(oldFont.deriveFont(pin.isGroup() ? Font.BOLD : Font.PLAIN, 11f));
            int w = g2.getFontMetrics().stringWidth(pin.label());
            g2.drawString(pin.label(), (float) (pin.x - 9 - w), (float) (pin.y + 4));
        }
        g2.setFont(oldFont);
    }

    private void drawPin(Graphics2D g2, PinInfo pin) {
        if (pin.isGroup()) {
            Color c = new Color(0, 140, 130);
            g2.setColor(c);
            g2.fill(new java.awt.geom.RoundRectangle2D.Double(pin.x - 5, pin.y - 5, 10, 10, 3, 3));
            g2.setColor(c.darker());
            g2.setStroke(new BasicStroke(1.2f));
            g2.draw(new java.awt.geom.RoundRectangle2D.Double(pin.x - 5, pin.y - 5, 10, 10, 3, 3));
        } else {
            Port port = pin.port;
            Color c = port.canDrive() && port.canReceive() ? new Color(150, 70, 190)
                    : port.canDrive() ? new Color(210, 110, 30) : new Color(30, 110, 210);
            g2.setColor(c);
            g2.fill(new java.awt.geom.Ellipse2D.Double(pin.x - 4, pin.y - 4, 8, 8));
        }
    }

    private void drawExternalPort(Graphics2D g2, ExternalPort p) {
        boolean sel = p == selectedItem;
        Color c = p.direction == Direction.IN ? new Color(30, 150, 70)
                : p.direction == Direction.OUT ? new Color(190, 60, 60) : new Color(150, 70, 190);
        int s = 8;
        java.awt.geom.GeneralPath diamond = new java.awt.geom.GeneralPath();
        diamond.moveTo(p.x, p.y - s);
        diamond.lineTo(p.x + s, p.y);
        diamond.lineTo(p.x, p.y + s);
        diamond.lineTo(p.x - s, p.y);
        diamond.closePath();
        g2.setColor(c);
        g2.fill(diamond);
        g2.setColor(sel ? Color.BLACK : new Color(80, 80, 80));
        g2.setStroke(new BasicStroke(sel ? 2f : 1f));
        g2.draw(diamond);

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
        g2.setColor(Color.BLACK);
        String label = p.name + " : " + p.direction.vhdl();
        g2.drawString(label, (float) (p.x + s + 4), (float) (p.y + 4));
    }

    private void drawExternalGroupPin(Graphics2D g2, ExternalPinInfo pin) {
        boolean sel = pin.group == selectedItem;
        PortGroup g = pin.group;
        int s = 9;
        Color c = new Color(0, 140, 130);
        java.awt.geom.RoundRectangle2D shape = new java.awt.geom.RoundRectangle2D.Double(pin.x - s, pin.y - s, s * 2, s * 2, 4, 4);
        g2.setColor(c);
        g2.fill(shape);
        g2.setColor(sel ? Color.BLACK : c.darker());
        g2.setStroke(new BasicStroke(sel ? 2.2f : 1.2f));
        g2.draw(shape);

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
        g2.setColor(Color.BLACK);
        String label = g.name.toUpperCase() + " (" + (g.isMaster() ? "M" : "S") + "-AXIS, " + g.signals.size() + " sig.)";
        g2.drawString(label, (float) (pin.x + s + 4), (float) (pin.y + 4));
    }

    // ---------------- hit testing ----------------

    private Instance hitTestInstance(Point p) {
        for (int i = project.instances.size() - 1; i >= 0; i--) {
            Instance inst = project.instances.get(i);
            InstanceBox box = computeBox(inst);
            if (p.x >= box.x && p.x <= box.x + box.width && p.y >= box.y && p.y <= box.y + box.height) return inst;
        }
        return null;
    }

    /** A single logical pin among the project's external (top-level) ports: either one
     *  ordinary port, or a whole detected AXI-Stream interface bundled into one pin. */
    private static class ExternalPinInfo {
        ExternalPort single; // non-null for an ordinary (ungrouped) external port
        PortGroup group;     // non-null for a bundled AXI-Stream external interface
        double x, y;

        List<ExternalPort> members(Project project) {
            if (single != null) return java.util.Collections.singletonList(single);
            List<ExternalPort> result = new ArrayList<>();
            for (Port p : group.signals.values()) {
                ExternalPort ep = project.getExternalPort(p.name);
                if (ep != null) result.add(ep);
            }
            return result;
        }
    }

    private List<ExternalPinInfo> computeExternalPins() {
        List<ExternalPinInfo> pins = new ArrayList<>();
        List<PortGroup> groups = AxiStreamDetector.detectGroups(externalPortsAsProxies());
        java.util.Set<String> groupedNames = new java.util.HashSet<>();
        for (PortGroup g : groups) for (Port p : g.signals.values()) groupedNames.add(p.name);

        for (PortGroup g : groups) {
            ExternalPort anchor = project.getExternalPort(g.signals.values().iterator().next().name);
            if (anchor == null) continue;
            ExternalPinInfo info = new ExternalPinInfo();
            info.group = g;
            info.x = anchor.x;
            info.y = anchor.y;
            pins.add(info);
        }
        for (ExternalPort ep : project.externalPorts) {
            if (groupedNames.contains(ep.name)) continue;
            ExternalPinInfo info = new ExternalPinInfo();
            info.single = ep;
            info.x = ep.x;
            info.y = ep.y;
            pins.add(info);
        }
        return pins;
    }

    private List<Port> externalPortsAsProxies() {
        List<Port> proxies = new ArrayList<>();
        for (ExternalPort ep : project.externalPorts) proxies.add(new Port(ep.name, ep.direction, ep.type));
        return proxies;
    }

    /** The precise tip of the pin - clicking within this radius starts a wire. Deliberately
     *  tighter than {@link #hitTestExternalPin}, whose looser radius plus the label text
     *  is the "grab handle" for moving the port (see the class-level note on why these two
     *  need to be different: a plain external port's whole body IS its wire terminal, so
     *  without splitting the hit-test, dragging it could never mean anything but "wire"). */
    private ExternalPinInfo hitTestExternalWirePin(Point p) {
        for (ExternalPinInfo info : computeExternalPins()) {
            if (p.distance(info.x, info.y) <= EXT_WIRE_HIT_RADIUS) return info;
        }
        return null;
    }

    /** The port's whole body (diamond + adjacent label) - used for moving, selecting, and
     *  the right-click menu, all of which should work from anywhere on the visible symbol. */
    private ExternalPinInfo hitTestExternalPin(Point p) {
        for (ExternalPinInfo info : computeExternalPins()) {
            if (p.distance(info.x, info.y) <= EXT_PORT_HIT_RADIUS) return info;
            if (externalLabelBounds(info).contains(p.x, p.y)) return info;
        }
        return null;
    }

    /** Text label bounds for an external pin, kept in sync with what drawExternalPort /
     *  drawExternalGroupPin actually draw so the "grab the label" hit-test matches the eye. */
    private Rectangle2D externalLabelBounds(ExternalPinInfo pin) {
        FontMetrics fm = getFontMetrics(getFont().deriveFont(Font.BOLD, 11f));
        String label = pin.group != null
                ? pin.group.name.toUpperCase() + " (" + (pin.group.isMaster() ? "M" : "S") + "-AXIS, " + pin.group.signals.size() + " sig.)"
                : pin.single.name + " : " + pin.single.direction.vhdl();
        int s = pin.group != null ? 9 : 8;
        double baseline = pin.y + 4;
        double left = pin.x + s + 4;
        return new Rectangle2D.Double(left, baseline - fm.getAscent(), fm.stringWidth(label), fm.getAscent() + fm.getDescent());
    }

    /** What a click/drag landed on: either a single wire-able signal (instance port or
     *  external port) or a whole bundled AXI-Stream interface, on an instance or on the
     *  project's external (top-level) ports. */
    private class PinTarget {
        Instance instance;    // non-null when this pin belongs to an instance; null => external
        Port port;            // non-null for a single-signal pin on an instance
        PortGroup group;      // non-null for a bundled interface pin (instance or external)
        String externalName;  // non-null for a single external port pin

        private String anyMemberName() {
            return group.signals.values().iterator().next().name;
        }

        Point2D point() {
            if (group != null) {
                return instance != null
                        ? resolveEndpointPoint(Endpoint.instancePort(instance.id, anyMemberName()))
                        : resolveEndpointPoint(Endpoint.external(anyMemberName()));
            }
            if (instance != null) return resolveEndpointPoint(Endpoint.instancePort(instance.id, port.name));
            return resolveEndpointPoint(Endpoint.external(externalName));
        }

        Endpoint toEndpoint() {
            if (group != null) return null;
            return instance != null ? Endpoint.instancePort(instance.id, port.name) : Endpoint.external(externalName);
        }

        boolean sameAs(PinTarget o) {
            if (group != null || o.group != null) return group == o.group && instance == o.instance;
            Endpoint a = toEndpoint(), b = o.toEndpoint();
            return a != null && a.equals(b);
        }
    }

    private PinTarget hitTestPin(Point p) {
        for (int i = project.instances.size() - 1; i >= 0; i--) {
            Instance inst = project.instances.get(i);
            InstanceBox box = computeBox(inst);
            for (PinInfo pin : box.leftPins) if (p.distance(pin.x, pin.y) <= PIN_HIT_RADIUS) return toTarget(inst, pin);
            for (PinInfo pin : box.rightPins) if (p.distance(pin.x, pin.y) <= PIN_HIT_RADIUS) return toTarget(inst, pin);
        }
        ExternalPinInfo extPin = hitTestExternalWirePin(p);
        if (extPin != null) {
            PinTarget t = new PinTarget();
            if (extPin.group != null) t.group = extPin.group; else t.externalName = extPin.single.name;
            return t;
        }
        return null;
    }

    private PinTarget toTarget(Instance inst, PinInfo pin) {
        PinTarget t = new PinTarget();
        t.instance = inst;
        if (pin.isGroup()) t.group = pin.group; else t.port = pin.port;
        return t;
    }

    private Connection hitTestConnection(Point p) {
        for (int i = visualConnections.size() - 1; i >= 0; i--) {
            Connection c = visualConnections.get(i);
            List<Point2D> path = routedPaths.get(c.id);
            if (path == null || path.size() < 2) continue;
            for (int j = 0; j + 1 < path.size(); j++) {
                Point2D a = path.get(j), b = path.get(j + 1);
                if (Line2D.ptSegDist(a.getX(), a.getY(), b.getX(), b.getY(), p.x, p.y) <= CONN_HIT_DIST) return c;
            }
        }
        return null;
    }

    // ---------------- mouse handling ----------------

    private void handlePressed(MouseEvent e) {
        requestFocusInWindow();
        Point p = e.getPoint();

        if (SwingUtilities.isRightMouseButton(e)) {
            showContextMenu(e);
            return;
        }

        PinTarget pin = hitTestPin(p);
        if (pin != null) {
            draggingWire = true;
            wireStart = pin;
            wireCurrentPoint = p;
            selectedItem = null;
            selectedInstances.clear();
            repaint();
            return;
        }

        ExternalPinInfo extPin = hitTestExternalPin(p);
        if (extPin != null) {
            draggingExternalPorts = extPin.members(project);
            dragOffsetX = p.x - extPin.x;
            dragOffsetY = p.y - extPin.y;
            selectedItem = extPin.group != null ? extPin.group : extPin.single;
            selectedInstances.clear();
            repaint();
            return;
        }

        Instance inst = hitTestInstance(p);
        if (inst != null) {
            draggingInstance = inst;
            dragOffsetX = p.x - inst.x;
            dragOffsetY = p.y - inst.y;
            boolean ctrl = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
            if (ctrl) {
                // Ctrl+Click toggles this instance's membership in the multi-selection,
                // used for picking several instances to copy together.
                if (!selectedInstances.remove(inst)) selectedInstances.add(inst);
            } else if (!selectedInstances.contains(inst)) {
                // plain click on an instance outside the current multi-selection replaces it;
                // plain click on one already in the selection leaves the group selected, so
                // you can still drag that one instance without losing the group for copying.
                selectedInstances.clear();
                selectedInstances.add(inst);
            }
            selectedItem = inst;
            project.instances.remove(inst);
            project.instances.add(inst); // bring to front
            repaint();
            return;
        }

        Connection conn = hitTestConnection(p);
        selectedItem = conn; // may be null -> deselect
        selectedInstances.clear();
        repaint();
    }

    private void handleDragged(MouseEvent e) {
        Point p = e.getPoint();
        if (draggingWire) {
            wireCurrentPoint = p;
            repaint();
            return;
        }
        if (draggingInstance != null) {
            draggingInstance.x = Math.max(0, p.x - dragOffsetX);
            draggingInstance.y = Math.max(0, p.y - dragOffsetY);
            layoutChanged();
            changed();
            return;
        }
        if (draggingExternalPorts != null) {
            double nx = Math.max(0, p.x - dragOffsetX);
            double ny = Math.max(0, p.y - dragOffsetY);
            for (ExternalPort ep : draggingExternalPorts) { ep.x = nx; ep.y = ny; }
            layoutChanged();
            changed();
        }
    }

    private void handleReleased(MouseEvent e) {
        if (draggingWire) {
            Point p = e.getPoint();
            PinTarget target = hitTestPin(p);
            if (target != null && !target.sameAs(wireStart)) {
                tryConnect(wireStart, target);
            }
            draggingWire = false;
            wireStart = null;
            wireCurrentPoint = null;
        }
        draggingInstance = null;
        draggingExternalPorts = null;
        repaint();
    }

    private void tryConnect(PinTarget a, PinTarget b) {
        if (a.group != null && b.group != null) {
            tryConnectGroups(a, b);
            return;
        }
        if (a.group != null || b.group != null) {
            status("Connect a whole AXI-Stream interface to another interface, not to a single signal.");
            return;
        }
        tryConnectSingle(a.toEndpoint(), b.toEndpoint());
    }

    private Endpoint endpointFor(PinTarget t, Port memberPort) {
        return t.instance != null ? Endpoint.instancePort(t.instance.id, memberPort.name) : Endpoint.external(memberPort.name);
    }

    private void tryConnectSingle(Endpoint a, Endpoint b) {
        if (project.isEndpointAlreadyConnected(a, b)) {
            status("Already connected.");
            return;
        }
        boolean ok = (project.canDrive(a) && project.canReceive(b)) || (project.canDrive(b) && project.canReceive(a));
        if (!ok) {
            if (project.canReceive(a) && project.canReceive(b)) {
                Endpoint otherA = null;
                Endpoint otherB = null;
                for (Connection c : project.connections) {
                    if (c.a.equals(a) && project.canDrive(c.b)) { otherA = c.b; break; }
                    if (c.b.equals(a) && project.canDrive(c.a)) { otherA = c.a; break; }
                    if (c.a.equals(b) && project.canDrive(c.b)) { otherB = c.b; break; }
                    if (c.b.equals(b) && project.canDrive(c.a)) { otherB = c.a; break; }
                }
                if (otherA != null) {
                    a = otherA;
                    ok = true;
                }
                if (otherB != null) {
                    b = otherB;
                    ok = true;
                }
            }
        }
        if (!ok) {
            status("Incompatible directions: cannot connect " + describeDir(a) + " to " + describeDir(b));
            return;
        }
        Connection c = new Connection(project.nextConnectionId(), a, b, false);
        project.connections.add(c);
        selectedItem = c;
        status("Connected " + a + " -- " + b);
        changed();
    }

    /** Wires up every AXI-Stream signal the two interfaces have in common (tdata<->tdata,
     *  tvalid<->tvalid, ...), rejecting the whole operation if both sides would drive (or
     *  both would receive) TVALID, since that can never be a valid AXI-Stream link. Uses
     *  project.canDrive rather than PortGroup.isMaster() for this check specifically,
     *  because an external port's driving role is the flip of its declared direction
     *  (see ExternalPort.canDrive/canReceive) while an instance port's isn't - isMaster()
     *  alone would wrongly reject a perfectly valid instance-to-external link. */
    private void tryConnectGroups(PinTarget a, PinTarget b) {
        PortGroup groupA = a.group, groupB = b.group;
        Port anchorA = groupA.signals.containsKey("VALID") ? groupA.signals.get("VALID") : groupA.signals.values().iterator().next();
        Port anchorB = groupB.signals.containsKey("VALID") ? groupB.signals.get("VALID") : groupB.signals.values().iterator().next();
        boolean aDrives = project.canDrive(endpointFor(a, anchorA));
        boolean bDrives = project.canDrive(endpointFor(b, anchorB));
        if (aDrives == bDrives) {
            status("Cannot connect two AXI-Stream " + (aDrives ? "master" : "slave") + " interfaces together.");
            return;
        }
        int made = 0, skipped = 0;
        List<String> madeSuffixes = new ArrayList<>();
        java.util.Set<String> allSuffixes = new java.util.LinkedHashSet<>();
        allSuffixes.addAll(groupA.signals.keySet());
        allSuffixes.addAll(groupB.signals.keySet());
        for (String suffix : allSuffixes) {
            Port pa = groupA.signals.get(suffix);
            Port pb = groupB.signals.get(suffix);
            if (pa == null || pb == null) { skipped++; continue; }
            Endpoint ea = endpointFor(a, pa);
            Endpoint eb = endpointFor(b, pb);
            if (project.isEndpointAlreadyConnected(ea, eb)) continue;
            boolean ok = (project.canDrive(ea) && project.canReceive(eb)) || (project.canDrive(eb) && project.canReceive(ea));
            if (!ok) { skipped++; continue; }
            project.connections.add(new Connection(project.nextConnectionId(), ea, eb, true));
            made++;
            madeSuffixes.add(suffix.toLowerCase());
        }
        status("AXI-Stream " + groupA.name + " <-> " + groupB.name + ": connected " + made + " signal(s)"
                + (madeSuffixes.isEmpty() ? "" : " (" + String.join(", ", madeSuffixes) + ")")
                + (skipped > 0 ? ", " + skipped + " skipped" : ""));
        if (made > 0) changed(); else repaint();
    }

    /** Opens the auto-connect dialog and, if confirmed, wires the picked source signal to
     *  the matching port on every enabled instance that has one. */
    public void showAutoConnectDialog() {
        if (project == null) return;
        Dialogs.AutoConnectRequest req = Dialogs.promptAutoConnect(this, project);
        if (req != null) autoConnect(req);
    }

    /** For every enabled instance with a port named req.destinationPortName, wires it to
     *  req.source - unless that destination pin is already connected to something (left
     *  alone, to avoid creating a multi-driven net) or the directions are incompatible.
     *  The source instance itself (if the source is an instance port) is always skipped as
     *  a destination, since connecting a pin to itself would be meaningless. */
    private void autoConnect(Dialogs.AutoConnectRequest req) {
        Port sourcePort = project.resolvePort(req.source);
        if (sourcePort == null) {
            status("Auto-connect: source port could not be resolved.");
            return;
        }

        int connected = 0, alreadyWired = 0, incompatible = 0, noMatch = 0;
        for (Instance inst : project.instances) {
            if (!req.enabledInstanceIds.contains(inst.id)) continue;
            if (req.source.kind == Endpoint.Kind.INSTANCE && inst.id.equals(req.source.instanceId)) continue;

            VhdlEntity entity = project.getEntityForInstance(inst);
            if (entity == null) continue;
            Port destPort = entity.getPort(req.destinationPortName);
            if (destPort == null) { noMatch++; continue; }

            Endpoint dest = Endpoint.instancePort(inst.id, destPort.name);
            if (project.isEndpointAlreadyConnected(dest)) { alreadyWired++; continue; }

            boolean ok = (project.canDrive(req.source) && project.canReceive(dest)) || (project.canDrive(dest) && project.canReceive(req.source));
            if (!ok) { incompatible++; continue; }

            project.connections.add(new Connection(project.nextConnectionId(), req.source, dest, false));
            connected++;
        }

        StringBuilder msg = new StringBuilder("Auto-connect '").append(req.destinationPortName).append("': ")
                .append(connected).append(" connected");
        if (alreadyWired > 0) msg.append(", ").append(alreadyWired).append(" already wired (left alone)");
        if (incompatible > 0) msg.append(", ").append(incompatible).append(" incompatible direction");
        if (noMatch > 0) msg.append(", ").append(noMatch).append(" without a matching port");
        status(msg.toString());

        if (connected > 0) { layoutChanged(); changed(); } else { repaint(); }
    }

    private String describeDir(Endpoint e) {
        Port p = project.resolvePort(e);
        return p == null ? e.toString() : (e + " (" + p.direction.vhdl() + ")");
    }

    private void deleteSelected() {
        if (!selectedInstances.isEmpty()) {
            int count = selectedInstances.size();
            for (Instance inst : new ArrayList<>(selectedInstances)) project.removeInstance(inst.id);
            status(count > 1 ? count + " instances deleted." : "Instance deleted.");
            selectedInstances.clear();
        } else if (selectedItem instanceof ExternalPort) {
            project.removeExternalPort(((ExternalPort) selectedItem).name);
            status("External port deleted.");
        } else if (selectedItem instanceof PortGroup) {
            for (Port sig : ((PortGroup) selectedItem).signals.values()) project.removeExternalPort(sig.name);
            status("AXI-Stream interface deleted.");
        } else if (selectedItem instanceof Connection) {
            Connection conn = (Connection) selectedItem;
            List<Connection> bundle = bundleFor(conn);
            for (Connection c : bundle) project.removeConnection(c.id);
            status(bundle.size() > 1 ? "AXI-Stream link deleted (" + bundle.size() + " signals)." : "Connection deleted.");
        } else {
            return;
        }
        selectedItem = null;
        layoutChanged();
        changed();
    }

    // ---------------- copy / paste ----------------

    private static class ClipboardInstance {
        String entityName;
        String label;
        double x, y;
        Map<String, String> genericOverrides;
    }

    private static class ClipboardConnection {
        int fromIndex, toIndex; // indices into the clipboard instance list
        String fromPort, toPort;
        boolean isBus;
    }

    /** Copies the current multi-selection (or the single selected instance) to an in-memory
     *  clipboard: entity, label, position and generic overrides for each instance, plus any
     *  connection that runs between two of the copied instances - a connection to anything
     *  outside the copied set is deliberately left out, since it can't be reproduced without
     *  also copying whatever it connects to. */
    private void copySelection() {
        List<Instance> toCopy = new ArrayList<>(selectedInstances);
        if (toCopy.isEmpty() && selectedItem instanceof Instance) toCopy.add((Instance) selectedItem);
        if (toCopy.isEmpty()) {
            status("Nothing selected to copy.");
            return;
        }

        clipboardInstances.clear();
        clipboardConnections.clear();

        Map<String, Integer> idToIndex = new java.util.HashMap<>();
        for (Instance inst : toCopy) {
            idToIndex.put(inst.id, clipboardInstances.size());
            ClipboardInstance ci = new ClipboardInstance();
            ci.entityName = inst.entityName;
            ci.label = inst.label;
            ci.x = inst.x;
            ci.y = inst.y;
            ci.genericOverrides = new java.util.LinkedHashMap<>(inst.genericOverrides);
            clipboardInstances.add(ci);
        }

        for (Connection c : project.connections) {
            if (c.a.kind != Endpoint.Kind.INSTANCE || c.b.kind != Endpoint.Kind.INSTANCE) continue;
            Integer fromIndex = idToIndex.get(c.a.instanceId);
            Integer toIndex = idToIndex.get(c.b.instanceId);
            if (fromIndex == null || toIndex == null) continue; // touches something outside the copied set
            ClipboardConnection cc = new ClipboardConnection();
            cc.fromIndex = fromIndex;
            cc.fromPort = c.a.portName;
            cc.toIndex = toIndex;
            cc.toPort = c.b.portName;
            cc.isBus = c.isBus;
            clipboardConnections.add(cc);
        }

        status("Copied " + toCopy.size() + " instance" + (toCopy.size() == 1 ? "" : "s")
                + (clipboardConnections.isEmpty() ? "" : " and " + clipboardConnections.size() + " connection(s)") + ".");
    }

    /** Pastes the clipboard as new instances (new unique ids and, if needed, unique
     *  instantiation labels) with their generic overrides intact, offset from their copied
     *  position, plus new connections reproducing every link the copy captured between them.
     *  Each paste cascades further from the last, like most editors' repeated-paste behavior. */
    private void pasteClipboard() {
        if (clipboardInstances.isEmpty()) {
            status("Nothing to paste.");
            return;
        }
        for (ClipboardInstance ci : clipboardInstances) {
            if (project.library.get(ci.entityName) == null) {
                status("Cannot paste: entity '" + ci.entityName + "' is no longer in the library.");
                return;
            }
        }

        Map<Integer, Instance> newInstances = new java.util.HashMap<>();
        List<Instance> pasted = new ArrayList<>();
        for (int i = 0; i < clipboardInstances.size(); i++) {
            ClipboardInstance ci = clipboardInstances.get(i);
            ci.x += PASTE_OFFSET;
            ci.y += PASTE_OFFSET;
            Instance inst = new Instance(project.nextInstanceId(ci.entityName), ci.entityName, ci.x, ci.y);
            inst.label = uniqueInstanceLabel(ci.label);
            inst.genericOverrides = new java.util.LinkedHashMap<>(ci.genericOverrides);
            project.instances.add(inst);
            newInstances.put(i, inst);
            pasted.add(inst);
        }
        for (ClipboardConnection cc : clipboardConnections) {
            Instance from = newInstances.get(cc.fromIndex);
            Instance to = newInstances.get(cc.toIndex);
            if (from == null || to == null) continue;
            Endpoint a = Endpoint.instancePort(from.id, cc.fromPort);
            Endpoint b = Endpoint.instancePort(to.id, cc.toPort);
            project.connections.add(new Connection(project.nextConnectionId(), a, b, cc.isBus));
        }

        selectedInstances.clear();
        selectedInstances.addAll(pasted);
        selectedItem = pasted.size() == 1 ? pasted.get(0) : null;
        status("Pasted " + pasted.size() + " instance" + (pasted.size() == 1 ? "" : "s")
                + (clipboardConnections.isEmpty() ? "" : " and " + clipboardConnections.size() + " connection(s)") + ".");
        layoutChanged();
        changed();
    }

    private String uniqueInstanceLabel(String base) {
        if (!isLabelTaken(base)) return base;
        int n = 2;
        String candidate;
        do {
            candidate = base + "_" + n++;
        } while (isLabelTaken(candidate));
        return candidate;
    }

    private boolean isLabelTaken(String label) {
        for (Instance inst : project.instances) if (inst.label.equals(label)) return true;
        return false;
    }

    // ---------------- context menu ----------------

    private void showContextMenu(MouseEvent e) {
        Point p = e.getPoint();
        Instance inst = hitTestInstance(p);
        ExternalPinInfo extPin = hitTestExternalPin(p);
        Connection conn = extPin == null ? hitTestConnection(p) : null;

        JPopupMenu menu = new JPopupMenu();
        if (extPin != null && extPin.group != null) {
            PortGroup group = extPin.group;
            selectedItem = group;
            JMenuItem editItem = new JMenuItem("Edit AXI-Stream Interface...");
            editItem.addActionListener(a -> editExternalGroup(group));
            JMenuItem delItem = new JMenuItem("Delete AXI-Stream Interface (" + group.signals.size() + " ports)");
            delItem.addActionListener(a -> {
                for (Port sig : group.signals.values()) project.removeExternalPort(sig.name);
                selectedItem = null;
                layoutChanged();
                changed();
            });
            menu.add(editItem);
            menu.add(delItem);
        } else if (extPin != null) {
            ExternalPort ep = extPin.single;
            selectedItem = ep;
            JMenuItem editItem = new JMenuItem("Edit External Port...");
            editItem.addActionListener(a -> editExternalPortSingle(ep));
            JMenuItem delItem = new JMenuItem("Delete External Port");
            delItem.addActionListener(a -> { project.removeExternalPort(ep.name); selectedItem = null; layoutChanged(); changed(); });
            menu.add(editItem);
            menu.add(delItem);
        } else if (inst != null) {
            selectedItem = inst;
            JMenuItem renameItem = new JMenuItem("Rename Instance...");
            renameItem.addActionListener(a -> renameInstance(inst));
            JMenuItem genItem = new JMenuItem("Edit Generics...");
            genItem.addActionListener(a -> editGenerics(inst));
            JMenuItem delItem = new JMenuItem("Delete Instance");
            delItem.addActionListener(a -> { project.removeInstance(inst.id); selectedItem = null; layoutChanged(); changed(); });
            menu.add(renameItem);
            menu.add(genItem);
            menu.addSeparator();
            menu.add(delItem);
        } else if (conn != null) {
            selectedItem = conn;
            List<Connection> bundle = bundleFor(conn);
            if (bundle.size() > 1) {
                // this wire represents a whole AXI-Stream link - offer only the bundle-wide
                // delete, since deleting just the representative signal would silently leave
                // the other signals connected (a confusing partial-disconnect state)
                JMenuItem delBundleItem = new JMenuItem("Delete AXI-Stream Link (" + bundle.size() + " signals)");
                delBundleItem.addActionListener(a -> {
                    for (Connection c : bundle) project.removeConnection(c.id);
                    selectedItem = null;
                    changed();
                });
                menu.add(delBundleItem);
            } else {
                JMenuItem delItem = new JMenuItem("Delete Connection");
                delItem.addActionListener(a -> { project.removeConnection(conn.id); selectedItem = null; changed(); });
                menu.add(delItem);
            }
        } else {
            JMenuItem addItem = new JMenuItem("Add External Port Here...");
            addItem.addActionListener(a -> {
                List<ExternalPort> created = Dialogs.promptNewExternalPort(this, p.x, p.y);
                if (created != null) addExternalPortsAt(created);
            });
            menu.add(addItem);
        }
        repaint();
        menu.show(this, e.getX(), e.getY());
    }

    private void renameInstance(Instance inst) {
        String newLabel = Dialogs.promptString(this, "Rename Instance", "Instantiation label:", inst.label);
        if (newLabel != null && !newLabel.trim().isEmpty()) {
            inst.label = newLabel.trim();
            layoutChanged();
            changed();
        }
    }

    private void editGenerics(Instance inst) {
        VhdlEntity entity = project.getEntityForInstance(inst);
        if (entity == null) return;
        Map<String, String> result = Dialogs.promptGenericOverrides(this, entity, inst.genericOverrides);
        if (result != null) {
            inst.genericOverrides = result;
            changed();
        }
    }

    private void editExternalPortSingle(ExternalPort ep) {
        List<ExternalPort> edited = Dialogs.promptExternalPort(this, ep);
        if (edited != null) editExternalPorts(java.util.Collections.singletonList(ep), edited);
    }

    private void editExternalGroup(PortGroup group) {
        List<ExternalPort> oldMembers = new ArrayList<>();
        for (Port sig : group.signals.values()) {
            ExternalPort ep = project.getExternalPort(sig.name);
            if (ep != null) oldMembers.add(ep);
        }
        if (oldMembers.isEmpty()) return;
        List<ExternalPort> edited = Dialogs.promptAxiStreamGroup(this, group, oldMembers.get(0).x, oldMembers.get(0).y);
        if (edited != null) editExternalPorts(oldMembers, edited);
    }

    // ---------------- AXI-Stream bundle helpers ----------------

    private PortGroup groupOfEndpoint(Endpoint e) {
        if (e.kind == Endpoint.Kind.EXTERNAL) {
            for (PortGroup g : AxiStreamDetector.detectGroups(externalPortsAsProxies())) {
                for (Port p : g.signals.values()) if (p.name.equalsIgnoreCase(e.portName)) return g;
            }
            return null;
        }
        Instance inst = project.getInstance(e.instanceId);
        if (inst == null) return null;
        VhdlEntity entity = project.getEntityForInstance(inst);
        if (entity == null) return null;
        for (PortGroup g : entity.getAxiStreamGroups()) {
            for (Port p : g.signals.values()) if (p.name.equalsIgnoreCase(e.portName)) return g;
        }
        return null;
    }

    private Instance ownerOf(Endpoint e) {
        return e.kind == Endpoint.Kind.INSTANCE ? project.getInstance(e.instanceId) : null;
    }

    /** owner == null means "external"; otherwise the endpoint must belong to that instance. */
    private boolean endpointOwnedBy(Endpoint e, Instance owner, PortGroup g) {
        if (owner == null) {
            if (e.kind != Endpoint.Kind.EXTERNAL) return false;
        } else if (e.kind != Endpoint.Kind.INSTANCE || !e.instanceId.equals(owner.id)) {
            return false;
        }
        for (Port p : g.signals.values()) if (p.name.equalsIgnoreCase(e.portName)) return true;
        return false;
    }

    /** All connections that belong to the same AXI-Stream link as conn (i.e. every individual
     *  signal wired between the same pair of interfaces, whether on instances or external
     *  ports). Returns just {conn} if either endpoint isn't part of a detected group. */
    private List<Connection> bundleFor(Connection conn) {
        PortGroup ga = groupOfEndpoint(conn.a);
        PortGroup gb = groupOfEndpoint(conn.b);
        if (ga == null || gb == null) return java.util.Collections.singletonList(conn);
        Instance ownerA = ownerOf(conn.a), ownerB = ownerOf(conn.b);
        List<Connection> result = new ArrayList<>();
        for (Connection c : project.connections) {
            boolean direct = endpointOwnedBy(c.a, ownerA, ga) && endpointOwnedBy(c.b, ownerB, gb);
            boolean swapped = endpointOwnedBy(c.a, ownerB, gb) && endpointOwnedBy(c.b, ownerA, ga);
            if (direct || swapped) result.add(c);
        }
        return result;
    }

    /** Tiny helper so we can fill a rounded rect with a solid color without repeating boilerplate. */
    private static final class RoundRectangle2DHelper {
        static void fillRoundRect(Graphics2D g2, double x, double y, double w, double h, int arc, Color color) {
            g2.setColor(color);
            g2.fillRoundRect((int) x, (int) y, (int) w, (int) h, arc, arc);
        }
    }
}
