package vhdlconnector.model;

import java.util.Objects;

/** One end of a wire: either a port on a placed instance, or an external (top-level) port. */
public class Endpoint {
    public enum Kind { INSTANCE, EXTERNAL }

    public Kind kind;
    public String instanceId; // null when kind == EXTERNAL
    public String portName;

    public Endpoint(Kind kind, String instanceId, String portName) {
        this.kind = kind;
        this.instanceId = instanceId;
        this.portName = portName;
    }

    public static Endpoint instancePort(String instanceId, String portName) {
        return new Endpoint(Kind.INSTANCE, instanceId, portName);
    }

    public static Endpoint external(String portName) {
        return new Endpoint(Kind.EXTERNAL, null, portName);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Endpoint)) return false;
        Endpoint e = (Endpoint) o;
        return kind == e.kind && Objects.equals(instanceId, e.instanceId) && Objects.equals(portName, e.portName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, instanceId, portName);
    }

    @Override
    public String toString() {
        return kind == Kind.EXTERNAL ? ("EXT:" + portName) : (instanceId + "." + portName);
    }
}
