package id.rnggagib.entity.ai.coordination;

import id.rnggagib.TownyRaider;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.RaidSquad;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.RaiderRole;
import id.rnggagib.entity.ai.PathfindingManager;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class TacticalBehavior {
    private final TownyRaider plugin;
    private final RaiderCoordinationManager coordinationManager;
    private final PathfindingManager pathfindingManager;
    
    public TacticalBehavior(TownyRaider plugin, RaiderCoordinationManager coordinationManager, 
                           PathfindingManager pathfindingManager) {
        this.plugin = plugin;
        this.coordinationManager = coordinationManager;
        this.pathfindingManager = pathfindingManager;
    }
    
    /**
     * Execute a flanking maneuver with a squad
     */
    public void executeFlankingManeuver(RaidSquad squad, Entity target) {
        if (!(target instanceof LivingEntity)) return;
        LivingEntity leader = getLeaderEntity(squad);
        if (leader == null) return;
        
        // Get flank positions
        Vector direction = target.getLocation().subtract(leader.getLocation()).toVector().normalize();
        Vector right = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        Vector left = right.clone().multiply(-1);
        
        // Assign flank positions
        Map<UUID, Location> flankPositions = new HashMap<>();
        List<UUID> tanks = new ArrayList<>();
        List<UUID> ranged = new ArrayList<>();
        
        // Group raiders by role
        for (Map.Entry<UUID, RaiderRole> entry : squad.getMembers().entrySet()) {
            if (entry.getValue() == RaiderRole.TANK) {
                tanks.add(entry.getKey());
            } else if (entry.getValue() == RaiderRole.RANGED) {
                ranged.add(entry.getKey());
            }
        }
        
        // Position tanks on one flank
        Location flankPos = target.getLocation().add(right.multiply(10));
        for (UUID tankId : tanks) {
            Entity entity = plugin.getServer().getEntity(tankId);
            if (entity instanceof Mob) {
                pathfindingManager.navigateTo((Mob)entity, flankPos, 0.7); // Reduced from 1.2
            }
        }
        
        // Position ranged on other flank
        flankPos = target.getLocation().add(left.multiply(10));
        for (UUID rangerId : ranged) {
            Entity entity = plugin.getServer().getEntity(rangerId);
            if (entity instanceof Mob) {
                pathfindingManager.navigateTo((Mob)entity, flankPos, 0.7); // Reduced from 1.2
            }
        }
        
        // Leader approaches from front
        if (leader instanceof Mob) {
            ((Mob)leader).setTarget((LivingEntity)target);
        }
    }
    
    /**
     * Execute tactical retreat covering the squad
     */
    public void executeCoveredRetreat(RaidSquad squad, Location exitPoint) {
        // Identify ranged units to provide cover
        List<UUID> ranged = new ArrayList<>();
        List<UUID> others = new ArrayList<>();
        
        for (Map.Entry<UUID, RaiderRole> entry : squad.getMembers().entrySet()) {
            if (entry.getValue() == RaiderRole.RANGED) {
                ranged.add(entry.getKey());
            } else {
                others.add(entry.getKey());
            }
        }
        
        // First move non-ranged units toward exit
        for (UUID entityId : others) {
            Entity entity = plugin.getServer().getEntity(entityId);
            if (entity instanceof Mob) {
                pathfindingManager.navigateTo((Mob)entity, exitPoint, 0.8); // Reduced from 1.2
            }
        }
        
        // Ranged units provide cover and retreat last
        if (!ranged.isEmpty()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (UUID rangerId : ranged) {
                        Entity entity = plugin.getServer().getEntity(rangerId);
                        if (entity instanceof Mob) {
                            pathfindingManager.navigateTo((Mob)entity, exitPoint, 0.8); // Reduced from 1.2
                        }
                    }
                }
            }.runTaskLater(plugin, 60L); // 3 seconds later
        }
    }
    
    /**
     * Execute a distraction maneuver with decoys
     */
    public void executeDistractionManeuver(RaidSquad squad, Location targetLocation, Location lootLocation) {
        // Identify roles for diversion
        LivingEntity leader = getLeaderEntity(squad);
        if (leader == null) return;
        
        List<UUID> tanks = new ArrayList<>();
        List<UUID> looters = new ArrayList<>();
        List<UUID> others = new ArrayList<>();
        
        // Group by roles
        for (Map.Entry<UUID, RaiderRole> entry : squad.getMembers().entrySet()) {
            switch (entry.getValue()) {
                case TANK: tanks.add(entry.getKey()); break;
                case LOOTER: looters.add(entry.getKey()); break;
                default: others.add(entry.getKey()); break;
            }
        }
        
        // Tanks and some others create distraction
        List<UUID> distractors = new ArrayList<>(tanks);
        distractors.addAll(others.subList(0, Math.min(2, others.size())));
        
        for (UUID distractorId : distractors) {
            Entity entity = plugin.getServer().getEntity(distractorId);
            if (entity instanceof Mob) {
                // Make noise, attack players, be visible
                pathfindingManager.navigateTo((Mob)entity, targetLocation, 0.7); // Reduced from 1.1
                
                if (entity instanceof LivingEntity) {
                    // Add glowing effect to make them obvious targets
                    ((LivingEntity)entity).addPotionEffect(
                        new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.GLOWING, 200, 0, false, true
                        )
                    );
                }
            }
        }
        
        // Looters sneak to the loot location
        for (UUID looterId : looters) {
            Entity entity = plugin.getServer().getEntity(looterId);
            if (entity instanceof Mob) {
                pathfindingManager.navigateTo((Mob)entity, lootLocation, 0.5); // Reduced from 0.8
            }
        }
    }
    
    /**
     * Get the leader entity for a squad
     */
    private LivingEntity getLeaderEntity(RaidSquad squad) {
        Entity entity = plugin.getServer().getEntity(squad.getLeaderId());
        if (entity instanceof LivingEntity && entity.isValid() && !entity.isDead()) {
            return (LivingEntity)entity;
        }
        return null;
    }
}