package vhdlconnector.model;

public class Port {
    public String name;
    public Direction direction;
    public String type;

    public Port(String name, Direction direction, String type) {
        this.name = name;
        this.direction = direction;
        this.type = type;
    }

    public boolean canDrive() {
        return direction.canDrive();
    }

    public boolean canReceive() {
        return direction.canReceive();
    }

    @Override
    public String toString() {
        return name + " : " + direction.vhdl() + " " + type;
    }
}
