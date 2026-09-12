package vhdlconnector.export;

import vhdlconnector.model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generates a VHDL entity + architecture that instantiates every placed instance
 *  and wires them together according to the project's connections. */
public class VhdlExporter {

    public static class Result {
        public String vhdl;
        public List<String> warnings = new ArrayList<>();
    }

    public Result generate(Project project) {
        Result result = new Result();
        StringBuilder sb = new StringBuilder();

        // ---- union-find over all endpoints that appear in a connection ----
        Map<Endpoint, Endpoint> parent = new LinkedHashMap<>();
        for (Connection c : project.connections) {
            parent.putIfAbsent(c.a, c.a);
            parent.putIfAbsent(c.b, c.b);
        }
        for (Connection c : project.connections) {
            union(parent, c.a, c.b);
        }

        // group endpoints by net root
        Map<Endpoint, List<Endpoint>> nets = new LinkedHashMap<>();
        for (Endpoint e : parent.keySet()) {
            Endpoint root = find(parent, e);
            nets.computeIfAbsent(root, k -> new ArrayList<>()).add(e);
        }

        // assign a signal name per net that needs one (nets touching 2+ instance ports,
        // or a net with more than one endpoint at all)
        Map<Endpoint, String> netSignalName = new LinkedHashMap<>();
        int sigCounter = 0;
        for (List<Endpoint> net : nets.values()) {
            if (net.size() < 2) continue;
            String signalName = "sig_" + (++sigCounter);
            // prefer a readable name based on an external port or instance.port in the net
            for (Endpoint e : net) {
                if (e.kind == Endpoint.Kind.EXTERNAL) {
                    signalName = "sig_" + e.portName;
                    break;
                }
            }
            for (Endpoint e : net) {
                netSignalName.put(e, signalName);
            }
        }

        // net type/width: take from the first endpoint we can resolve a Port for. An
        // instance port's type may reference the entity's own generics (e.g.
        // "std_logic_vector(WIDTH-1 downto 0)") - WIDTH only exists in that instance's own
        // generic map, not in the architecture's declarative region, so it must be
        // substituted with that instance's actual (overridden or default) generic value
        // before it can be used for a `signal` declaration here.
        Map<Endpoint, String> netType = new LinkedHashMap<>();
        for (List<Endpoint> net : nets.values()) {
            if (net.size() < 2) continue;
            String type = "std_logic";
            String firstType = null;
            boolean mismatch = false;
            for (Endpoint e : net) {
                String resolved = resolveEndpointType(project, e, result.warnings);
                if (resolved == null) continue;
                if (firstType == null) { type = resolved; firstType = resolved; }
                else if (!resolved.equalsIgnoreCase(firstType)) mismatch = true;
            }
            if (mismatch) {
                result.warnings.add("Net containing " + net.get(0) + " has mismatched resolved types (e.g. '" + firstType + "') - check generic-dependent widths line up");
            }
            for (Endpoint e : net) netType.put(e, type);
        }

        // check for unconnected instance ports -> warnings
        for (Instance inst : project.instances) {
            VhdlEntity entity = project.getEntityForInstance(inst);
            if (entity == null) {
                result.warnings.add("Instance '" + inst.id + "' references unknown entity '" + inst.entityName + "'");
                continue;
            }
            for (Port p : entity.ports) {
                Endpoint ep = Endpoint.instancePort(inst.id, p.name);
                if (!netSignalName.containsKey(ep)) {
                    result.warnings.add("Port '" + p.name + "' of instance '" + inst.id + "' is unconnected");
                }
            }
        }

        // ---------- library clauses ----------
        sb.append("library ieee;\n");
        sb.append("use ieee.std_logic_1164.all;\n");
        sb.append("use ieee.numeric_std.all;\n\n");

        // ---------- entity ----------
        sb.append("entity ").append(project.topEntityName).append(" is\n");
        if (!project.externalPorts.isEmpty()) {
            sb.append("  port (\n");
            for (int i = 0; i < project.externalPorts.size(); i++) {
                ExternalPort p = project.externalPorts.get(i);
                sb.append("    ").append(p.name).append(" : ").append(p.direction.vhdl()).append(' ').append(p.type);
                sb.append(i < project.externalPorts.size() - 1 ? ";\n" : "\n");
            }
            sb.append("  );\n");
        }
        sb.append("end entity ").append(project.topEntityName).append(";\n\n");

        // ---------- architecture ----------
        sb.append("architecture structural of ").append(project.topEntityName).append(" is\n\n");

        // component declarations, one per distinct entity type actually used
        Set<String> usedEntities = new LinkedHashSet<>();
        for (Instance inst : project.instances) usedEntities.add(inst.entityName);
        for (String entName : usedEntities) {
            VhdlEntity entity = project.library.get(entName);
            if (entity == null) continue;
            sb.append("  component ").append(entity.name).append("\n");
            if (!entity.generics.isEmpty()) {
                sb.append("    generic (\n");
                for (int i = 0; i < entity.generics.size(); i++) {
                    GenericParam g = entity.generics.get(i);
                    sb.append("      ").append(g.name).append(" : ").append(g.type);
                    if (g.defaultValue != null) sb.append(" := ").append(g.defaultValue);
                    sb.append(i < entity.generics.size() - 1 ? ";\n" : "\n");
                }
                sb.append("    );\n");
            }
            if (!entity.ports.isEmpty()) {
                sb.append("    port (\n");
                for (int i = 0; i < entity.ports.size(); i++) {
                    Port p = entity.ports.get(i);
                    sb.append("      ").append(p.name).append(" : ").append(p.direction.vhdl()).append(' ').append(p.type);
                    sb.append(i < entity.ports.size() - 1 ? ";\n" : "\n");
                }
                sb.append("    );\n");
            }
            sb.append("  end component;\n\n");
        }

        // signal declarations
        Set<String> declaredSignals = new LinkedHashSet<>();
        for (Map.Entry<Endpoint, String> e : netSignalName.entrySet()) {
            String name = e.getValue();
            if (declaredSignals.add(name)) {
                String type = netType.getOrDefault(e.getKey(), "std_logic");
                sb.append("  signal ").append(name).append(" : ").append(type).append(";\n");
            }
        }
        sb.append("\nbegin\n\n");

        // component instantiations
        for (Instance inst : project.instances) {
            VhdlEntity entity = project.getEntityForInstance(inst);
            if (entity == null) continue;
            sb.append("  ").append(inst.label).append(" : ").append(entity.name).append('\n');
            if (!inst.genericOverrides.isEmpty()) {
                sb.append("    generic map (\n");
                List<String> keys = new ArrayList<>(inst.genericOverrides.keySet());
                for (int i = 0; i < keys.size(); i++) {
                    String k = keys.get(i);
                    sb.append("      ").append(k).append(" => ").append(inst.genericOverrides.get(k));
                    sb.append(i < keys.size() - 1 ? ",\n" : "\n");
                }
                sb.append("    )\n");
            }
            List<Port> connectedPorts = new ArrayList<>();
            for (Port p : entity.ports) {
                Endpoint ep = Endpoint.instancePort(inst.id, p.name);
                if (netSignalName.containsKey(ep)) connectedPorts.add(p);
            }
            if (!connectedPorts.isEmpty()) {
                sb.append("    port map (\n");
                for (int i = 0; i < connectedPorts.size(); i++) {
                    Port p = connectedPorts.get(i);
                    String sig = netSignalName.get(Endpoint.instancePort(inst.id, p.name));
                    sb.append("      ").append(p.name).append(" => ").append(sig);
                    sb.append(i < connectedPorts.size() - 1 ? ",\n" : "\n");
                }
                sb.append("    );\n\n");
            } else {
                sb.append("    port map ( -- all ports unconnected\n    );\n\n");
            }
        }

        // external port <-> signal assignments (for external ports that share a net with something)
        for (ExternalPort ep : project.externalPorts) {
            Endpoint e = Endpoint.external(ep.name);
            String sig = netSignalName.get(e);
            if (sig == null) continue; // unconnected external port
            if (ep.direction == Direction.OUT) {
                sb.append("  ").append(ep.name).append(" <= ").append(sig).append(";\n");
            } else if (ep.direction == Direction.IN) {
                sb.append("  ").append(sig).append(" <= ").append(ep.name).append(";\n");
            } else { // INOUT: only valid if directly 1:1, otherwise flag as a warning
                sb.append("  ").append(ep.name).append(" <= ").append(sig).append("; -- inout: verify directionality manually\n");
            }
        }

        sb.append("\nend architecture structural;\n");

        result.vhdl = sb.toString();
        return result;
    }

    /** The endpoint's port type with any of its owning entity's generics substituted by
     *  that specific instance's actual value (its generic map override, or the entity's
     *  own default if not overridden), then numerically evaluated (e.g. "16-1 downto 0"
     *  becomes "15 downto 0") so it reads cleanly and, just as importantly, so two ports
     *  that are really the same width but got there via different generic values/arithmetic
     *  compare equal instead of triggering a false "mismatched width" warning. External
     *  ports have no generics but are normalized the same way for that comparison to work
     *  uniformly. Returns null if the endpoint can't be resolved at all. */
    private String resolveEndpointType(Project project, Endpoint e, List<String> warnings) {
        if (e.kind == Endpoint.Kind.EXTERNAL) {
            ExternalPort ep = project.getExternalPort(e.portName);
            return ep != null ? normalizeVectorWidth(ep.type) : null;
        }
        Instance inst = project.getInstance(e.instanceId);
        if (inst == null) return null;
        VhdlEntity entity = project.getEntityForInstance(inst);
        if (entity == null) return null;
        Port port = entity.getPort(e.portName);
        if (port == null) return null;
        return normalizeVectorWidth(substituteGenerics(port.type, entity, inst, warnings));
    }

    private static final Pattern VECTOR_TYPE_PATTERN = Pattern.compile(
            "std_logic_vector\\s*\\(\\s*(.+?)\\s+downto\\s+0\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** Evaluates the high-bit expression of a std_logic_vector(...) type if it's a plain
     *  integer arithmetic expression (as a generic-width port's usually is once its generic
     *  names have been substituted with literal values), replacing it with the computed
     *  number. Leaves the type untouched if it isn't in that shape or won't evaluate. */
    private String normalizeVectorWidth(String type) {
        Matcher m = VECTOR_TYPE_PATTERN.matcher(type.trim());
        if (!m.matches()) return type;
        Integer high = evalIntExpr(m.group(1));
        return high != null ? ("std_logic_vector(" + high + " downto 0)") : type;
    }

    /** Evaluates a simple integer arithmetic expression (+, -, *, /, parentheses), or
     *  returns null if it isn't one (e.g. still contains an unresolved identifier). */
    private static Integer evalIntExpr(String expr) {
        try {
            IntExprParser parser = new IntExprParser(expr);
            int value = parser.parseExpr();
            parser.skipWs();
            return parser.atEnd() ? value : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static final class IntExprParser {
        private final String s;
        private int pos = 0;
        IntExprParser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }
        void skipWs() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

        int parseExpr() {
            int v = parseTerm();
            while (true) {
                skipWs();
                if (pos < s.length() && s.charAt(pos) == '+') { pos++; v += parseTerm(); }
                else if (pos < s.length() && s.charAt(pos) == '-') { pos++; v -= parseTerm(); }
                else return v;
            }
        }

        int parseTerm() {
            int v = parseFactor();
            while (true) {
                skipWs();
                if (pos < s.length() && s.charAt(pos) == '*') { pos++; v *= parseFactor(); }
                else if (pos < s.length() && s.charAt(pos) == '/') { pos++; v /= parseFactor(); }
                else return v;
            }
        }

        int parseFactor() {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                int v = parseExpr();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ')') throw new RuntimeException("expected ')'");
                pos++;
                return v;
            }
            if (pos < s.length() && s.charAt(pos) == '-') { pos++; return -parseFactor(); }
            int start = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            if (start == pos) throw new RuntimeException("expected a number at " + pos);
            return Integer.parseInt(s.substring(start, pos));
        }
    }

    private String substituteGenerics(String type, VhdlEntity entity, Instance inst, List<String> warnings) {
        String result = type;
        for (GenericParam g : entity.generics) {
            if (!containsWord(result, g.name)) continue;
            String value = inst.genericOverrides.get(g.name);
            if (value == null) value = g.defaultValue;
            if (value == null) {
                warnings.add("Generic '" + g.name + "' on instance '" + inst.id + "' has no override and no default - "
                        + "'" + type + "' will reference an undefined identifier in the generated VHDL");
                continue;
            }
            Pattern p = Pattern.compile("\\b" + Pattern.quote(g.name) + "\\b", Pattern.CASE_INSENSITIVE);
            result = p.matcher(result).replaceAll(Matcher.quoteReplacement(value));
        }
        return result;
    }

    private boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private Endpoint find(Map<Endpoint, Endpoint> parent, Endpoint e) {
        Endpoint p = parent.get(e);
        if (p == null) return e;
        if (!p.equals(e)) {
            Endpoint root = find(parent, p);
            parent.put(e, root);
            return root;
        }
        return e;
    }

    private void union(Map<Endpoint, Endpoint> parent, Endpoint a, Endpoint b) {
        Endpoint ra = find(parent, a);
        Endpoint rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }
}
