package vhdlconnector.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects AXI4-Stream interfaces among an entity's ports purely by name convention:
 *  ports ending in "_t&lt;signal&gt;" (tdata, tvalid, tready, ...) whose remaining prefix
 *  contains an "axi" or "axis" token and is otherwise identical are grouped into a single
 *  logical interface, e.g. s_axis_tdata/s_axis_tvalid/s_axis_tready share prefix "s_axis".
 *  The "s" in "axis" is optional, since plenty of real-world cores drop it and just use
 *  "_axi_t..." (e.g. s_axi_tvalid). Xilinx-generated IP (.vho instantiation templates) adds
 *  an extra qualifier between "axis" and the signal when a core has more than one interface
 *  of the same role - e.g. floating-point add's two inputs are "s_axis_a_tdata" and
 *  "s_axis_b_tdata", its output "m_axis_result_tdata" - so the whole prefix (not just the
 *  "axi[s]" token itself) is what has to match for two ports to be considered the same
 *  interface; "s_axis_a" and "s_axis_b" are two separate interfaces, not one. */
public final class AxiStreamDetector {
    private AxiStreamDetector() {}

    private static final Pattern SIGNAL_PATTERN = Pattern.compile(
            "^(.+)_t(data|valid|ready|last|keep|strb|user|id|dest|wakeup)$",
            Pattern.CASE_INSENSITIVE);

    /** Returns detected groups, in first-appearance order. A group needs at least 2
     *  recognized AXI-Stream signals to be treated as an interface. */
    public static List<PortGroup> detectGroups(VhdlEntity entity) {
        return detectGroups(entity.ports);
    }

    /** Same detection, but over any flat list of ports/pseudo-ports (used for both an
     *  entity's ports and a project's external top-level ports). */
    public static List<PortGroup> detectGroups(List<Port> ports) {
        Map<String, PortGroup> groups = new LinkedHashMap<>();
        for (Port p : ports) {
            Matcher m = SIGNAL_PATTERN.matcher(p.name);
            if (!m.matches()) continue;
            String prefix = m.group(1);
            if (!hasAxiToken(prefix)) continue;
            String key = prefix.toLowerCase();
            String suffix = m.group(2).toUpperCase();
            groups.computeIfAbsent(key, k -> new PortGroup(prefix)).signals.put(suffix, p);
        }
        List<PortGroup> result = new ArrayList<>();
        for (PortGroup g : groups.values()) {
            if (g.signals.size() >= 2) result.add(g);
        }
        return result;
    }

    /** The AXI-Stream interface prefix a port name belongs to (e.g. "s_axis" for
     *  "s_axis_tdata", "s_axis_a" for "s_axis_a_tdata"), or null if the name doesn't
     *  match the convention at all (no "_t&lt;signal&gt;" ending, or no "axi"/"axis"
     *  token anywhere in what's left). */
    public static String prefixOf(String portName) {
        Matcher m = SIGNAL_PATTERN.matcher(portName);
        if (!m.matches()) return null;
        String prefix = m.group(1);
        return hasAxiToken(prefix) ? prefix : null;
    }

    private static boolean hasAxiToken(String prefix) {
        for (String part : prefix.split("_")) {
            if (part.equalsIgnoreCase("axi") || part.equalsIgnoreCase("axis")) return true;
        }
        return false;
    }

    /** Ports that are not part of any detected group. */
    public static List<Port> ungroupedPorts(VhdlEntity entity, List<PortGroup> groups) {
        Set<Port> grouped = new HashSet<>();
        for (PortGroup g : groups) grouped.addAll(g.signals.values());
        List<Port> result = new ArrayList<>();
        for (Port p : entity.ports) if (!grouped.contains(p)) result.add(p);
        return result;
    }
}
