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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        this.aiManager = new SimpleAiManager(plugin);
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

    private Zombie spawnRaiderZombie(ActiveRaid raid, Location location, ConfigurationSection config) {
        World world = location.getWorld();
        
        Zombie zombie = (Zombie) world.spawnEntity(location, EntityType.ZOMBIE);
        
        // Using the setAge method instead of the deprecated setBaby
        zombie.setAge(-2400); // Setting age to make it a baby zombie
        zombie.setCustomName(config.getString("name", "Raider Zombie"));
        zombie.setCustomNameVisible(true);
        
        // Significantly increase health
        if (zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double health = config.getDouble("health", 25.0); // Increased from 10.0
            zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
            zombie.setHealth(health);
        }
        
        if (zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            double speed = config.getDouble("speed", 0.3);
            zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);
        }
        
        // Add armor for damage reduction
        zombie.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(org.bukkit.Material.LEATHER_HELMET));
        zombie.getEquipment().setChestplate(new org.bukkit.inventory.ItemStack(org.bukkit.Material.LEATHER_CHESTPLATE));
        zombie.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.WOODEN_SWORD));
        
        // Set all drop chances to 0
        zombie.getEquipment().setHelmetDropChance(0f);
        zombie.getEquipment().setChestplateDropChance(0f);
        zombie.getEquipment().setItemInMainHandDropChance(0f);
        
        // Add knockback resistance
        if (zombie.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE) != null) {
            zombie.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(0.5); // 50% knockback resistance
        }
        
        // Add damage resistance effect
        zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(
            org.bukkit.potion.PotionEffectType.DAMAGE_RESISTANCE, 
            Integer.MAX_VALUE, 
            1, // Resistance II (40% damage reduction)
            false, 
            false)
        );
        
        // Set mob properties from config
        zombie.setBaby(true); // Make it a baby zombie
        zombie.setRemoveWhenFarAway(false);
        zombie.setPersistent(true);
        
        // Make immune to fire
        zombie.setFireTicks(0);
        zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 1, false, false));
        zombie.setVisualFire(false); // Don't show fire effect
        zombie.setInvisible(false);
        
        // Apply metadata to identify this as a raid entity
        zombie.getPersistentDataContainer().set(
            raiderKey, 
            PersistentDataType.STRING, 
            "baby-zombie"
        );
        
        markAsRaider(zombie, raid.getId(), RAIDER_TYPE_ZOMBIE);
        
        plugin.getVisualEffectsManager().applyGlowEffect(zombie, "baby-zombie");
        
        aiManager.applyRaiderAI(zombie, raid, RAIDER_TYPE_ZOMBIE);
        
        return zombie;
    }

    private Skeleton spawnGuardianSkeleton(ActiveRaid raid, Location location, ConfigurationSection config, Zombie protectTarget) {
        World world = location.getWorld();
        
        Skeleton skeleton = (Skeleton) world.spawnEntity(location, EntityType.SKELETON);
        
        skeleton.setCustomName(config.getString("name", "Guardian Skeleton"));
        skeleton.setCustomNameVisible(true);
        
        // Significantly increase health
        if (skeleton.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double health = config.getDouble("health", 30.0); // Increased from 15.0
            skeleton.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
            skeleton.setHealth(health);
        }
        
        if (skeleton.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            double speed = config.getDouble("speed", 0.25);
            skeleton.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);
        }
        
        if (skeleton.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
            double damage = config.getDouble("damage", 3.0);
            skeleton.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(damage);
        }
        
        // Add armor for damage reduction
        skeleton.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(org.bukkit.Material.CHAINMAIL_HELMET));
        skeleton.getEquipment().setChestplate(new org.bukkit.inventory.ItemStack(org.bukkit.Material.CHAINMAIL_CHESTPLATE));
        skeleton.getEquipment().setLeggings(new org.bukkit.inventory.ItemStack(org.bukkit.Material.CHAINMAIL_LEGGINGS));
        skeleton.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.BOW));
        
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
        skeleton.addPotionEffect(new org.bukkit.potion.PotionEffect(
            org.bukkit.potion.PotionEffectType.DAMAGE_RESISTANCE, 
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
        skeleton.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 1, false, false)); // Fire resistance
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