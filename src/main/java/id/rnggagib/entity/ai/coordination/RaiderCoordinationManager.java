package id.rnggagib.entity.ai.coordination;

import id.rnggagib.TownyRaider;
import id.rnggagib.entity.ai.PathfindingManager;
import id.rnggagib.entity.ai.retreat.StrategicRetreatManager;
import id.rnggagib.entity.ai.retreat.StrategicRetreatManager.RetreatType;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages coordination between raid entities for more intelligent group behavior
 */
public class RaiderCoordinationManager {
    private final TownyRaider plugin;
    private final PathfindingManager pathfindingManager;
    private final StrategicRetreatManager retreatManager;
    
    // Keys for entity metadata
    private final NamespacedKey leaderKey;
    private final NamespacedKey roleKey;
    private final NamespacedKey squadKey;
    private final NamespacedKey intelligenceKey;
    
    // Active raid groups and formations
    private final Map<UUID, RaidSquad> activeSquads = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> entitySquadMap = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Location>> sharedKnowledgeMap = new ConcurrentHashMap<>();
    
    // Constants for coordination behavior
    private static final double FORMATION_UPDATE_DISTANCE = 2.0;
    private static final double FORMATION_SPACING = 2.5;
    private static final int COORDINATION_UPDATE_TICKS = 20;
    private static final int MAX_SQUAD_SIZE = 5;
    
    public RaiderCoordinationManager(TownyRaider plugin, PathfindingManager pathfindingManager, 
                                    StrategicRetreatManager retreatManager) {
        this.plugin = plugin;
        this.pathfindingManager = pathfindingManager;
        this.retreatManager = retreatManager;
        
        this.leaderKey = new NamespacedKey(plugin, "raider_leader");
        this.roleKey = new NamespacedKey(plugin, "raider_role");
        this.squadKey = new NamespacedKey(plugin, "raider_squad");
        this.intelligenceKey = new NamespacedKey(plugin, "intelligence");
        
        startCoordinationTask();
    }
    
    /**
     * Start the recurring coordination update task
     */
    private void startCoordinationTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                updateAllSquadFormations();
            }
        }.runTaskTimer(plugin, 20L, COORDINATION_UPDATE_TICKS);
    }
    
    /**
     * Organize entities into coordinated squads when a raid starts
     */
    public void organizeRaidSquads(ActiveRaid raid) {
        List<UUID> raiderIds = raid.getRaiderEntities();
        Map<UUID, LivingEntity> raiders = new HashMap<>();
        
        // Collect all living raid entities
        for (UUID raiderId : raiderIds) {
            Entity entity = plugin.getServer().getEntity(raiderId);
            if (entity instanceof LivingEntity && entity.isValid() && !entity.isDead()) {
                raiders.put(raiderId, (LivingEntity)entity);
            }
        }
        
        // Find potential leaders (high intelligence entities)
        List<LivingEntity> potentialLeaders = raiders.values().stream()
            .sorted((e1, e2) -> Integer.compare(
                getEntityIntelligence(e2), 
                getEntityIntelligence(e1)
            ))
            .limit(5)  // Maximum 5 squad leaders
            .collect(Collectors.toList());
        
        // Create squads around leaders
        for (LivingEntity leader : potentialLeaders) {
            UUID squadId = UUID.randomUUID();
            RaidSquad squad = new RaidSquad(squadId, leader.getUniqueId(), raid.getId());
            
            // Mark leader
            setEntityAsLeader(leader, squadId);
            
            // Add leader to squad
            squad.addMember(leader.getUniqueId(), RaiderRole.LEADER);
            activeSquads.put(squadId, squad);
            entitySquadMap.put(leader.getUniqueId(), squadId);
            
            // Find nearby entities to add to squad
            List<LivingEntity> nearbyRaiders = raiders.values().stream()
                .filter(e -> !isEntityInSquad(e) && e != leader)
                .filter(e -> e.getLocation().distance(leader.getLocation()) < 15)
                .sorted((e1, e2) -> Double.compare(
                    e1.getLocation().distance(leader.getLocation()),
                    e2.getLocation().distance(leader.getLocation())
                ))
                .limit(MAX_SQUAD_SIZE - 1)  // -1 because leader is already in squad
                .collect(Collectors.toList());
            
            // Assign roles and add to squad
            for (LivingEntity member : nearbyRaiders) {
                RaiderRole role = determineRoleForEntity(member);
                squad.addMember(member.getUniqueId(), role);
                
                // Mark entity with squad
                setEntitySquad(member, squadId, role);
                
                // Add to entity-squad map
                entitySquadMap.put(member.getUniqueId(), squadId);
                
                // Remove from available raiders
                raiders.remove(member.getUniqueId());
            }
            
            // Remove leader from available raiders
            raiders.remove(leader.getUniqueId());
            
            // Assign formation
            squad.setFormation(determineFormationForSquad(squad, raid));
        }
        
        // Process any remaining entities as individual raiders
        for (LivingEntity remainingRaider : raiders.values()) {
            // Try to add to existing squads if they're not full
            boolean added = false;
            
            for (RaidSquad squad : activeSquads.values()) {
                if (squad.getMembers().size() < MAX_SQUAD_SIZE && 
                    getLeaderEntity(squad) != null &&
                    remainingRaider.getLocation().distance(getLeaderEntity(squad).getLocation()) < 20) {
                    
                    RaiderRole role = determineRoleForEntity(remainingRaider);
                    squad.addMember(remainingRaider.getUniqueId(), role);
                    
                    // Mark entity with squad
                    setEntitySquad(remainingRaider, squad.getId(), role);
                    
                    // Add to entity-squad map
                    entitySquadMap.put(remainingRaider.getUniqueId(), squad.getId());
                    
                    added = true;
                    break;
                }
            }
            
            // If couldn't add to existing squad, make a solo "squad"
            if (!added) {
                UUID squadId = UUID.randomUUID();
                RaidSquad soloSquad = new RaidSquad(squadId, remainingRaider.getUniqueId(), raid.getId());
                
                // Mark as lone wolf
                setEntityAsLeader(remainingRaider, squadId);
                
                // Add to squad
                soloSquad.addMember(remainingRaider.getUniqueId(), RaiderRole.LONE_WOLF);
                activeSquads.put(squadId, soloSquad);
                entitySquadMap.put(remainingRaider.getUniqueId(), squadId);
                
                // Solo entities use flexible formation
                soloSquad.setFormation(SquadFormation.FLEXIBLE);
            }
        }
    }
    
    /**
     * Update formations for all active squads
     */
    private void updateAllSquadFormations() {
        List<UUID> invalidSquads = new ArrayList<>();
        
        for (RaidSquad squad : activeSquads.values()) {
            boolean valid = updateSquadFormation(squad);
            if (!valid) {
                invalidSquads.add(squad.getId());
            }
        }
        
        // Clean up invalid squads
        for (UUID squadId : invalidSquads) {
            disbandSquad(squadId);
        }
    }
    
    /**
     * Update a specific squad's formation
     * @return true if squad is still valid, false if it should be disbanded
     */
    private boolean updateSquadFormation(RaidSquad squad) {
        LivingEntity leader = getLeaderEntity(squad);
        
        // If leader is gone, squad is invalid
        if (leader == null || !leader.isValid() || leader.isDead()) {
            return false;
        }
        
        // Check if leader is retreating - whole squad should follow
        if (retreatManager.isRetreating(leader)) {
            coordinateSquadRetreat(squad);
            return true;
        }
        
        // Get all active members
        List<UUID> memberIds = new ArrayList<>(squad.getMembers().keySet());
        List<LivingEntity> activeMembers = new ArrayList<>();
        
        for (UUID memberId : memberIds) {
            Entity entity = plugin.getServer().getEntity(memberId);
            if (entity instanceof LivingEntity && entity.isValid() && !entity.isDead()) {
                activeMembers.add((LivingEntity)entity);
            } else {
                // Remove invalid members
                squad.removeMember(memberId);
                entitySquadMap.remove(memberId);
            }
        }
        
        // If squad is empty or just the leader, disband
        if (activeMembers.size() <= 1) {
            return true; // Still valid but might consider disbanding in the future
        }
        
        // Calculate formation positions
        Map<UUID, Location> formationPositions = calculateFormationPositions(squad, leader.getLocation());
        
        // Move members to formation positions
        for (LivingEntity member : activeMembers) {
            if (member.getUniqueId().equals(squad.getLeaderId())) {
                continue; // Skip leader
            }
            
            Location targetPos = formationPositions.get(member.getUniqueId());
            if (targetPos != null && member instanceof Mob) {
                // Only update if entity is far enough from their position
                if (member.getLocation().distance(targetPos) > FORMATION_UPDATE_DISTANCE) {
                    // Get role to determine movement speed
                    RaiderRole role = squad.getMembers().get(member.getUniqueId());
                    double speed = getMovementSpeedForRole(role);
                    
                    pathfindingManager.navigateTo((Mob)member, targetPos, speed);
                }
            }
        }
        
        return true;
    }
    
    /**
     * Calculate positions for squad members based on formation
     */
    private Map<UUID, Location> calculateFormationPositions(RaidSquad squad, Location leaderLocation) {
        Map<UUID, Location> positions = new HashMap<>();
        
        // Leader is always at their current position
        positions.put(squad.getLeaderId(), leaderLocation);
        
        // Get all active members except leader
        List<UUID> memberIds = squad.getMembers().keySet().stream()
            .filter(id -> !id.equals(squad.getLeaderId()))
            .collect(Collectors.toList());
        
        if (memberIds.isEmpty()) {
            return positions;
        }
        
        // Calculate direction leader is facing
        Vector forwardDir = leaderLocation.getDirection().normalize();
        Vector rightDir = new Vector(-forwardDir.getZ(), 0, forwardDir.getX()).normalize();
        
        // Calculate positions based on formation type
        switch (squad.getFormation()) {
            case ARROW:
                // Leader in front, others behind in a V formation
                for (int i = 0; i < memberIds.size(); i++) {
                    int row = (i / 2) + 1;
                    int col = (i % 2 == 0) ? row : -row;
                    
                    Vector offset = forwardDir.clone().multiply(-row * FORMATION_SPACING)
                        .add(rightDir.clone().multiply(col * FORMATION_SPACING));
                    
                    Location pos = leaderLocation.clone().add(offset);
                    positions.put(memberIds.get(i), pos);
                }
                break;
                
            case LINE:
                // Line formation (good for narrow passages)
                for (int i = 0; i < memberIds.size(); i++) {
                    Vector offset = forwardDir.clone().multiply(-(i + 1) * FORMATION_SPACING);
                    Location pos = leaderLocation.clone().add(offset);
                    positions.put(memberIds.get(i), pos);
                }
                break;
                
            case SPREAD:
                // Spread formation (good for surrounding targets)
                double angleStep = 2 * Math.PI / (memberIds.size() + 1);
                for (int i = 0; i < memberIds.size(); i++) {
                    double angle = angleStep * (i + 1);
                    double x = Math.sin(angle) * FORMATION_SPACING;
                    double z = Math.cos(angle) * FORMATION_SPACING;
                    
                    Location pos = leaderLocation.clone().add(x, 0, z);
                    positions.put(memberIds.get(i), pos);
                }
                break;
                
            case PROTECTED:
                // Tank in front, ranged behind, others protected in middle
                List<UUID> tanks = new ArrayList<>();
                List<UUID> ranged = new ArrayList<>();
                List<UUID> others = new ArrayList<>();
                
                // Sort entities by role
                for (UUID memberId : memberIds) {
                    RaiderRole role = squad.getMembers().get(memberId);
                    if (role == RaiderRole.TANK) {
                        tanks.add(memberId);
                    } else if (role == RaiderRole.RANGED) {
                        ranged.add(memberId);
                    } else {
                        others.add(memberId);
                    }
                }
                
                // Position tanks in front
                for (int i = 0; i < tanks.size(); i++) {
                    double offset = (tanks.size() > 1) ? 
                        (i - (tanks.size() - 1) / 2.0) * FORMATION_SPACING : 0;
                    
                    Vector pos = forwardDir.clone().multiply(FORMATION_SPACING)
                        .add(rightDir.clone().multiply(offset));
                    
                    positions.put(tanks.get(i), leaderLocation.clone().add(pos));
                }
                
                // Position ranged behind
                for (int i = 0; i < ranged.size(); i++) {
                    double offset = (ranged.size() > 1) ? 
                        (i - (ranged.size() - 1) / 2.0) * FORMATION_SPACING : 0;
                    
                    Vector pos = forwardDir.clone().multiply(-FORMATION_SPACING)
                        .add(rightDir.clone().multiply(offset));
                    
                    positions.put(ranged.get(i), leaderLocation.clone().add(pos));
                }
                
                // Position others in middle
                for (int i = 0; i < others.size(); i++) {
                    double offset = (others.size() > 1) ? 
                        (i - (others.size() - 1) / 2.0) * (FORMATION_SPACING / 2) : 0;
                    
                    Vector pos = rightDir.clone().multiply(offset);
                    positions.put(others.get(i), leaderLocation.clone().add(pos));
                }
                break;
                
            default:
            case FLEXIBLE:
                // Flexible formation - looser grouping
                for (int i = 0; i < memberIds.size(); i++) {
                    double angle = 2 * Math.PI * i / memberIds.size();
                    double distance = FORMATION_SPACING * (0.8 + Math.random() * 0.4);
                    
                    double x = Math.sin(angle) * distance;
                    double z = Math.cos(angle) * distance;
                    
                    Location pos = leaderLocation.clone().add(x, 0, z);
                    positions.put(memberIds.get(i), pos);
                }
                break;
        }
        
        return positions;
    }
    
    /**
     * Coordinate squad retreat when leader is retreating
     */
    private void coordinateSquadRetreat(RaidSquad squad) {
        LivingEntity leader = getLeaderEntity(squad);
        if (leader == null) return;
        
        // Get retreat info from leader
        StrategicRetreatManager.RetreatInfo leaderRetreatInfo = retreatManager.getRetreatInfo(leader.getUniqueId());
        if (leaderRetreatInfo == null) return;
        
        ActiveRaid raid = plugin.getRaidManager().getActiveRaid(squad.getRaidId());
        if (raid == null) return;
        
        // Make whole squad retreat together
        for (UUID memberId : squad.getMembers().keySet()) {
            if (memberId.equals(squad.getLeaderId())) continue;
            
            Entity entity = plugin.getServer().getEntity(memberId);
            if (entity instanceof LivingEntity && !retreatManager.isRetreating((LivingEntity)entity)) {
                // Use same retreat type as leader
                retreatManager.initiateRetreat((LivingEntity)entity, raid);
            }
        }
    }
    
    /**
     * Share a point of interest with squad members
     */
    public void sharePointOfInterest(LivingEntity entity, Location location, PointOfInterestType type) {
        UUID squadId = getEntitySquadId(entity);
        if (squadId == null) return;
        
        RaidSquad squad = activeSquads.get(squadId);
        if (squad == null) return;
        
        // Store in shared knowledge
        if (!sharedKnowledgeMap.containsKey(squadId)) {
            sharedKnowledgeMap.put(squadId, new HashSet<>());
        }
        
        // Add the point of interest
        sharedKnowledgeMap.get(squadId).add(location);
        
        // Different behavior based on point of interest type
        switch (type) {
            case CHEST:
            case VALUABLE_BLOCK:
                // If it's a valuable target, have looters investigate
                for (UUID memberId : squad.getMembers().keySet()) {
                    if (squad.getMembers().get(memberId) == RaiderRole.LOOTER) {
                        Entity member = plugin.getServer().getEntity(memberId);
                        if (member instanceof Mob) {
                            pathfindingManager.navigateTo((Mob)member, location, 1.0);
                            break; // Just send one looter
                        }
                    }
                }
                break;
                
            case DANGER:
                // Alert leader to danger
                LivingEntity leader = getLeaderEntity(squad);
                if (leader instanceof Mob) {
                    // If leader is danger-aware, approach cautiously or set defenders
                    if (getEntityIntelligence(leader) >= 3) {
                        // Find defender(s) to investigate
                        for (UUID memberId : squad.getMembers().keySet()) {
                            if (squad.getMembers().get(memberId) == RaiderRole.TANK) {
                                Entity member = plugin.getServer().getEntity(memberId);
                                if (member instanceof Mob) {
                                    pathfindingManager.navigateTo((Mob)member, location, 0.7);
                                    break; // Just send one defender
                                }
                            }
                        }
                    }
                }
                break;
                
            case EXIT_POINT:
                // Remember exit point for retreats
                if (getRaidFromSquad(squad) != null) {
                    getRaidFromSquad(squad).setMetadata("extraction_point", location);
                }
                break;
        }
    }
    
    /**
     * Handle a squad member taking damage
     */
    public void handleSquadMemberDamaged(LivingEntity damaged, Entity damager, double damage) {
        // Check if entity is in a squad
        UUID squadId = getEntitySquadId(damaged);
        if (squadId == null) return;
        
        RaidSquad squad = activeSquads.get(squadId);
        if (squad == null) return;
        
        // If the damaged entity is the leader, alert all members
        if (damaged.getUniqueId().equals(squad.getLeaderId())) {
            // Leader is in danger - respond accordingly
            for (UUID memberId : squad.getMembers().keySet()) {
                if (memberId.equals(squad.getLeaderId())) continue;
                
                Entity member = plugin.getServer().getEntity(memberId);
                if (member instanceof Mob && damager != null) {
                    RaiderRole role = squad.getMembers().get(memberId);
                    
                    if (role == RaiderRole.TANK) {
                        // Tanks intercept the threat
                        ((Mob) member).setTarget(damager instanceof LivingEntity ? (LivingEntity)damager : null);
                    } else if (role == RaiderRole.RANGED && member instanceof Skeleton) {
                        // Ranged members attack from distance
                        ((Mob) member).setTarget(damager instanceof LivingEntity ? (LivingEntity)damager : null);
                    } else if (role == RaiderRole.LOOTER && getEntityIntelligence(member) >= 2) {
                        // Looters retreat if smart enough
                        ActiveRaid raid = getRaidFromSquad(squad);
                        if (raid != null) {
                            retreatManager.initiateRetreat((LivingEntity)member, raid);
                        }
                    }
                }
            }
        } else {
            // A squad member is in danger
            
            // Get squad leader
            LivingEntity leader = getLeaderEntity(squad);
            if (leader == null) return;
            
            // High intelligence leaders coordinate defense
            if (getEntityIntelligence(leader) >= 3 && damager instanceof LivingEntity) {
                // Alert leader to the threat
                if (leader instanceof Mob && leader != damaged) {
                    if (Math.random() < 0.3) { // Don't always redirect leader
                        ((Mob) leader).setTarget((LivingEntity)damager);
                    }
                    
                    // Share information about threat
                    sharePointOfInterest(leader, damager.getLocation(), PointOfInterestType.DANGER);
                }
            }
        }
    }
    
    /**
     * Coordinate strategic target selection
     */
    public Entity selectTargetStrategically(LivingEntity entity, List<LivingEntity> potentialTargets) {
        if (potentialTargets.isEmpty()) return null;
        
        // Get squad information
        UUID squadId = getEntitySquadId(entity);
        if (squadId == null) {
            // No squad, use basic targeting
            return potentialTargets.get(0);
        }
        
        RaidSquad squad = activeSquads.get(squadId);
        if (squad == null) return potentialTargets.get(0);
        
        // Different targeting based on role
        RaiderRole role = squad.getMembers().get(entity.getUniqueId());
        
        switch (role) {
            case TANK:
                // Tanks prefer closest target
                potentialTargets.sort(Comparator.comparing(target -> 
                    target.getLocation().distanceSquared(entity.getLocation())));
                return potentialTargets.get(0);
                
            case RANGED:
                // Ranged attackers prefer stationary targets or those focused on other squad members
                for (LivingEntity target : potentialTargets) {
                    if (target instanceof Player) {
                        Player player = (Player)target;
                        
                        Entity targetEntity = null;
                        if (player.hasLineOfSight(entity)) {
                            // Get entities in player's line of sight
                            for (Entity nearbyEntity : player.getNearbyEntities(10, 10, 10)) {
                                if (nearbyEntity instanceof LivingEntity && 
                                    isInSameSquad(entity, nearbyEntity) && 
                                    player.hasLineOfSight(nearbyEntity)) {
                                    targetEntity = nearbyEntity;
                                    break;
                                }
                            }
                        }
                        
                        if (targetEntity != null) {
                            return player;
                        }
                    }
                }
                // Fallback to random target
                return potentialTargets.get((int)(Math.random() * potentialTargets.size()));
                
            case LEADER:
                // Leaders focus on the biggest threat
                if (entity instanceof Mob) {
                    Mob mob = (Mob)entity;
                    
                    // If already has a target and it's not dead, stick with it
                    if (mob.getTarget() != null && !mob.getTarget().isDead() &&
                        potentialTargets.contains(mob.getTarget())) {
                        return mob.getTarget();
                    }
                    
                    // Otherwise, find highest health/most dangerous target
                    LivingEntity mostDangerous = potentialTargets.get(0);
                    double highestThreat = calculateThreatLevel(mostDangerous);
                    
                    for (LivingEntity target : potentialTargets) {
                        double threat = calculateThreatLevel(target);
                        if (threat > highestThreat) {
                            highestThreat = threat;
                            mostDangerous = target;
                        }
                    }
                    
                    return mostDangerous;
                }
                break;
                
            case LOOTER:
                // Looters avoid combat when possible
                return null;
                
            default:
                // Default to closest target
                potentialTargets.sort(Comparator.comparing(target -> 
                    target.getLocation().distanceSquared(entity.getLocation())));
                return potentialTargets.get(0);
        }
        
        return potentialTargets.get(0);
    }
    
    /**
     * Calculate how dangerous a target is based on various factors
     */
    private double calculateThreatLevel(LivingEntity target) {
        double threat = target.getHealth();
        
        // Players are more dangerous
        if (target instanceof Player) {
            Player player = (Player)target;
            threat *= 2;
            
            // Check armor
            try {
                if (player.getInventory().getHelmet() != null) threat += 2;
                if (player.getInventory().getChestplate() != null) threat += 3;
                if (player.getInventory().getLeggings() != null) threat += 2;
                if (player.getInventory().getBoots() != null) threat += 1;
            } catch (Exception e) {
                // Silently ignore inventory issues
            }
            
            // Check for weapon
            try {
                if (player.getInventory().getItemInMainHand().getType().toString().contains("SWORD")) {
                    threat += 5;
                } else if (player.getInventory().getItemInMainHand().getType().toString().contains("BOW")) {
                    threat += 4;
                } else if (player.getInventory().getItemInMainHand().getType().toString().contains("AXE")) {
                    threat += 6;
                }
            } catch (Exception e) {
                // Silently ignore inventory issues
            }
        }
        
        return threat;
    }
    
    /**
     * Reorganize squads when their composition changes significantly
     */
    public void evaluateAndReorganizeSquads(ActiveRaid raid) {
        // Count active members in each squad
        Map<UUID, Integer> squadSizes = new HashMap<>();
        
        for (RaidSquad squad : activeSquads.values()) {
            if (squad.getRaidId().equals(raid.getId())) {
                int activeMembers = 0;
                for (UUID memberId : squad.getMembers().keySet()) {
                    Entity entity = plugin.getServer().getEntity(memberId);
                    if (entity != null && entity.isValid() && !entity.isDead()) {
                        activeMembers++;
                    }
                }
                squadSizes.put(squad.getId(), activeMembers);
            }
        }
        
        // Find squads that can be merged (too small)
        List<UUID> smallSquads = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : squadSizes.entrySet()) {
            if (entry.getValue() <= 1) {
                smallSquads.add(entry.getKey());
            }
        }
        
        // Merge small squads if possible
        if (smallSquads.size() >= 2) {
            // Take two small squads
            UUID squad1Id = smallSquads.get(0);
            UUID squad2Id = smallSquads.get(1);
            
            RaidSquad squad1 = activeSquads.get(squad1Id);
            RaidSquad squad2 = activeSquads.get(squad2Id);
            
            if (squad1 != null && squad2 != null) {
                // Find valid leader from either squad
                LivingEntity newLeader = getLeaderEntity(squad1);
                if (newLeader == null) newLeader = getLeaderEntity(squad2);
                
                if (newLeader != null) {
                    // Create new merged squad
                    UUID newSquadId = UUID.randomUUID();
                    RaidSquad newSquad = new RaidSquad(newSquadId, newLeader.getUniqueId(), raid.getId());
                    
                    // Set leader
                    setEntityAsLeader(newLeader, newSquadId);
                    
                    // Add leader to squad
                    newSquad.addMember(newLeader.getUniqueId(), RaiderRole.LEADER);
                    
                    // Add remaining members from both squads
                    List<UUID> allMembers = new ArrayList<>();
                    allMembers.addAll(squad1.getMembers().keySet());
                    allMembers.addAll(squad2.getMembers().keySet());
                    
                    for (UUID memberId : allMembers) {
                        if (memberId.equals(newLeader.getUniqueId())) continue;
                        
                        Entity entity = plugin.getServer().getEntity(memberId);
                        if (entity instanceof LivingEntity && entity.isValid() && !entity.isDead()) {
                            RaiderRole role = squad1.getMembers().getOrDefault(memberId, 
                                              squad2.getMembers().getOrDefault(memberId, RaiderRole.MEMBER));
                            
                            newSquad.addMember(memberId, role);
                            setEntitySquad((LivingEntity)entity, newSquadId, role);
                            entitySquadMap.put(memberId, newSquadId);
                        }
                    }
                    
                    // Add new squad
                    activeSquads.put(newSquadId, newSquad);
                    
                    // Assign formation
                    newSquad.setFormation(determineFormationForSquad(newSquad, raid));
                    
                    // Remove old squads
                    activeSquads.remove(squad1Id);
                    activeSquads.remove(squad2Id);
                }
            }
        }
    }
    
    /**
     * Disband a squad and clean up references
     */
    private void disbandSquad(UUID squadId) {
        RaidSquad squad = activeSquads.get(squadId);
        if (squad == null) return;
        
        // Remove squad reference from all members
        for (UUID memberId : squad.getMembers().keySet()) {
            entitySquadMap.remove(memberId);
            
            Entity entity = plugin.getServer().getEntity(memberId);
            if (entity != null) {
                PersistentDataContainer pdc = entity.getPersistentDataContainer();
                pdc.remove(squadKey);
                pdc.remove(roleKey);
                
                if (memberId.equals(squad.getLeaderId())) {
                    pdc.remove(leaderKey);
                }
            }
        }
        
        // Remove shared knowledge
        sharedKnowledgeMap.remove(squadId);
        
        // Remove squad
        activeSquads.remove(squadId);
    }
    
    /**
     * Clean up resources when raid ends
     */
    public void cleanupRaid(ActiveRaid raid) {
        UUID raidId = raid.getId();
        
        // Find squads from this raid
        List<UUID> raidSquads = new ArrayList<>();
        
        for (RaidSquad squad : activeSquads.values()) {
            if (squad.getRaidId().equals(raidId)) {
                raidSquads.add(squad.getId());
            }
        }
        
        // Disband all squads
        for (UUID squadId : raidSquads) {
            disbandSquad(squadId);
        }
    }
    
    /**
     * Get the raid associated with a squad
     */
    private ActiveRaid getRaidFromSquad(RaidSquad squad) {
        return plugin.getRaidManager().getActiveRaid(squad.getRaidId());
    }
    
    /**
     * Check if entity is in a squad
     */
    private boolean isEntityInSquad(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(squadKey, PersistentDataType.STRING);
    }
    
    /**
     * Check if two entities are in the same squad
     */
    private boolean isInSameSquad(Entity entity1, Entity entity2) {
        UUID squad1 = getEntitySquadId(entity1);
        UUID squad2 = getEntitySquadId(entity2);
        
        return squad1 != null && squad2 != null && squad1.equals(squad2);
    }
    
    /**
     * Get the squad ID for an entity
     */
    private UUID getEntitySquadId(Entity entity) {
        return entitySquadMap.get(entity.getUniqueId());
    }
    
    /**
     * Set an entity as a leader of a squad
     */
    private void setEntityAsLeader(LivingEntity entity, UUID squadId) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(leaderKey, PersistentDataType.BYTE, (byte)1);
        pdc.set(squadKey, PersistentDataType.STRING, squadId.toString());
        pdc.set(roleKey, PersistentDataType.STRING, RaiderRole.LEADER.toString());
    }
    
    /**
     * Set an entity's squad and role
     */
    private void setEntitySquad(LivingEntity entity, UUID squadId, RaiderRole role) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(squadKey, PersistentDataType.STRING, squadId.toString());
        pdc.set(roleKey, PersistentDataType.STRING, role.toString());
    }
    
    /**
     * Get entity intelligence level
     */
    private int getEntityIntelligence(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (pdc.has(intelligenceKey, PersistentDataType.INTEGER)) {
            return pdc.get(intelligenceKey, PersistentDataType.INTEGER);
        }
        return 1; // Default intelligence
    }
    
    /**
     * Get movement speed multiplier for a role
     */
    private double getMovementSpeedForRole(RaiderRole role) {
        switch (role) {
            case LEADER:
                return 1.0;
            case TANK:
                return 0.8;
            case RANGED:
                return 1.1;
            case LOOTER:
                return 1.2;
            case MEMBER:
                return 1.0;
            case LONE_WOLF:
                return 1.3;
            default:
                return 1.0;
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
    
    /**
     * Determine the best role for an entity based on type
     */
    private RaiderRole determineRoleForEntity(LivingEntity entity) {
        // Skeletons are ranged attackers
        if (entity instanceof Skeleton) {
            return RaiderRole.RANGED;
        }
        
        // Zombies can be tanks or looters
        if (entity instanceof Zombie) {
            // Check if this is a baby zombie (faster)
            if (entity instanceof Zombie && ((Zombie)entity).isBaby()) {
                return RaiderRole.LOOTER;
            }
            
            // Check equipment - better equipped = tank
            try {
                if (entity.getEquipment() != null && 
                   (entity.getEquipment().getHelmet() != null || 
                    entity.getEquipment().getChestplate() != null)) {
                    return RaiderRole.TANK;
                }
            } catch (Exception e) {
                // Ignore equipment errors
            }
            
            // Otherwise, regular member
            return RaiderRole.MEMBER;
        }
        
        // Default role
        return RaiderRole.MEMBER;
    }
    
    /**
     * Determine best formation for a squad
     */
    private SquadFormation determineFormationForSquad(RaidSquad squad, ActiveRaid raid) {
        // Count roles in squad
        int tanks = 0, ranged = 0, looters = 0;
        
        for (RaiderRole role : squad.getMembers().values()) {
            switch (role) {
                case TANK: tanks++; break;
                case RANGED: ranged++; break;
                case LOOTER: looters++; break;
            }
        }
        
        // If enough tanks and ranged, use protected formation
        if (tanks >= 1 && ranged >= 1) {
            return SquadFormation.PROTECTED;
        }
        
        // If many ranged attackers, use spread formation
        if (ranged > 2) {
            return SquadFormation.SPREAD;
        }
        
        // If in restricted space (inside buildings), use line formation
        LivingEntity leader = getLeaderEntity(squad);
        if (leader != null) {
            // Check if we're indoors
            Location loc = leader.getLocation();
            if (loc.getWorld().getHighestBlockYAt(loc) > loc.getY() + 3) {
                return SquadFormation.LINE;
            }
        }
        
        // Default to arrow formation for normal movement
        return SquadFormation.ARROW;
    }
    
    /**
     * Get all squads for a specific raid
     */
    public List<RaidSquad> getSquadsForRaid(UUID raidId) {
        return activeSquads.values().stream()
            .filter(squad -> squad.getRaidId().equals(raidId))
            .collect(Collectors.toList());
    }
    
    /**
     * Contains data about a squad of raiders
     */
    public static class RaidSquad {
        private final UUID id;
        private final UUID leaderId;
        private final UUID raidId;
        private final Map<UUID, RaiderRole> members = new HashMap<>();
        private SquadFormation formation = SquadFormation.ARROW;
        
        public RaidSquad(UUID id, UUID leaderId, UUID raidId) {
            this.id = id;
            this.leaderId = leaderId;
            this.raidId = raidId;
        }
        
        public UUID getId() {
            return id;
        }
        
        public UUID getLeaderId() {
            return leaderId;
        }
        
        public UUID getRaidId() {
            return raidId;
        }
        
        public Map<UUID, RaiderRole> getMembers() {
            return members;
        }
        
        public void addMember(UUID entityId, RaiderRole role) {
            members.put(entityId, role);
        }
        
        public void removeMember(UUID entityId) {
            members.remove(entityId);
        }
        
        public SquadFormation getFormation() {
            return formation;
        }
        
        public void setFormation(SquadFormation formation) {
            this.formation = formation;
        }
    }
    
    /**
     * Raider roles within a squad
     */
    public enum RaiderRole {
        LEADER,     // Commands the squad
        TANK,       // Takes damage and engages in direct combat
        RANGED,     // Attacks from distance
        LOOTER,     // Focuses on stealing items
        MEMBER,     // Generic squad member
        LONE_WOLF   // Independent raider (solo)
    }
    
    /**
     * Squad movement formations
     */
    public enum SquadFormation {
        ARROW,      // V-shaped formation for efficient movement
        LINE,       // Single-file for narrow passages
        SPREAD,     // Spread out for combat
        PROTECTED,  // Tanks in front, ranged in back, others in middle
        FLEXIBLE    // Looser grouping
    }
    
    /**
     * Types of points of interest that can be shared
     */
    public enum PointOfInterestType {
        CHEST,          // Valuable container
        VALUABLE_BLOCK, // Ore or other valuable block
        DANGER,         // Threat or hazard
        EXIT_POINT      // Potential escape route
    }
}