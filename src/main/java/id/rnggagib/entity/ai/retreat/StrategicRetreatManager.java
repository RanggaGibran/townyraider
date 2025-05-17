package id.rnggagib.entity.ai.retreat;

import id.rnggagib.TownyRaider;
import id.rnggagib.entity.ai.PathfindingManager;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Skeleton;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import com.palmergames.bukkit.towny.object.Town;

import java.util.*;

/**
 * Manages strategic retreat behavior for raid entities
 */
public class StrategicRetreatManager {
    private final TownyRaider plugin;
    private final PathfindingManager pathfindingManager;
    private final NamespacedKey retreatingKey;
    private final NamespacedKey isCarryingLootKey;
    private final NamespacedKey retreatTypeKey;
    private final Map<UUID, Long> lastRetreatTime = new HashMap<>();
    private final Map<UUID, RetreatInfo> activeRetreats = new HashMap<>();
    
    // Constants for retreat behavior
    private static final int RETREAT_COOLDOWN = 30000; // 30 seconds
    private static final double RETREAT_HEALTH_THRESHOLD = 0.3; // 30% health
    private static final int RETREAT_DURATION = 200; // 10 seconds (in ticks)
    private static final double RETREAT_SPEED_MULTIPLIER = 1.5;
    private static final int MAX_RETREAT_DISTANCE = 50; // blocks
    
    public StrategicRetreatManager(TownyRaider plugin, PathfindingManager pathfindingManager) {
        this.plugin = plugin;
        this.pathfindingManager = pathfindingManager;
        this.retreatingKey = new NamespacedKey(plugin, "retreating");
        this.isCarryingLootKey = new NamespacedKey(plugin, "carrying_loot");
        this.retreatTypeKey = new NamespacedKey(plugin, "retreat_type");
    }
    
    /**
     * Check if an entity should retreat based on criteria
     */
    public boolean shouldRetreat(LivingEntity entity, ActiveRaid raid) {
        // Don't retreat if already retreating
        if (isRetreating(entity)) {
            return false;
        }
        
        UUID entityId = entity.getUniqueId();
        
        // Check cooldown
        Long lastRetreat = lastRetreatTime.get(entityId);
        boolean cooldownOver = lastRetreat == null || 
                             (System.currentTimeMillis() - lastRetreat) > RETREAT_COOLDOWN;
        if (!cooldownOver) {
            return false;
        }
        
        // Check if entity is carrying loot that should be preserved
        boolean hasLoot = entity.getPersistentDataContainer().has(isCarryingLootKey, PersistentDataType.BYTE);
        
        // Check health condition
        double healthPercent = entity.getHealth() / entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
        boolean healthLow = healthPercent <= RETREAT_HEALTH_THRESHOLD;
        
        // Prioritize retreat with loot
        if (hasLoot) {
            // More likely to retreat if carrying loot, even at higher health
            return healthPercent <= 0.6 || (healthLow && cooldownOver);
        }
        
        // Check raid phase - more likely to retreat in later phases
        boolean isLatePhase = raid != null && 
            raid.getStolenItems() > plugin.getConfigManager().getConfig().getInt("raids.retreat-threshold", 5);
        
        // Zombies carrying loot are more likely to retreat when raid goal is reached
        if (entity instanceof Zombie && isLatePhase) {
            return Math.random() < 0.3; // 30% chance to retreat in late phase
        }
        
        // Standard health-based retreat
        return healthLow && cooldownOver;
    }
    
    /**
     * Check if entity is in retreat mode
     */
    public boolean isRetreating(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(retreatingKey, PersistentDataType.BYTE);
    }
    
    /**
     * Set an entity to retreat state
     */
    public RetreatType initiateRetreat(LivingEntity entity, ActiveRaid raid) {
        UUID entityId = entity.getUniqueId();
        
        // Record retreat time
        lastRetreatTime.put(entityId, System.currentTimeMillis());
        
        // Mark entity as retreating
        entity.getPersistentDataContainer().set(retreatingKey, PersistentDataType.BYTE, (byte)1);
        
        // Determine retreat type based on entity and situation
        RetreatType retreatType = determineRetreatType(entity, raid);
        
        // Store retreat type
        entity.getPersistentDataContainer().set(
            retreatTypeKey, 
            PersistentDataType.STRING, 
            retreatType.toString()
        );
        
        // Find retreat location based on type
        Location retreatLocation = findRetreatLocation(entity, raid, retreatType);
        
        if (retreatLocation == null) {
            // Fallback to simple retreat if no strategic location found
            retreatLocation = findSimpleRetreatLocation(entity, raid);
        }
        
        // Calculate retreat path
        if (retreatLocation != null && entity instanceof Mob) {
            Mob mob = (Mob) entity;
            
            // Clear any target
            mob.setTarget(null);
            
            // Apply retreat effects based on type
            applyRetreatEffects(mob, retreatType);
            
            // Store retreat info
            RetreatInfo info = new RetreatInfo(entityId, retreatLocation, retreatType);
            activeRetreats.put(entityId, info);
            
            // Start retreat movement
            manageRetreatMovement(mob, retreatLocation, retreatType);
            
            // Notify nearby raid entities about the retreat
            notifyNearbyRaiders(entity, retreatType, raid);
            
            // Schedule end of retreat state
            scheduleRetreatEnd(mob, retreatType);
            
            return retreatType;
        }
        
        return RetreatType.SIMPLE;
    }
    
    /**
     * Apply visual and movement effects based on retreat type
     */
    private void applyRetreatEffects(Mob entity, RetreatType retreatType) {
        switch (retreatType) {
            case TACTICAL:
                // Tactical retreat is slower but more careful
                // No particles to stay hidden
                break;
                
            case EMERGENCY:
                // Emergency retreat is fast but visually obvious
                entity.getWorld().spawnParticle(
                    org.bukkit.Particle.SMOKE_NORMAL,
                    entity.getLocation().add(0, 1, 0),
                    10, 0.2, 0.2, 0.2, 0.02
                );
                
                // Add speed effect
                entity.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.SPEED,
                    RETREAT_DURATION,
                    1,
                    false, true
                ));
                break;
                
            case LOOT_CARRIER:
                // Loot carriers move with glowing effect to signal value
                entity.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.GLOWING,
                    RETREAT_DURATION,
                    0,
                    false, true
                ));
                
                // Slower but more determined movement
                entity.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.SLOW,
                    RETREAT_DURATION,
                    0,
                    false, true
                ));
                break;
                
            case DEFENSIVE:
                // Defensive retreat involves projectiles for skeletons
                if (entity instanceof Skeleton) {
                    // Fire arrows in the direction of pursuers
                    fireCoveringArrows((Skeleton) entity);
                }
                break;
                
            case SIMPLE:
            default:
                // Simple retreat just moves away
                entity.getWorld().spawnParticle(
                    org.bukkit.Particle.CLOUD,
                    entity.getLocation().add(0, 0.5, 0),
                    5, 0.1, 0.1, 0.1, 0.01
                );
                break;
        }
    }
    
    /**
     * Fire covering arrows for defensive retreat
     */
    private void fireCoveringArrows(Skeleton skeleton) {
        new BukkitRunnable() {
            int count = 0;
            
            @Override
            public void run() {
                if (!skeleton.isValid() || skeleton.isDead() || count >= 3) {
                    this.cancel();
                    return;
                }
                
                // Find nearby players
                List<Player> nearbyPlayers = getNearbyPlayers(skeleton, 15);
                if (!nearbyPlayers.isEmpty()) {
                    // Face the closest player
                    Player target = nearbyPlayers.get(0);
                    Vector direction = target.getLocation().toVector().subtract(skeleton.getLocation().toVector());
                    Location lookLocation = skeleton.getLocation().setDirection(direction);
                    skeleton.teleport(lookLocation);
                    
                    // Shoot an arrow
                    skeleton.launchProjectile(org.bukkit.entity.Arrow.class);
                }
                
                count++;
            }
        }.runTaskTimer(plugin, 5L, 20L);
    }
    
    /**
     * Determine best retreat type based on entity and situation
     */
    private RetreatType determineRetreatType(LivingEntity entity, ActiveRaid raid) {
        // Check if entity is carrying loot
        boolean hasLoot = entity.getPersistentDataContainer().has(isCarryingLootKey, PersistentDataType.BYTE);
        if (hasLoot) {
            return RetreatType.LOOT_CARRIER;
        }
        
        // Skeletons prefer defensive retreat
        if (entity instanceof Skeleton) {
            return RetreatType.DEFENSIVE;
        }
        
        // Based on health - very low health means emergency
        double healthPercent = entity.getHealth() / entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
        if (healthPercent < 0.15) { // 15% health
            return RetreatType.EMERGENCY;
        }
        
        // Check if players are nearby
        List<Player> nearbyPlayers = getNearbyPlayers(entity, 10);
        if (nearbyPlayers.isEmpty()) {
            // No players nearby, can do tactical retreat
            return RetreatType.TACTICAL;
        }
        
        // Default to simple retreat
        return RetreatType.SIMPLE;
    }
    
    /**
     * Find a strategic retreat location based on retreat type
     */
    private Location findRetreatLocation(LivingEntity entity, ActiveRaid raid, RetreatType retreatType) {
        Location entityLoc = entity.getLocation();
        
        switch (retreatType) {
            case TACTICAL:
                // Find cover position behind obstacles
                return findCoverPosition(entity);
                
            case EMERGENCY:
                // Find fastest way outside town
                return findExitLocation(entity, raid);
                
            case LOOT_CARRIER:
                // Find safe path to raid edge
                return findLootExtractionPoint(entity, raid);
                
            case DEFENSIVE:
                // Find high ground or defensible position
                return findDefensivePosition(entity);
                
            case SIMPLE:
            default:
                // Find any direction away from players
                return findSimpleRetreatLocation(entity, raid);
        }
    }
    
    /**
     * Find a position behind cover for tactical retreat
     */
    private Location findCoverPosition(LivingEntity entity) {
        Location entityLoc = entity.getLocation();
        List<Player> nearbyPlayers = getNearbyPlayers(entity, 20);
        if (nearbyPlayers.isEmpty()) {
            return null;
        }
        
        // Calculate average player position
        Location avgPlayerLoc = new Location(entityLoc.getWorld(), 0, 0, 0);
        for (Player player : nearbyPlayers) {
            avgPlayerLoc.add(player.getLocation());
        }
        avgPlayerLoc.setX(avgPlayerLoc.getX() / nearbyPlayers.size());
        avgPlayerLoc.setY(avgPlayerLoc.getY() / nearbyPlayers.size());
        avgPlayerLoc.setZ(avgPlayerLoc.getZ() / nearbyPlayers.size());
        
        // Direction from players to entity
        Vector retreatDir = entityLoc.toVector().subtract(avgPlayerLoc.toVector()).normalize();
        
        // Search for blocks that can provide cover
        for (int distance = 3; distance <= 15; distance++) {
            Vector searchVec = retreatDir.clone().multiply(distance);
            Location searchLoc = entityLoc.clone().add(searchVec);
            
            // Check if this block provides cover
            if (providesCover(searchLoc)) {
                // Find a position behind this cover
                Location behindCover = searchLoc.clone().add(retreatDir);
                behindCover.setY(getFinalYPosition(behindCover));
                return behindCover;
            }
        }
        
        return null;
    }
    
    /**
     * Check if a location provides tactical cover
     */
    private boolean providesCover(Location location) {
        Block block = location.getBlock();
        // Solid blocks provide cover
        return block.getType().isSolid() && 
               !block.getType().toString().contains("LEAVES") &&
               !block.getType().toString().contains("GLASS");
    }
    
    /**
     * Find fastest route to exit the town
     */
    private Location findExitLocation(LivingEntity entity, ActiveRaid raid) {
        if (raid == null) return null;
        
        Town town = plugin.getTownyHandler().getTownByName(raid.getTownName());
        if (town == null) return null;
        
        // Get town bounds
        int[] bounds = plugin.getTownyHandler().getTownBounds(town);
        if (bounds == null) return null;
        
        Location entityLoc = entity.getLocation();
        
        // Find the closest edge of town
        double distToMinX = Math.abs(entityLoc.getX() - bounds[0]);
        double distToMaxX = Math.abs(entityLoc.getX() - bounds[2]);
        double distToMinZ = Math.abs(entityLoc.getZ() - bounds[1]);
        double distToMaxZ = Math.abs(entityLoc.getZ() - bounds[3]);
        
        // Determine which edge is closest
        double minDist = Math.min(Math.min(distToMinX, distToMaxX), Math.min(distToMinZ, distToMaxZ));
        
        Location exitPoint = entityLoc.clone();
        
        if (minDist == distToMinX) {
            exitPoint.setX(bounds[0] - 5); // 5 blocks outside town
        } else if (minDist == distToMaxX) {
            exitPoint.setX(bounds[2] + 5);
        } else if (minDist == distToMinZ) {
            exitPoint.setZ(bounds[1] - 5);
        } else {
            exitPoint.setZ(bounds[3] + 5);
        }
        
        // Adjust Y to be on ground
        exitPoint.setY(getFinalYPosition(exitPoint));
        
        return exitPoint;
    }
    
    /**
     * Find a good extraction point for entities carrying loot
     */
    private Location findLootExtractionPoint(LivingEntity entity, ActiveRaid raid) {
        if (raid == null) return null;
        
        // Check if we can use a pre-defined extraction point
        if (raid.getMetadata("extraction_point") instanceof Location) {
            Location extractionPoint = (Location) raid.getMetadata("extraction_point");
            return extractionPoint;
        }
        
        // Otherwise find a good edge point that's far from players
        Location exitPoint = findExitLocation(entity, raid);
        
        // Store this exit point for other raiders to use
        raid.setMetadata("extraction_point", exitPoint);
        
        return exitPoint;
    }
    
    /**
     * Find high ground or defensible position
     */
    private Location findDefensivePosition(LivingEntity entity) {
        Location entityLoc = entity.getLocation();
        World world = entityLoc.getWorld();
        
        // Look for higher position within range
        Location bestSpot = null;
        double bestScore = -1;
        
        // Search in a spiral pattern
        for (int radius = 3; radius <= 15; radius += 3) {
            for (int angle = 0; angle < 360; angle += 45) {
                double rad = Math.toRadians(angle);
                int x = (int) (entityLoc.getX() + radius * Math.cos(rad));
                int z = (int) (entityLoc.getZ() + radius * Math.sin(rad));
                
                // Find the highest block at this position
                int y = world.getHighestBlockYAt(x, z);
                Location spot = new Location(world, x, y + 1, z);
                
                // Score this position
                double heightAdvantage = spot.getY() - entityLoc.getY();
                double coverScore = hasCoverNearby(spot) ? 5 : 0;
                double score = heightAdvantage + coverScore;
                
                if (score > bestScore && isValidLocation(spot)) {
                    bestScore = score;
                    bestSpot = spot;
                }
            }
        }
        
        return bestSpot;
    }
    
    /**
     * Check if a location has cover nearby (blocks that can be used as shields)
     */
    private boolean hasCoverNearby(Location location) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                
                Block block = location.getWorld().getBlockAt(
                    location.getBlockX() + x,
                    location.getBlockY(),
                    location.getBlockZ() + z
                );
                
                if (block.getType().isSolid() && !block.getType().toString().contains("LEAVES")) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Simple retreat direction away from players
     */
    private Location findSimpleRetreatLocation(LivingEntity entity, ActiveRaid raid) {
        Location entityLoc = entity.getLocation();
        List<Player> nearbyPlayers = getNearbyPlayers(entity, 20);
        
        if (nearbyPlayers.isEmpty()) {
            // No players, pick random direction
            double angle = Math.random() * 2 * Math.PI;
            double distance = 10 + Math.random() * 10;  // 10-20 blocks
            int x = (int) (entityLoc.getX() + distance * Math.cos(angle));
            int z = (int) (entityLoc.getZ() + distance * Math.sin(angle));
            
            Location retreatLoc = new Location(entityLoc.getWorld(), x, 0, z);
            retreatLoc.setY(getFinalYPosition(retreatLoc));
            
            return retreatLoc;
        }
        
        // Calculate average player position
        Location avgPlayerLoc = new Location(entityLoc.getWorld(), 0, 0, 0);
        for (Player player : nearbyPlayers) {
            avgPlayerLoc.add(player.getLocation());
        }
        avgPlayerLoc.setX(avgPlayerLoc.getX() / nearbyPlayers.size());
        avgPlayerLoc.setY(avgPlayerLoc.getY() / nearbyPlayers.size());
        avgPlayerLoc.setZ(avgPlayerLoc.getZ() / nearbyPlayers.size());
        
        // Move away from average player position
        Vector awayDir = entityLoc.toVector().subtract(avgPlayerLoc.toVector()).normalize();
        Location retreatLoc = entityLoc.clone().add(awayDir.multiply(15)); // 15 blocks away
        
        retreatLoc.setY(getFinalYPosition(retreatLoc));
        
        return retreatLoc;
    }
    
    /**
     * Get appropriate Y position at a location (for safe movement)
     */
    private int getFinalYPosition(Location location) {
        World world = location.getWorld();
        int x = location.getBlockX();
        int z = location.getBlockZ();
        
        // Find the highest block
        int y = world.getHighestBlockYAt(x, z);
        
        // Make sure it's not a tree or other tall structure
        Block highestBlock = world.getBlockAt(x, y, z);
        if (highestBlock.getType().toString().contains("LEAVES")) {
            // Find ground level
            while (y > 0) {
                Block block = world.getBlockAt(x, y, z);
                if (!block.getType().toString().contains("LEAVES") && 
                    !block.getType().toString().contains("LOG") &&
                    block.getType().isSolid()) {
                    break;
                }
                y--;
            }
        }
        
        return y + 1;
    }
    
    /**
     * Manage entity movement during retreat
     */
    private void manageRetreatMovement(Mob entity, Location retreatLocation, RetreatType retreatType) {
        // Declare speed as final
        final double speed;
        
        // Adjust speed based on retreat type
        switch (retreatType) {
            case EMERGENCY:
                speed = 1.4;
                break;
            case LOOT_CARRIER:
                speed = 0.8;
                break;
            case TACTICAL:
                speed = 0.7;
                break;
            default:
                speed = 1.0;
                break;
        }
        
        // Use pathfinder for movement
        pathfindingManager.navigateTo(entity, retreatLocation, speed);
        
        // Setup backup movement system
        new BukkitRunnable() {
            int ticks = 0;
            
            @Override
            public void run() {
                ticks++;
                
                // Stop if entity is no longer valid
                if (!entity.isValid() || entity.isDead() || ticks > RETREAT_DURATION) {
                    this.cancel();
                    return;
                }
                
                // Check if we've reached the destination
                if (entity.getLocation().distance(retreatLocation) < 3.0) {
                    // Retreat completed successfully
                    handleRetreatSuccess(entity, retreatType);
                    this.cancel();
                    return;
                }
                
                // Every 20 ticks, ensure the entity is still moving toward target
                if (ticks % 20 == 0) {
                    // Get direction to retreat location
                    Vector direction = retreatLocation.clone().subtract(entity.getLocation()).toVector();
                    
                    // If too far, normalize and scale
                    if (direction.lengthSquared() > 1) {
                        direction.normalize().multiply(speed * 0.3);
                    }
                    
                    // Apply velocity
                    entity.setVelocity(direction);
                    
                    // Add retreat effects
                    if (retreatType == RetreatType.LOOT_CARRIER || retreatType == RetreatType.EMERGENCY) {
                        entity.getWorld().spawnParticle(
                            org.bukkit.Particle.CLOUD,
                            entity.getLocation().add(0, 0.5, 0),
                            3, 0.1, 0.1, 0.1, 0.01
                        );
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 1L);
    }
    
    /**
     * Check if a location is valid for movement
     */
    private boolean isValidLocation(Location location) {
        Block block = location.getBlock();
        Block above = location.clone().add(0, 1, 0).getBlock();
        Block below = location.clone().add(0, -1, 0).getBlock();
        
        // Needs empty space and solid ground
        return block.isEmpty() &&
               above.isEmpty() &&
               below.getType().isSolid();
    }
    
    /**
     * Schedule the end of retreat state
     */
    private void scheduleRetreatEnd(Mob entity, RetreatType retreatType) {
        int duration = RETREAT_DURATION;
        
        // Adjust duration based on retreat type
        switch (retreatType) {
            case EMERGENCY:
                duration = 100; // Shorter
                break;
            case TACTICAL:
                duration = 300; // Longer
                break;
            default:
                duration = RETREAT_DURATION;
                break;
        }
        
        new BukkitRunnable() {
            @Override
            public void run() {
                if (entity.isValid() && !entity.isDead()) {
                    // Remove retreat flag
                    entity.getPersistentDataContainer().remove(retreatingKey);
                    activeRetreats.remove(entity.getUniqueId());
                }
            }
        }.runTaskLater(plugin, duration);
    }
    
    /**
     * Get nearby players to an entity
     */
    private List<Player> getNearbyPlayers(Entity entity, double range) {
        List<Player> result = new ArrayList<>();
        
        for (Entity nearby : entity.getNearbyEntities(range, range, range)) {
            if (nearby instanceof Player) {
                Player player = (Player) nearby;
                result.add(player);
            }
        }
        
        return result;
    }
    
    /**
     * Handle successful retreat
     */
    private void handleRetreatSuccess(Mob entity, RetreatType retreatType) {
        // Special handling for loot carriers - they should disappear with loot
        if (retreatType == RetreatType.LOOT_CARRIER) {
            // Add teleport effect
            entity.getWorld().spawnParticle(
                org.bukkit.Particle.PORTAL,
                entity.getLocation(),
                50, 0.5, 1, 0.5, 0.1
            );
            
            // Remove entity
            entity.remove();
            return;
        }
        
        // Regular retreat entities just stop retreating
        entity.getPersistentDataContainer().remove(retreatingKey);
        activeRetreats.remove(entity.getUniqueId());
    }
    
    /**
     * Notify nearby raid entities about the retreat
     */
    private void notifyNearbyRaiders(Entity entity, RetreatType retreatType, ActiveRaid raid) {
        // Only coordinate for certain types
        if (retreatType != RetreatType.LOOT_CARRIER && retreatType != RetreatType.TACTICAL) {
            return;
        }
        
        List<Entity> nearbyEntities = entity.getNearbyEntities(10, 10, 10);
        for (Entity nearby : nearbyEntities) {
            // Only notify raid entities
            if (raid.isRaiderEntity(nearby.getUniqueId()) && nearby instanceof LivingEntity) {
                // If a loot carrier is retreating, send protection
                if (retreatType == RetreatType.LOOT_CARRIER && nearby instanceof Skeleton) {
                    // 30% chance for skeletons to protect retreating loot carriers
                    if (Math.random() < 0.3) {
                        initiateRetreat((LivingEntity)nearby, raid);
                    }
                }
                
                // If tactical retreat, coordinate others to retreat too
                if (retreatType == RetreatType.TACTICAL) {
                    // 20% chance for tactical group retreat
                    if (Math.random() < 0.2) {
                        initiateRetreat((LivingEntity)nearby, raid);
                    }
                }
            }
        }
    }
    
    /**
     * Mark entity as carrying loot
     */
    public void markEntityAsLootCarrier(Entity entity) {
        entity.getPersistentDataContainer().set(isCarryingLootKey, PersistentDataType.BYTE, (byte)1);
    }
    
    /**
     * Check if entity is carrying loot
     */
    public boolean isCarryingLoot(Entity entity) {
        return entity.getPersistentDataContainer().has(isCarryingLootKey, PersistentDataType.BYTE);
    }
    
    /**
     * Get retreat info for an entity
     */
    public RetreatInfo getRetreatInfo(UUID entityId) {
        return activeRetreats.get(entityId);
    }
    
    /**
     * Clean up resources for this manager
     */
    public void cleanup() {
        lastRetreatTime.clear();
        activeRetreats.clear();
    }
    
    /**
     * Retreat types for different situations
     */
    public enum RetreatType {
        SIMPLE,      // Basic retreat away from danger
        TACTICAL,    // Strategic retreat using cover
        EMERGENCY,   // Fast retreat when critically injured
        LOOT_CARRIER, // Entity carrying stolen items
        DEFENSIVE    // Retreat while fighting back
    }
    
    /**
     * Information about an active retreat
     */
    public class RetreatInfo {
        private final UUID entityId;
        private final Location destination;
        private final RetreatType type;
        
        public RetreatInfo(UUID entityId, Location destination, RetreatType type) {
            this.entityId = entityId;
            this.destination = destination;
            this.type = type;
        }
        
        public UUID getEntityId() {
            return entityId;
        }
        
        public Location getDestination() {
            return destination;
        }
        
        public RetreatType getType() {
            return type;
        }
    }
}