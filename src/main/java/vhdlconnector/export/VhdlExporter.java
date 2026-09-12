package vhdlconnector.export;

import vhdlconnector.model.*;

import java.util.*;

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

        // net type/width: take from the first endpoint we can resolve a Port for
        Map<Endpoint, String> netType = new LinkedHashMap<>();
        for (List<Endpoint> net : nets.values()) {
            if (net.size() < 2) continue;
            String type = "std_logic";
            for (Endpoint e : net) {
                Port p = project.resolvePort(e);
                if (p != null) { type = p.type; break; }
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
