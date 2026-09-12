package vhdlconnector.model;

public enum Direction {
    IN, OUT, INOUT, BUFFER, LINKAGE;

    public static Direction parse(String s) {
        if (s == null) return IN;
        switch (s.trim().toLowerCase()) {
            case "in": return IN;
            case "out": return OUT;
            case "inout": return INOUT;
            case "buffer": return BUFFER;
            case "linkage": return LINKAGE;
            default: return IN;
        }
    }

    public String vhdl() {
        switch (this) {
            case IN: return "in";
            case OUT: return "out";
            case INOUT: return "inout";
            case BUFFER: return "buffer";
            case LINKAGE: return "linkage";
            default: return "in";
        }
    }

    /** True if a port/endpoint with this direction can act as a driver of a net. */
    public boolean canDrive() {
        return this == OUT || this == INOUT || this == BUFFER || this == LINKAGE;
    }

    /** True if a port/endpoint with this direction can act as a receiver on a net. */
    public boolean canReceive() {
        return this == IN || this == INOUT || this == LINKAGE;
    }
}
