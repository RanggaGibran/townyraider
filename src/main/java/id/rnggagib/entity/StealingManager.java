package id.rnggagib.entity;

import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;
import id.rnggagib.raid.RaidManager;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.Particle;
import org.bukkit.Sound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

// Add import for Town and TownBlock (and World if needed)
import org.bukkit.World;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;

public class StealingManager {
    private final TownyRaider plugin;
    private final Random random = new Random();
    private final Map<UUID, Integer> raiderThefts = new HashMap<>();

    public StealingManager(TownyRaider plugin) {
        this.plugin = plugin;
    }

    public void startStealingTasks() {
        // Task to direct zombies toward chests
        new BukkitRunnable() {
            @Override
            public void run() {
                for (ActiveRaid raid : plugin.getRaidManager().getActiveRaids()) {
                    for (UUID entityId : raid.getRaiderEntities()) {
                        Entity entity = findEntityByUuid(entityId);
                        if (entity instanceof Zombie && plugin.getRaiderEntityManager().isRaiderZombie(entity)) {
                            // Direction finding happens less frequently to avoid too much CPU usage
                            if (Math.random() < 0.3) { // 30% chance each time
                                directZombieTowardChest((Zombie) entity, raid);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 40L); // Every 2 seconds
        
        // Original task for opportunistic stealing
        new BukkitRunnable() {
            @Override
            public void run() {
                for (ActiveRaid raid : plugin.getRaidManager().getActiveRaids()) {
                    for (UUID entityId : raid.getRaiderEntities()) {
                        Entity entity = findEntityByUuid(entityId);
                        if (entity instanceof Zombie && plugin.getRaiderEntityManager().isRaiderZombie(entity)) {
                            // Only attempt to steal if the zombie isn't already fleeing
                            NamespacedKey fleeingKey = new NamespacedKey(plugin, "fleeing");
                            if (!entity.getPersistentDataContainer().has(fleeingKey, PersistentDataType.BYTE)) {
                                attemptToSteal((Zombie) entity, raid);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L * plugin.getConfigManager().getMobConfig("baby-zombie").getInt("steal-delay", 20));
    }

    private void attemptToSteal(Zombie zombie, ActiveRaid raid) {
        if (zombie == null || !zombie.isValid() || zombie.isDead()) {
            return;
        }

        int maxSteals = plugin.getConfigManager().getMobConfig("baby-zombie").getInt("max-steals", 5);
        int currentThefts = raiderThefts.getOrDefault(zombie.getUniqueId(), 0);
        
        if (currentThefts >= maxSteals) {
            return;
        }
        
        Location zombieLocation = zombie.getLocation();
        
        if (!plugin.getTownyHandler().isLocationInTown(zombieLocation, 
                plugin.getTownyHandler().getTownByName(raid.getTownName()))) {
            return;
        }
        
        Block targetBlock = findNearbyValuableBlock(zombieLocation, 5);
        if (targetBlock != null) {
            stealBlock(zombie, targetBlock, raid);
            return;
        }
        
        Chest targetChest = findNearbyChest(zombieLocation, 5);
        if (targetChest != null && plugin.getConfigManager().isChestStealingEnabled()) {
            stealFromChest(zombie, targetChest, raid);
        }
    }

    private Block findNearbyValuableBlock(Location center, int radius) {
        Set<Material> stealableBlocks = plugin.getConfigManager().getStealableBlocks();
        List<Block> valuableBlocks = new ArrayList<>();
        
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = center.getWorld().getBlockAt(
                            center.getBlockX() + x, 
                            center.getBlockY() + y, 
                            center.getBlockZ() + z);
                    
                    if (stealableBlocks.contains(block.getType())) {
                        valuableBlocks.add(block);
                    }
                }
            }
        }
        
        if (valuableBlocks.isEmpty()) {
            return null;
        }
        
        return valuableBlocks.get(random.nextInt(valuableBlocks.size()));
    }

    private Chest findNearbyChest(Location center, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = center.getWorld().getBlockAt(
                            center.getBlockX() + x, 
                            center.getBlockY() + y, 
                            center.getBlockZ() + z);
                    
                    BlockState state = block.getState();
                    if (state instanceof Chest) {
                        return (Chest) state;
                    }
                }
            }
        }
        
        return null;
    }

    private void stealBlock(Zombie zombie, Block block, ActiveRaid raid) {
        Material type = block.getType();
        
        int currentThefts = raiderThefts.getOrDefault(zombie.getUniqueId(), 0);
        raiderThefts.put(zombie.getUniqueId(), currentThefts + 1);
        
        raid.incrementStolenItems(1);
        
        // Show visual effects before changing the block
        plugin.getVisualEffectsManager().showStealEffects(block.getLocation());
        
        block.setType(Material.AIR);
        
        // Update raid progress
        plugin.getVisualEffectsManager().updateRaidProgress(raid);
        
        plugin.getLogger().info("Raider zombie stole " + type.name() + " during raid " + raid.getId());
    }

    private void stealFromChest(Zombie zombie, Chest chest, ActiveRaid raid) {
        Set<Material> stealableItems = plugin.getConfigManager().getStealableItems();
        int maxItemsPerCategory = plugin.getConfigManager().getMaxItemsPerCategory();
        
        // First, animate chest opening
        BlockData chestData = chest.getBlock().getBlockData();
        if (chestData instanceof org.bukkit.block.data.type.Chest) {
            // Play chest open sound
            chest.getWorld().playSound(chest.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
        }
        
        // Schedule task to steal items one by one with delay
        new BukkitRunnable() {
            private List<ItemStack> itemsToSteal = new ArrayList<>();
            private int currentIndex = 0;
            
            @Override
            public void run() {
                // On first run, identify all items to steal
                if (currentIndex == 0) {
                    for (Material targetMaterial : stealableItems) {
                        int itemsStolen = 0;
                        
                        for (int i = 0; i < chest.getInventory().getSize() && itemsStolen < maxItemsPerCategory; i++) {
                            ItemStack item = chest.getInventory().getItem(i);
                            if (item != null && item.getType() == targetMaterial) {
                                int amountToSteal = Math.min(item.getAmount(), maxItemsPerCategory - itemsStolen);
                                
                                if (amountToSteal > 0) {
                                    // Add to items to steal
                                    ItemStack stealItem = item.clone();
                                    stealItem.setAmount(amountToSteal);
                                    itemsToSteal.add(stealItem);
                                    
                                    // Remove from original stack
                                    item.setAmount(item.getAmount() - amountToSteal);
                                    if (item.getAmount() <= 0) {
                                        chest.getInventory().setItem(i, null);
                                    }
                                    
                                    itemsStolen += amountToSteal;
                                }
                            }
                        }
                    }
                }
                
                // Process current item
                if (!itemsToSteal.isEmpty() && currentIndex < itemsToSteal.size()) {
                    ItemStack currentItem = itemsToSteal.get(currentIndex);
                    
                    // Create visual effect of item being stolen
                    Location effectLocation = chest.getLocation().add(0.5, 1.0, 0.5);
                    chest.getWorld().spawnParticle(
                        Particle.ITEM_CRACK, 
                        effectLocation,
                        10,  // amount
                        0.3, 0.3, 0.3,  // offset
                        0.05,  // speed
                        currentItem  // data - shows particles of the actual item
                    );
                    
                    // Draw line of particles from chest to zombie
                    Vector direction = zombie.getLocation().add(0, 0.5, 0).subtract(effectLocation).toVector().normalize();
                    double distance = zombie.getLocation().distance(effectLocation);
                    for (double d = 0.5; d < distance; d += 0.5) {
                        Location particleLocation = effectLocation.clone().add(direction.clone().multiply(d));
                        chest.getWorld().spawnParticle(
                            Particle.ITEM_CRACK, 
                            particleLocation,
                            1,
                            0, 0, 0,
                            0,
                            currentItem
                        );
                    }
                    
                    // Play stealing sound
                    chest.getWorld().playSound(effectLocation, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.0f);
                    
                    // Update raid counter
                    raid.incrementStolenItems(1);
                    plugin.getVisualEffectsManager().updateRaidProgress(raid);
                    
                    currentIndex++;
                    
                    if (currentIndex >= itemsToSteal.size()) {
                        // Last item stolen, close chest after delay
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                // Close chest animation
                                BlockState state = chest.getBlock().getState();
                                if (state instanceof Chest) {
                                    Chest chestState = (Chest) state;
                                    chestState.getBlockInventory(); // Access inventory to ensure state is loaded
                                    chestState.setCustomName(chest.getCustomName()); // Retain custom name if any
                                    chestState.update(true, true); // Update state
                                    // Play chest close sound
                                    chest.getWorld().playSound(chest.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.5f, 1.0f);
                                }
                            }
                        }.runTaskLater(plugin, 10);
                        
                        // Record theft
                        int currentThefts = raiderThefts.getOrDefault(zombie.getUniqueId(), 0);
                        raiderThefts.put(zombie.getUniqueId(), currentThefts + 1);
                        
                        // Log information
                        plugin.getLogger().info("Raider zombie stole " + itemsToSteal.size() + " items from chest during raid " + raid.getId());
                        
                        // Make zombie flee after successful stealing
                        initiateZombieEscape(zombie, raid);
                        
                        // Cancel this task
                        this.cancel();
                    }
                } else {
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 10L); // Run every half second (10 ticks)
    }

    private Entity findEntityByUuid(UUID entityId) {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getUniqueId().equals(entityId)) {
                    return entity;
                }
            }
        }
        return null;
    }

    public void resetTheftCount(UUID entityId) {
        raiderThefts.remove(entityId);
    }

    public void handleBlockStealing(Zombie zombie, Block block, ActiveRaid raid) {
        if (!plugin.getRaiderEntityManager().isRaiderZombie(zombie)) return;
        
        Material type = block.getType();
        if (!plugin.getConfigManager().getStealableBlocks().contains(type)) return;
        
        int currentThefts = raiderThefts.getOrDefault(zombie.getUniqueId(), 0);
        int maxTheftsPerRaider = plugin.getConfigManager().getMobConfig("baby-zombie").getInt("max-thefts", 5);
        
        if (currentThefts >= maxTheftsPerRaider) {
            return;
        }
        
        raiderThefts.put(zombie.getUniqueId(), currentThefts + 1);
        raid.incrementStolenItems(1);
        
        plugin.getVisualEffectsManager().showStealEffects(block.getLocation());
        
        block.setType(Material.AIR);
        
        plugin.getVisualEffectsManager().updateRaidProgress(raid);
        
        plugin.getLogger().info("Raider zombie stole " + type.name() + " during raid " + raid.getId());
    }

    public void attemptToStealFromChest(Zombie zombie, Chest chest, ActiveRaid raid) {
        if (zombie == null || !zombie.isValid() || zombie.isDead()) {
            return;
        }

        int maxSteals = plugin.getConfigManager().getMobConfig("baby-zombie").getInt("max-steals", 5);
        int currentThefts = raiderThefts.getOrDefault(zombie.getUniqueId(), 0);
        
        if (currentThefts >= maxSteals) {
            return;
        }
        
        if (!plugin.getTownyHandler().isLocationInTown(chest.getLocation(), 
                plugin.getTownyHandler().getTownByName(raid.getTownName()))) {
            return;
        }
        
        stealFromChest(zombie, chest, raid);
    }

    /**
     * Make the zombie flee after successful stealing
     */
    private void initiateZombieEscape(Zombie zombie, ActiveRaid raid) {
        if (zombie == null || !zombie.isValid() || zombie.isDead()) {
            return;
        }

        // Set a "fleeing" flag on the zombie's PersistentDataContainer
        NamespacedKey fleeingKey = new NamespacedKey(plugin, "fleeing");
        zombie.getPersistentDataContainer().set(fleeingKey, PersistentDataType.BYTE, (byte)1);
        
        // Find escape location - outside the town
        Location escapeLocation = findEscapeLocation(zombie, raid);
        if (escapeLocation == null) {
            // If no escape location, just make the zombie move faster randomly
            zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SPEED, 
                200, // 10 seconds
                2, // Speed III
                false, 
                true)
            );
            return;
        }
        
        // Add visual effect to show the zombie is escaping
        zombie.getWorld().spawnParticle(
            Particle.SMOKE_NORMAL, 
            zombie.getLocation().add(0, 1, 0),
            20, 0.2, 0.5, 0.2, 0.05
        );
        
        // Make zombie move faster
        zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(
            org.bukkit.potion.PotionEffectType.SPEED, 
            400, // 20 seconds
            2, // Speed III
            false, 
            true)
        );
        
        // Clear zombie's target
        if (zombie instanceof org.bukkit.entity.Mob) {
            ((org.bukkit.entity.Mob)zombie).setTarget(null);
        }
        
        // Start a task to make zombie move towards escape location
        new BukkitRunnable() {
            private int counter = 0;
            private final int maxTicks = 200; // 10 seconds
            
            @Override
            public void run() {
                counter++;
                
                if (!zombie.isValid() || zombie.isDead() || counter >= maxTicks) {
                    this.cancel();
                    return;
                }
                
                // Move zombie towards escape location
                Vector direction = escapeLocation.clone().subtract(zombie.getLocation()).toVector().normalize();
                zombie.setVelocity(direction.multiply(0.5)); // Faster escape movement
                
                // Add some particle trail
                zombie.getWorld().spawnParticle(
                    Particle.CLOUD, 
                    zombie.getLocation().add(0, 0.5, 0),
                    3, 0.1, 0.1, 0.1, 0.01
                );
                
                // If zombie reached escape point or is far from town, consider it escaped
                if (zombie.getLocation().distance(escapeLocation) < 3.0 || 
                    !plugin.getTownyHandler().isLocationInTown(zombie.getLocation(), 
                        plugin.getTownyHandler().getTownByName(raid.getTownName()))) {
                    
                    // Add successful escape effect
                    zombie.getWorld().spawnParticle(
                        Particle.PORTAL, 
                        zombie.getLocation(),
                        50, 0.5, 1, 0.5, 0.1
                    );
                    
                    // Cancel this task
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L); // Run every tick for smoother movement
    }

    /**
     * Find a suitable escape location outside the town
     */
    private Location findEscapeLocation(Zombie zombie, ActiveRaid raid) {
        Town town = plugin.getTownyHandler().getTownByName(raid.getTownName());
        if (town == null) return null;
        
        List<Location> borderPoints = plugin.getTownyHandler().getTownBorderPoints(town, 1);
        if (borderPoints.isEmpty()) {
            // Fallback: just move away from town center
            Location townCenter = plugin.getTownyHandler().getTownSpawnLocation(town);
            if (townCenter != null) {
                Vector direction = zombie.getLocation().clone().subtract(townCenter).toVector().normalize();
                return zombie.getLocation().add(direction.multiply(30)); // 30 blocks away
            }
            return null;
        }
        
        // Find closest border point
        Location closest = null;
        double closestDistance = Double.MAX_VALUE;
        
        for (Location border : borderPoints) {
            double distance = border.distance(zombie.getLocation());
            if (distance < closestDistance) {
                closest = border;
                closestDistance = distance;
            }
        }
        
        // Move slightly beyond border
        if (closest != null) {
            Vector direction = closest.clone().subtract(zombie.getLocation()).toVector().normalize();
            return closest.clone().add(direction.multiply(5)); // 5 blocks outside border
        }
        
        return null;
    }

    /**
     * Finds all chests in a town - call this at the start of a raid
     */
    public List<Location> findTownChests(Town town, int maxChests) {
        List<Location> chestLocations = new ArrayList<>();
        
        if (town == null) return chestLocations;
        
        // Get town bounds
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        
        for (TownBlock townBlock : town.getTownBlocks()) {
            int x = townBlock.getX();
            int z = townBlock.getZ();
            
            if (x < minX) minX = x;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (z > maxZ) maxZ = z;
        }
        
        // Convert to block coordinates
        minX = minX * 16;
        minZ = minZ * 16;
        maxX = (maxX + 1) * 16 - 1;
        maxZ = (maxZ + 1) * 16 - 1;
        
        // Iterate through town blocks looking for chests
        World world = town.getWorld();
        Random random = new Random();
        
        // Sample some random locations instead of checking every block
        int samples = Math.min((maxX - minX) * (maxZ - minZ) / 200, 500); // Limit samples
        
        for (int i = 0; i < samples && chestLocations.size() < maxChests; i++) {
            int x = minX + random.nextInt(maxX - minX + 1);
            int z = minZ + random.nextInt(maxZ - minZ + 1);
            
            // Find the highest block at this x,z
            int y = world.getHighestBlockYAt(x, z);
            
            // Check a column of blocks
            for (int dy = -5; dy <= 5; dy++) {
                Location loc = new Location(world, x, y + dy, z);
                
                if (!plugin.getTownyHandler().isLocationInTown(loc, town)) {
                    continue;
                }
                
                Block block = loc.getBlock();
                if (block.getState() instanceof Chest) {
                    chestLocations.add(loc);
                    break;
                }
            }
        }
        
        return chestLocations;
    }

    /**
     * Update the zombie ai to direct it toward the closest chest
     */
    public void directZombieTowardChest(Zombie zombie, ActiveRaid raid) {
        if (zombie == null || !zombie.isValid() || zombie.isDead()) {
            return;
        }
        
        // Check if zombie is already fleeing
        NamespacedKey fleeingKey = new NamespacedKey(plugin, "fleeing");
        if (zombie.getPersistentDataContainer().has(fleeingKey, PersistentDataType.BYTE)) {
            return;
        }
        
        Town town = plugin.getTownyHandler().getTownByName(raid.getTownName());
        if (town == null) return;
        
        // Get or find chest locations
        List<Location> chestLocations;
        if (!raid.hasMetadata("chest_locations")) {
            chestLocations = findTownChests(town, 20);
            raid.setMetadata("chest_locations", chestLocations);
        } else {
            chestLocations = (List<Location>) raid.getMetadata("chest_locations");
        }
        
        if (chestLocations.isEmpty()) {
            return;
        }
        
        // Find closest chest
        Location zombieLocation = zombie.getLocation();
        Location closest = null;
        double closestDistance = Double.MAX_VALUE;
        
        for (Location chestLoc : chestLocations) {
            double distance = chestLoc.distance(zombieLocation);
            if (distance < closestDistance) {
                closest = chestLoc;
                closestDistance = distance;
            }
        }
        
        if (closest == null) {
            return;
        }
        
        // Create a final copy of closest
        final Location finalClosest = closest;

        // Store target in zombie's metadata
        NamespacedKey targetChestKey = new NamespacedKey(plugin, "target_chest");
        zombie.getPersistentDataContainer().set(
            targetChestKey, 
            PersistentDataType.STRING,
            finalClosest.getWorld().getName() + "," + finalClosest.getBlockX() + "," + finalClosest.getBlockY() + "," + finalClosest.getBlockZ()
        );
        
        // Make zombie face and move toward chest
        if (zombie instanceof org.bukkit.entity.Mob) {
            ((org.bukkit.entity.Mob)zombie).setTarget(null); // Clear any entity target
            
            // Start a task to navigate the zombie to the chest
            new BukkitRunnable() {
                private int counter = 0;
                private final int maxTicks = 200; // Stop after 10 seconds if not reached
                
                @Override
                public void run() {
                    counter++;
                    
                    if (!zombie.isValid() || zombie.isDead() || counter >= maxTicks) {
                        this.cancel();
                        return;
                    }
                    
                    // Check if zombie is now fleeing
                    if (zombie.getPersistentDataContainer().has(fleeingKey, PersistentDataType.BYTE)) {
                        this.cancel();
                        return;
                    }
                    
                    // Look for a chest in immediate vicinity
                    Chest nearbyChest = findNearbyChest(zombie.getLocation(), 2);
                    if (nearbyChest != null) {
                        // We're near a chest! Stop navigation and attempt to steal
                        this.cancel();
                        attemptToStealFromChest(zombie, nearbyChest, raid);
                        return;
                    }
                    
                    // Calculate path movement (use finalClosest instead of closest)
                    Vector pathVector = finalClosest.clone().add(0.5, 0, 0.5).subtract(zombie.getLocation()).toVector();
                    if (pathVector.length() > 10) {
                        // If too far, just teleport closer to avoid getting stuck
                        // This helps zombies not get stuck on terrain
                        if (counter % 40 == 0) { // Every 2 seconds
                            Vector moveDir = pathVector.normalize().multiply(5);
                            Location newLoc = zombie.getLocation().add(moveDir);
                            
                            // Make sure the new location is valid
                            if (newLoc.getBlock().getType().isAir() && 
                                newLoc.clone().add(0, 1, 0).getBlock().getType().isAir()) {
                                zombie.teleport(newLoc);
                            }
                        }
                    }
                    
                    // Adjust zombie pathing
                    pathVector.normalize().multiply(0.4); // Control speed
                    zombie.setVelocity(pathVector);
                }
            }.runTaskTimer(plugin, 0L, 5L); // Run every 1/4 second
        }
    }
}