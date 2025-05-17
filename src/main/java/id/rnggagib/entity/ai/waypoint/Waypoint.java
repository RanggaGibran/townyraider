package id.rnggagib.entity.ai.waypoint;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Represents a single point in a navigation path
 */
public class Waypoint {
    private final Location location;
    private final List<Consumer<Waypoint>> actions;
    private double cost = 1.0;
    private WaypointType type = WaypointType.NORMAL;
    
    public Waypoint(Location location) {
        this.location = location.clone();
        this.actions = new ArrayList<>();
    }
    
    /**
     * Gets the location of this waypoint
     */
    public Location getLocation() {
        return location.clone();
    }
    
    /**
     * Adds an action to execute when this waypoint is reached
     */
    public void addAction(Consumer<Waypoint> action) {
        actions.add(action);
    }
    
    /**
     * Executes all actions associated with this waypoint
     */
    public void executeActions() {
        for (Consumer<Waypoint> action : actions) {
            action.accept(this);
        }
    }
    
    /**
     * Gets the cost of traveling to this waypoint
     */
    public double getCost() {
        return cost;
    }
    
    /**
     * Sets the cost of traveling to this waypoint
     */
    public void setCost(double cost) {
        this.cost = Math.max(0.1, cost);
    }
    
    /**
     * Gets the type of this waypoint
     */
    public WaypointType getType() {
        return type;
    }
    
    /**
     * Sets the type of this waypoint
     */
    public void setType(WaypointType type) {
        this.type = type;
    }
    
    /**
     * Types of waypoints for different navigation behavior
     */
    public enum WaypointType {
        NORMAL,        // Standard movement
        JUMP,          // Entity should jump at this point
        CAREFUL,       // Entity should move carefully (reduced speed)
        DOOR,          // Entity should interact with a door
        BREAK_BLOCK,   // Entity should break a block
        PLACE_BLOCK    // Entity should place a block
    }
}