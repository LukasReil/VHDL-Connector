package vhdlconnector.model;

public class GenericParam {
    public String name;
    public String type;
    public String defaultValue; // nullable

    public GenericParam(String name, String type, String defaultValue) {
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
    }

    @Override
    public String toString() {
        return name + " : " + type + (defaultValue != null ? (" := " + defaultValue) : "");
    }
}
