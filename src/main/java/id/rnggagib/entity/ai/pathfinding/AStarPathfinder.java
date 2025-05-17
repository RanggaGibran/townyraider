package id.rnggagib.entity.ai.pathfinding;

import id.rnggagib.TownyRaider;
import id.rnggagib.entity.ai.waypoint.Waypoint;
import id.rnggagib.entity.ai.waypoint.WaypointPath;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.*;

public class AStarPathfinder {
    private final TownyRaider plugin;
    private final Set<Material> problematicBlocks;
    private final int maxIterations;
    private final int maxPathLength;
    
    // Direction vectors for neighbors (x, y, z) - cardinal directions + diagonals
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
            {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
            {0, 1, 0}, {0, -1, 0}  // Up and down for stairs/jumps
    };
    
    public AStarPathfinder(TownyRaider plugin, Set<Material> problematicBlocks) {
        this.plugin = plugin;
        this.problematicBlocks = problematicBlocks;
        this.maxIterations = 1000;  // Prevent infinite loops
        this.maxPathLength = 100;   // Maximum path length to search
    }
    
    /**
     * Find a path from start to end using A* algorithm
     */
    public WaypointPath findPath(Location start, Location end) {
        if (start.getWorld() != end.getWorld()) {
            return null; // Can't path between worlds
        }
        
        // Create nodes for start and end
        PathNode startNode = new PathNode(start);
        PathNode endNode = new PathNode(end);
        
        // Initialize open and closed sets
        PriorityQueue<PathNode> openSet = new PriorityQueue<>();
        Set<String> closedSet = new HashSet<>();
        
        // Add start node
        startNode.g = 0;
        startNode.h = heuristic(startNode, endNode);
        startNode.f = startNode.g + startNode.h;
        openSet.add(startNode);
        
        int iterations = 0;
        
        // Main A* loop
        while (!openSet.isEmpty() && iterations < maxIterations) {
            iterations++;
            
            // Get node with lowest f value
            PathNode current = openSet.poll();
            
            // Check if we reached the goal
            if (isSameBlock(current.location, end)) {
                return reconstructPath(current);
            }
            
            // Add to closed set
            closedSet.add(nodeToString(current));
            
            // Process neighbors
            for (int[] dir : NEIGHBORS) {
                // Calculate neighbor location
                Location neighborLoc = current.location.clone().add(dir[0], dir[1], dir[2]);
                
                // Skip if already in closed set
                PathNode neighborNode = new PathNode(neighborLoc);
                if (closedSet.contains(nodeToString(neighborNode))) {
                    continue;
                }
                
                // Check if location is valid for movement
                if (!isValidLocation(neighborLoc, current.location)) {
                    continue;
                }
                
                // Calculate g score (distance from start)
                double tentativeG = current.g + getMovementCost(current.location, neighborLoc);
                
                // Skip if path is too long
                if (tentativeG > maxPathLength) {
                    continue;
                }
                
                // Create or update neighbor node
                boolean inOpenSet = false;
                for (PathNode node : openSet) {
                    if (isSameBlock(node.location, neighborLoc)) {
                        inOpenSet = true;
                        if (tentativeG < node.g) {
                            // Found better path
                            node.parent = current;
                            node.g = tentativeG;
                            node.f = node.g + node.h;
                        }
                        break;
                    }
                }
                
                // Add to open set if not already in it
                if (!inOpenSet) {
                    neighborNode.g = tentativeG;
                    neighborNode.h = heuristic(neighborNode, endNode);
                    neighborNode.f = neighborNode.g + neighborNode.h;
                    neighborNode.parent = current;
                    openSet.add(neighborNode);
                }
            }
        }
        
        // No path found
        return null;
    }
    
    /**
     * Reconstruct path from end node to start node
     */
    private WaypointPath reconstructPath(PathNode endNode) {
        WaypointPath path = new WaypointPath();
        PathNode current = endNode;
        
        // Build path in reverse
        List<Location> locations = new ArrayList<>();
        while (current != null) {
            locations.add(current.location.clone());
            current = current.parent;
        }
        
        // Reverse the path and create waypoints
        for (int i = locations.size() - 1; i >= 0; i--) {
            path.addWaypoint(locations.get(i));
        }
        
        // Optimize path to remove unnecessary waypoints
        path.optimize();
        
        return path;
    }
    
    /**
     * Calculate heuristic (estimated distance to goal)
     */
    private double heuristic(PathNode a, PathNode b) {
        // Euclidean distance
        return a.location.distance(b.location);
    }
    
    /**
     * Calculate movement cost between two locations
     */
    private double getMovementCost(Location from, Location to) {
        double baseCost = from.distance(to);
        
        // Add penalties for difficult terrain
        if (isDifficultTerrain(to)) {
            baseCost *= 1.5;
        }
        
        // Add penalty for height changes
        if (Math.abs(from.getY() - to.getY()) > 0) {
            baseCost *= 1.2;
        }
        
        return baseCost;
    }
    
    /**
     * Check if a location is difficult terrain
     */
    private boolean isDifficultTerrain(Location location) {
        Block block = location.getBlock();
        Block below = block.getRelative(BlockFace.DOWN);
        
        // Check for difficult blocks
        return block.getType() == Material.SOUL_SAND ||
               block.getType() == Material.HONEY_BLOCK ||
               below.getType() == Material.ICE ||
               below.getType() == Material.PACKED_ICE ||
               below.getType() == Material.SLIME_BLOCK;
    }
    
    /**
     * Check if a location is valid for movement
     */
    private boolean isValidLocation(Location location, Location from) {
        Block block = location.getBlock();
        Block above = block.getRelative(BlockFace.UP);
        Block below = block.getRelative(BlockFace.DOWN);
        
        // Must have 2 blocks of air for clearance
        if (!block.isEmpty() || !above.isEmpty()) {
            return false;
        }
        
        // Must have solid ground below (or water for swimming)
        if (!below.getType().isSolid() && !below.isLiquid()) {
            return false;
        }
        
        // Avoid problematic blocks
        if (problematicBlocks.contains(block.getType()) ||
            problematicBlocks.contains(above.getType()) ||
            problematicBlocks.contains(below.getType())) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Check if two locations refer to the same block
     */
    private boolean isSameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX() &&
               a.getBlockY() == b.getBlockY() &&
               a.getBlockZ() == b.getBlockZ();
    }
    
    /**
     * Convert node to string key for closed set
     */
    private String nodeToString(PathNode node) {
        return node.location.getBlockX() + ":" + 
               node.location.getBlockY() + ":" +
               node.location.getBlockZ();
    }
    
    /**
     * Node class for A* algorithm
     */
    private static class PathNode implements Comparable<PathNode> {
        Location location;
        PathNode parent;
        double g; // Cost from start
        double h; // Heuristic cost to end
        double f; // Total cost (g + h)
        
        PathNode(Location location) {
            this.location = location.clone();
        }
        
        @Override
        public int compareTo(PathNode other) {
            return Double.compare(this.f, other.f);
        }
    }
}