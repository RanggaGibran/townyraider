package id.rnggagib.entity.ai.decision;

import id.rnggagib.TownyRaider;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.RaidSquad;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.RaiderRole;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.PointOfInterestType;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.SquadFormation;
import id.rnggagib.entity.ai.coordination.TacticalBehavior;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;

public class LeaderDecisionSystem {
    private final TownyRaider plugin;
    private final RaiderCoordinationManager coordinationManager;
    private final TacticalBehavior tacticalBehavior;
    
    // Cooldowns to prevent rapid decision changes
    private final Map<UUID, Long> tacticalDecisionCooldowns = new HashMap<>();
    private final long DECISION_COOLDOWN = 10000; // 10 seconds
    
    public LeaderDecisionSystem(TownyRaider plugin, RaiderCoordinationManager coordinationManager,
                               TacticalBehavior tacticalBehavior) {
        this.plugin = plugin;
        this.coordinationManager = coordinationManager;
        this.tacticalBehavior = tacticalBehavior;
    }
    
    /**
     * Make tactical decisions for a squad leader
     */
    public void processTacticalDecisions(RaidSquad squad, LivingEntity leader) {
        // Check cooldown
        if (isTacticalDecisionOnCooldown(leader.getUniqueId())) return;
        
        // Get intelligence factor for more complex decisions
        int intelligence = getEntityIntelligence(leader);
        ActiveRaid raid = plugin.getRaidManager().getActiveRaid(squad.getRaidId());
        if (raid == null) return;
        
        // Get nearby players (potential threats)
        List<Player> nearbyPlayers = getNearbyPlayers(leader, 30);
        
        // Decision tree based on situation
        if (nearbyPlayers.size() >= 2 && intelligence >= 3) {
            // Multiple threats - evaluate tactical options
            if (isSquadOutnumbered(squad, nearbyPlayers) && raid.getStolenItems() > 3) {
                // Retreat if we've stolen enough items and are outnumbered
                Location exitPoint = findExitPoint(leader, raid);
                if (exitPoint != null) {
                    tacticalBehavior.executeCoveredRetreat(squad, exitPoint);
                    setTacticalDecisionCooldown(leader.getUniqueId());
                    return;
                }
            } else if (countRoleInSquad(squad, RaiderRole.LOOTER) > 0) {
                // We have looters - try distraction tactic
                Player primaryTarget = nearbyPlayers.get(0);
                Location lootLocation = findNearbyLootLocation(leader, raid);
                
                if (lootLocation != null) {
                    tacticalBehavior.executeDistractionManeuver(squad, primaryTarget.getLocation(), lootLocation);
                    setTacticalDecisionCooldown(leader.getUniqueId());
                    return;
                }
            }
            
            // Multiple players - try flanking
            if (nearbyPlayers.size() <= 3 && countRoleInSquad(squad, RaiderRole.TANK) > 0) {
                // Execute flanking against primary target
                tacticalBehavior.executeFlankingManeuver(squad, nearbyPlayers.get(0));
                setTacticalDecisionCooldown(leader.getUniqueId());
                return;
            }
        }
        
        // Single threat - direct engagement or focus on looting
        if (nearbyPlayers.size() == 1 && intelligence >= 2) {
            // Decide whether to engage or focus on mission
            int targetItems = getMetadataInt(raid, "target_items", 10);
            if (raid.getStolenItems() < targetItems || countRoleInSquad(squad, RaiderRole.LOOTER) == 0) {
                // Not enough items stolen and no dedicated looters - engage player
                squad.setFormation(SquadFormation.PROTECTED);
                for (UUID memberId : squad.getMembers().keySet()) {
                    Entity member = plugin.getServer().getEntity(memberId);
                    if (member instanceof org.bukkit.entity.Mob) {
                        ((org.bukkit.entity.Mob)member).setTarget(nearbyPlayers.get(0));
                    }
                }
                setTacticalDecisionCooldown(leader.getUniqueId());
                return;
            } else {
                // Enough items stolen or we have looters - split focus
                RaiderRole leaderRole = squad.getMembers().get(leader.getUniqueId());
                if (leaderRole == RaiderRole.LEADER || leaderRole == RaiderRole.TANK) {
                    // Leader engages the player
                    ((org.bukkit.entity.Mob)leader).setTarget(nearbyPlayers.get(0));
                    
                    // Direct looters to continue looting
                    for (UUID memberId : squad.getMembers().keySet()) {
                        RaiderRole role = squad.getMembers().get(memberId);
                        if (role == RaiderRole.LOOTER) {
                            Entity member = plugin.getServer().getEntity(memberId);
                            if (member instanceof org.bukkit.entity.Mob) {
                                Location lootTarget = findNearbyLootLocation(leader, raid);
                                if (lootTarget != null) {
                                    // Share with squad
                                    coordinationManager.sharePointOfInterest(
                                        (LivingEntity)member, lootTarget, PointOfInterestType.CHEST);
                                }
                            }
                        }
                    }
                    setTacticalDecisionCooldown(leader.getUniqueId());
                }
            }
        }
        
        // No players nearby - focus on mission
        if (nearbyPlayers.isEmpty() && intelligence >= 1) {
            // Find valuable targets
            Location lootTarget = findNearbyLootLocation(leader, raid);
            if (lootTarget != null) {
                // Share with squad and change formation to more spread out for searching
                coordinationManager.sharePointOfInterest(leader, lootTarget, PointOfInterestType.CHEST);
                squad.setFormation(SquadFormation.SPREAD);
                setTacticalDecisionCooldown(leader.getUniqueId());
            }
        }
    }
    
    /**
     * Helper method to safely get integer metadata
     */
    private int getMetadataInt(ActiveRaid raid, String key, int defaultValue) {
        Object value = raid.getMetadata(key);
        if (value instanceof Number) {
            return ((Number)value).intValue();
        }
        return defaultValue;
    }
    
    private List<Player> getNearbyPlayers(LivingEntity entity, int radius) {
        List<Player> players = new ArrayList<>();
        for (Entity nearby : entity.getNearbyEntities(radius, radius, radius)) {
            if (nearby instanceof Player) {
                players.add((Player) nearby);
            }
        }
        return players;
    }
    
    private boolean isSquadOutnumbered(RaidSquad squad, List<Player> threats) {
        int activeSquadMembers = 0;
        for (UUID memberId : squad.getMembers().keySet()) {
            Entity member = plugin.getServer().getEntity(memberId);
            if (member != null && member.isValid() && !member.isDead()) {
                activeSquadMembers++;
            }
        }
        return threats.size() > activeSquadMembers;
    }
    
    private int countRoleInSquad(RaidSquad squad, RaiderRole role) {
        int count = 0;
        for (RaiderRole memberRole : squad.getMembers().values()) {
            if (memberRole == role) count++;
        }
        return count;
    }
    
    private Location findExitPoint(LivingEntity entity, ActiveRaid raid) {
        // Get remembered exit points
        Object exitPointsObj = raid.getMetadata("exit_points");
        if (exitPointsObj instanceof List) {
            List<?> exitPoints = (List<?>) exitPointsObj;
            if (!exitPoints.isEmpty() && exitPoints.get(0) instanceof Location) {
                @SuppressWarnings("unchecked")
                List<Location> locations = (List<Location>) exitPoints;
                
                // Find closest exit point
                Location closest = null;
                double closestDist = Double.MAX_VALUE;
                
                for (Location exit : locations) {
                    double dist = exit.distanceSquared(entity.getLocation());
                    if (dist < closestDist) {
                        closestDist = dist;
                        closest = exit;
                    }
                }
                
                return closest;
            }
        }
        
        // Fallback - just move away from town center
        Location townCenter = raid.getLocation();
        if (townCenter != null) {
            // Using org.bukkit.util.Vector (not java.util.Vector)
            Vector direction = entity.getLocation().subtract(townCenter).toVector().normalize();
            return entity.getLocation().add(direction.multiply(30));
        }
        
        return null;
    }
    
    private Location findNearbyLootLocation(LivingEntity entity, ActiveRaid raid) {
        // Try to find chests or valuable blocks nearby
        for (Entity nearby : entity.getNearbyEntities(20, 10, 20)) {
            if (nearby.getLocation().getBlock().getState() instanceof org.bukkit.block.Chest) {
                return nearby.getLocation();
            }
        }
        
        // Check if raid has remembered loot locations
        Object lootLocationsObj = raid.getMetadata("loot_locations");
        if (lootLocationsObj instanceof List) {
            List<?> lootLocations = (List<?>) lootLocationsObj;
            if (!lootLocations.isEmpty() && lootLocations.get(0) instanceof Location) {
                @SuppressWarnings("unchecked")
                List<Location> locations = (List<Location>) lootLocations;
                
                // Find closest loot location
                Location closest = null;
                double closestDist = Double.MAX_VALUE;
                
                for (Location loot : locations) {
                    double dist = loot.distanceSquared(entity.getLocation());
                    if (dist < closestDist && dist < 400) { // Within 20 blocks
                        closestDist = dist;
                        closest = loot;
                    }
                }
                
                if (closest != null) return closest;
            }
        }
        
        return null;
    }
    
    private int getEntityIntelligence(Entity entity) {
        if (!(entity instanceof LivingEntity)) return 1;
        
        NamespacedKey intelligenceKey = new NamespacedKey(plugin, "intelligence");
        return ((LivingEntity)entity).getPersistentDataContainer()
            .getOrDefault(intelligenceKey, org.bukkit.persistence.PersistentDataType.INTEGER, 1);
    }
    
    private boolean isTacticalDecisionOnCooldown(UUID entityId) {
        Long lastDecision = tacticalDecisionCooldowns.get(entityId);
        return lastDecision != null && System.currentTimeMillis() - lastDecision < DECISION_COOLDOWN;
    }
    
    private void setTacticalDecisionCooldown(UUID entityId) {
        tacticalDecisionCooldowns.put(entityId, System.currentTimeMillis());
    }
}