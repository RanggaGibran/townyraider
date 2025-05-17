package id.rnggagib.entity.ai;

import id.rnggagib.TownyRaider;
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
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import com.palmergames.bukkit.towny.object.Town;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SimpleAiManager {
    private final TownyRaider plugin;
    private final Map<UUID, Location> targetLocations = new HashMap<>();
    private final Map<UUID, UUID> protectionTargets = new HashMap<>();
    private final Map<UUID, Long> lastRetreatTime = new HashMap<>();
    private final NamespacedKey targetBlockKey;
    private final NamespacedKey retreatingKey;
    private final Map<UUID, BukkitRunnable> aiTasks = new HashMap<>();
    
    private static final int RETREAT_COOLDOWN = 30000; // 30 seconds
    private static final double RETREAT_HEALTH_THRESHOLD = 0.3; // 30% health
    private static final int PATH_UPDATE_INTERVAL = 10; // ticks
    private static final int TARGET_SEARCH_RADIUS = 20; // blocks

    public SimpleAiManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.targetBlockKey = new NamespacedKey(plugin, "target_block");
        this.retreatingKey = new NamespacedKey(plugin, "retreating");
        
        startAiTasks();
    }

    public void applyRaiderAI(LivingEntity entity, ActiveRaid raid, String raiderType) {
        if (entity instanceof Zombie) {
            applyZombieAI((Zombie) entity, raid);
        } else if (entity instanceof Skeleton) {
            applySkeletonAI((Skeleton) entity, raid, null);
        }
    }
    
    public void applyZombieAI(Zombie zombie, ActiveRaid raid) {
        BukkitRunnable aiTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!zombie.isValid() || zombie.isDead()) {
                    this.cancel();
                    return;
                }
                
                if (shouldRetreat(zombie)) {
                    handleRetreat(zombie);
                    return;
                }

                if (isRetreating(zombie)) {
                    return;
                }
                
                Location targetLocation = targetLocations.get(zombie.getUniqueId());
                if (targetLocation == null || zombie.getLocation().distance(targetLocation) < 2.0 
                        || Math.random() < 0.05) {
                    Location newTarget = findValuableBlockLocation(zombie, raid);
                    if (newTarget != null) {
                        targetLocations.put(zombie.getUniqueId(), newTarget);
                        zombie.getPersistentDataContainer().set(
                            targetBlockKey, 
                            PersistentDataType.STRING, 
                            newTarget.getWorld().getName() + "," + 
                            newTarget.getBlockX() + "," + 
                            newTarget.getBlockY() + "," + 
                            newTarget.getBlockZ()
                        );
                    }
                }
                
                if (targetLocation != null) {
                    if (zombie instanceof Mob) {
                        ((Mob) zombie).setTarget(null);
                        Location finalTarget = targetLocation.clone();
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                if (!zombie.isValid() || zombie.isDead()) {
                                    this.cancel();
                                    return;
                                }
                                zombie.teleport(zombie.getLocation().add(
                                    finalTarget.clone().subtract(zombie.getLocation()).toVector().normalize().multiply(0.3)
                                ));
                            }
                        }.runTaskTimer(plugin, 0L, 5L);
                        
                        // If close to target block, attempt to steal
                        if (zombie.getLocation().distanceSquared(targetLocation) <= 4.0) {
                            handleBlockStealing(zombie, targetLocation.getBlock(), raid);
                        }
                    }
                }
            }
        };
        
        aiTask.runTaskTimer(plugin, 5L, 20L);
        aiTasks.put(zombie.getUniqueId(), aiTask);
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
    
    public void cleanup() {
        for (BukkitRunnable task : aiTasks.values()) {
            task.cancel();
        }
        
        targetLocations.clear();
        protectionTargets.clear();
        lastRetreatTime.clear();
        aiTasks.clear();
    }
}