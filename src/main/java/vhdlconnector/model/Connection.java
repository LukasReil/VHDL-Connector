package vhdlconnector.model;

public class Connection {
    public String id;
    public Endpoint a;
    public Endpoint b;

    public Connection(String id, Endpoint a, Endpoint b) {
        this.id = id;
        this.a = a;
        this.b = b;
    }

    public boolean touches(Endpoint e) {
        return a.equals(e) || b.equals(e);
    }

    public boolean touchesInstance(String instanceId) {
        return (a.kind == Endpoint.Kind.INSTANCE && a.instanceId.equals(instanceId))
                || (b.kind == Endpoint.Kind.INSTANCE && b.instanceId.equals(instanceId));
    }

    public boolean touchesExternal(String portName) {
        return (a.kind == Endpoint.Kind.EXTERNAL && a.portName.equals(portName))
                || (b.kind == Endpoint.Kind.EXTERNAL && b.portName.equals(portName));
    }
}
