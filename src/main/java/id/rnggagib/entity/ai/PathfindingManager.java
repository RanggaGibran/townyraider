package id.rnggagib.entity.ai;

import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;
import id.rnggagib.entity.ai.pathfinding.AStarPathfinder;
import id.rnggagib.entity.ai.waypoint.Waypoint;
import id.rnggagib.entity.ai.waypoint.WaypointPath;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import com.palmergames.bukkit.towny.object.Town;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public class PathfindingManager {
    private final TownyRaider plugin;
    private final Map<Entity, PathCache> pathCache = new WeakHashMap<>();
    private final Set<Material> problematicBlocks;
    private final AStarPathfinder pathfinder;
    private final Map<UUID, WaypointPath> entityPaths = new HashMap<>();
    private final Map<UUID, MovementState> entityMovementStates = new HashMap<>();
    
    // Pathfinding constants
    private static final int PATH_RECALCULATION_TICKS = 40; // Recalculate path every 2 seconds
    private static final int STUCK_THRESHOLD = 3; // Number of checks before considered stuck
    private static final double STUCK_DISTANCE_SQUARED = 0.2 * 0.2; // Distance squared to consider mob stuck
    
    public PathfindingManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.problematicBlocks = initProblematicBlocks();
        this.pathfinder = new AStarPathfinder(plugin, problematicBlocks);
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
        UUID entityId = entity.getUniqueId();
        
        // Check if we need to recalculate the path
        if (shouldRecalculatePath(entity, target, cache)) {
            // Generate a path using A* algorithm
            WaypointPath path = pathfinder.findPath(entity.getLocation(), target);
            if (path != null) {
                // Store the path for this entity
                entityPaths.put(entityId, path);
                
                // Update cache info
                cache.target = target.clone();
                cache.lastCalculationTime = System.currentTimeMillis();
                cache.isNavigating = true;
                
                // Store previous location to detect if entity gets stuck
                cache.lastPosition = entity.getLocation();
                cache.stuckCounter = 0;
                
                // Start following the path
                followPath(entity, path, speed);
                return true;
            } else {
                // Fallback to basic movement if no path found
                return navigateToFallback(entity, target, speed);
            }
        } else if (entityPaths.containsKey(entityId)) {
            // Continue following existing path
            WaypointPath path = entityPaths.get(entityId);
            if (!path.isCompleted()) {
                // Update progress on current path
                path.updateProgress(entity.getLocation());
                
                // Move toward current waypoint
                Waypoint currentWaypoint = path.getCurrentWaypoint();
                if (currentWaypoint != null) {
                    moveTowardWaypoint(entity, currentWaypoint, speed);
                    return true;
                }
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
     * Check and handle obstacles around entity with advanced obstacle handling
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
                    
                    // Handle problematic blocks
                    if (problematicBlocks.contains(block.getType())) {
                        handleObstacleByType(entity, block);
                    }
                    
                    // Check for doors to interact with
                    if (block.getType().toString().contains("DOOR") && 
                        !block.getType().toString().contains("TRAP")) {
                        handleDoor(entity, block);
                    }
                }
            }
        }
        
        // Check for gaps in the path ahead
        Location ahead = entity.getLocation().add(
            entity.getLocation().getDirection().multiply(1.5));
        Block blockAhead = ahead.getBlock();
        Block blockBelow = ahead.clone().add(0, -1, 0).getBlock();
        
        if (!blockAhead.getType().isSolid() && !blockBelow.getType().isSolid()) {
            handleGap(entity);
        }
    }
    
    /**
     * Handle obstacles based on their type
     */
    private void handleObstacleByType(Mob entity, Block block) {
        Material type = block.getType();
        int intelligence = getEntityIntelligence(entity);
        
        switch (type) {
            case COBWEB:
                // Break cobwebs if intelligence is high enough
                if (intelligence >= 2 && Math.random() < 0.7) {
                    breakBlock(block);
                }
                break;
                
            case WATER:
            case LAVA:
                // Attempt to build a bridge over liquid
                if (intelligence >= 3) {
                    buildBridge(entity, block);
                } else {
                    // Less intelligent entities just try to jump over
                    entity.setVelocity(entity.getVelocity().setY(0.4));
                }
                break;
                
            case SWEET_BERRY_BUSH:
            case CACTUS:
                // Break harmful plants if intelligence is high enough
                if (intelligence >= 2) {
                    breakBlock(block);
                } else {
                    // Move away from harmful blocks
                    moveAwayFromBlock(entity, block);
                }
                break;
                
            case FIRE:
            case SOUL_FIRE:
            case CAMPFIRE:
            case SOUL_CAMPFIRE:
                // Extinguish fires if intelligence is high enough
                if (intelligence >= 3) {
                    extinguishFire(block);
                } else {
                    // Move away from fire
                    moveAwayFromBlock(entity, block);
                }
                break;
                
            case HONEY_BLOCK:
                // Jump to get unstuck from honey
                entity.setVelocity(entity.getVelocity().setY(0.5));
                break;
        }
    }
    
    /**
     * Get entity intelligence level (defaults to 1 if not set)
     */
    private int getEntityIntelligence(Mob entity) {
        NamespacedKey intelligenceKey = new NamespacedKey(plugin, "intelligence");
        if (entity.getPersistentDataContainer().has(intelligenceKey, PersistentDataType.INTEGER)) {
            return entity.getPersistentDataContainer().get(intelligenceKey, PersistentDataType.INTEGER);
        }
        return 1;
    }
    
    /**
     * Handle door interaction
     */
    private void handleDoor(Mob entity, Block doorBlock) {
        // Only some doors should be interacted with based on intelligence
        int intelligence = getEntityIntelligence(entity);
        
        if (intelligence < 2) {
            return; // Not smart enough to use doors
        }
        
        // Check if door is worth opening based on target direction
        if (entityPaths.containsKey(entity.getUniqueId())) {
            WaypointPath path = entityPaths.get(entity.getUniqueId());
            Waypoint current = path.getCurrentWaypoint();
            
            if (current != null) {
                Vector doorToTarget = current.getLocation().toVector()
                    .subtract(doorBlock.getLocation().toVector());
                Vector entityToTarget = current.getLocation().toVector()
                    .subtract(entity.getLocation().toVector());
                
                // If door is between entity and target, open it
                if (doorToTarget.dot(entityToTarget) > 0) {
                    toggleDoor(doorBlock);
                    
                    // Create a short delay to allow door to open
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (entity.isValid() && !entity.isDead()) {
                                // Give a small boost toward the door
                                Vector direction = doorBlock.getLocation().toVector()
                                    .subtract(entity.getLocation().toVector()).normalize();
                                entity.setVelocity(direction.multiply(0.3));
                            }
                        }
                    }.runTaskLater(plugin, 5L);
                }
            }
        }
    }
    
    /**
     * Toggle a door's open/closed state
     */
    private void toggleDoor(Block doorBlock) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (doorBlock.getBlockData() instanceof org.bukkit.block.data.Openable) {
                org.bukkit.block.data.Openable openable = 
                    (org.bukkit.block.data.Openable) doorBlock.getBlockData();
                openable.setOpen(!openable.isOpen());
                doorBlock.setBlockData(openable);
                
                // Play door sound
                doorBlock.getWorld().playSound(
                    doorBlock.getLocation(),
                    openable.isOpen() ? org.bukkit.Sound.BLOCK_WOODEN_DOOR_OPEN : org.bukkit.Sound.BLOCK_WOODEN_DOOR_CLOSE,
                    0.5f, 1.0f
                );
            }
        });
    }
    
    /**
     * Handle gap in the path
     */
    private void handleGap(Mob entity) {
        // Jump to cross small gaps
        entity.setVelocity(entity.getVelocity().multiply(1.2).setY(0.4));
    }
    
    /**
     * Break a block if block breaking is enabled
     */
    private void breakBlock(Block block) {
        if (!plugin.getConfigManager().isBlockBreakingEnabled()) {
            return;
        }
        
        if (!plugin.getConfigManager().isBreakableBlock(block.getType())) {
            return;
        }
        
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // Create breaking effect
            block.getWorld().playEffect(block.getLocation(), org.bukkit.Effect.STEP_SOUND, block.getType());
            
            // Break the block
            block.setType(Material.AIR);
        });
    }
    
    /**
     * Build a bridge over liquid
     */
    private void buildBridge(Mob entity, Block liquidBlock) {
        if (!plugin.getConfigManager().isBridgeBuildingEnabled()) {
            return;
        }
        
        // Check if intelligence is high enough (already checked in caller but double-check)
        int intelligence = getEntityIntelligence(entity);
        if (intelligence < 3) return;
        
        // High intelligence mobs can build bridges over liquid
        Material bridgeMaterial = getBridgeMaterial(entity);
        
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (liquidBlock.getType() == Material.WATER || liquidBlock.getType() == Material.LAVA) {
                // Place a block on top of the liquid
                Block blockAbove = liquidBlock.getWorld().getBlockAt(
                    liquidBlock.getX(), 
                    liquidBlock.getY() + 1, 
                    liquidBlock.getZ()
                );
                
                if (blockAbove.isEmpty()) {
                    // Check if this is allowed by protection plugins
                    if (plugin.getProtectionManager().canEntityPlaceBlock(entity, blockAbove)) {
                        blockAbove.setType(bridgeMaterial);
                        
                        // Play sound and effect
                        liquidBlock.getWorld().playSound(
                            liquidBlock.getLocation(),
                            org.bukkit.Sound.BLOCK_STONE_PLACE,
                            0.5f, 1.0f
                        );
                    }
                }
            }
        });
    }
    
    /**
     * Get an appropriate block for bridge building based on entity type
     */
    private Material getBridgeMaterial(Entity entity) {
        // Use different materials based on entity type
        if (entity instanceof org.bukkit.entity.Zombie) {
            return Material.DIRT;
        } else if (entity instanceof org.bukkit.entity.Skeleton) {
            return Material.COBBLESTONE;
        } else {
            return Material.DIRT;
        }
    }
    
    /**
     * Move entity away from a dangerous block
     */
    private void moveAwayFromBlock(Mob entity, Block block) {
        // Calculate direction away from block
        Vector awayDirection = entity.getLocation().toVector()
            .subtract(block.getLocation().toVector())
            .normalize();
        
        // Apply velocity to move away
        entity.setVelocity(awayDirection.multiply(0.5).setY(0.2));
    }
    
    /**
     * Extinguish a fire block
     */
    private void extinguishFire(Block fireBlock) {
        Material type = fireBlock.getType();
        
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (type == Material.FIRE || type == Material.SOUL_FIRE) {
                fireBlock.setType(Material.AIR);
                
                // Play extinguish sound
                fireBlock.getWorld().playSound(
                    fireBlock.getLocation(),
                    org.bukkit.Sound.BLOCK_FIRE_EXTINGUISH,
                    0.5f, 1.0f
                );
            } else if (type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE) {
                if (fireBlock.getBlockData() instanceof org.bukkit.block.data.Lightable) {
                    org.bukkit.block.data.Lightable campfire = 
                        (org.bukkit.block.data.Lightable) fireBlock.getBlockData();
                    campfire.setLit(false);
                    fireBlock.setBlockData(campfire);
                    
                    // Play extinguish sound
                    fireBlock.getWorld().playSound(
                        fireBlock.getLocation(),
                        org.bukkit.Sound.BLOCK_FIRE_EXTINGUISH,
                        0.5f, 1.0f
                    );
                }
            }
        });
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
        entityPaths.remove(entity.getUniqueId());
        entityMovementStates.remove(entity.getUniqueId());
    }
    
    /**
     * Clean up all resources
     */
    public void cleanup() {
        pathCache.clear();
        entityPaths.clear();
        entityMovementStates.clear();
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
    
    /**
     * Internal class to track entity movement state for smooth transitions
     */
    private static class MovementState {
        Vector currentVelocity = null;
        Location targetLocation = null;
        double targetSpeed = 0.0;
        long lastUpdateTime = 0;
        boolean jumping = false;
        int smoothTurningTicks = 0;
        
        // Cache for performance optimization
        double cachedDistance = -1;
        double cachedVelocityMag = 0;
    }
    
    /**
     * Add new method to follow a path with improved performance
     */
    private void followPath(Mob entity, WaypointPath path, double speed) {
        UUID entityId = entity.getUniqueId();
        
        // Use longer timer intervals for distant entities
        long updateInterval = entity.getWorld().getPlayers().stream()
            .anyMatch(p -> p.getLocation().distance(entity.getLocation()) < 32) ? 2L : 10L;
        
        new BukkitRunnable() {
            private int consecutiveSkippedUpdates = 0;
            
            @Override
            public void run() {
                // Cancel if entity is invalid
                if (!entity.isValid() || entity.isDead() || path.isCompleted()) {
                    this.cancel();
                    entityPaths.remove(entityId);
                    entityMovementStates.remove(entityId); // Clean up movement state
                    return;
                }
                
                // Performance - Skip update if no players nearby and not primary entity
                if (!isEntityImportant(entity)) {
                    Player nearestPlayer = getNearestPlayer(entity);
                    if (nearestPlayer == null || nearestPlayer.getLocation().distanceSquared(entity.getLocation()) > 1024) { // 32 blocks squared
                        consecutiveSkippedUpdates++;
                        
                        // If we've skipped too many updates, slow down even more
                        if (consecutiveSkippedUpdates > 5) {
                            return; // Skip this update
                        }
                    } else {
                        consecutiveSkippedUpdates = 0;
                    }
                }
                
                // Update path progress based on current location
                boolean waypointReached = path.updateProgress(entity.getLocation());
                
                // Get current waypoint
                Waypoint currentWaypoint = path.getCurrentWaypoint();
                if (currentWaypoint == null) {
                    // End of path reached
                    this.cancel();
                    entityPaths.remove(entityId);
                    entityMovementStates.remove(entityId);
                    return;
                }
                
                // If we just reached a waypoint, perform special handling based on waypoint type
                if (waypointReached) {
                    handleWaypointType(entity, currentWaypoint);
                }
                
                // Move toward the current waypoint
                moveTowardWaypoint(entity, currentWaypoint, speed);
            }
        }.runTaskTimer(plugin, 1L, updateInterval);
    }
    
    /**
     * Check if an entity is important enough for frequent updates
     */
    private boolean isEntityImportant(Mob entity) {
        // Leaders and special entities should update more frequently
        return entity.getPersistentDataContainer().has(
            new NamespacedKey(plugin, "leader"), 
            PersistentDataType.BYTE
        ) || entity.getPersistentDataContainer().has(
            new NamespacedKey(plugin, "important"),
            PersistentDataType.BYTE
        );
    }
    
    /**
     * Find the nearest player to an entity
     */
    private Player getNearestPlayer(Entity entity) {
        Player nearest = null;
        double nearestDistSquared = Double.MAX_VALUE;
        
        for (Player player : entity.getWorld().getPlayers()) {
            double distSquared = player.getLocation().distanceSquared(entity.getLocation());
            if (distSquared < nearestDistSquared) {
                nearest = player;
                nearestDistSquared = distSquared;
            }
        }
        
        return nearest;
    }
    
    /**
     * Optimized moveTowardWaypoint method with performance improvements
     */
    private void moveTowardWaypoint(Mob entity, Waypoint waypoint, double speed) {
        UUID entityId = entity.getUniqueId();
        Location waypointLoc = waypoint.getLocation();
        
        // Get movement state with local variable to avoid multiple map lookups
        MovementState state = entityMovementStates.get(entityId);
        if (state == null) {
            state = new MovementState();
            entityMovementStates.put(entityId, state);
        }
        
        // Update target location - only if changed significantly to reduce object creation
        if (state.targetLocation == null || state.targetLocation.distanceSquared(waypointLoc) > 0.25) {
            state.targetLocation = waypointLoc.clone();
        }
        state.targetSpeed = speed;
        
        // Calculate direction and distance
        // Reuse vectors when possible to reduce object creation
        Vector entityPos = entity.getLocation().toVector();
        Vector targetPos = waypointLoc.toVector();
        Vector direction = targetPos.clone().subtract(entityPos);
        double distanceToWaypoint = direction.length();
        
        // Skip processing if too close to reduce CPU usage
        if (distanceToWaypoint < 0.1) {
            entity.setVelocity(new Vector(0, entity.getVelocity().getY(), 0));
            return;
        }
        
        // Normalize direction
        direction.normalize();
        
        // Performance: Only update yaw when necessary
        if (entity.getTicksLived() % 3 == 0) {
            // Calculate yaw based on direction for smooth turning
            float targetYaw = calculateYawFromVector(direction);
            float currentYaw = entity.getLocation().getYaw();
            
            // Smooth turning
            float yawDifference = normalizeAngle(targetYaw - currentYaw);
            float maxTurnPerTick = 15.0f; // Slightly faster turning
            
            if (Math.abs(yawDifference) > maxTurnPerTick) {
                // Gradually turn toward the target
                float newYaw = currentYaw + Math.signum(yawDifference) * maxTurnPerTick;
                Location turnLocation = entity.getLocation().clone();
                turnLocation.setYaw(newYaw);
                entity.teleport(turnLocation);
                
                // Reduce speed during sharp turns
                if (Math.abs(yawDifference) > 60) {
                    speed *= 0.6;
                    state.smoothTurningTicks = 3; // Reduced tick count for performance
                }
            }
        }
        
        // Calculate velocity with cached computation where possible
        double velocityMagnitude;
        if (state.cachedDistance > 0 && Math.abs(state.cachedDistance - distanceToWaypoint) < 0.5) {
            // Reuse cached value if distance hasn't changed much
            velocityMagnitude = state.cachedVelocityMag;
        } else {
            // Recalculate velocity magnitude
            if (distanceToWaypoint < 2.0) {
                velocityMagnitude = 0.1 * (distanceToWaypoint / 2.0); // Simpler calculation
            } else if (distanceToWaypoint < 5.0) {
                velocityMagnitude = 0.15;
            } else {
                velocityMagnitude = Math.min(0.2, speed * 0.25);
            }
            
            // Cache the calculated values
            state.cachedDistance = distanceToWaypoint;
            state.cachedVelocityMag = velocityMagnitude;
        }
        
        // Check if entity is turning sharply
        if (state.smoothTurningTicks > 0) {
            velocityMagnitude *= 0.7;
            state.smoothTurningTicks--;
        }
        
        // Handle Y movement with simplified logic
        double yVelocity = entity.getVelocity().getY();
        double yDiff = waypointLoc.getY() - entity.getLocation().getY();
        
        // Jumping logic
        if (yDiff > 0.5 && entity.isOnGround() && !state.jumping) {
            yVelocity = 0.25;
            state.jumping = true;
        } else if (entity.isOnGround()) {
            state.jumping = false;
        }
        
        // Apply velocity (more efficient application)
        Vector velocity = direction.multiply(velocityMagnitude).setY(yVelocity);
        entity.setVelocity(velocity);
        
        // Store current velocity with minimal object creation
        if (state.currentVelocity == null) {
            state.currentVelocity = velocity.clone();
        } else {
            state.currentVelocity.copy(velocity);
        }
        
        // Special handling for jump waypoints with reduced frequency
        if (waypoint.getType() == Waypoint.WaypointType.JUMP && 
            entity.getLocation().distance(waypointLoc) < 2.0 && 
            entity.isOnGround() && entity.getTicksLived() % 5 == 0) {
            entity.setVelocity(entity.getVelocity().clone().setY(0.25));
        }
    }
    
    /**
     * Calculate yaw angle from a direction vector
     */
    private float calculateYawFromVector(Vector vector) {
        // Convert vector to yaw (rotation around Y axis)
        double dx = vector.getX();
        double dz = vector.getZ();
        double yaw = Math.atan2(-dx, dz) * (180 / Math.PI);
        return (float) yaw;
    }
    
    /**
     * Normalize angle to be between -180 and 180
     */
    private float normalizeAngle(float angle) {
        angle %= 360.0f;
        if (angle > 180.0f) {
            angle -= 360.0f;
        } else if (angle < -180.0f) {
            angle += 360.0f;
        }
        return angle;
    }
    
    /**
     * Add method to handle special waypoint types
     */
    private void handleWaypointType(Mob entity, Waypoint waypoint) {
        switch (waypoint.getType()) {
            case DOOR:
                // Handle door interaction
                Block block = waypoint.getLocation().getBlock();
                if (block.getType().toString().contains("DOOR")) {
                    // Toggle door state - in a real implementation you would check if the door is open/closed
                    // Bukkit API for doors can be complex, so I'm simplifying here
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        // Would use block.getState() and change door state here
                    });
                }
                break;
                
            case BREAK_BLOCK:
                // Handle block breaking (for advanced raiders)
                if (plugin.getConfigManager().isBlockBreakingEnabled()) {
                    Block block2 = waypoint.getLocation().getBlock();
                    if (plugin.getConfigManager().isBreakableBlock(block2.getType())) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            // Would trigger block breaking here
                        });
                    }
                }
                break;
                
            default:
                break;
        }
    }
    
    /**
     * Enhance chunk loading for raids with performance improvements
     */
    public void keepRaidChunksLoaded(ActiveRaid raid) {
        Location raidLocation = raid.getLocation();
        if (raidLocation == null) return;
        
        World world = raidLocation.getWorld();
        int centerX = raidLocation.getBlockX() >> 4;
        int centerZ = raidLocation.getBlockZ() >> 4;
        
        // Use a smaller chunk area (3x3 instead of 5x5)
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    world.loadChunk(x, z, true);
                }
                world.setChunkForceLoaded(x, z, true);
            }
        }
        
        // Use a more efficient timer for chunk verification
        new BukkitRunnable() {
            private int counter = 0;
            
            @Override
            public void run() {
                if (!plugin.getRaidManager().getActiveRaids().containsKey(raid.getId())) {
                    this.cancel();
                    PathfindingManager.this.unloadRaidChunks(raid); // Fixed reference to outer class method
                    return;
                }
                
                // Only verify chunks every 3rd run (15 seconds instead of 5)
                counter++;
                if (counter % 3 == 0) {
                    // Only check the most critical chunks
                    if (!world.isChunkLoaded(centerX, centerZ)) {
                        world.loadChunk(centerX, centerZ, true);
                        world.setChunkForceLoaded(centerX, centerZ, true);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 300L); // Check every 15 seconds instead of 5
    }
    
    /**
     * Unload raid chunks when a raid ends
     */
    public void unloadRaidChunks(ActiveRaid raid) {
        Location raidLocation = raid.getLocation();
        if (raidLocation == null) return;
        
        World world = raidLocation.getWorld();
        int centerX = raidLocation.getBlockX() >> 4;
        int centerZ = raidLocation.getBlockZ() >> 4;
        
        // Use the same chunk area as in keepRaidChunksLoaded (3x3)
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                world.setChunkForceLoaded(x, z, false);
            }
        }
        
        // Log that chunks were unloaded
        plugin.getLogger().info("Unloaded chunks for ended raid " + raid.getId());
    }
}