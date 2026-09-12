package vhdlconnector.gui;

import vhdlconnector.model.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
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
    private static final double EXT_PORT_HIT_RADIUS = 9;
    private static final double CONN_HIT_DIST = 5;

    private Project project;
    private Listener listener;

    // drag state
    private Instance draggingInstance;
    private ExternalPort draggingExternalPort;
    private double dragOffsetX, dragOffsetY;

    private boolean draggingWire;
    private PinTarget wireStart;
    private Point wireCurrentPoint;

    private Object selectedItem; // Instance | ExternalPort | Connection | null

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
        return null;
    }

    public void setProject(Project project) {
        this.project = project;
        selectedItem = null;
        draggingInstance = null;
        draggingExternalPort = null;
        draggingWire = false;
        layoutChanged();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void changed() {
        if (listener != null) listener.onProjectChanged();
        repaint();
    }

    private void status(String msg) {
        if (listener != null) listener.onStatusMessage(msg);
    }

    public void layoutChanged() {
        int maxX = 1600, maxY = 1000;
        if (project != null) {
            for (Instance inst : project.instances) {
                InstanceBox box = computeBox(inst);
                maxX = Math.max(maxX, (int) (box.x + box.width) + 200);
                maxY = Math.max(maxY, (int) (box.y + box.height) + 200);
            }
            for (ExternalPort p : project.externalPorts) {
                maxX = Math.max(maxX, (int) p.x + 200);
                maxY = Math.max(maxY, (int) p.y + 200);
            }
        }
        setPreferredSize(new Dimension(maxX, maxY));
        revalidate();
        repaint();
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

    public void addExternalPortAt(ExternalPort template, Point p) {
        if (project == null) return;
        String name = template.name;
        if (project.getExternalPort(name) != null) {
            int n = 2;
            while (project.getExternalPort(name + n) != null) n++;
            name = name + n;
        }
        ExternalPort port = new ExternalPort(name, template.direction, template.type, p.x, p.y);
        project.externalPorts.add(port);
        selectedItem = port;
        layoutChanged();
        changed();
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

        // connections
        for (Connection c : project.connections) {
            Point2D p1 = resolveEndpointPoint(c.a);
            Point2D p2 = resolveEndpointPoint(c.b);
            if (p1 == null || p2 == null) continue;
            boolean sel = c == selectedItem;
            g2.setColor(sel ? new Color(220, 90, 30) : new Color(90, 100, 115));
            g2.setStroke(new BasicStroke(sel ? 2.4f : 1.6f));
            drawElbow(g2, p1, p2);
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
        for (ExternalPort p : project.externalPorts) {
            drawExternalPort(g2, p);
        }
    }

    private void drawElbow(Graphics2D g2, Point2D p1, Point2D p2) {
        double midX = (p1.getX() + p2.getX()) / 2.0;
        java.awt.geom.GeneralPath path = new java.awt.geom.GeneralPath();
        path.moveTo(p1.getX(), p1.getY());
        path.lineTo(midX, p1.getY());
        path.lineTo(midX, p2.getY());
        path.lineTo(p2.getX(), p2.getY());
        g2.draw(path);
    }

    private void drawInstance(Graphics2D g2, InstanceBox box) {
        boolean sel = box.inst == selectedItem;
        RoundRectangle2DHelper.fillRoundRect(g2, box.x, box.y, box.width, box.height, 8, new Color(214, 226, 245));
        g2.setColor(sel ? new Color(40, 110, 210) : new Color(120, 135, 160));
        g2.setStroke(new BasicStroke(sel ? 2.2f : 1.2f));
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

    // ---------------- hit testing ----------------

    private Instance hitTestInstance(Point p) {
        for (int i = project.instances.size() - 1; i >= 0; i--) {
            Instance inst = project.instances.get(i);
            InstanceBox box = computeBox(inst);
            if (p.x >= box.x && p.x <= box.x + box.width && p.y >= box.y && p.y <= box.y + box.height) return inst;
        }
        return null;
    }

    private ExternalPort hitTestExternalPort(Point p) {
        for (int i = project.externalPorts.size() - 1; i >= 0; i--) {
            ExternalPort ep = project.externalPorts.get(i);
            if (p.distance(ep.x, ep.y) <= EXT_PORT_HIT_RADIUS) return ep;
        }
        return null;
    }

    /** What a click/drag landed on: either a single wire-able signal (instance port or
     *  external port) or a whole bundled AXI-Stream interface on an instance. */
    private class PinTarget {
        Instance instance;   // set when this pin belongs to an instance
        Port port;           // non-null for a single-signal pin (instance or external)
        PortGroup group;      // non-null for a bundled interface pin (instance only)
        String externalName; // non-null for a single external port pin

        Point2D point() {
            if (group != null) return resolveEndpointPoint(Endpoint.instancePort(instance.id, group.signals.values().iterator().next().name));
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
        ExternalPort ep = hitTestExternalPort(p);
        if (ep != null) {
            PinTarget t = new PinTarget();
            t.externalName = ep.name;
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
        for (int i = project.connections.size() - 1; i >= 0; i--) {
            Connection c = project.connections.get(i);
            Point2D p1 = resolveEndpointPoint(c.a);
            Point2D p2 = resolveEndpointPoint(c.b);
            if (p1 == null || p2 == null) continue;
            double midX = (p1.getX() + p2.getX()) / 2.0;
            if (Line2D.ptSegDist(p1.getX(), p1.getY(), midX, p1.getY(), p.x, p.y) <= CONN_HIT_DIST) return c;
            if (Line2D.ptSegDist(midX, p1.getY(), midX, p2.getY(), p.x, p.y) <= CONN_HIT_DIST) return c;
            if (Line2D.ptSegDist(midX, p2.getY(), p2.getX(), p2.getY(), p.x, p.y) <= CONN_HIT_DIST) return c;
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
            repaint();
            return;
        }

        ExternalPort ep = hitTestExternalPort(p);
        if (ep != null) {
            draggingExternalPort = ep;
            dragOffsetX = p.x - ep.x;
            dragOffsetY = p.y - ep.y;
            selectedItem = ep;
            repaint();
            return;
        }

        Instance inst = hitTestInstance(p);
        if (inst != null) {
            draggingInstance = inst;
            dragOffsetX = p.x - inst.x;
            dragOffsetY = p.y - inst.y;
            selectedItem = inst;
            project.instances.remove(inst);
            project.instances.add(inst); // bring to front
            repaint();
            return;
        }

        Connection conn = hitTestConnection(p);
        selectedItem = conn; // may be null -> deselect
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
        if (draggingExternalPort != null) {
            draggingExternalPort.x = Math.max(0, p.x - dragOffsetX);
            draggingExternalPort.y = Math.max(0, p.y - dragOffsetY);
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
        draggingExternalPort = null;
        repaint();
    }

    private void tryConnect(PinTarget a, PinTarget b) {
        if (a.group != null && b.group != null) {
            tryConnectGroups(a.instance, a.group, b.instance, b.group);
            return;
        }
        if (a.group != null || b.group != null) {
            status("Connect a whole AXI-Stream interface to another interface, not to a single signal.");
            return;
        }
        tryConnectSingle(a.toEndpoint(), b.toEndpoint());
    }

    private void tryConnectSingle(Endpoint a, Endpoint b) {
        if (project.isEndpointAlreadyConnected(a, b)) {
            status("Already connected.");
            return;
        }
        boolean ok = (project.canDrive(a) && project.canReceive(b)) || (project.canDrive(b) && project.canReceive(a));
        if (!ok) {
            status("Incompatible directions: cannot connect " + describeDir(a) + " to " + describeDir(b));
            return;
        }
        Connection c = new Connection(project.nextConnectionId(), a, b);
        project.connections.add(c);
        selectedItem = c;
        status("Connected " + a + " -- " + b);
        changed();
    }

    /** Wires up every AXI-Stream signal the two interfaces have in common (tdata<->tdata,
     *  tvalid<->tvalid, ...), rejecting the whole operation if both sides are the same
     *  role (two masters or two slaves), since that can never be a valid AXI-Stream link. */
    private void tryConnectGroups(Instance instA, PortGroup groupA, Instance instB, PortGroup groupB) {
        if (groupA.isMaster() == groupB.isMaster()) {
            status("Cannot connect two AXI-Stream " + (groupA.isMaster() ? "master" : "slave") + " interfaces together.");
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
            Endpoint ea = Endpoint.instancePort(instA.id, pa.name);
            Endpoint eb = Endpoint.instancePort(instB.id, pb.name);
            if (project.isEndpointAlreadyConnected(ea, eb)) continue;
            boolean ok = (project.canDrive(ea) && project.canReceive(eb)) || (project.canDrive(eb) && project.canReceive(ea));
            if (!ok) { skipped++; continue; }
            project.connections.add(new Connection(project.nextConnectionId(), ea, eb));
            made++;
            madeSuffixes.add(suffix.toLowerCase());
        }
        status("AXI-Stream " + groupA.name + " <-> " + groupB.name + ": connected " + made + " signal(s)"
                + (madeSuffixes.isEmpty() ? "" : " (" + String.join(", ", madeSuffixes) + ")")
                + (skipped > 0 ? ", " + skipped + " skipped" : ""));
        if (made > 0) changed(); else repaint();
    }

    private String describeDir(Endpoint e) {
        Port p = project.resolvePort(e);
        return p == null ? e.toString() : (e + " (" + p.direction.vhdl() + ")");
    }

    private void deleteSelected() {
        if (selectedItem instanceof Instance) {
            project.removeInstance(((Instance) selectedItem).id);
            status("Instance deleted.");
        } else if (selectedItem instanceof ExternalPort) {
            project.removeExternalPort(((ExternalPort) selectedItem).name);
            status("External port deleted.");
        } else if (selectedItem instanceof Connection) {
            project.removeConnection(((Connection) selectedItem).id);
            status("Connection deleted.");
        } else {
            return;
        }
        selectedItem = null;
        layoutChanged();
        changed();
    }

    // ---------------- context menu ----------------

    private void showContextMenu(MouseEvent e) {
        Point p = e.getPoint();
        Instance inst = hitTestInstance(p);
        ExternalPort ep = hitTestExternalPort(p);
        Connection conn = ep == null ? hitTestConnection(p) : null;

        JPopupMenu menu = new JPopupMenu();
        if (ep != null) {
            selectedItem = ep;
            JMenuItem editItem = new JMenuItem("Edit External Port...");
            editItem.addActionListener(a -> editExternalPort(ep));
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
            JMenuItem delItem = new JMenuItem("Delete Connection");
            delItem.addActionListener(a -> { project.removeConnection(conn.id); selectedItem = null; changed(); });
            menu.add(delItem);

            List<Connection> bundle = bundleFor(conn);
            if (bundle.size() > 1) {
                JMenuItem delBundleItem = new JMenuItem("Delete AXI-Stream Link (" + bundle.size() + " signals)");
                delBundleItem.addActionListener(a -> {
                    for (Connection c : bundle) project.removeConnection(c.id);
                    selectedItem = null;
                    changed();
                });
                menu.add(delBundleItem);
            }
        } else {
            JMenuItem addItem = new JMenuItem("Add External Port Here...");
            addItem.addActionListener(a -> {
                ExternalPort created = Dialogs.promptExternalPort(this, null);
                if (created != null) addExternalPortAt(created, p);
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

    private void editExternalPort(ExternalPort ep) {
        String oldName = ep.name;
        ExternalPort edited = Dialogs.promptExternalPort(this, ep);
        if (edited == null) return;
        if (!edited.name.equals(oldName) && project.getExternalPort(edited.name) != null) {
            JOptionPane.showMessageDialog(this, "An external port named '" + edited.name + "' already exists.", "Name conflict", JOptionPane.ERROR_MESSAGE);
            return;
        }
        ep.name = edited.name;
        ep.direction = edited.direction;
        ep.type = edited.type;
        if (!oldName.equals(ep.name)) {
            for (Connection c : project.connections) {
                if (c.a.kind == Endpoint.Kind.EXTERNAL && c.a.portName.equals(oldName)) c.a.portName = ep.name;
                if (c.b.kind == Endpoint.Kind.EXTERNAL && c.b.portName.equals(oldName)) c.b.portName = ep.name;
            }
        }
        layoutChanged();
        changed();
    }

    // ---------------- AXI-Stream bundle helpers ----------------

    private PortGroup groupOfEndpoint(Endpoint e) {
        if (e.kind != Endpoint.Kind.INSTANCE) return null;
        Instance inst = project.getInstance(e.instanceId);
        if (inst == null) return null;
        VhdlEntity entity = project.getEntityForInstance(inst);
        if (entity == null) return null;
        for (PortGroup g : entity.getAxiStreamGroups()) {
            for (Port p : g.signals.values()) if (p.name.equalsIgnoreCase(e.portName)) return g;
        }
        return null;
    }

    private boolean endpointInGroup(Endpoint e, String instId, PortGroup g) {
        if (e.kind != Endpoint.Kind.INSTANCE || !e.instanceId.equals(instId)) return false;
        for (Port p : g.signals.values()) if (p.name.equalsIgnoreCase(e.portName)) return true;
        return false;
    }

    /** All connections that belong to the same instance-to-instance AXI-Stream link as conn
     *  (i.e. every individual signal wired between the same pair of interfaces). Returns just
     *  {conn} if either endpoint isn't part of a detected group. */
    private List<Connection> bundleFor(Connection conn) {
        PortGroup ga = groupOfEndpoint(conn.a);
        PortGroup gb = groupOfEndpoint(conn.b);
        if (ga == null || gb == null) return java.util.Collections.singletonList(conn);
        String instA = conn.a.instanceId, instB = conn.b.instanceId;
        List<Connection> result = new ArrayList<>();
        for (Connection c : project.connections) {
            boolean direct = endpointInGroup(c.a, instA, ga) && endpointInGroup(c.b, instB, gb);
            boolean swapped = endpointInGroup(c.a, instB, gb) && endpointInGroup(c.b, instA, ga);
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
