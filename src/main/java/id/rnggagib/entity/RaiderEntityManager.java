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
        
        if (zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double health = config.getDouble("health", 10.0);
            zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
            zombie.setHealth(health);
        }
        
        if (zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            double speed = config.getDouble("speed", 0.3);
            zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);
        }
        
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
        
        if (skeleton.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double health = config.getDouble("health", 15.0);
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
        double x = center.getX() + (Math.random() * 2 - 1) * radius;
        double z = center.getZ() + (Math.random() * 2 - 1) * radius;
        World world = center.getWorld();
        int y = world.getHighestBlockYAt((int) x, (int) z);
        
        return new Location(world, x, y + 1, z);
    }
}