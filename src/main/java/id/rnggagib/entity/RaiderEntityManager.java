package id.rnggagib.entity;

import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;
import id.rnggagib.raid.RaidManager;

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
    }

    public void spawnRaidMobs(ActiveRaid raid, Location location) {
        if (location == null || location.getWorld() == null) {
            plugin.getLogger().warning("Cannot spawn raid mobs: Invalid location");
            return;
        }
        
        ConfigurationSection zombieConfig = plugin.getConfigManager().getMobConfig("baby-zombie");
        ConfigurationSection skeletonConfig = plugin.getConfigManager().getMobConfig("skeleton");
        
        int zombieCount = 2;
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
        
        zombie.setBaby(true);
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
        
        return skeleton;
    }

    private void markAsRaider(LivingEntity entity, UUID raidId, String type) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        container.set(raiderKey, PersistentDataType.BYTE, (byte) 1);
        container.set(raidIdKey, PersistentDataType.STRING, raidId.toString());
        container.set(raiderTypeKey, PersistentDataType.STRING, type);
        
        entity.setMetadata(METADATA_RAIDER, new FixedMetadataValue(plugin, true));
        entity.setMetadata(METADATA_RAID_ID, new FixedMetadataValue(plugin, raidId.toString()));
        entity.setMetadata(METADATA_RAIDER_TYPE, new FixedMetadataValue(plugin, type));
    }

    public boolean isRaider(Entity entity) {
        if (entity == null) return false;
        return entity.hasMetadata(METADATA_RAIDER) || entity.getPersistentDataContainer().has(raiderKey, PersistentDataType.BYTE);
    }

    public boolean isRaiderZombie(Entity entity) {
        if (!isRaider(entity)) return false;
        return entity.hasMetadata(METADATA_RAIDER_TYPE) && 
               RAIDER_TYPE_ZOMBIE.equals(entity.getMetadata(METADATA_RAIDER_TYPE).get(0).asString());
    }

    public boolean isRaiderSkeleton(Entity entity) {
        if (!isRaider(entity)) return false;
        return entity.hasMetadata(METADATA_RAIDER_TYPE) && 
               RAIDER_TYPE_SKELETON.equals(entity.getMetadata(METADATA_RAIDER_TYPE).get(0).asString());
    }

    public UUID getRaidId(Entity entity) {
        if (!isRaider(entity)) return null;
        
        if (entity.hasMetadata(METADATA_RAID_ID)) {
            String raidIdStr = entity.getMetadata(METADATA_RAID_ID).get(0).asString();
            return UUID.fromString(raidIdStr);
        }
        
        PersistentDataContainer container = entity.getPersistentDataContainer();
        if (container.has(raidIdKey, PersistentDataType.STRING)) {
            String raidIdStr = container.get(raidIdKey, PersistentDataType.STRING);
            return UUID.fromString(raidIdStr);
        }
        
        return null;
    }

    public void cleanupRaidMobs(UUID raidId) {
        RaidManager raidManager = plugin.getRaidManager();
        ActiveRaid raid = null;
        
        for (ActiveRaid activeRaid : raidManager.getActiveRaids()) {
            if (activeRaid.getId().equals(raidId)) {
                raid = activeRaid;
                break;
            }
        }
        
        if (raid == null) return;
        
        List<UUID> raiderEntities = new ArrayList<>(raid.getRaiderEntities());
        
        for (UUID entityId : raiderEntities) {
            for (World world : plugin.getServer().getWorlds()) {
                for (LivingEntity entity : world.getLivingEntities()) {
                    if (entity.getUniqueId().equals(entityId)) {
                        entity.remove();
                        raid.removeRaiderEntity(entityId);
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
    }
    
    private Location getRandomNearbyLocation(Location center, int radius) {
        double angle = Math.random() * 2 * Math.PI;
        double distance = Math.random() * radius;
        double x = center.getX() + distance * Math.cos(angle);
        double z = center.getZ() + distance * Math.sin(angle);
        
        Location location = center.clone();
        location.setX(x);
        location.setZ(z);
        location.setY(center.getWorld().getHighestBlockYAt((int) x, (int) z) + 1);
        
        return location;
    }
}