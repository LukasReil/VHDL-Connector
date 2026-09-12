package vhdlconnector.model;

import java.util.ArrayList;
import java.util.List;

/** A VHDL entity signature: name, generics, and ports, as parsed from a source file. */
public class VhdlEntity {
    public String name;
    public String sourceFile; // absolute path of the .vhd file it was imported from, nullable
    public List<GenericParam> generics = new ArrayList<>();
    public List<Port> ports = new ArrayList<>();

    private List<PortGroup> cachedAxiStreamGroups;

    public VhdlEntity(String name) {
        this.name = name;
    }

    /** AXI4-Stream interfaces detected among this entity's ports by name convention
     *  (see AxiStreamDetector). Cached since ports don't change after parsing. */
    public List<PortGroup> getAxiStreamGroups() {
        if (cachedAxiStreamGroups == null) cachedAxiStreamGroups = AxiStreamDetector.detectGroups(this);
        return cachedAxiStreamGroups;
    }

    public List<Port> getUngroupedPorts() {
        return AxiStreamDetector.ungroupedPorts(this, getAxiStreamGroups());
    }

    public Port getPort(String portName) {
        for (Port p : ports) {
            if (p.name.equalsIgnoreCase(portName)) return p;
        }
        return null;
    }

    public GenericParam getGeneric(String genName) {
        for (GenericParam g : generics) {
            if (g.name.equalsIgnoreCase(genName)) return g;
        }
        return null;
    }

    public List<Port> getInputPorts() {
        List<Port> result = new ArrayList<>();
        for (Port p : ports) if (p.canReceive() && !p.canDrive()) result.add(p);
        return result;
    }

    public List<Port> getOutputPorts() {
        List<Port> result = new ArrayList<>();
        for (Port p : ports) if (p.canDrive() && !p.canReceive()) result.add(p);
        return result;
    }

    public List<Port> getInoutPorts() {
        List<Port> result = new ArrayList<>();
        for (Port p : ports) if (p.canDrive() && p.canReceive()) result.add(p);
        return result;
    }
}
