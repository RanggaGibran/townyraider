package id.rnggagib.entity.ai.decision;

import id.rnggagib.TownyRaider;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.RaidSquad;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.RaiderRole;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.PointOfInterestType;
import id.rnggagib.entity.ai.coordination.RaiderCoordinationManager.SquadFormation;
import id.rnggagib.entity.ai.coordination.TacticalBehavior;
import id.rnggagib.entity.ai.coordination.AdvancedGroupTactics;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Location;
import org.bukkit.Material;
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
    private final AdvancedGroupTactics advancedTactics;

    // Cooldowns to prevent rapid decision changes
    private final Map<UUID, Long> tacticalDecisionCooldowns = new HashMap<>();
    private final long DECISION_COOLDOWN = 10000; // 10 seconds

    // Create a new constant for movement speeds
    private static final double DEFAULT_MOVEMENT_SPEED = 0.6; // Base movement speed
    private static final double STEALER_MOVEMENT_SPEED = 0.5; // Stealthy movement for stealers
    private static final double MINER_MOVEMENT_SPEED = 0.4;  // Slower miners to look more methodical

    public LeaderDecisionSystem(TownyRaider plugin, RaiderCoordinationManager coordinationManager,
                               TacticalBehavior tacticalBehavior, AdvancedGroupTactics advancedTactics) {
        this.plugin = plugin;
        this.coordinationManager = coordinationManager;
        this.tacticalBehavior = tacticalBehavior;
        this.advancedTactics = advancedTactics;
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

        // Check if squad can use advanced tactics
        if (advancedTactics.canUseAdvancedTactics(squad)) {
            // Choose advanced tactic based on situation
            if (nearbyPlayers.size() >= 3) {
                // Multiple players - use concentrated assault on weakest
                Player primaryTarget = findWeakestPlayer(nearbyPlayers);
                advancedTactics.executeConcentratedAssault(squad, primaryTarget, nearbyPlayers);
                setTacticalDecisionCooldown(leader.getUniqueId());
                return;
            } else if (nearbyPlayers.size() == 2) {
                // Two players - use bait and ambush
                Location ambushLoc = findAmbushLocation(leader.getLocation(), nearbyPlayers.get(0).getLocation());
                advancedTactics.executeBaitAndAmbush(squad, nearbyPlayers.get(0), ambushLoc);
                setTacticalDecisionCooldown(leader.getUniqueId());
                return;
            } else if (nearbyPlayers.size() == 1) {
                // Single player - use surround and attack
                advancedTactics.executeSurroundAndAttack(squad, nearbyPlayers.get(0));
                setTacticalDecisionCooldown(leader.getUniqueId());
                return;
            } else if (countRoleInSquad(squad, RaiderRole.STEALER) > 0 || 
                       countRoleInSquad(squad, RaiderRole.MINER) > 0) {
                // No players but we have specialized raiders - use leapfrog to destination
                Location lootTarget = findNearbyLootLocation(leader, raid);
                if (lootTarget != null) {
                    advancedTactics.executeLeapfrogAdvance(squad, lootTarget);
                    setTacticalDecisionCooldown(leader.getUniqueId());
                    return;
                }
            }
        }

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

        // After the main decision tree, check for specialized roles
        if (!nearbyPlayers.isEmpty()) {
            // Combat situation - let the main decision tree handle it
            // Already handled by existing code
        } else {
            // No threats - direct specialized roles
            directStealers(squad, raid);
            directMiners(squad, raid);
        }
    }

    /**
     * Find the weakest player based on equipment and health
     */
    private Player findWeakestPlayer(List<Player> players) {
        // Default to first player
        if (players.isEmpty()) return null;

        Player weakest = players.get(0);
        double lowestStrength = evaluatePlayerStrength(weakest);

        for (Player player : players) {
            double strength = evaluatePlayerStrength(player);
            if (strength < lowestStrength) {
                lowestStrength = strength;
                weakest = player;
            }
        }

        return weakest;
    }

    /**
     * Evaluate player strength based on equipment and health
     */
    private double evaluatePlayerStrength(Player player) {
        double strength = player.getHealth();

        // Add points for armor
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        org.bukkit.inventory.ItemStack helmet = inv.getHelmet();
        org.bukkit.inventory.ItemStack chestplate = inv.getChestplate();
        org.bukkit.inventory.ItemStack leggings = inv.getLeggings();
        org.bukkit.inventory.ItemStack boots = inv.getBoots();

        if (helmet != null) strength += getArmorValue(helmet);
        if (chestplate != null) strength += getArmorValue(chestplate);
        if (leggings != null) strength += getArmorValue(leggings);
        if (boots != null) strength += getArmorValue(boots);

        return strength;
    }

    /**
     * Get armor value based on material
     */
    private double getArmorValue(org.bukkit.inventory.ItemStack item) {
        Material type = item.getType();
        if (type.toString().contains("NETHERITE")) return 5.0;
        if (type.toString().contains("DIAMOND")) return 4.0;
        if (type.toString().contains("IRON")) return 3.0;
        if (type.toString().contains("CHAINMAIL")) return 2.5;
        if (type.toString().contains("GOLD")) return 2.0;
        if (type.toString().contains("LEATHER")) return 1.0;
        return 0.0;
    }

    /**
     * Find a good location for ambushing
     */
    private Location findAmbushLocation(Location raiderLoc, Location playerLoc) {
        // Calculate directional vector from player to raider
        Vector direction = raiderLoc.toVector().subtract(playerLoc.toVector()).normalize();

        // Position ambush point behind the raider from player perspective
        Vector offset = direction.multiply(10); // 10 blocks behind raider
        Location ambushLoc = raiderLoc.clone().add(offset);

        // Ensure the ambush location is valid
        ambushLoc.setY(ambushLoc.getWorld().getHighestBlockYAt(ambushLoc) + 1);

        return ambushLoc;
    }

    /**
     * Direct specialized stealers to target chests
     */
    private void directStealers(RaidSquad squad, ActiveRaid raid) {
        List<UUID> stealers = getSquadMembersByRole(squad, RaiderRole.STEALER);
        if (stealers.isEmpty()) return;

        // Get potential chest locations
        List<Location> chestLocations = getChestLocations(raid);
        if (chestLocations.isEmpty()) return;

        // Assign different chests to different stealers when possible
        int chestIndex = 0;
        for (UUID stealerId : stealers) {
            Entity entity = plugin.getServer().getEntity(stealerId);
            if (!(entity instanceof LivingEntity)) continue;

            LivingEntity stealer = (LivingEntity) entity;

            // Get next chest location (wrapping around if needed)
            Location targetChest = chestLocations.get(chestIndex % chestLocations.size());
            chestIndex++;

            // Direct stealer to the chest
            coordinationManager.sharePointOfInterest(stealer, targetChest, PointOfInterestType.CHEST, STEALER_MOVEMENT_SPEED);

            // Set special metadata for increased theft efficiency
            stealer.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "specialized_stealer"),
                org.bukkit.persistence.PersistentDataType.BYTE,
                (byte) 1
            );
        }
    }

    /**
     * Direct specialized miners to target valuable blocks
     */
    private void directMiners(RaidSquad squad, ActiveRaid raid) {
        List<UUID> miners = getSquadMembersByRole(squad, RaiderRole.MINER);
        if (miners.isEmpty()) return;

        // Get potential valuable block locations
        List<Location> blockLocations = getValuableBlockLocations(raid);
        if (blockLocations.isEmpty()) return;

        // Assign different blocks to different miners when possible
        int blockIndex = 0;
        for (UUID minerId : miners) {
            Entity entity = plugin.getServer().getEntity(minerId);
            if (!(entity instanceof LivingEntity)) continue;

            LivingEntity miner = (LivingEntity) entity;

            // Get next block location (wrapping around if needed)
            Location targetBlock = blockLocations.get(blockIndex % blockLocations.size());
            blockIndex++;

            // Direct miner to the block
            coordinationManager.sharePointOfInterest(miner, targetBlock, PointOfInterestType.VALUABLE_BLOCK, MINER_MOVEMENT_SPEED);

            // Set special metadata for increased mining efficiency
            miner.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "specialized_miner"),
                org.bukkit.persistence.PersistentDataType.BYTE,
                (byte) 1
            );
        }
    }

    /**
     * Get squad members with specific role
     */
    private List<UUID> getSquadMembersByRole(RaidSquad squad, RaiderRole role) {
        List<UUID> members = new ArrayList<>();

        for (Map.Entry<UUID, RaiderRole> entry : squad.getMembers().entrySet()) {
            if (entry.getValue() == role) {
                members.add(entry.getKey());
            }
        }

        return members;
    }

    /**
     * Get chest locations from raid metadata or find them if needed
     */
    private List<Location> getChestLocations(ActiveRaid raid) {
        Object chestLocationsObj = raid.getMetadata("chest_locations");
        if (chestLocationsObj instanceof List) {
            List<?> chestList = (List<?>) chestLocationsObj;
            if (!chestList.isEmpty() && chestList.get(0) instanceof Location) {
                @SuppressWarnings("unchecked")
                List<Location> locations = (List<Location>) chestList;
                return locations;
            }
        }

        // No cached chest locations, find them now
        String townName = raid.getTownName();
        com.palmergames.bukkit.towny.object.Town town = plugin.getTownyHandler().getTownByName(townName);
        if (town != null) {
            List<Location> chests = plugin.getStealingManager().findTownChests(town, 20);
            raid.setMetadata("chest_locations", chests);
            return chests;
        }

        return new ArrayList<>();
    }

    /**
     * Get valuable block locations from raid metadata or find them
     */
    private List<Location> getValuableBlockLocations(ActiveRaid raid) {
        Object blockLocationsObj = raid.getMetadata("valuable_blocks");
        if (blockLocationsObj instanceof List) {
            List<?> blockList = (List<?>) blockLocationsObj;
            if (!blockList.isEmpty() && blockList.get(0) instanceof Location) {
                @SuppressWarnings("unchecked")
                List<Location> locations = (List<Location>) blockList;
                return locations;
            }
        }

        // No cached block locations, find them now
        List<Location> valuableBlocks = new ArrayList<>();
        Location raidLocation = raid.getLocation();
        if (raidLocation != null) {
            // Search for valuable blocks
            Set<Material> stealableBlocks = plugin.getConfigManager().getStealableBlocks();
            int radius = 50; // Search radius

            for (int x = -radius; x <= radius; x += 5) { // Skip every 5 blocks for performance
                for (int y = -10; y <= 10; y += 2) {
                    for (int z = -radius; z <= radius; z += 5) {
                        Location checkLoc = raidLocation.clone().add(x, y, z);
                        Material blockType = checkLoc.getBlock().getType();

                        if (stealableBlocks.contains(blockType)) {
                            valuableBlocks.add(checkLoc);
                            if (valuableBlocks.size() >= 30) break; // Limit to 30 blocks
                        }
                    }
                    if (valuableBlocks.size() >= 30) break;
                }
                if (valuableBlocks.size() >= 30) break;
            }

            // Cache the found blocks
            raid.setMetadata("valuable_blocks", valuableBlocks);
        }

        return valuableBlocks;
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