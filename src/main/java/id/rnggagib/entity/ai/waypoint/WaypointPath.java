package id.rnggagib.entity.ai.waypoint;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Represents a path consisting of multiple waypoints
 */
public class WaypointPath {
    private final List<Waypoint> waypoints;
    private int currentWaypointIndex;
    private boolean completed;
    private final double waypointReachedDistance = 1.5;
    
    public WaypointPath() {
        this.waypoints = new ArrayList<>();
        this.currentWaypointIndex = 0;
        this.completed = false;
    }
    
    /**
     * Adds a waypoint to the path
     */
    public void addWaypoint(Waypoint waypoint) {
        waypoints.add(waypoint);
    }
    
    /**
     * Adds a waypoint at the specified location
     */
    public void addWaypoint(Location location) {
        waypoints.add(new Waypoint(location));
    }
    
    /**
     * Gets the current waypoint
     */
    public Waypoint getCurrentWaypoint() {
        if (currentWaypointIndex < waypoints.size()) {
            return waypoints.get(currentWaypointIndex);
        }
        return null;
    }
    
    /**
     * Updates path progress based on current entity location
     * @return true if waypoint was reached and path updated
     */
    public boolean updateProgress(Location currentLocation) {
        if (completed || waypoints.isEmpty()) {
            return false;
        }
        
        Waypoint current = getCurrentWaypoint();
        if (current == null) {
            completed = true;
            return false;
        }
        
        // Check if current waypoint reached
        if (currentLocation.distanceSquared(current.getLocation()) <= waypointReachedDistance * waypointReachedDistance) {
            // Execute any actions at this waypoint
            current.executeActions();
            
            // Move to next waypoint
            currentWaypointIndex++;
            
            // Check if path is completed
            if (currentWaypointIndex >= waypoints.size()) {
                completed = true;
                return true;
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if path is completed
     */
    public boolean isCompleted() {
        return completed;
    }
    
    /**
     * Gets all waypoints in path
     */
    public List<Waypoint> getWaypoints() {
        return new ArrayList<>(waypoints);
    }
    
    /**
     * Optimizes the path by removing unnecessary waypoints
     */
    public void optimize() {
        if (waypoints.size() <= 2) return;
        
        Iterator<Waypoint> iterator = waypoints.iterator();
        Waypoint prev = iterator.next();
        Waypoint current = null;
        
        while (iterator.hasNext()) {
            current = iterator.next();
            
            if (iterator.hasNext()) {
                Waypoint next = waypoints.get(waypoints.indexOf(current) + 1);
                
                // Check if the waypoint is along a straight line
                Vector v1 = current.getLocation().toVector().subtract(prev.getLocation().toVector());
                Vector v2 = next.getLocation().toVector().subtract(current.getLocation().toVector());
                
                if (v1.normalize().dot(v2.normalize()) > 0.95) { // If vectors are nearly parallel
                    iterator.remove();
                } else {
                    prev = current;
                }
            }
        }
    }
    
    /**
     * Get estimated remaining distance in the path
     */
    public double getRemainingDistance() {
        if (completed || waypoints.isEmpty()) {
            return 0;
        }
        
        double distance = 0;
        for (int i = currentWaypointIndex; i < waypoints.size() - 1; i++) {
            distance += waypoints.get(i).getLocation().distance(waypoints.get(i + 1).getLocation());
        }
        
        return distance;
    }
}