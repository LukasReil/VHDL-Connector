package vhdlconnector.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** A placed instance of a library entity on the canvas. */
public class Instance {
    public String id;         // unique within the project, e.g. "inst1"
    public String entityName; // references VhdlEntity.name in the project's library
    public String label;      // display / instantiation label, defaults to id
    public double x, y;       // canvas position (top-left of the block)
    public Map<String, String> genericOverrides = new LinkedHashMap<>();

    public Instance(String id, String entityName, double x, double y) {
        this.id = id;
        this.entityName = entityName;
        this.label = id;
        this.x = x;
        this.y = y;
    }
}
