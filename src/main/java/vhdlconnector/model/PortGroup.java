package vhdlconnector.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A set of AXI4-Stream signals detected on an entity that share a common prefix,
 *  e.g. s_axis_tdata / s_axis_tvalid / s_axis_tready / s_axis_tlast -> group "s_axis".
 *  Purely a UI/interaction grouping: the underlying ports and connections are still
 *  ordinary per-signal Port/Connection objects. */
public class PortGroup {
    public final String name; // interface name as it appears in the source, e.g. "s_axis", "m00_axis"
    public final Map<String, Port> signals = new LinkedHashMap<>(); // suffix (DATA, VALID, READY, LAST, ...) -> Port

    public PortGroup(String name) {
        this.name = name;
    }

    /** An AXI-Stream source (master) drives TDATA/TVALID/TLAST/... and receives TREADY back. */
    public boolean isMaster() {
        Port anchor = signals.get("VALID");
        if (anchor == null) anchor = signals.get("DATA");
        if (anchor == null && !signals.isEmpty()) anchor = signals.values().iterator().next();
        return anchor != null && anchor.canDrive();
    }

    public List<Port> ports() {
        return new ArrayList<>(signals.values());
    }

    public boolean contains(Port p) {
        return signals.containsValue(p);
    }
}
