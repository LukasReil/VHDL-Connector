package vhdlconnector.model;

/** A top-level port of the entity that will be generated for the whole canvas. */
public class ExternalPort {
    public String name;
    public Direction direction; // direction as seen from OUTSIDE the generated entity
    public String type;
    public double x, y; // canvas position of the pin symbol

    public ExternalPort(String name, Direction direction, String type, double x, double y) {
        this.name = name;
        this.direction = direction;
        this.type = type;
        this.x = x;
        this.y = y;
    }

    /** Internally (inside the generated architecture) an external IN port behaves like a driver. */
    public boolean canDrive() {
        return direction == Direction.IN || direction == Direction.INOUT;
    }

    /** Internally an external OUT port behaves like a receiver (something must drive it). */
    public boolean canReceive() {
        return direction == Direction.OUT || direction == Direction.INOUT;
    }
}
