package id.rnggagib.entity.ai;

import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.Particle;

import com.palmergames.bukkit.towny.object.Town;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class SimpleAiManager {
    private final TownyRaider plugin;
    private final Map<UUID, Location> targetLocations = new HashMap<>();
    private final Map<UUID, UUID> protectionTargets = new HashMap<>();
    private final Map<UUID, Long> lastRetreatTime = new HashMap<>();
    private final NamespacedKey targetBlockKey;
    private final NamespacedKey retreatingKey;
    private final Map<UUID, BukkitRunnable> aiTasks = new HashMap<>();
    private final PathfindingManager pathfindingManager;

    private static final int RETREAT_COOLDOWN = 30000; // 30 seconds
    private static final double RETREAT_HEALTH_THRESHOLD = 0.3; // 30% health
    private static final int PATH_UPDATE_INTERVAL = 10; // ticks
    private static final int TARGET_SEARCH_RADIUS = 20; // blocks

    public SimpleAiManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.targetBlockKey = new NamespacedKey(plugin, "target_block");
        this.retreatingKey = new NamespacedKey(plugin, "retreating");
        this.pathfindingManager = new PathfindingManager(plugin);

        startAiTasks();
        startRescueTask();
    }

    public void applyRaiderAI(LivingEntity entity, ActiveRaid raid, String raiderType) {
        if (entity instanceof Zombie) {
            applyZombieAI((Zombie) entity, raid);
        } else if (entity instanceof Skeleton) {
            applySkeletonAI((Skeleton) entity, raid, null);
        }
    }

    public void applyZombieAI(Zombie zombie, ActiveRaid raid) {
        // Get intelligence level for this zombie
        int intelligence = 1; // Default
        NamespacedKey intelligenceKey = new NamespacedKey(plugin, "intelligence");
        if (zombie.getPersistentDataContainer().has(intelligenceKey, PersistentDataType.INTEGER)) {
            intelligence = zombie.getPersistentDataContainer().get(intelligenceKey, PersistentDataType.INTEGER);
        }

        final int zombieIntelligence = intelligence;
        final Set<Location> exploredLocations = new HashSet<>(); // Track visited areas

        BukkitRunnable aiTask = new BukkitRunnable() {
            private int stuckCounter = 0;
            private Location lastLocation = zombie.getLocation();
            private int priorityChestCheckCooldown = 0;
            private int explorationCooldown = 0;

            @Override
            public void run() {
                if (!zombie.isValid() || zombie.isDead()) {
                    this.cancel();
                    return;
                }

                // Clear any targeting that might have occurred
                if (zombie instanceof Mob) {
                    ((Mob) zombie).setTarget(null);
                }

                // Check if zombie is currently fleeing
                if (isRetreating(zombie) || isZombieFleeing(zombie)) {
                    return;
                }

                // Check if zombie is stuck or not making significant progress
                if (zombie.getLocation().distanceSquared(lastLocation) < 0.2) {
                    stuckCounter++;
                    if (stuckCounter > 4) { // Reduced to make them unstuck faster
                        // Try to unstuck by small teleport or jumping
                        unstuckZombie(zombie);
                        stuckCounter = 0;
                    }
                } else {
                    stuckCounter = 0;
                    lastLocation = zombie.getLocation();
                }

                // Reduce cooldowns for more intelligent zombies
                priorityChestCheckCooldown--;
                explorationCooldown--;

                // High priority: Direct the zombie to nearest chest with higher intelligence
                if (priorityChestCheckCooldown <= 0) {
                    // Intelligent zombies will check for chests more frequently
                    if (Math.random() < 0.3 + (0.1 * zombieIntelligence)) {
                        if (plugin.getStealingManager().directZombieTowardChest(zombie, raid)) {
                            // directZombieTowardChest returns true if a chest was found
                            priorityChestCheckCooldown = 10 - (zombieIntelligence * 2);
                            return;
                        }
                    }
                }

                // If we have a target, navigate to it
                Location targetLocation = targetLocations.get(zombie.getUniqueId());
                if (targetLocation != null) {
                    double speed = 1.0;

                    // More intelligent zombies move faster
                    if (zombieIntelligence > 1) {
                        speed += 0.1 * zombieIntelligence;
                    }

                    // Check if close enough to interact with target
                    if (zombie.getLocation().distance(targetLocation) < 2.0) {
                        // If target is a chest, try to steal
                        Block block = targetLocation.getBlock();
                        if (block.getState() instanceof Chest) {
                            plugin.getStealingManager().attemptToStealFromChest(zombie, (Chest) block.getState(), raid);
                            explorationCooldown = 20; // Wait a bit after trying to steal
                        }
                    } else {
                        // Not close enough, navigate to target
                        pathfindingManager.navigateTo(zombie, targetLocation, speed);
                    }
                } else {
                    // If no chest found or not checking for chests, handle exploration
                    boolean shouldFindNewTarget = targetLocation == null ||
                        zombie.getLocation().distance(targetLocation) < 2.0 ||
                        (explorationCooldown <= 0 && Math.random() < 0.15); // 15% chance to change direction

                    if (shouldFindNewTarget) {
                        // First check if there's a chest nearby
                        Chest nearbyChest = findNearbyChest(zombie.getLocation(), TARGET_SEARCH_RADIUS + (zombieIntelligence * 2));
                        if (nearbyChest != null) {
                            Location chestLoc = nearbyChest.getLocation();
                            targetLocations.put(zombie.getUniqueId(), chestLoc);
                            // Rest of chest targeting code...
                        } else {
                            // No chest found, so explore town more broadly
                            Location newTarget = findExplorationTarget(zombie, raid, exploredLocations);
                            if (newTarget != null) {
                                targetLocations.put(zombie.getUniqueId(), newTarget);
                                exploredLocations.add(newTarget.clone().getBlock().getLocation()); // Mark as explored
                                explorationCooldown = 15 - zombieIntelligence; // Cooldown before changing target again
                            }
                        }
                    }
                }
            }
        };

        aiTask.runTaskTimer(plugin, 5L, 20L);
        aiTasks.put(zombie.getUniqueId(), aiTask);
    }

    /**
     * Finds a new exploration target in the town for the zombie to move toward
     * @param entity The zombie entity
     * @param raid The active raid
     * @param exploredLocations Set of locations already explored
     * @return A new target location
     */
    private Location findExplorationTarget(LivingEntity entity, ActiveRaid raid, Set<Location> exploredLocations) {
        // Get town boundaries
        Town town = plugin.getTownyHandler().getTownByName(raid.getTownName());
        if (town == null) return null;

        // Get town boundaries
        int[] townBounds = plugin.getTownyHandler().getTownBounds(town);
        if (townBounds == null) return null;

        int minX = townBounds[0];
        int minZ = townBounds[1];
        int maxX = townBounds[2];
        int maxZ = townBounds[3];

        World world = entity.getWorld();
        Location currentLoc = entity.getLocation();

        // Define exploration parameters
        int explorationRange = 20 + (getIntelligence(entity) * 5);  // More intelligent zombies explore further
        int attempts = 0;
        int maxAttempts = 10;

        while (attempts < maxAttempts) {
            attempts++;

            // Choose a direction vector with some bias toward unexplored areas
            Vector dirVector;
            if (exploredLocations.size() > 0 && Math.random() < 0.7) {
                // Try to move away from most recently explored locations (last 5)
                List<Location> recentLocations = exploredLocations.stream()
                    .sorted((a, b) -> Double.compare(
                        a.distanceSquared(currentLoc),
                        b.distanceSquared(currentLoc)))
                    .limit(Math.min(5, exploredLocations.size()))
                    .collect(Collectors.toList());

                // Calculate average position of recent locations
                Vector avgPos = new Vector(0, 0, 0);
                for (Location loc : recentLocations) {
                    avgPos.add(loc.toVector());
                }
                avgPos.multiply(1.0 / recentLocations.size());

                // Move away from average position of recent locations
                dirVector = currentLoc.toVector().subtract(avgPos).normalize();

                // Add some randomness
                dirVector.add(new Vector(
                    (Math.random() - 0.5) * 0.5,
                    0,
                    (Math.random() - 0.5) * 0.5
                )).normalize();
            } else {
                // Just choose a random direction
                double angle = Math.random() * Math.PI * 2;
                dirVector = new Vector(Math.cos(angle), 0, Math.sin(angle));
            }

            // Calculate target position
            int distance = 10 + (int)(Math.random() * explorationRange);
            int targetX = currentLoc.getBlockX() + (int)(dirVector.getX() * distance);
            int targetZ = currentLoc.getBlockZ() + (int)(dirVector.getZ() * distance);

            // Check if target is within town boundaries
            if (targetX >= minX && targetX <= maxX && targetZ >= minZ && targetZ <= maxZ) {
                // Find the highest block at this position
                int targetY = world.getHighestBlockYAt(targetX, targetZ);
                Location targetLoc = new Location(world, targetX + 0.5, targetY + 1, targetZ + 0.5);

                // Check if this location is in the town
                if (plugin.getTownyHandler().isLocationInTown(targetLoc, town)) {
                    Block block = world.getBlockAt(targetX, targetY, targetZ);
                    // Avoid water, lava, and other problematic blocks
                    if (!block.isLiquid() && block.getType().isSolid()) {
                        // Check if the blocks above are clear
                        if (world.getBlockAt(targetX, targetY + 1, targetZ).isEmpty() &&
                            world.getBlockAt(targetX, targetY + 2, targetZ).isEmpty()) {
                            return targetLoc;
                        }
                    }
                }
            }
        }

        // Fallback - just pick a random location near the center of town
        Location townCenter = plugin.getTownyHandler().getTownCenter(town);
        if (townCenter != null) {
            return getRandomNearbyLocation(townCenter, 20);
        }

        // Last resort - move in a random direction
        double angle = Math.random() * Math.PI * 2;
        int distance = 5 + (int)(Math.random() * 10);
        int x = currentLoc.getBlockX() + (int)(Math.cos(angle) * distance);
        int z = currentLoc.getBlockZ() + (int)(Math.sin(angle) * distance);
        int y = world.getHighestBlockYAt(x, z);

        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    /**
     * Gets the intelligence level of an entity
     */
    private int getIntelligence(LivingEntity entity) {
        NamespacedKey intelligenceKey = new NamespacedKey(plugin, "intelligence");
        if (entity.getPersistentDataContainer().has(intelligenceKey, PersistentDataType.INTEGER)) {
            return entity.getPersistentDataContainer().get(intelligenceKey, PersistentDataType.INTEGER);
        }
        return 1; // Default intelligence
    }

    // Add this helper method to unstuck zombies
    private void unstuckZombie(Zombie zombie) {
        // Try jumping
        zombie.setVelocity(new Vector(0, 0.4, 0));

        // Add some random movement
        Vector randomMove = new Vector(
            (Math.random() - 0.5) * 0.5,
            0,
            (Math.random() - 0.5) * 0.5
        );

        // Schedule the random movement after jump
        new BukkitRunnable() {
            @Override
            public void run() {
                if (zombie.isValid() && !zombie.isDead()) {
                    zombie.setVelocity(randomMove);
                }
            }
        }.runTaskLater(plugin, 5L);
    }

    // Add this helper method to check if zombie is fleeing
    private boolean isZombieFleeing(Zombie zombie) {
        NamespacedKey fleeingKey = new NamespacedKey(plugin, "fleeing");
        return zombie.getPersistentDataContainer().has(fleeingKey, PersistentDataType.BYTE);
    }

    // Add this helper method to find nearby chests
    private Chest findNearbyChest(Location center, int radius) {
        Set<Material> chestTypes = Set.of(Material.CHEST, Material.TRAPPED_CHEST);
        List<Block> chestBlocks = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius/2; y <= radius/2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = center.getWorld().getBlockAt(
                            center.getBlockX() + x, 
                            center.getBlockY() + y, 
                            center.getBlockZ() + z);

                    if (chestTypes.contains(block.getType())) {
                        chestBlocks.add(block);
                    }
                }
            }
        }

        if (chestBlocks.isEmpty()) {
            return null;
        }

        // Return the closest chest
        Block closest = chestBlocks.stream()
            .min(Comparator.comparingDouble(b -> 
                b.getLocation().distanceSquared(center)))
            .orElse(null);

        if (closest != null && closest.getState() instanceof Chest) {
            return (Chest) closest.getState();
        }
        return null;
    }

    public void applySkeletonAI(Skeleton skeleton, ActiveRaid raid, UUID protectTarget) {
        if (protectTarget != null) {
            protectionTargets.put(skeleton.getUniqueId(), protectTarget);
        }

        BukkitRunnable aiTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!skeleton.isValid() || skeleton.isDead()) {
                    this.cancel();
                    return;
                }

                if (shouldRetreat(skeleton)) {
                    handleRetreat(skeleton);
                    return;
                }

                if (isRetreating(skeleton)) {
                    return;
                }

                // Find and attack nearby town residents
                Player nearestPlayer = findNearestPlayer(skeleton, 15);
                if (nearestPlayer != null) {
                    skeleton.setTarget(nearestPlayer);
                    return;
                }

                // If no players to attack, stay near protected entity
                UUID protectId = protectionTargets.get(skeleton.getUniqueId());
                if (protectId != null) {
                    Entity protectEntity = findEntityByUuid(protectId);
                    if (protectEntity instanceof LivingEntity && protectEntity.isValid() && !protectEntity.isDead()) {
                        LivingEntity protectTarget = (LivingEntity) protectEntity;

                        if (skeleton.getLocation().distance(protectTarget.getLocation()) > 10) {
                            if (skeleton instanceof Mob) {
                                ((Mob) skeleton).setTarget(null);
                                Location targetLoc = protectTarget.getLocation().clone();
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        if (!skeleton.isValid() || skeleton.isDead() || !protectTarget.isValid() || protectTarget.isDead()) {
                                            this.cancel();
                                            return;
                                        }
                                        skeleton.teleport(skeleton.getLocation().add(
                                            protectTarget.getLocation().clone().subtract(skeleton.getLocation()).toVector().normalize().multiply(0.2)
                                        ));
                                    }
                                }.runTaskTimer(plugin, 0L, 10L);
                            }
                        }
                    }
                }
            }
        };

        aiTask.runTaskTimer(plugin, 5L, 20L);
        aiTasks.put(skeleton.getUniqueId(), aiTask);
    }

    private void startAiTasks() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // Clean up invalid entity references
                targetLocations.entrySet().removeIf(entry -> 
                    findEntityByUuid(entry.getKey()) == null);
                protectionTargets.entrySet().removeIf(entry -> 
                    findEntityByUuid(entry.getKey()) == null);
            }
        }.runTaskTimer(plugin, PATH_UPDATE_INTERVAL * 10, PATH_UPDATE_INTERVAL * 10);
    }

    private void startRescueTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // Iterate through all AI-managed entities
                for (UUID entityId : new ArrayList<>(targetLocations.keySet())) {
                    Entity entity = findEntityByUuid(entityId);
                    if (entity instanceof LivingEntity) {
                        LivingEntity living = (LivingEntity) entity;

                        // Check if entity is in problematic situation
                        if (isStuck(living)) {
                            rescueEntity(living);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 100L, 100L); // Check every 5 seconds (100 ticks)
    }

    private boolean shouldRetreat(LivingEntity entity) {
        double healthPercent = entity.getHealth() / entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
        boolean healthLow = healthPercent <= RETREAT_HEALTH_THRESHOLD;

        Long lastRetreat = lastRetreatTime.get(entity.getUniqueId());
        boolean cooldownOver = lastRetreat == null || 
                               (System.currentTimeMillis() - lastRetreat) > RETREAT_COOLDOWN;

        return healthLow && cooldownOver;
    }

    private void handleRetreat(LivingEntity entity) {
        if (isRetreating(entity)) return;

        lastRetreatTime.put(entity.getUniqueId(), System.currentTimeMillis());
        entity.getPersistentDataContainer().set(retreatingKey, PersistentDataType.BYTE, (byte) 1);

        Location retreatLocation = findRetreatLocation(entity);
        if (retreatLocation != null && entity instanceof Mob) {
            Mob mob = (Mob) entity;
            mob.setTarget(null);
            Location retreatLoc = retreatLocation.clone();
            new BukkitRunnable() {
                int counter = 0;
                @Override
                public void run() {
                    if (!mob.isValid() || mob.isDead() || counter > 40) {
                        this.cancel();
                        return;
                    }
                    mob.teleport(mob.getLocation().add(
                        retreatLoc.clone().subtract(mob.getLocation()).toVector().normalize().multiply(0.25)
                    ));
                    counter++;
                }
            }.runTaskTimer(plugin, 0L, 5L);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (entity.isValid() && !entity.isDead()) {
                    entity.getPersistentDataContainer().remove(retreatingKey);
                }
            }
        }.runTaskLater(plugin, 100); // 5 seconds retreat
    }

    private boolean isRetreating(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(retreatingKey, PersistentDataType.BYTE);
    }

    private Location findRetreatLocation(LivingEntity entity) {
        Location origin = entity.getLocation();
        List<Player> nearbyPlayers = getNearbyPlayers(entity, 20);

        if (nearbyPlayers.isEmpty()) {
            return null;
        }

        Location averagePlayerLocation = new Location(origin.getWorld(), 0, 0, 0);
        for (Player player : nearbyPlayers) {
            averagePlayerLocation.add(player.getLocation());
        }

        averagePlayerLocation.setX(averagePlayerLocation.getX() / nearbyPlayers.size());
        averagePlayerLocation.setY(averagePlayerLocation.getY() / nearbyPlayers.size());
        averagePlayerLocation.setZ(averagePlayerLocation.getZ() / nearbyPlayers.size());

        Vector directionFromPlayers = origin.toVector().subtract(averagePlayerLocation.toVector()).normalize();
        Location retreatLocation = origin.clone().add(directionFromPlayers.multiply(10));

        World world = origin.getWorld();
        int x = retreatLocation.getBlockX();
        int z = retreatLocation.getBlockZ();

        retreatLocation.setY(world.getHighestBlockYAt(x, z) + 1);

        return retreatLocation;
    }

    private Location findValuableBlockLocation(LivingEntity entity, ActiveRaid raid) {
        Location origin = entity.getLocation();
        Town town = plugin.getTownyHandler().getTownByName(raid.getTownName());

        if (town == null) return null;

        Set<Material> stealableBlocks = plugin.getConfigManager().getStealableBlocks();
        List<Block> valuableBlocks = new ArrayList<>();

        int radius = TARGET_SEARCH_RADIUS;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius/2; y <= radius/2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Location checkLoc = origin.clone().add(x, y, z);

                    if (!plugin.getTownyHandler().isLocationInTown(checkLoc, town)) {
                        continue;
                    }

                    Block block = checkLoc.getBlock();
                    if (stealableBlocks.contains(block.getType())) {
                        valuableBlocks.add(block);
                    }
                }
            }
        }

        if (valuableBlocks.isEmpty()) {
            return null;
        }

        Block target = valuableBlocks.get((int) (Math.random() * valuableBlocks.size()));
        return target.getLocation().add(0.5, 0.5, 0.5);
    }

    private void handleBlockStealing(Zombie zombie, Block block, ActiveRaid raid) {
        // Only try to steal occasionally to avoid rapid block breaking
        if (Math.random() > 0.2) return;

        zombie.swingMainHand();
        plugin.getStealingManager().handleBlockStealing(zombie, block, raid);
    }

    private List<Player> getNearbyPlayers(LivingEntity entity, int radius) {
        List<Player> nearbyPlayers = new ArrayList<>();

        for (Entity nearby : entity.getNearbyEntities(radius, radius, radius)) {
            if (nearby instanceof Player) {
                nearbyPlayers.add((Player) nearby);
            }
        }

        return nearbyPlayers;
    }

    private Player findNearestPlayer(LivingEntity entity, int radius) {
        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity nearby : entity.getNearbyEntities(radius, radius, radius)) {
            if (nearby instanceof Player) {
                double distance = entity.getLocation().distanceSquared(nearby.getLocation());
                if (distance < nearestDistance) {
                    nearest = (Player) nearby;
                    nearestDistance = distance;
                }
            }
        }

        return nearest;
    }

    private Entity findEntityByUuid(UUID entityId) {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getUniqueId().equals(entityId)) {
                    return entity;
                }
            }
        }
        return null;
    }

    private boolean isStuck(LivingEntity entity) {
        // Check if in water/lava
        Block blockAt = entity.getLocation().getBlock();
        if (blockAt.isLiquid()) {
            return true;
        }

        // Check if has nowhere to move (surrounded by blocks)
        Location loc = entity.getLocation();
        int blockedSides = 0;
        for (int x = -1; x <= 1; x += 2) {
            if (!loc.clone().add(x, 0, 0).getBlock().isPassable()) blockedSides++;
        }
        for (int z = -1; z <= 1; z += 2) {
            if (!loc.clone().add(0, 0, z).getBlock().isPassable()) blockedSides++;
        }
        if (blockedSides >= 4) return true;

        return false;
    }

    private void rescueEntity(LivingEntity entity) {
        // Get current location
        Location current = entity.getLocation();
        World world = current.getWorld();

        // Find the highest block at this XZ coordinate
        int highestY = world.getHighestBlockYAt(current.getBlockX(), current.getBlockZ());

        // Teleport the entity to above the highest block with a margin
        Location safeLocation = new Location(
            world, 
            current.getX(), 
            highestY + 1.5, 
            current.getZ(),
            current.getYaw(),
            current.getPitch()
        );

        // Ensure there's enough space
        if (safeLocation.getBlock().isEmpty() && 
            safeLocation.clone().add(0, 1, 0).getBlock().isEmpty()) {

            // Teleport and add a small upward velocity to prevent immediate falling
            entity.teleport(safeLocation);
            entity.setVelocity(new Vector(0, 0.1, 0));

            // Log the rescue if debug is enabled
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info("Rescued stuck entity: " + entity.getType() + 
                    " at " + safeLocation.getBlockX() + "," + safeLocation.getBlockY() + "," + safeLocation.getBlockZ());
            }
        }
    }

    /**
     * Gets a random location nearby a center point
     * @param center The center location
     * @param radius The radius to search within
     * @return A random nearby location that's safe for entities
     */
    private Location getRandomNearbyLocation(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return center;
        }

        // Try multiple times to find a suitable location
        for (int attempts = 0; attempts < 10; attempts++) {
            // Generate random offsets within radius
            double x = center.getX() + (Math.random() * 2 - 1) * radius;
            double z = center.getZ() + (Math.random() * 2 - 1) * radius;

            // Find the highest block at this position
            int y = world.getHighestBlockYAt((int) x, (int) z);

            // Create location with padding for safety
            Location loc = new Location(world, x, y + 1, z);

            // Check if the location is valid (not in water or lava)
            Block block = loc.getBlock();
            Block blockBelow = loc.clone().add(0, -1, 0).getBlock();

            if (!block.isLiquid() && !blockBelow.isLiquid() && 
                blockBelow.getType().isSolid() &&
                !blockBelow.getType().toString().contains("SLAB") && 
                !blockBelow.getType().toString().contains("STAIRS")) {

                // Ensure there's enough space for entity
                if (loc.clone().add(0, 1, 0).getBlock().isEmpty()) {
                    return loc;
                }
            }
        }

        // If no suitable location found after attempts, return safer fallback
        return new Location(world, center.getX(), center.getY() + 3, center.getZ());
    }

    public void cleanup() {
        for (BukkitRunnable task : aiTasks.values()) {
            task.cancel();
        }

        targetLocations.clear();
        protectionTargets.clear();
        lastRetreatTime.clear();
        aiTasks.clear();

        pathfindingManager.cleanup();
    }
}