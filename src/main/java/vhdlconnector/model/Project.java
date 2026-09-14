package vhdlconnector.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Project {
    public String topEntityName = "top_design";
    public Map<String, VhdlEntity> library = new LinkedHashMap<>(); // keyed by entity name
    public List<Instance> instances = new ArrayList<>();
    public List<ExternalPort> externalPorts = new ArrayList<>();
    public List<Connection> connections = new ArrayList<>();

    public String projectFilePath; // where this project was last saved/loaded from, nullable
    public String workspaceRoot;   // folder opened via "Open Workspace Folder...", nullable

    private int instanceCounter = 0;
    private int connectionCounter = 0;

    public void addEntity(VhdlEntity e) {
        library.put(e.name, e);
    }

    public Instance getInstance(String id) {
        for (Instance i : instances) if (i.id.equals(id)) return i;
        return null;
    }

    public ExternalPort getExternalPort(String name) {
        for (ExternalPort p : externalPorts) if (p.name.equals(name)) return p;
        return null;
    }

    public VhdlEntity getEntityForInstance(Instance inst) {
        return library.get(inst.entityName);
    }

    public String nextInstanceId(String entityName) {
        String base = entityName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        int n = 1;
        String id;
        do {
            id = base + "_inst" + (n++);
        } while (getInstance(id) != null);
        return id;
    }

    /** Unlike a plain incrementing counter, this checks against the connections actually
     *  present - the counter alone isn't reliable across a save/load round-trip (a freshly
     *  loaded Project starts it back at 0, so the very next connection created after loading
     *  could mint an id like "conn2" that the loaded file already uses for something else;
     *  since routedPaths and similar id-keyed maps just let the newer one overwrite the
     *  older one's entry, that collision silently hijacks the older connection's rendering -
     *  see deduplicateConnectionIds for repairing a project that already has this problem). */
    public String nextConnectionId() {
        String id;
        do {
            id = "conn" + (++connectionCounter);
        } while (hasConnectionId(id));
        return id;
    }

    private boolean hasConnectionId(String id) {
        for (Connection c : connections) if (c.id.equals(id)) return true;
        return false;
    }

    /** Repairs a project where two different connections ended up with the same id (the
     *  save/load counter bug described on nextConnectionId): keeps the first occurrence of
     *  each id and reassigns a fresh, genuinely unique one to every later duplicate. Returns
     *  how many were reassigned. */
    public int deduplicateConnectionIds() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        int fixed = 0;
        for (Connection c : connections) {
            if (!seen.add(c.id)) {
                c.id = nextConnectionId();
                seen.add(c.id);
                fixed++;
            }
        }
        return fixed;
    }

    public void removeInstance(String id) {
        instances.removeIf(i -> i.id.equals(id));
        connections.removeIf(c -> c.touchesInstance(id));
    }

    public void removeExternalPort(String name) {
        externalPorts.removeIf(p -> p.name.equals(name));
        connections.removeIf(c -> c.touchesExternal(name));
    }

    public void removeConnection(String id) {
        connections.removeIf(c -> c.id.equals(id));
    }

    public void removeConnectionsTouching(Endpoint e) {
        connections.removeIf(c -> c.touches(e));
    }

    /** Removes any connection whose endpoint can no longer be resolved to a real port - e.g.
     *  an instance whose entity was re-imported with a different port list (renaming or
     *  dropping a port), leaving a connection referencing a name that no longer exists. Such
     *  a connection can never be drawn (there's no pin left to draw it at) but still counts
     *  as "already connected" for that dangling endpoint, silently blocking any new wire to
     *  it forever - so it needs to actually be removed, not just skipped when rendering.
     *  Returns how many were removed. */
    public int pruneOrphanedConnections() {
        int before = connections.size();
        connections.removeIf(c -> resolvePort(c.a) == null || resolvePort(c.b) == null);
        return before - connections.size();
    }

    /** Look up direction + type for an endpoint, or null if it cannot be resolved. */
    public Port resolvePort(Endpoint e) {
        if (e.kind == Endpoint.Kind.EXTERNAL) {
            ExternalPort ep = getExternalPort(e.portName);
            if (ep == null) return null;
            return new Port(ep.name, ep.direction, ep.type);
        } else {
            Instance inst = getInstance(e.instanceId);
            if (inst == null) return null;
            VhdlEntity ent = getEntityForInstance(inst);
            if (ent == null) return null;
            return ent.getPort(e.portName);
        }
    }

    public boolean canDrive(Endpoint e) {
        if (e.kind == Endpoint.Kind.EXTERNAL) {
            ExternalPort ep = getExternalPort(e.portName);
            return ep != null && ep.canDrive();
        }
        Port p = resolvePort(e);
        return p != null && p.canDrive();
    }

    public boolean canReceive(Endpoint e) {
        if (e.kind == Endpoint.Kind.EXTERNAL) {
            ExternalPort ep = getExternalPort(e.portName);
            return ep != null && ep.canReceive();
        }
        Port p = resolvePort(e);
        return p != null && p.canReceive();
    }


    public boolean isEndpointAlreadyConnected(Endpoint e) {
        for (Connection c : connections) {
            if (c.touches(e)) return true;
        }
        return false;
    }

    public boolean isEndpointAlreadyConnected(Endpoint a, Endpoint b) {
        for (Connection c : connections) {
            if ((c.a.equals(a) && c.b.equals(b)) || (c.a.equals(b) && c.b.equals(a))) return true;
        }
        return false;
    }
}
