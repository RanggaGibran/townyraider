package id.rnggagib.entity;

import id.rnggagib.TownyRaider;
import id.rnggagib.entity.ai.SimpleAiManager;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Particle;
import org.bukkit.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class RaiderEntityManager {
    private final TownyRaider plugin;
    private final NamespacedKey raiderKey;
    private final NamespacedKey raidIdKey;
    private final NamespacedKey raiderTypeKey;
    private final SimpleAiManager aiManager;
    
    public static final String METADATA_RAIDER = "townyraider.raider";
    public static final String METADATA_RAID_ID = "townyraider.raid_id";
    public static final String METADATA_RAIDER_TYPE = "townyraider.raider_type";
    
    public static final String RAIDER_TYPE_ZOMBIE = "zombie";
    public static final String RAIDER_TYPE_SKELETON = "skeleton";

    public RaiderEntityManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.raiderKey = new NamespacedKey(plugin, "raider");
        this.raidIdKey = new NamespacedKey(plugin, "raid_id");
        this.raiderTypeKey = new NamespacedKey(plugin, "raider_type");
        this.aiManager = new SimpleAiManager(plugin); // To handle AI behavior
    }

    /**
     * Enhanced raid mob spawning with improved reliability
     */
    public void spawnRaidMobs(ActiveRaid raid, Location location) {
        if (location == null || location.getWorld() == null) {
            plugin.getLogger().warning("Cannot spawn raid mobs: Invalid location");
            return;
        }
        
        // Check if we've exceeded respawn attempts
        Integer respawnAttempts = (Integer) raid.getMetadata("respawn_attempts");
        if (respawnAttempts != null && respawnAttempts >= 3) {
            plugin.getLogger().warning("Raid " + raid.getId() + " has exceeded respawn attempt limit. Skipping spawn.");
            return;
        }
        
        // Force load a larger chunk area to ensure all mobs spawn
        int chunkRadius = 2; // Load a 5x5 chunk area
        preloadChunksAroundLocation(location, chunkRadius);
        
        // Ensure the spawn chunk is in a valid location
        Chunk chunk = location.getChunk();
        if (!chunk.isLoaded()) {
            chunk.load(true);
            plugin.getLogger().info("Had to load chunk at " + chunk.getX() + ", " + chunk.getZ() + " for spawning");
        }
        
        // Make sure location is safe for spawning
        Location spawnLocation = findSafeSpawnLocation(location);
        if (spawnLocation == null) {
            plugin.getLogger().warning("Cannot find safe spawn location for raid " + raid.getId());
            return;
        }
        
        // Check if the location is in the spawn protection area
        if (isInSpawnProtection(spawnLocation)) {
            plugin.getLogger().warning("Cannot spawn raid mobs in spawn protection area");
            
            // Try to find a new location outside spawn protection
            for (int attempt = 0; attempt < 5; attempt++) {
                Location newLoc = spawnLocation.clone().add((Math.random() * 40) - 20, 0, (Math.random() * 40) - 20);
                newLoc.setY(newLoc.getWorld().getHighestBlockYAt(newLoc));
                
                if (!isInSpawnProtection(newLoc)) {
                    spawnLocation = newLoc;
                    plugin.getLogger().info("Found alternative spawn location outside spawn protection");
                    break;
                }
            }
        }
        
        ConfigurationSection zombieConfig = plugin.getConfigManager().getMobConfig("baby-zombie");
        ConfigurationSection skeletonConfig = plugin.getConfigManager().getMobConfig("skeleton");
        
        int zombieCount = zombieConfig.getInt("count", 2);
        int skeletonPerZombie = skeletonConfig.getInt("count-per-zombie", 2);
        
        List<LivingEntity> raiders = new ArrayList<>();
        
        // Add visual indicator when spawning
        spawnLocation.getWorld().spawnParticle(Particle.EXPLOSION_LARGE, spawnLocation, 3, 0.5, 0.5, 0.5, 0.01);
        spawnLocation.getWorld().playSound(spawnLocation, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.5f);
        
        // Spawn mobs with retry mechanism
        for (int i = 0; i < zombieCount; i++) {
            Location zombieLocation = getRandomNearbyLocation(spawnLocation, 3);
            
            // Ensure this location's chunk is loaded
            zombieLocation.getChunk().load(true);
            
            // Verify location is safe
            zombieLocation = ensureSafeSpawnLocation(zombieLocation);
            
            // Try to spawn up to 3 times
            Zombie zombie = null;
            for (int attempt = 0; attempt < 3 && zombie == null; attempt++) {
                zombie = spawnRaiderZombie(raid, zombieLocation, zombieConfig);
                if (zombie == null && attempt < 2) {
                    // Try a different location
                    zombieLocation = getRandomNearbyLocation(spawnLocation, 5);
                    zombieLocation.getChunk().load(true);
                    zombieLocation = ensureSafeSpawnLocation(zombieLocation);
                }
            }
            
            if (zombie != null) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!zombie.isValid() || zombie.isDead()) {
                        plugin.getLogger().warning("Zombie " + zombie.getUniqueId() + " despawned immediately after spawn at " + formatLocation(zombie.getLocation()));
                    }
                }, 2L);
                
                raiders.add(zombie);
                raid.addRaiderEntity(zombie.getUniqueId());
                
                // Make entity persistent to prevent despawning
                zombie.setPersistent(true);
                zombie.setRemoveWhenFarAway(false);
                
                // Add metadata to track raid
                zombie.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, "raid_id"), 
                    PersistentDataType.STRING, 
                    raid.getId().toString()
                );
                
                // Add visual confirmation
                zombieLocation.getWorld().spawnParticle(Particle.PORTAL, zombieLocation.add(0, 1, 0), 30, 0.5, 1, 0.5, 0.01);
                
                // Log exact spawn location for debugging
                plugin.getLogger().info("Spawned zombie at: " + formatLocation(zombieLocation) + 
                                       " for raid " + raid.getId());
                
                // Spawn skeletons
                for (int j = 0; j < skeletonPerZombie; j++) {
                    Location skeletonLocation = getRandomNearbyLocation(zombieLocation, 3);
                    skeletonLocation.getChunk().load(true);
                    skeletonLocation = ensureSafeSpawnLocation(skeletonLocation);
                    
                    // Try to spawn with retry
                    Skeleton skeleton = null;
                    for (int attempt = 0; attempt < 3 && skeleton == null; attempt++) {
                        skeleton = spawnGuardianSkeleton(raid, skeletonLocation, skeletonConfig, zombie);
                        if (skeleton == null && attempt < 2) {
                            skeletonLocation = getRandomNearbyLocation(zombieLocation, 4);
                            skeletonLocation.getChunk().load(true);
                            skeletonLocation = ensureSafeSpawnLocation(skeletonLocation);
                        }
                    }
                    
                    if (skeleton != null) {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (!skeleton.isValid() || skeleton.isDead()) {
                                plugin.getLogger().warning("Skeleton " + skeleton.getUniqueId() + " despawned immediately after spawn at " + formatLocation(skeleton.getLocation()));
                            }
                        }, 2L);
                        
                        raiders.add(skeleton);
                        raid.addRaiderEntity(skeleton.getUniqueId());
                        
                        // Make entity persistent
                        skeleton.setPersistent(true);
                        skeleton.setRemoveWhenFarAway(false);
                        
                        // Add metadata
                        skeleton.getPersistentDataContainer().set(
                            new NamespacedKey(plugin, "raid_id"), 
                            PersistentDataType.STRING, 
                            raid.getId().toString()
                        );
                        
                        // Visual effects
                        skeleton.getWorld().spawnParticle(Particle.SOUL, skeletonLocation.add(0, 1, 0), 20, 0.5, 1, 0.5, 0.01);
                    } else {
                        plugin.getLogger().warning("Failed to spawn skeleton after 3 attempts");
                    }
                }
            } else {
                plugin.getLogger().warning("Failed to spawn zombie after 3 attempts");
            }
        }
        
        plugin.getLogger().info("Spawned " + raiders.size() + " raid mobs for raid " + raid.getId());
        
        // Schedule a verification check after a longer delay
        new BukkitRunnable() {
            @Override
            public void run() {
                verifyRaidMobsExist(raid);
            }
        }.runTaskLater(plugin, 60L); // Check after 3 seconds instead of 1
    }

    /**
     * Check if a location is in the server's spawn protection area
     */
    private boolean isInSpawnProtection(Location location) {
        if (location.getWorld() != Bukkit.getWorlds().get(0)) {
            return false; // Spawn protection only applies to the main world
        }
        
        int spawnRadius = Bukkit.getServer().getSpawnRadius();
        if (spawnRadius <= 0) return false;
        
        Location spawnLoc = location.getWorld().getSpawnLocation();
        double distanceSquared = location.distanceSquared(spawnLoc);
        
        return distanceSquared < (spawnRadius * spawnRadius);
    }

    /**
     * Spawn raid mobs with scaling based on raid difficulty
     * Uses difficulty values stored in the raid metadata
     */
    public void spawnScaledRaidMobs(ActiveRaid raid, Location location) {
        if (location == null || location.getWorld() == null) {
            plugin.getLogger().warning("Cannot spawn scaled raid mobs: Invalid location");
            return;
        }
        
        // Force load a larger chunk area to ensure all mobs spawn
        int chunkRadius = 2; // Load a 5x5 chunk area
        preloadChunksAroundLocation(location, chunkRadius);
        
        // Make sure location is safe for spawning
        Location spawnLocation = findSafeSpawnLocation(location);
        if (spawnLocation == null) {
            plugin.getLogger().warning("Cannot find safe spawn location for raid " + raid.getId());
            return;
        }
        
        // Get the difficulty-scaled values from the raid metadata
        int zombieCount = (int) raid.getMetadata("zombie_count");
        double zombieHealth = (double) raid.getMetadata("zombie_health");
        double zombieSpeed = (double) raid.getMetadata("zombie_speed");
        double zombieDamage = (double) raid.getMetadata("zombie_damage");
        
        int skeletonCount = (int) raid.getMetadata("skeleton_count");
        double skeletonHealth = (double) raid.getMetadata("skeleton_health");
        double skeletonSpeed = (double) raid.getMetadata("skeleton_speed");
        double skeletonDamage = (double) raid.getMetadata("skeleton_damage");
        
        // Create zombie configuration
        MemoryConfiguration zombieConfig = new MemoryConfiguration();
        zombieConfig.set("count", zombieCount);
        zombieConfig.set("health", zombieHealth);
        zombieConfig.set("speed", zombieSpeed);
        zombieConfig.set("damage", zombieDamage);
        
        // Create skeleton configuration
        MemoryConfiguration skeletonConfig = new MemoryConfiguration();
        skeletonConfig.set("count", skeletonCount);
        skeletonConfig.set("health", skeletonHealth);
        skeletonConfig.set("speed", skeletonSpeed);
        skeletonConfig.set("damage", skeletonDamage);
        
        List<LivingEntity> raiders = new ArrayList<>();
        
        // Add visual indicator when spawning
        spawnLocation.getWorld().spawnParticle(Particle.EXPLOSION_LARGE, spawnLocation, 3, 0.5, 0.5, 0.5, 0.01);
        spawnLocation.getWorld().playSound(spawnLocation, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.5f);
        
        // Spawn zombies with the scaled config
        for (int i = 0; i < zombieCount; i++) {
            Location zombieLocation = getRandomNearbyLocation(spawnLocation, 3);
            zombieLocation.getChunk().load(true);
            zombieLocation = ensureSafeSpawnLocation(zombieLocation);
            
            Zombie zombie = spawnRaiderZombie(raid, zombieLocation, zombieConfig);
            if (zombie != null) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!zombie.isValid() || zombie.isDead()) {
                        plugin.getLogger().warning("Zombie " + zombie.getUniqueId() + " despawned immediately after spawn at " + formatLocation(zombie.getLocation()));
                    }
                }, 2L);
                
                raiders.add(zombie);
                raid.addRaiderEntity(zombie.getUniqueId());
                
                // Apply scaled attributes directly
                if (zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                    zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(zombieHealth);
                    zombie.setHealth(zombieHealth);
                }
                
                if (zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
                    zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(zombieSpeed);
                }
                
                if (zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
                    zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(zombieDamage);
                }
                
                // Spawn skeletons for each zombie
                for (int j = 0; j < skeletonCount / zombieCount; j++) {
                    Location skeletonLocation = getRandomNearbyLocation(zombieLocation, 3);
                    skeletonLocation.getChunk().load(true);
                    skeletonLocation = ensureSafeSpawnLocation(skeletonLocation);
                    
                    Skeleton skeleton = spawnGuardianSkeleton(raid, skeletonLocation, skeletonConfig, zombie);
                    if (skeleton != null) {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (!skeleton.isValid() || skeleton.isDead()) {
                                plugin.getLogger().warning("Skeleton " + skeleton.getUniqueId() + " despawned immediately after spawn at " + formatLocation(skeleton.getLocation()));
                            }
                        }, 2L);
                        
                        raiders.add(skeleton);
                        raid.addRaiderEntity(skeleton.getUniqueId());
                        
                        // Apply scaled attributes directly
                        if (skeleton.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                            skeleton.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(skeletonHealth);
                            skeleton.setHealth(skeletonHealth);
                        }
                        
                        if (skeleton.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
                            skeleton.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(skeletonSpeed);
                        }
                        
                        if (skeleton.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
                            skeleton.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(skeletonDamage);
                        }
                    }
                }
            }
        }
        
        plugin.getLogger().info("Spawned " + raiders.size() + " scaled raid mobs for raid " + raid.getId());
        
        // Start the raid officially
        raid.startRaid();
        
        // Schedule verification check
        new BukkitRunnable() {
            @Override
            public void run() {
                verifyRaidMobsExist(raid);
            }
        }.runTaskLater(plugin, 20L);
    }

    /**
     * Helper method to format a location for logging
     */
    private String formatLocation(Location loc) {
        return String.format("(%.1f, %.1f, %.1f) in %s", 
                            loc.getX(), loc.getY(), loc.getZ(), 
                            loc.getWorld().getName());
    }

    /**
     * Preload chunks around a central location
     */
    private void preloadChunksAroundLocation(Location center, int radius) {
        World world = center.getWorld();
        int centerX = center.getBlockX() >> 4;
        int centerZ = center.getBlockZ() >> 4;
        
        // Load a square of chunks around the center
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    world.loadChunk(x, z, true);
                }
            }
        }
    }

    /**
     * Find a safe location for mob spawning
     */
    private Location findSafeSpawnLocation(Location location) {
        if (location == null) return null;
        
        World world = location.getWorld();
        
        // Start with the given location
        if (isSafeForSpawning(location)) {
            return location;
        }
        
        // Try to find a safe location nearby
        for (int radius = 1; radius < 10; radius++) {
            for (int attempt = 0; attempt < 8; attempt++) {
                double angle = Math.random() * 2 * Math.PI;
                double x = location.getX() + radius * Math.cos(angle);
                double z = location.getZ() + radius * Math.sin(angle);
                
                // Find the highest non-air block at this x,z
                int y = world.getHighestBlockYAt((int)x, (int)z);
                
                Location testLocation = new Location(world, x, y + 1, z);
                if (isSafeForSpawning(testLocation)) {
                    return testLocation;
                }
            }
        }
        
        // As a last resort, try directly above the highest block
        int y = world.getHighestBlockYAt(location.getBlockX(), location.getBlockZ());
        Location highLocation = new Location(world, location.getX(), y + 1, location.getZ());
        
        return highLocation; // May still not be safe, but it's our best option
    }

    /**
     * Check if a location is safe for mob spawning
     */
    private boolean isSafeForSpawning(Location location) {
        World world = location.getWorld();
        
        // Need 2 blocks of air above the ground
        Block groundBlock = world.getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ());
        Block feetBlock = world.getBlockAt(location);
        Block headBlock = world.getBlockAt(location.getBlockX(), location.getBlockY() + 1, location.getBlockZ());
        
        // Ground should be solid
        if (!groundBlock.getType().isSolid()) {
            return false;
        }
        
        // Space for mob should be air
        if (!feetBlock.getType().isAir() || !headBlock.getType().isAir()) {
            return false;
        }
        
        // Avoid dangerous blocks
        if (groundBlock.getType() == Material.LAVA || 
            groundBlock.getType() == Material.CACTUS || 
            groundBlock.getType() == Material.CAMPFIRE ||
            groundBlock.getType() == Material.MAGMA_BLOCK) {
            return false;
        }
        
        return true;
    }

    /**
     * Ensure a location is safe by adjusting the Y coordinate if needed
     */
    private Location ensureSafeSpawnLocation(Location location) {
        if (location == null) return null;
        
        // If already safe, return as is
        if (isSafeForSpawning(location)) {
            return location;
        }
        
        // Try to find a safe Y at this X,Z
        World world = location.getWorld();
        int x = location.getBlockX();
        int z = location.getBlockZ();
        
        // Start from the highest block and check downward
        int topY = world.getHighestBlockYAt(x, z);
        for (int y = topY; y > 0; y--) {
            Location testLoc = new Location(world, x + 0.5, y + 1, z + 0.5);
            if (isSafeForSpawning(testLoc)) {
                return testLoc;
            }
        }
        
        // If no safe location was found, return original but raised slightly
        return location.clone().add(0, 2, 0);
    }

    /**
     * Verify that raid mobs actually exist in the world
     */
    private void verifyRaidMobsExist(ActiveRaid raid) {
        int found = 0;
        int missing = 0;
        
        List<UUID> entityIds = new ArrayList<>(raid.getRaiderEntities());
        
        for (UUID entityId : entityIds) {
            boolean entityExists = false;
            
            // Check across all loaded worlds
            for (World world : plugin.getServer().getWorlds()) {
                for (LivingEntity entity : world.getLivingEntities()) {
                    if (entity.getUniqueId().equals(entityId)) {
                        entityExists = true;
                        found++;
                        break;
                    }
                }
                if (entityExists) break;
            }
            
            if (!entityExists) {
                missing++;
                // Entity missing, remove from raid
                raid.removeRaiderEntity(entityId);
            }
        }
        
        plugin.getLogger().info("Raid " + raid.getId() + " verification: Found " + found + 
                               " mobs, missing " + missing + " mobs");
        
        // Check if we've had too many attempts already
        Integer respawnAttempts = (Integer) raid.getMetadata("respawn_attempts");
        if (respawnAttempts == null) {
            respawnAttempts = 0;
        }
        
        // If too many mobs are missing, try to respawn some - but only for a limited number of attempts
        if (missing > 0 && found < 3 && respawnAttempts < 3) {
            plugin.getLogger().warning("Too many mobs missing from raid " + raid.getId() + 
                                     ", attempting to respawn (attempt " + (respawnAttempts + 1) + "/3)");
            raid.setMetadata("respawn_attempts", respawnAttempts + 1);
            
            // Add a longer delay before respawning to prevent immediate loops
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (plugin.getRaidManager().getActiveRaids().containsKey(raid.getId())) {
                        // Check again before respawning
                        int currentCount = countExistingRaidEntities(raid);
                        if (currentCount < 3) {
                            spawnRaidMobs(raid, raid.getLocation());
                        } else {
                            plugin.getLogger().info("Raid " + raid.getId() + " now has sufficient mobs (" + currentCount + "), respawn canceled");
                        }
                    }
                }
            }.runTaskLater(plugin, 60L); // 3-second delay before respawn
        } else if (respawnAttempts >= 3 && found < 3) {
            plugin.getLogger().warning("Failed to maintain mob presence for raid " + raid.getId() + " after multiple attempts. Continuing raid without respawning more mobs.");
        }
    }

    /**
     * Count existing entities belonging to a raid
     */
    private int countExistingRaidEntities(ActiveRaid raid) {
        int count = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (isRaider(entity)) {
                    UUID raidId = getRaidId(entity);
                    if (raidId != null && raidId.equals(raid.getId())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private Zombie spawnRaiderZombie(ActiveRaid raid, Location location, ConfigurationSection config) {
        World world = location.getWorld();
        
        Zombie zombie = (Zombie) world.spawnEntity(location, EntityType.ZOMBIE);
        
        // Always ensure it's a baby zombie
        zombie.setBaby(true);
        String mobName = config.getString("name", "Towny Plunderer");
        // Parse the name through MiniMessage
        Component parsedName = plugin.getMessageManager().format(mobName);
        // Set the custom name using compatible method
        String legacyName = plugin.getMessageManager().toLegacy(parsedName);
        zombie.setCustomName(legacyName);
        zombie.setCustomNameVisible(true);
        
        // Enhanced health and abilities
        if (zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double health = config.getDouble("health", 15.0); // Increased health
            zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
            zombie.setHealth(health);
        }
        
        if (zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            double speed = config.getDouble("speed", 0.35); // Increased speed
            zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);
        }
        
        // Add better equipment
        zombie.getEquipment().setHelmet(new ItemStack(Material.GOLDEN_HELMET));
        zombie.getEquipment().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
        zombie.getEquipment().setBoots(new ItemStack(Material.GOLDEN_BOOTS)); // Added boots for speed
        zombie.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SHOVEL)); // Thief's tool
        
        // Set all drop chances to 0
        zombie.getEquipment().setHelmetDropChance(0f);
        zombie.getEquipment().setChestplateDropChance(0f);
        zombie.getEquipment().setBootsDropChance(0f);
        zombie.getEquipment().setItemInMainHandDropChance(0f);
        
        // Add knockback resistance
        if (zombie.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE) != null) {
            zombie.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(0.3); // 30% resistance
        }
        
        // Improved combat stats
        if (zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
            zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(2.0); // Lower damage - focus on stealing
        }
        
        // Better survivability with effects
        zombie.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
        zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 1, false, false));
        
        // Make zombies avoid water (smarter pathing)
        zombie.setCanBreakDoors(false); // Focus on chest stealing, not door breaking
        zombie.setRemoveWhenFarAway(false);
        zombie.setPersistent(true);
        
        // Apply metadata to identify this as a raid entity
        zombie.getPersistentDataContainer().set(
            raiderKey, 
            PersistentDataType.STRING, 
            "baby-zombie"
        );
        
        // Store intelligence level in metadata for varied behavior
        zombie.getPersistentDataContainer().set(
            new NamespacedKey(plugin, "intelligence"), 
            PersistentDataType.INTEGER, 
            new Random().nextInt(3) + 1 // Intelligence level 1-3
        );
        
        markAsRaider(zombie, raid.getId(), RAIDER_TYPE_ZOMBIE);
        
        // Apply visual effects
        plugin.getVisualEffectsManager().applyGlowEffect(zombie, "baby-zombie");
        
        // Add particle trail
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!zombie.isValid() || zombie.isDead()) {
                    this.cancel();
                    return;
                }
                
                // Gold coin particles trail
                zombie.getWorld().spawnParticle(
                    Particle.VILLAGER_HAPPY,
                    zombie.getLocation().add(0, 0.5, 0),
                    2, 0.2, 0.2, 0.2, 0.01
                );
            }
        }.runTaskTimer(plugin, 20L, 20L);
        
        // Apply AI behavior
        aiManager.applyRaiderAI(zombie, raid, RAIDER_TYPE_ZOMBIE);
        
        return zombie;
    }

    private Skeleton spawnGuardianSkeleton(ActiveRaid raid, Location location, ConfigurationSection config, Zombie protectTarget) {
        World world = location.getWorld();
        
        Skeleton skeleton = (Skeleton) world.spawnEntity(location, EntityType.SKELETON);
        
        // Determine guardian rank based on configurable weights and randomization
        String rank = determineGuardianRank(config);
        
        // Store the rank in skeleton's metadata
        skeleton.getPersistentDataContainer().set(
            new NamespacedKey(plugin, "guardian_rank"),
            PersistentDataType.STRING,
            rank
        );
        
        // Format name based on rank
        String mobName = formatGuardianNameByRank(config, rank);
        Component parsedName = plugin.getMessageManager().format(mobName);
        String legacyName = plugin.getMessageManager().toLegacy(parsedName);
        skeleton.setCustomName(legacyName);
        skeleton.setCustomNameVisible(true);
        
        // Apply rank-specific stats and abilities
        applyRankStats(skeleton, config, rank);
        
        // Add armor for damage reduction
        skeleton.getEquipment().setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
        skeleton.getEquipment().setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
        skeleton.getEquipment().setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
        skeleton.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
        
        // Set all drop chances to 0
        skeleton.getEquipment().setHelmetDropChance(0f);
        skeleton.getEquipment().setChestplateDropChance(0f);
        skeleton.getEquipment().setLeggingsDropChance(0f);
        skeleton.getEquipment().setItemInMainHandDropChance(0f);
        
        // Add knockback resistance
        if (skeleton.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE) != null) {
            skeleton.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(0.3); // 30% knockback resistance
        }
        
        // Add damage resistance effect
        skeleton.addPotionEffect(new PotionEffect(
            PotionEffectType.DAMAGE_RESISTANCE, 
            Integer.MAX_VALUE, 
            1, // Resistance II (40% damage reduction)
            false, 
            false)
        );
        
        // Set mob properties from config
        skeleton.setRemoveWhenFarAway(false);
        skeleton.setPersistent(true);
        
        // Make immune to fire
        skeleton.setFireTicks(0);
        skeleton.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 1, false, false)); // Fire resistance
        skeleton.setVisualFire(false); // Don't show fire effect
        skeleton.setInvisible(false);
        
        // Apply metadata to identify this as a raid entity
        skeleton.getPersistentDataContainer().set(
            raiderKey, 
            PersistentDataType.STRING, 
            "skeleton"
        );
        
        markAsRaider(skeleton, raid.getId(), RAIDER_TYPE_SKELETON);
        
        skeleton.setMetadata("protectTarget", new FixedMetadataValue(plugin, protectTarget.getUniqueId().toString()));
        
        plugin.getVisualEffectsManager().applyGlowEffect(skeleton, "skeleton");
        
        aiManager.applyRaiderAI(skeleton, raid, RAIDER_TYPE_SKELETON);
        if (protectTarget != null) {
            aiManager.applySkeletonAI(skeleton, raid, protectTarget.getUniqueId());
        }
        
        return skeleton;
    }

    private String determineGuardianRank(ConfigurationSection config) {
        ConfigurationSection rankSection = config.getConfigurationSection("ranks");
        if (rankSection == null) {
            return "novice"; // Default rank
        }
        
        // Get available ranks and their weights
        Map<String, Integer> rankWeights = new HashMap<>();
        int totalWeight = 0;
        
        for (String rank : rankSection.getKeys(false)) {
            int weight = rankSection.getInt(rank + ".weight", 10);
            rankWeights.put(rank, weight);
            totalWeight += weight;
        }
        
        // If no ranks defined, return default
        if (totalWeight == 0) {
            return "novice";
        }
        
        // Select a rank based on weighted probability
        int selection = new Random().nextInt(totalWeight);
        int currentWeight = 0;
        
        for (Map.Entry<String, Integer> entry : rankWeights.entrySet()) {
            currentWeight += entry.getValue();
            if (selection < currentWeight) {
                return entry.getKey();
            }
        }
        
        return "novice"; // Fallback
    }

    private String formatGuardianNameByRank(ConfigurationSection config, String rank) {
        ConfigurationSection rankSection = config.getConfigurationSection("ranks." + rank);
        
        if (rankSection == null) {
            // Default format if rank config not found
            return "<gradient:#C0C0C0:#696969><bold>Shadow Archer</bold></gradient> <dark_gray>[Guardian]";
        }
        
        String nameFormat = rankSection.getString("name_format", 
            "<gradient:{gradient}><bold>{title}</bold></gradient> <{color}>[{rank}]");
        
        String gradient = rankSection.getString("gradient", "#C0C0C0:#696969");
        String title = rankSection.getString("title", "Shadow Archer");
        String color = rankSection.getString("color", "dark_gray");
        String displayRank = rankSection.getString("display", rank.substring(0, 1).toUpperCase() + rank.substring(1));
        
        return nameFormat
            .replace("{gradient}", gradient)
            .replace("{title}", title)
            .replace("{color}", color)
            .replace("{rank}", displayRank);
    }

    private void applyRankStats(Skeleton skeleton, ConfigurationSection config, String rank) {
        ConfigurationSection rankSection = config.getConfigurationSection("ranks." + rank);
        
        if (rankSection == null) {
            return;
        }
        
        // Apply health multiplier
        double healthMultiplier = rankSection.getDouble("health_multiplier", 1.0);
        if (skeleton.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double baseHealth = config.getDouble("health", 30.0);
            double finalHealth = baseHealth * healthMultiplier;
            skeleton.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(finalHealth);
            skeleton.setHealth(finalHealth);
        }
        
        // Apply speed multiplier
        double speedMultiplier = rankSection.getDouble("speed_multiplier", 1.0);
        if (skeleton.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            double baseSpeed = config.getDouble("speed", 0.25);
            skeleton.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED)
                .setBaseValue(baseSpeed * speedMultiplier);
        }
        
        // Apply damage multiplier
        double damageMultiplier = rankSection.getDouble("damage_multiplier", 1.0);
        if (skeleton.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
            double baseDamage = config.getDouble("damage", 3.0);
            skeleton.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE)
                .setBaseValue(baseDamage * damageMultiplier);
        }
        
        // Apply effects based on rank
        int resistanceLevel = rankSection.getInt("resistance_level", 1);
        skeleton.addPotionEffect(new PotionEffect(
            PotionEffectType.DAMAGE_RESISTANCE, 
            Integer.MAX_VALUE, 
            resistanceLevel - 1,
            false, 
            false
        ));
        
        // Apply special effects for higher ranks
        if (rankSection.getBoolean("fire_arrows", false)) {
            skeleton.setPersistent(true); // Required for some special abilities
        }
        
        // Apply visual rank indicators
        String particleType = rankSection.getString("rank_particle", null);
        if (particleType != null) {
            applyRankParticleEffect(skeleton, particleType);
        }
    }

    private void applyRankParticleEffect(Skeleton skeleton, String particleType) {
        Particle particle;
        try {
            particle = Particle.valueOf(particleType.toUpperCase());
        } catch (IllegalArgumentException e) {
            particle = Particle.ENCHANTMENT_TABLE;
        }
        
        final Particle finalParticle = particle;
        
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!skeleton.isValid() || skeleton.isDead()) {
                    this.cancel();
                    return;
                }
                
                skeleton.getWorld().spawnParticle(
                    finalParticle,
                    skeleton.getLocation().add(0, 1.8, 0),
                    3, 0.2, 0.2, 0.2, 0.01
                );
            }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    private void markAsRaider(Entity entity, UUID raidId, String type) {
        // Use both PDC and metadata for redundancy
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(raiderKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(raidIdKey, PersistentDataType.STRING, raidId.toString());
        pdc.set(raiderTypeKey, PersistentDataType.STRING, type);
        
        // Set metadata as backup
        entity.setMetadata(METADATA_RAIDER, new FixedMetadataValue(plugin, true));
        entity.setMetadata(METADATA_RAID_ID, new FixedMetadataValue(plugin, raidId.toString()));
        entity.setMetadata(METADATA_RAIDER_TYPE, new FixedMetadataValue(plugin, type));
        
        // Log for debugging
        if (plugin.getConfigManager().isDebugEnabled()) {
            plugin.getLogger().info("Marked entity " + entity.getUniqueId() + " as raider for raid " + raidId);
        }
        
        // Ensure the entity is properly tracked by the raid
        if (entity instanceof LivingEntity) {
            ActiveRaid raid = plugin.getRaidManager().getActiveRaid(raidId);
            if (raid != null && !raid.getRaiderEntities().contains(entity.getUniqueId())) {
                raid.addRaiderEntity(entity.getUniqueId());
            }
        }
    }

    public boolean isRaider(Entity entity) {
        return entity.getPersistentDataContainer().has(raiderKey, PersistentDataType.BYTE);
    }

    public boolean isRaiderZombie(Entity entity) {
        if (!isRaider(entity)) return false;
        
        return entity.getPersistentDataContainer()
                .getOrDefault(raiderTypeKey, PersistentDataType.STRING, "")
                .equals(RAIDER_TYPE_ZOMBIE);
    }

    public boolean isRaiderSkeleton(Entity entity) {
        if (!isRaider(entity)) return false;
        
        return entity.getPersistentDataContainer()
                .getOrDefault(raiderTypeKey, PersistentDataType.STRING, "")
                .equals(RAIDER_TYPE_SKELETON);
    }

    public UUID getRaidId(Entity entity) {
        if (!isRaider(entity)) return null;
        
        String raidIdStr = entity.getPersistentDataContainer()
                .getOrDefault(raidIdKey, PersistentDataType.STRING, null);
                
        if (raidIdStr == null) return null;
        
        try {
            return UUID.fromString(raidIdStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void removeRaidMobs(ActiveRaid raid) {
        List<UUID> toRemove = new ArrayList<>(raid.getRaiderEntities());
        for (UUID entityId : toRemove) {
            for (World world : plugin.getServer().getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity.getUniqueId().equals(entityId)) {
                        entity.remove();
                        break;
                    }
                }
            }
        }
    }

    public void removeAllRaidMobs() {
        for (World world : plugin.getServer().getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (isRaider(entity)) {
                    entity.remove();
                }
            }
        }
        
        aiManager.cleanup();
    }

    public void cleanupRaidMobs(UUID raidId) {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                UUID entityRaidId = getRaidId(entity);
                if (entityRaidId != null && entityRaidId.equals(raidId)) {
                    entity.remove();
                }
            }
        }
    }

    private Location getRandomNearbyLocation(Location center, int radius) {
        World world = center.getWorld();
        
        // Try multiple times to find a suitable location
        for (int attempts = 0; attempts < 10; attempts++) {
            double x = center.getX() + (Math.random() * 2 - 1) * radius;
            double z = center.getZ() + (Math.random() * 2 - 1) * radius;
            int y = world.getHighestBlockYAt((int) x, (int) z);
            
            // Create location with a more generous height buffer
            Location loc = new Location(world, x, y + 1.5, z);
            
            // Check if the location is valid (not in water or lava)
            Block block = loc.getBlock();
            Block blockBelow = loc.clone().add(0, -1, 0).getBlock();
            
            if (!block.isLiquid() && !blockBelow.isLiquid() && 
                blockBelow.getType().isSolid() && 
                !blockBelow.getType().toString().contains("SLAB") && 
                !blockBelow.getType().toString().contains("STAIRS")) {
                
                // Ensure there's enough space (2 blocks) for the mob to spawn
                if (loc.clone().add(0, 1, 0).getBlock().isEmpty() && 
                    loc.clone().add(0, 2, 0).getBlock().isEmpty()) {
                    
                    return loc;
                }
            }
        }
        
        // Fallback to a safer spawn location if no suitable location found
        return new Location(world, center.getX(), center.getY() + 3, center.getZ());
    }

    /**
     * Enhanced chunk loading management for raids
     */
    public void keepRaidChunksLoaded(ActiveRaid raid) {
        Location raidLocation = raid.getLocation();
        if (raidLocation == null) return;
        
        World world = raidLocation.getWorld();
        int centerX = raidLocation.getBlockX() >> 4;
        int centerZ = raidLocation.getBlockZ() >> 4;
        
        // Store the chunks that we're force-loading so we can unload them later
        Set<ChunkCoord> forcedChunks = new HashSet<>();
        raid.setMetadata("forced_chunks", forcedChunks);
        
        // Load a 5x5 chunk area around the raid center
        for (int x = centerX - 2; x <= centerX + 2; x++) {
            for (int z = centerZ - 2; z <= centerZ + 2; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    world.loadChunk(x, z, true);
                }
                world.setChunkForceLoaded(x, z, true);
                forcedChunks.add(new ChunkCoord(world.getName(), x, z));
                
                // Debug message
                plugin.getLogger().info("Forced chunk loaded at " + x + ", " + z);
            }
        }
        
        // Schedule a task to verify chunks remain loaded and mobs exist
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.getRaidManager().getActiveRaids().containsKey(raid.getId())) {
                    this.cancel();
                    unloadRaidChunks(raid);
                    return;
                }
                
                // Verify chunks are still loaded
                Set<ChunkCoord> chunks = (Set<ChunkCoord>) raid.getMetadata("forced_chunks");
                if (chunks != null) {
                    for (ChunkCoord coord : chunks) {
                        World w = plugin.getServer().getWorld(coord.world);
                        if (w != null && !w.isChunkLoaded(coord.x, coord.z)) {
                            w.loadChunk(coord.x, coord.z, true);
                            w.setChunkForceLoaded(coord.x, coord.z, true);
                            plugin.getLogger().warning("Had to reload chunk at " + coord.x + ", " + coord.z);
                        }
                    }
                }
                
                // Every 5 cycles, verify mob entities still exist
                if (getRunCount() % 5 == 0) {
                    verifyRaidMobsExist(raid);
                }
            }
            
            private int runCount = 0;
            private int getRunCount() {
                return runCount++;
            }
        }.runTaskTimer(plugin, 20L, 100L); // Check every 5 seconds
    }

    /**
     * Helper class for tracking chunks
     */
    private static class ChunkCoord {
        final String world;
        final int x;
        final int z;
        
        ChunkCoord(String world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ChunkCoord)) return false;
            ChunkCoord other = (ChunkCoord) obj;
            return world.equals(other.world) && x == other.x && z == other.z;
        }
        
        @Override
        public int hashCode() {
            return world.hashCode() ^ (x * 31) ^ (z * 17);
        }
    }

    // Add this method to unload chunks when raid ends
    public void unloadRaidChunks(ActiveRaid raid) {
        Location raidLocation = raid.getLocation();
        if (raidLocation == null) return;
        
        World world = raidLocation.getWorld();
        int centerX = raidLocation.getBlockX() >> 4;
        int centerZ = raidLocation.getBlockZ() >> 4;
        
        for (int x = centerX - 2; x <= centerX + 2; x++) {
            for (int z = centerZ - 2; z <= centerZ + 2; z++) {
                world.setChunkForceLoaded(x, z, false);
            }
        }
    }
}