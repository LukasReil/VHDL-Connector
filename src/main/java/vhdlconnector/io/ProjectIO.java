package vhdlconnector.io;

import vhdlconnector.json.Json;
import vhdlconnector.model.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Saves/loads a Project as JSON. The library entities are embedded in full, so a
 *  project file is self-contained and does not need the original .vhd files to reload. */
public class ProjectIO {

    public void save(Project project, File file) throws IOException {
        File projectDir = file.getAbsoluteFile().getParentFile();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("topEntityName", project.topEntityName);

        List<Object> entities = new ArrayList<>();
        for (VhdlEntity e : project.library.values()) entities.add(entityToJson(e, projectDir));
        root.put("library", entities);

        List<Object> instances = new ArrayList<>();
        for (Instance i : project.instances) instances.add(instanceToJson(i));
        root.put("instances", instances);

        List<Object> extPorts = new ArrayList<>();
        for (ExternalPort p : project.externalPorts) extPorts.add(externalPortToJson(p));
        root.put("externalPorts", extPorts);

        List<Object> conns = new ArrayList<>();
        for (Connection c : project.connections) conns.add(connectionToJson(c));
        root.put("connections", conns);

        Files.write(file.toPath(), Json.write(root).getBytes(StandardCharsets.UTF_8));
        project.projectFilePath = file.getAbsolutePath();
    }

    public Project load(File file) throws IOException {
        File projectDir = file.getAbsoluteFile().getParentFile();
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Map<String, Object> root = Json.obj(Json.parse(text));

        Project project = new Project();
        project.topEntityName = Json.str(root, "topEntityName", "top_design");

        for (Object o : Json.arr(root.getOrDefault("library", new ArrayList<>()))) {
            VhdlEntity e = entityFromJson(Json.obj(o), projectDir);
            project.addEntity(e);
        }
        for (Object o : Json.arr(root.getOrDefault("instances", new ArrayList<>()))) {
            project.instances.add(instanceFromJson(Json.obj(o)));
        }
        for (Object o : Json.arr(root.getOrDefault("externalPorts", new ArrayList<>()))) {
            project.externalPorts.add(externalPortFromJson(Json.obj(o)));
        }
        for (Object o : Json.arr(root.getOrDefault("connections", new ArrayList<>()))) {
            project.connections.add(connectionFromJson(Json.obj(o)));
        }
        project.projectFilePath = file.getAbsolutePath();
        return project;
    }

    // ---------- entity ----------

    private Map<String, Object> entityToJson(VhdlEntity e, File projectDir) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.name);
        if (e.sourceFile != null) m.put("sourceFile", relativizeToProject(e.sourceFile, projectDir));
        List<Object> gens = new ArrayList<>();
        for (GenericParam g : e.generics) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("name", g.name);
            gm.put("type", g.type);
            if (g.defaultValue != null) gm.put("defaultValue", g.defaultValue);
            gens.add(gm);
        }
        m.put("generics", gens);
        List<Object> ports = new ArrayList<>();
        for (Port p : e.ports) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name);
            pm.put("direction", p.direction.vhdl());
            pm.put("type", p.type);
            ports.add(pm);
        }
        m.put("ports", ports);
        return m;
    }

    private VhdlEntity entityFromJson(Map<String, Object> m, File projectDir) {
        VhdlEntity e = new VhdlEntity(Json.str(m, "name", "unnamed"));
        String storedSourceFile = (String) m.get("sourceFile");
        e.sourceFile = storedSourceFile != null ? resolveAgainstProject(storedSourceFile, projectDir) : null;
        for (Object o : Json.arr(m.getOrDefault("generics", new ArrayList<>()))) {
            Map<String, Object> gm = Json.obj(o);
            e.generics.add(new GenericParam(Json.str(gm, "name", ""), Json.str(gm, "type", ""), (String) gm.get("defaultValue")));
        }
        for (Object o : Json.arr(m.getOrDefault("ports", new ArrayList<>()))) {
            Map<String, Object> pm = Json.obj(o);
            e.ports.add(new Port(Json.str(pm, "name", ""), Direction.parse(Json.str(pm, "direction", "in")), Json.str(pm, "type", "std_logic")));
        }
        return e;
    }

    /** Stores an entity's source .vhd path relative to the project file's own directory,
     *  so a project stays portable if the whole folder (project + sources) is moved or
     *  shared - e.g. via git - rather than being tied to one machine's absolute layout.
     *  Falls back to the absolute path if the two can't be related (e.g. different drive
     *  roots on Windows). */
    private String relativizeToProject(String absoluteSourceFile, File projectDir) {
        if (projectDir == null) return absoluteSourceFile;
        try {
            return projectDir.toPath().relativize(java.nio.file.Paths.get(absoluteSourceFile)).toString();
        } catch (IllegalArgumentException ex) {
            return absoluteSourceFile;
        }
    }

    /** Resolves a stored sourceFile path against the project file's directory. Transparently
     *  handles project files saved before this change (which stored an absolute path):
     *  Path.resolve returns an absolute argument unchanged, so old projects keep working. */
    private String resolveAgainstProject(String storedSourceFile, File projectDir) {
        if (projectDir == null) return storedSourceFile;
        return projectDir.toPath().resolve(storedSourceFile).normalize().toString();
    }

    // ---------- instance ----------

    private Map<String, Object> instanceToJson(Instance i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id);
        m.put("entityName", i.entityName);
        m.put("label", i.label);
        m.put("x", i.x);
        m.put("y", i.y);
        Map<String, Object> overrides = new LinkedHashMap<>(i.genericOverrides);
        m.put("genericOverrides", overrides);
        return m;
    }

    private Instance instanceFromJson(Map<String, Object> m) {
        Instance i = new Instance(Json.str(m, "id", "inst"), Json.str(m, "entityName", ""), Json.num(m, "x", 0), Json.num(m, "y", 0));
        i.label = Json.str(m, "label", i.id);
        Object ov = m.get("genericOverrides");
        if (ov instanceof Map) {
            for (Map.Entry<String, Object> e : Json.obj(ov).entrySet()) {
                i.genericOverrides.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return i;
    }

    // ---------- external port ----------

    private Map<String, Object> externalPortToJson(ExternalPort p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name);
        m.put("direction", p.direction.vhdl());
        m.put("type", p.type);
        m.put("x", p.x);
        m.put("y", p.y);
        return m;
    }

    private ExternalPort externalPortFromJson(Map<String, Object> m) {
        return new ExternalPort(Json.str(m, "name", ""), Direction.parse(Json.str(m, "direction", "in")),
                Json.str(m, "type", "std_logic"), Json.num(m, "x", 0), Json.num(m, "y", 0));
    }

    // ---------- connection ----------

    private Map<String, Object> connectionToJson(Connection c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("a", endpointToJson(c.a));
        m.put("b", endpointToJson(c.b));
        m.put("isBus", c.isBus);
        return m;
    }

    private Connection connectionFromJson(Map<String, Object> m) {
        return new Connection(Json.str(m, "id", "conn"), endpointFromJson(Json.obj(m.get("a"))), endpointFromJson(Json.obj(m.get("b"))), Json.bool(m, "isBus", false));
    }

    private Map<String, Object> endpointToJson(Endpoint e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", e.kind.name());
        if (e.instanceId != null) m.put("instanceId", e.instanceId);
        m.put("portName", e.portName);
        return m;
    }

    private Endpoint endpointFromJson(Map<String, Object> m) {
        Endpoint.Kind kind = Endpoint.Kind.valueOf(Json.str(m, "kind", "EXTERNAL"));
        return new Endpoint(kind, (String) m.get("instanceId"), Json.str(m, "portName", ""));
    }
}
