package id.rnggagib.entity.ai;

import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_19_R3.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

import com.palmergames.bukkit.towny.object.Town;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class PathfindingManager {
    private final TownyRaider plugin;
    private final Map<Entity, PathCache> pathCache = new WeakHashMap<>();
    private final Set<Material> problematicBlocks;
    
    // Pathfinding constants
    private static final int PATH_RECALCULATION_TICKS = 40; // Recalculate path every 2 seconds
    private static final int STUCK_THRESHOLD = 3; // Number of checks before considered stuck
    private static final double STUCK_DISTANCE_SQUARED = 0.2 * 0.2; // Distance squared to consider mob stuck
    
    public PathfindingManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.problematicBlocks = initProblematicBlocks();
    }
    
    private Set<Material> initProblematicBlocks() {
        Set<Material> blocks = new HashSet<>();
        // Liquids
        blocks.add(Material.WATER);
        blocks.add(Material.LAVA);
        // Dangerous blocks
        blocks.add(Material.FIRE);
        blocks.add(Material.SOUL_FIRE);
        blocks.add(Material.CAMPFIRE);
        blocks.add(Material.SOUL_CAMPFIRE);
        blocks.add(Material.CACTUS);
        blocks.add(Material.SWEET_BERRY_BUSH);
        // Partial blocks that might cause pathing issues
        blocks.add(Material.COBWEB);
        blocks.add(Material.HONEY_BLOCK);
        
        return blocks;
    }
    
    /**
     * Navigate entity to target location using optimized pathfinding
     * @return True if path was successfully created
     */
    public boolean navigateTo(Mob entity, Location target, double speed) {
        if (entity == null || target == null || !entity.isValid() || entity.isDead()) {
            return false;
        }
        
        // Check if target is in same world
        if (!entity.getWorld().equals(target.getWorld())) {
            return false;
        }
        
        // Get or create path cache for this entity
        PathCache cache = pathCache.computeIfAbsent(entity, e -> new PathCache());
        
        // Check if we need to recalculate the path
        if (shouldRecalculatePath(entity, target, cache)) {
            // Use available methods in Mob interface for pathfinding
            boolean success = false;
            
            try {
                // First try: Use direct navigation method
                success = entity.getNavigation().setDestination(target, speed);
            } catch (Exception e) {
                // If above method doesn't exist, try alternate approach
                try {
                    // Second try: Use simple pathfinding in Minecraft 1.19
                    success = entity.getPathfinder().pathfind(target, speed);
                } catch (Exception e2) {
                    // Last resort: Fallback to our simple implementation
                    return navigateToFallback(entity, target, speed);
                }
            }
            
            // Update cache
            if (success) {
                cache.target = target.clone();
                cache.lastCalculationTime = System.currentTimeMillis();
                cache.isNavigating = true;
                
                // Store previous location to detect if entity gets stuck
                cache.lastPosition = entity.getLocation();
                cache.stuckCounter = 0;
                
                // Schedule path monitoring
                monitorPath(entity, target, cache);
                return true;
            } else {
                // If pathfinding failed, use fallback
                return navigateToFallback(entity, target, speed);
            }
        }
        
        return cache.isNavigating;
    }
    
    /**
     * Simple fallback navigation method when NMS methods aren't available
     */
    private boolean navigateToFallback(Mob entity, Location target, double speed) {
        // Calculate direction vector
        Vector direction = target.clone().subtract(entity.getLocation()).toVector().normalize();
        
        // Apply velocity
        entity.setVelocity(direction.multiply(speed));
        return true;
    }
    
    /**
     * Determine if path needs recalculation
     */
    private boolean shouldRecalculatePath(Mob entity, Location target, PathCache cache) {
        // Always calculate if no current target or not navigating
        if (cache.target == null || !cache.isNavigating) {
            return true;
        }
        
        // Check if target has changed significantly
        if (cache.target.getWorld() != target.getWorld() || 
            cache.target.distanceSquared(target) > 4.0) { // 2 blocks distance change
            return true;
        }
        
        // Recalculate if enough time has passed
        if (System.currentTimeMillis() - cache.lastCalculationTime > PATH_RECALCULATION_TICKS * 50) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Monitor path progress and handle issues
     */
    private void monitorPath(Mob entity, Location target, PathCache cache) {
        new BukkitRunnable() {
            @Override
            public void run() {
                // Check if entity or cache is gone
                if (!entity.isValid() || entity.isDead() || !pathCache.containsKey(entity)) {
                    this.cancel();
                    return;
                }
                
                // Check if reached target
                if (entity.getLocation().distanceSquared(target) < 4.0) { // 2 blocks distance
                    cache.isNavigating = false;
                    this.cancel();
                    return;
                }
                
                // Check if entity is stuck
                if (entity.getLocation().distanceSquared(cache.lastPosition) < STUCK_DISTANCE_SQUARED) {
                    cache.stuckCounter++;
                    
                    // Handle being stuck
                    if (cache.stuckCounter >= STUCK_THRESHOLD) {
                        handleStuckEntity(entity, target);
                        cache.stuckCounter = 0;
                    }
                } else {
                    cache.stuckCounter = 0;
                }
                
                // Update last position
                cache.lastPosition = entity.getLocation();
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }
    
    /**
     * Handle entity that's stuck during pathfinding
     */
    private void handleStuckEntity(Mob entity, Location target) {
        // First try: Small jump
        entity.setVelocity(entity.getVelocity().setY(0.4));
        
        // Schedule a push in direction of target
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!entity.isValid() || entity.isDead()) return;
                
                Vector direction = target.clone().subtract(entity.getLocation()).toVector().normalize();
                entity.setVelocity(direction.multiply(0.5));
            }
        }.runTaskLater(plugin, 5L);
        
        // Check surrounding blocks for problematic blocks and break/modify if needed
        checkAndHandleObstacles(entity);
    }
    
    /**
     * Check and handle obstacles around entity
     */
    private void checkAndHandleObstacles(Mob entity) {
        Location loc = entity.getLocation();
        World world = loc.getWorld();
        
        // Check blocks around and below
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block block = world.getBlockAt(
                            loc.getBlockX() + x, 
                            loc.getBlockY() + y, 
                            loc.getBlockZ() + z);
                    
                    if (problematicBlocks.contains(block.getType())) {
                        // For now, just log if debug is enabled
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getLogger().info("Detected problematic block: " + 
                                block.getType() + " at " + block.getLocation());
                        }
                        
                        // Future: Add code to handle specific obstacles
                        // e.g., break cobwebs, create bridges over water, etc.
                    }
                }
            }
        }
    }
    
    /**
     * Find path to a location within a town using optimized search
     */
    public Location findPathableLocation(Location start, Town town, int maxRadius) {
        if (start == null || town == null) return null;
        
        // Starting from closest to farthest in a spiral pattern
        int steps = 1;
        int direction = 0; // 0=right, 1=down, 2=left, 3=up
        int x = 0;
        int z = 0;
        
        // Max number of blocks to check
        int maxBlocks = maxRadius * maxRadius * 4;
        int blocksChecked = 0;
        
        while (blocksChecked < maxBlocks) {
            for (int i = 0; i < steps && blocksChecked < maxBlocks; i++) {
                blocksChecked++;
                
                // Calculate position in spiral
                switch (direction) {
                    case 0: x++; break; // right
                    case 1: z++; break; // down
                    case 2: x--; break; // left
                    case 3: z--; break; // up
                }
                
                // Get world location
                Location checkLoc = new Location(
                    start.getWorld(),
                    start.getBlockX() + x,
                    0, // will find highest block
                    start.getBlockZ() + z
                );
                
                // Check if location is in town
                if (plugin.getTownyHandler().isLocationInTown(checkLoc, town)) {
                    // Find the highest block
                    int y = start.getWorld().getHighestBlockYAt(checkLoc);
                    checkLoc.setY(y + 1); // Set to top of highest block
                    
                    // Check if location is safe
                    if (isSafeLocation(checkLoc)) {
                        return checkLoc;
                    }
                }
            }
            
            // Change direction
            direction = (direction + 1) % 4;
            
            // Every second direction change, increase the steps
            if (direction % 2 == 0) {
                steps++;
            }
        }
        
        return null;
    }
    
    /**
     * Check if a location is safe for entity navigation
     */
    private boolean isSafeLocation(Location loc) {
        World world = loc.getWorld();
        
        // Check if there's enough space (2 blocks high)
        if (!world.getBlockAt(loc).isEmpty() || 
            !world.getBlockAt(loc.clone().add(0, 1, 0)).isEmpty()) {
            return false;
        }
        
        // Check if the ground is solid
        Block ground = world.getBlockAt(loc.clone().add(0, -1, 0));
        if (!ground.getType().isSolid() || 
            problematicBlocks.contains(ground.getType()) ||
            ground.isLiquid()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Clean up resources for an entity
     */
    public void removeEntity(Entity entity) {
        pathCache.remove(entity);
    }
    
    /**
     * Clean up all resources
     */
    public void cleanup() {
        pathCache.clear();
    }
    
    /**
     * Internal class to store path caching information
     */
    private static class PathCache {
        Location target;
        Location lastPosition;
        long lastCalculationTime;
        boolean isNavigating;
        int stuckCounter;
        
        PathCache() {
            this.isNavigating = false;
            this.stuckCounter = 0;
        }
    }
}