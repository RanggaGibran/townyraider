package id.rnggagib.entity;

import id.rnggagib.TownyRaider;
import id.rnggagib.entity.ai.SimpleAiManager;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
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
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

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

    public void spawnRaidMobs(ActiveRaid raid, Location location) {
        if (location == null || location.getWorld() == null) {
            plugin.getLogger().warning("Cannot spawn raid mobs: Invalid location");
            return;
        }
        
        ConfigurationSection zombieConfig = plugin.getConfigManager().getMobConfig("baby-zombie");
        ConfigurationSection skeletonConfig = plugin.getConfigManager().getMobConfig("skeleton");
        
        int zombieCount = zombieConfig.getInt("count", 2);
        int skeletonPerZombie = skeletonConfig.getInt("count-per-zombie", 2);
        
        List<LivingEntity> raiders = new ArrayList<>();
        
        for (int i = 0; i < zombieCount; i++) {
            Zombie zombie = spawnRaiderZombie(raid, location, zombieConfig);
            if (zombie != null) {
                raiders.add(zombie);
                raid.addRaiderEntity(zombie.getUniqueId());
                
                for (int j = 0; j < skeletonPerZombie; j++) {
                    Location skeletonLocation = getRandomNearbyLocation(location, 3);
                    Skeleton skeleton = spawnGuardianSkeleton(raid, skeletonLocation, skeletonConfig, zombie);
                    if (skeleton != null) {
                        raiders.add(skeleton);
                        raid.addRaiderEntity(skeleton.getUniqueId());
                    }
                }
            }
        }
        
        plugin.getLogger().info("Spawned " + raiders.size() + " raid mobs for raid " + raid.getId());
    }

    /**
     * Spawns raid mobs with difficulty scaling applied
     * @param raid The active raid
     * @param location The spawn location
     */
    public void spawnScaledRaidMobs(ActiveRaid raid, Location location) {
        if (location == null || location.getWorld() == null) {
            plugin.getLogger().warning("Cannot spawn raid mobs: Invalid location");
            return;
        }
        
        ConfigurationSection zombieConfig = plugin.getConfigManager().getMobConfig("baby-zombie");
        ConfigurationSection skeletonConfig = plugin.getConfigManager().getMobConfig("skeleton");
        
        // Get scaled values from raid metadata
        int zombieCount = raid.hasMetadata("zombie_count") ? 
                         ((Number)raid.getMetadata("zombie_count")).intValue() : 
                         zombieConfig.getInt("count", 2);
        
        int skeletonPerZombie = raid.hasMetadata("skeleton_count") ? 
                               ((Number)raid.getMetadata("skeleton_count")).intValue() : 
                               skeletonConfig.getInt("count-per-zombie", 2);
        
        List<LivingEntity> raiders = new ArrayList<>();
        
        for (int i = 0; i < zombieCount; i++) {
            Zombie zombie = spawnScaledRaiderZombie(raid, location, zombieConfig);
            if (zombie != null) {
                raiders.add(zombie);
                raid.addRaiderEntity(zombie.getUniqueId());
                
                for (int j = 0; j < skeletonPerZombie; j++) {
                    Location skeletonLocation = getRandomNearbyLocation(location, 3);
                    Skeleton skeleton = spawnScaledGuardianSkeleton(raid, skeletonLocation, skeletonConfig, zombie);
                    if (skeleton != null) {
                        raiders.add(skeleton);
                        raid.addRaiderEntity(skeleton.getUniqueId());
                    }
                }
            }
        }
        
        plugin.getLogger().info("Spawned " + raiders.size() + " raid mobs for raid " + raid.getId() + 
                               " with difficulty score " + raid.getMetadata("difficulty_score"));
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

    private Zombie spawnScaledRaiderZombie(ActiveRaid raid, Location location, ConfigurationSection config) {
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
        
        // Enhanced health and abilities with scaling
        if (zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double health = raid.hasMetadata("zombie_health") ? 
                          ((Number)raid.getMetadata("zombie_health")).doubleValue() : 
                          config.getDouble("health", 15.0);
            zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
            zombie.setHealth(health);
        }
        
        if (zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            double speed = raid.hasMetadata("zombie_speed") ? 
                          ((Number)raid.getMetadata("zombie_speed")).doubleValue() : 
                          config.getDouble("speed", 0.35);
            zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);
        }
        
        // Rest of the method remains the same...
        
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

    private Skeleton spawnScaledGuardianSkeleton(ActiveRaid raid, Location location, ConfigurationSection config, Zombie protectTarget) {
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
        
        // Apply scaled rank stats
        applyScaledRankStats(skeleton, config, rank, raid);
        
        // Rest of the method remains the same...
        
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

    private void applyScaledRankStats(Skeleton skeleton, ConfigurationSection config, String rank, ActiveRaid raid) {
        ConfigurationSection rankSection = config.getConfigurationSection("ranks." + rank);
        
        if (rankSection == null) {
            return;
        }
        
        // Apply scaled health
        double healthMultiplier = rankSection.getDouble("health_multiplier", 1.0);
        if (skeleton.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double baseHealth = raid.hasMetadata("skeleton_health") ? 
                              ((Number)raid.getMetadata("skeleton_health")).doubleValue() : 
                              config.getDouble("health", 30.0);
            double finalHealth = baseHealth * healthMultiplier;
            skeleton.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(finalHealth);
            skeleton.setHealth(finalHealth);
        }
        
        // Apply scaled speed
        double speedMultiplier = rankSection.getDouble("speed_multiplier", 1.0);
        if (skeleton.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            double baseSpeed = raid.hasMetadata("skeleton_speed") ? 
                             ((Number)raid.getMetadata("skeleton_speed")).doubleValue() : 
                             config.getDouble("speed", 0.25);
            skeleton.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED)
                .setBaseValue(baseSpeed * speedMultiplier);
        }
        
        // Apply scaled damage
        double damageMultiplier = rankSection.getDouble("damage_multiplier", 1.0);
        if (skeleton.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
            double baseDamage = raid.hasMetadata("skeleton_damage") ? 
                              ((Number)raid.getMetadata("skeleton_damage")).doubleValue() : 
                              config.getDouble("damage", 3.0);
            skeleton.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE)
                .setBaseValue(baseDamage * damageMultiplier);
        }
        
        // Rest of the method remains the same...
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
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(raiderKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(raidIdKey, PersistentDataType.STRING, raidId.toString());
        pdc.set(raiderTypeKey, PersistentDataType.STRING, type);
        
        entity.setMetadata(METADATA_RAIDER, new FixedMetadataValue(plugin, true));
        entity.setMetadata(METADATA_RAID_ID, new FixedMetadataValue(plugin, raidId.toString()));
        entity.setMetadata(METADATA_RAIDER_TYPE, new FixedMetadataValue(plugin, type));
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

    // Add this method to keep chunks loaded during raids
    public void keepRaidChunksLoaded(ActiveRaid raid) {
        Location raidLocation = raid.getLocation();
        if (raidLocation == null) return;
        
        World world = raidLocation.getWorld();
        int centerX = raidLocation.getBlockX() >> 4; // Convert to chunk X
        int centerZ = raidLocation.getBlockZ() >> 4; // Convert to chunk Z
        
        // Load a 5x5 chunk area around the raid center
        for (int x = centerX - 2; x <= centerX + 2; x++) {
            for (int z = centerZ - 2; z <= centerZ + 2; z++) {
                world.loadChunk(x, z, true);
                world.setChunkForceLoaded(x, z, true);
            }
        }
        
        // Schedule a task to keep entities alive and verify they exist
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.getRaidManager().getActiveRaids().contains(raid)) {
                    this.cancel();
                    unloadRaidChunks(raid);
                    return;
                }
                
                // Check if mob entities still exist and respawn if needed
                verifyAndRespawnRaidMobs(raid);
            }
        }.runTaskTimer(plugin, 100L, 200L); // Check every 10 seconds
    }

    // Add this method to verify raid mobs exist and respawn them if needed
    private void verifyAndRespawnRaidMobs(ActiveRaid raid) {
        List<UUID> entitiesToRemove = new ArrayList<>();
        List<UUID> currentEntities = new ArrayList<>(raid.getRaiderEntities());
        
        // Check which entities are missing
        for (UUID entityId : currentEntities) {
            boolean found = false;
            for (World world : plugin.getServer().getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity.getUniqueId().equals(entityId) && entity.isValid() && !entity.isDead()) {
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
            
            if (!found) {
                entitiesToRemove.add(entityId);
            }
        }
        
        // Remove missing entities
        for (UUID entityId : entitiesToRemove) {
            raid.removeRaiderEntity(entityId);
        }
        
        // If too many entities are missing, respawn them
        if (entitiesToRemove.size() > 0 && raid.getRaiderEntities().size() < 3) {
            plugin.getLogger().info("Respawning raid mobs for raid " + raid.getId());
            spawnRaidMobs(raid, raid.getLocation());
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