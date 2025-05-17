package id.rnggagib.entity.ai.coordination;

import id.rnggagib.TownyRaider;
import id.rnggagib.entity.ai.PathfindingManager;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.RaidSquad;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.RaiderRole;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.SquadFormation;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.NamespacedKey;

import java.util.*;

public class AdvancedGroupTactics {
    private final TownyRaider plugin;
    private final RaiderCoordinationManager coordinationManager;
    private final PathfindingManager pathfindingManager;
    
    // Track active tactics
    private final Map<UUID, TacticType> activeTactics = new HashMap<>();
    private final Map<UUID, Long> tacticCooldowns = new HashMap<>();
    
    // Constants
    private static final long TACTIC_COOLDOWN = 15000; // 15 seconds
    private static final double SURROUND_RADIUS = 8.0;
    private static final double SLOW_APPROACH_SPEED = 0.3;
    private static final double NORMAL_APPROACH_SPEED = 0.5;
    private static final double FAST_APPROACH_SPEED = 0.7;
    
    public enum TacticType {
        SURROUND_AND_ATTACK,   // Encircle target and attack from all sides
        BAIT_AND_AMBUSH,       // One raider baits player while others ambush
        LEAPFROG_ADVANCE,      // Alternating advance where some provide cover
        CONCENTRATED_ASSAULT,   // All focus fire on high-value target
        FEIGNED_RETREAT,       // Fake retreat to lure players into trap
        HUNTING_PACK           // Coordinated pursuit of fleeing target
    }
    
    public AdvancedGroupTactics(TownyRaider plugin, RaiderCoordinationManager coordinationManager,
                              PathfindingManager pathfindingManager) {
        this.plugin = plugin;
        this.coordinationManager = coordinationManager;
        this.pathfindingManager = pathfindingManager;
    }
    
    /**
     * Execute a surround and attack tactic
     * Raiders will encircle the target and attack from multiple sides
     */
    public void executeSurroundAndAttack(RaidSquad squad, Player target) {
        if (target == null || isOnCooldown(squad.getId())) return;
        
        // Set the squad formation to spread
        squad.setFormation(SquadFormation.SPREAD);
        
        // Divide squad members into groups for surrounding
        Map<UUID, Location> surroundPositions = calculateSurroundPositions(target, squad);
        
        // Move raiders to their positions
        for (Map.Entry<UUID, Location> entry : surroundPositions.entrySet()) {
            Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (entity instanceof Mob) {
                pathfindingManager.navigateTo((Mob)entity, entry.getValue(), NORMAL_APPROACH_SPEED);
                
                // Set target after short delay (to reach position first)
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (entity.isValid() && !entity.isDead() && target.isValid() && !target.isDead()) {
                            ((Mob)entity).setTarget(target);
                        }
                    }
                }.runTaskLater(plugin, 30L);
            }
        }
        
        // Log tactic execution
        setActiveTactic(squad.getId(), TacticType.SURROUND_AND_ATTACK);
    }
    
    /**
     * Execute a bait and ambush tactic
     * One raider lures the player while others set up an ambush
     */
    public void executeBaitAndAmbush(RaidSquad squad, Player target, Location ambushLocation) {
        if (target == null || ambushLocation == null || isOnCooldown(squad.getId())) return;
        
        // Select bait (prefer TANK, fall back to any member)
        UUID baitId = findSquadMemberByRole(squad, RaiderRole.TANK);
        if (baitId == null) {
            // If no tank, find any member that's not leader
            for (Map.Entry<UUID, RaiderRole> entry : squad.getMembers().entrySet()) {
                if (!entry.getKey().equals(squad.getLeaderId())) {
                    baitId = entry.getKey();
                    break;
                }
            }
        }
        
        // If still no bait, use leader
        if (baitId == null) {
            baitId = squad.getLeaderId();
        }
        
        // Split squad into bait and ambush groups
        List<UUID> ambushers = new ArrayList<>();
        for (UUID memberId : squad.getMembers().keySet()) {
            if (!memberId.equals(baitId)) {
                ambushers.add(memberId);
            }
        }
        
        // Direct bait to engage target
        Entity baitEntity = plugin.getServer().getEntity(baitId);
        if (baitEntity instanceof Mob) {
            ((Mob)baitEntity).setTarget(target);
        }
        
        // Hide ambushers
        for (UUID ambusherId : ambushers) {
            Entity ambusher = plugin.getServer().getEntity(ambusherId);
            if (ambusher instanceof Mob) {
                pathfindingManager.navigateTo((Mob)ambusher, ambushLocation, SLOW_APPROACH_SPEED);
            }
        }
        
        // Set up ambush trigger
        final UUID finalBaitId = baitId;
        new BukkitRunnable() {
            @Override
            public void run() {
                // Cancel if raid ended or target gone
                if (!squad.isActive() || target == null || !target.isValid() || target.isDead()) {
                    this.cancel();
                    return;
                }
                
                // Check if target is close to ambush location
                if (target.getLocation().distance(ambushLocation) < 10) {
                    // Trigger ambush
                    for (UUID ambusherId : ambushers) {
                        Entity ambusher = plugin.getServer().getEntity(ambusherId);
                        if (ambusher instanceof Mob) {
                            ((Mob)ambusher).setTarget(target);
                            pathfindingManager.navigateTo((Mob)ambusher, target.getLocation(), FAST_APPROACH_SPEED);
                        }
                    }
                    this.cancel();
                }
                
                // Check if bait is dead or too far
                Entity bait = plugin.getServer().getEntity(finalBaitId);
                if (bait == null || !bait.isValid() || bait.isDead() || 
                    bait.getLocation().distance(target.getLocation()) > 30) {
                    // Bait lost, abort ambush
                    for (UUID ambusherId : ambushers) {
                        Entity ambusher = plugin.getServer().getEntity(ambusherId);
                        if (ambusher instanceof Mob) {
                            ((Mob)ambusher).setTarget(target);
                        }
                    }
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
        
        setActiveTactic(squad.getId(), TacticType.BAIT_AND_AMBUSH);
    }
    
    /**
     * Execute a leapfrog advance tactic
     * Raiders alternate between covering and advancing
     */
    public void executeLeapfrogAdvance(RaidSquad squad, Location destination) {
        if (destination == null || isOnCooldown(squad.getId())) return;
        
        List<UUID> members = new ArrayList<>(squad.getMembers().keySet());
        if (members.size() < 2) return; // Need at least 2 members
        
        // Split into two groups
        List<UUID> groupA = new ArrayList<>();
        List<UUID> groupB = new ArrayList<>();
        
        for (int i = 0; i < members.size(); i++) {
            if (i % 2 == 0) {
                groupA.add(members.get(i));
            } else {
                groupB.add(members.get(i));
            }
        }
        
        // Start leapfrog movement
        startLeapfrogMovement(groupA, groupB, destination);
        
        setActiveTactic(squad.getId(), TacticType.LEAPFROG_ADVANCE);
    }
    
    /**
     * Execute a concentrated assault tactic
     * All raiders focus on a high-value target
     */
    public void executeConcentratedAssault(RaidSquad squad, Player primaryTarget, List<Player> secondaryTargets) {
        if (primaryTarget == null || isOnCooldown(squad.getId())) return;
        
        // Set aggressive formation
        squad.setFormation(SquadFormation.ARROW);
        
        // All members focus on primary target
        for (UUID memberId : squad.getMembers().keySet()) {
            Entity entity = plugin.getServer().getEntity(memberId);
            if (entity instanceof Mob) {
                ((Mob)entity).setTarget(primaryTarget);
            }
        }
        
        // Leader communicates target priority
        LivingEntity leader = getLeaderEntity(squad);
        if (leader != null) {
            // Visual effect to show coordination
            leader.getWorld().spawnParticle(
                org.bukkit.Particle.DRAGON_BREATH, 
                primaryTarget.getLocation().add(0, 1, 0),
                10, 0.5, 0.5, 0.5, 0.01
            );
        }
        
        setActiveTactic(squad.getId(), TacticType.CONCENTRATED_ASSAULT);
    }
    
    /**
     * Execute a feigned retreat tactic
     * Raiders pretend to retreat, then turn and attack
     */
    public void executeFeintedRetreat(RaidSquad squad, List<Player> targets, Location ambushPoint) {
        if (targets.isEmpty() || ambushPoint == null || isOnCooldown(squad.getId())) return;
        
        // Initial retreat movement
        for (UUID memberId : squad.getMembers().keySet()) {
            Entity entity = plugin.getServer().getEntity(memberId);
            if (entity instanceof Mob) {
                // Move toward ambush point
                pathfindingManager.navigateTo((Mob)entity, ambushPoint, FAST_APPROACH_SPEED);
                
                // Clear targets to look like retreating
                ((Mob)entity).setTarget(null);
            }
        }
        
        // Wait and then turn to attack
        new BukkitRunnable() {
            @Override
            public void run() {
                // Filter to only get players who followed
                List<Player> closePlayers = new ArrayList<>();
                for (Player player : targets) {
                    if (player.isValid() && !player.isDead() && 
                        player.getLocation().distance(ambushPoint) < 15) {
                        closePlayers.add(player);
                    }
                }
                
                if (!closePlayers.isEmpty()) {
                    // Targets followed! Turn and attack
                    Player primaryTarget = closePlayers.get(0);
                    
                    // All members attack
                    for (UUID memberId : squad.getMembers().keySet()) {
                        Entity entity = plugin.getServer().getEntity(memberId);
                        if (entity instanceof Mob) {
                            ((Mob)entity).setTarget(primaryTarget);
                        }
                    }
                }
            }
        }.runTaskLater(plugin, 60L); // 3 seconds
        
        setActiveTactic(squad.getId(), TacticType.FEIGNED_RETREAT);
    }
    
    /**
     * Execute a hunting pack tactic to chase down targets
     */
    public void executeHuntingPack(RaidSquad squad, Player target) {
        if (target == null || isOnCooldown(squad.getId())) return;
        
        // Set formation for fast pursuit
        squad.setFormation(SquadFormation.ARROW);
        
        // Distribute raiders in pursuit pattern
        Map<UUID, Location> pursuitPositions = calculatePursuitPositions(target, squad);
        
        // Move raiders to their positions
        for (Map.Entry<UUID, Location> entry : pursuitPositions.entrySet()) {
            Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (entity instanceof Mob) {
                pathfindingManager.navigateTo((Mob)entity, entry.getValue(), FAST_APPROACH_SPEED);
                ((Mob)entity).setTarget(target);
            }
        }
        
        // Start pursuit tracking task
        startPursuitTracking(squad, target);
        
        setActiveTactic(squad.getId(), TacticType.HUNTING_PACK);
    }
    
    /**
     * Calculate surrounding positions for raiders
     */
    private Map<UUID, Location> calculateSurroundPositions(Player target, RaidSquad squad) {
        Map<UUID, Location> positions = new HashMap<>();
        List<UUID> members = new ArrayList<>(squad.getMembers().keySet());
        
        if (members.isEmpty()) return positions;
        
        // Calculate positions around target
        double angleStep = 2 * Math.PI / members.size();
        for (int i = 0; i < members.size(); i++) {
            double angle = angleStep * i;
            double x = Math.sin(angle) * SURROUND_RADIUS;
            double z = Math.cos(angle) * SURROUND_RADIUS;
            
            Location position = target.getLocation().clone().add(x, 0, z);
            positions.put(members.get(i), position);
        }
        
        return positions;
    }
    
    /**
     * Calculate pursuit positions for hunting pack formation
     */
    private Map<UUID, Location> calculatePursuitPositions(Player target, RaidSquad squad) {
        Map<UUID, Location> positions = new HashMap<>();
        
        // Get target movement direction
        Vector direction = target.getVelocity().clone().normalize();
        if (direction.lengthSquared() < 0.1) {
            // If target not moving much, use their facing direction
            direction = target.getLocation().getDirection();
        }
        
        // Calculate perpendicular vectors
        Vector right = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        
        // Get members except leader
        UUID leaderId = squad.getLeaderId();
        List<UUID> members = new ArrayList<>();
        for (UUID memberId : squad.getMembers().keySet()) {
            if (!memberId.equals(leaderId)) {
                members.add(memberId);
            }
        }
        
        // Leader directly behind target
        if (leaderId != null) {
            Vector leaderOffset = direction.clone().multiply(-SURROUND_RADIUS);
            positions.put(leaderId, target.getLocation().clone().add(leaderOffset));
        }
        
        // Position others on flanks, two groups
        int halfSize = members.size() / 2;
        for (int i = 0; i < members.size(); i++) {
            Vector offset;
            if (i < halfSize) {
                // Right flank
                double distance = SURROUND_RADIUS * 0.8;
                offset = right.clone().multiply(distance)
                    .add(direction.clone().multiply(-SURROUND_RADIUS * 0.5 * (i + 1) / halfSize));
            } else {
                // Left flank
                double distance = -SURROUND_RADIUS * 0.8;
                offset = right.clone().multiply(distance)
                    .add(direction.clone().multiply(-SURROUND_RADIUS * 0.5 * (i - halfSize + 1) / (members.size() - halfSize)));
            }
            
            positions.put(members.get(i), target.getLocation().clone().add(offset));
        }
        
        return positions;
    }
    
    /**
     * Start leapfrog movement pattern
     */
    private void startLeapfrogMovement(List<UUID> groupA, List<UUID> groupB, Location destination) {
        // Initial positions - group A moves first
        moveGroup(groupA, destination, NORMAL_APPROACH_SPEED);
        
        // Timer for alternating movement
        new BukkitRunnable() {
            private boolean isGroupATurn = false;
            private int cycles = 0;
            
            @Override
            public void run() {
                cycles++;
                if (cycles > 10) {
                    // End after 10 cycles
                    this.cancel();
                    return;
                }
                
                // Check if any members are invalid
                boolean membersValid = false;
                for (UUID id : isGroupATurn ? groupA : groupB) {
                    Entity entity = plugin.getServer().getEntity(id);
                    if (entity != null && entity.isValid() && !entity.isDead()) {
                        membersValid = true;
                        break;
                    }
                }
                
                if (!membersValid) {
                    this.cancel();
                    return;
                }
                
                // Alternate movement between groups
                if (isGroupATurn) {
                    moveGroup(groupA, destination, NORMAL_APPROACH_SPEED);
                } else {
                    moveGroup(groupB, destination, NORMAL_APPROACH_SPEED);
                }
                
                isGroupATurn = !isGroupATurn;
            }
        }.runTaskTimer(plugin, 40L, 40L); // Every 2 seconds
    }
    
    /**
     * Move a group of entities
     */
    private void moveGroup(List<UUID> group, Location destination, double speed) {
        for (UUID id : group) {
            Entity entity = plugin.getServer().getEntity(id);
            if (entity instanceof Mob) {
                pathfindingManager.navigateTo((Mob)entity, destination, speed);
            }
        }
    }
    
    /**
     * Start tracking logic for hunting pack behavior
     */
    private void startPursuitTracking(RaidSquad squad, Player target) {
        new BukkitRunnable() {
            private int ticks = 0;
            
            @Override
            public void run() {
                ticks++;
                
                // End pursuit after 30 seconds
                if (ticks > 600 || !target.isValid() || target.isDead() || !squad.isActive()) {
                    this.cancel();
                    return;
                }
                
                // Update pursuit positions every 1.5 seconds
                if (ticks % 30 == 0) {
                    Map<UUID, Location> updatedPositions = calculatePursuitPositions(target, squad);
                    
                    // Move raiders to updated positions
                    for (Map.Entry<UUID, Location> entry : updatedPositions.entrySet()) {
                        Entity entity = plugin.getServer().getEntity(entry.getKey());
                        if (entity instanceof Mob) {
                            // Only update if far from position
                            if (entity.getLocation().distance(entry.getValue()) > 5) {
                                pathfindingManager.navigateTo((Mob)entity, entry.getValue(), FAST_APPROACH_SPEED);
                            }
                            ((Mob)entity).setTarget(target);
                        }
                    }
                }
                
                // Check if target is trapped (surrounded by raiders)
                if (isTargetTrapped(target, squad)) {
                    // Signal for attack
                    for (UUID memberId : squad.getMembers().keySet()) {
                        Entity entity = plugin.getServer().getEntity(memberId);
                        if (entity instanceof Mob) {
                            ((Mob)entity).setTarget(target);
                            
                            // Adjust speed based on distance
                            double distance = entity.getLocation().distance(target.getLocation());
                            if (distance < 5) {
                                entity.setVelocity(entity.getLocation().subtract(target.getLocation()).toVector()
                                    .normalize().multiply(-0.5));
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 1L);
    }
    
    /**
     * Check if a target is trapped (surrounded by raiders)
     */
    private boolean isTargetTrapped(Player target, RaidSquad squad) {
        // Count raiders in different quadrants around player
        int[] quadrants = new int[4];
        Location targetLoc = target.getLocation();
        
        for (UUID memberId : squad.getMembers().keySet()) {
            Entity entity = plugin.getServer().getEntity(memberId);
            if (entity == null || !entity.isValid() || entity.isDead()) continue;
            
            // Calculate position relative to target
            double relX = entity.getLocation().getX() - targetLoc.getX();
            double relZ = entity.getLocation().getZ() - targetLoc.getZ();
            
            // Determine quadrant
            int quadrant;
            if (relX >= 0 && relZ >= 0) quadrant = 0; // Northeast
            else if (relX < 0 && relZ >= 0) quadrant = 1; // Northwest
            else if (relX < 0 && relZ < 0) quadrant = 2; // Southwest
            else quadrant = 3; // Southeast
            
            quadrants[quadrant]++;
        }
        
        // Target is trapped if raiders are in at least 3 quadrants
        int coveredQuadrants = 0;
        for (int count : quadrants) {
            if (count > 0) coveredQuadrants++;
        }
        
        return coveredQuadrants >= 3;
    }
    
    /**
     * Find a squad member with a specific role
     */
    private UUID findSquadMemberByRole(RaidSquad squad, RaiderRole role) {
        for (Map.Entry<UUID, RaiderRole> entry : squad.getMembers().entrySet()) {
            if (entry.getValue() == role) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    /**
     * Get the leader entity for a squad
     */
    private LivingEntity getLeaderEntity(RaidSquad squad) {
        Entity entity = plugin.getServer().getEntity(squad.getLeaderId());
        return (entity instanceof LivingEntity) ? (LivingEntity)entity : null;
    }
    
    /**
     * Set active tactic with cooldown
     */
    private void setActiveTactic(UUID squadId, TacticType tactic) {
        activeTactics.put(squadId, tactic);
        tacticCooldowns.put(squadId, System.currentTimeMillis() + TACTIC_COOLDOWN);
    }
    
    /**
     * Check if a squad has a tactic on cooldown
     */
    private boolean isOnCooldown(UUID squadId) {
        Long cooldown = tacticCooldowns.get(squadId);
        return cooldown != null && System.currentTimeMillis() < cooldown;
    }
    
    /**
     * Get current active tactic for a squad
     */
    public TacticType getActiveTactic(UUID squadId) {
        return activeTactics.getOrDefault(squadId, null);
    }
    
    /**
     * Check if this squad is capable of advanced tactics
     */
    public boolean canUseAdvancedTactics(RaidSquad squad) {
        // Need at least 3 members
        if (squad.getMembers().size() < 3) return false;
        
        // Leader must have high intelligence
        LivingEntity leader = getLeaderEntity(squad);
        if (leader == null) return false;
        
        // Check leader intelligence (assumed to be stored in persistent data)
        NamespacedKey intelligenceKey = new NamespacedKey(plugin, "intelligence");
        if (leader.getPersistentDataContainer().has(intelligenceKey, 
            org.bukkit.persistence.PersistentDataType.INTEGER)) {
            int intelligence = leader.getPersistentDataContainer()
                .get(intelligenceKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            return intelligence >= 3;
        }
        
        return false;
    }
}