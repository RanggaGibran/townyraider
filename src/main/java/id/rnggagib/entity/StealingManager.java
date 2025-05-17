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
import org.bukkit.entity.LivingEntity;
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
import java.util.AbstractMap;

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
                for (ActiveRaid raid : plugin.getRaidManager().getActiveRaids().values()) {
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
                for (ActiveRaid raid : plugin.getRaidManager().getActiveRaids().values()) {
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
        int initialMaxItemsPerCategory = plugin.getConfigManager().getMaxItemsPerCategory();
        
        // Get zombie intelligence level
        int initialIntelligence = 1; // Default
        NamespacedKey intelligenceKey = new NamespacedKey(plugin, "intelligence");
        if (zombie.getPersistentDataContainer().has(intelligenceKey, PersistentDataType.INTEGER)) {
            initialIntelligence = zombie.getPersistentDataContainer().get(intelligenceKey, PersistentDataType.INTEGER);
        }
        
        // Create final copies that can be used in the inner class
        final int intelligence = initialIntelligence;
        final int maxItemsPerCategory = initialMaxItemsPerCategory + intelligence;
        
        // First, animate chest opening
        BlockData chestData = chest.getBlock().getBlockData();
        if (chestData instanceof org.bukkit.block.data.type.Chest) {
            // Play chest open sound
            chest.getWorld().playSound(chest.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
        }
        
        // Animate zombie looking into chest
        Location zombieLookAt = chest.getLocation().clone().add(0.5, 0.5, 0.5);
        Location zombieLocation = zombie.getLocation().clone();
        zombieLocation.setDirection(zombieLookAt.subtract(zombieLocation).toVector());
        zombie.teleport(zombieLocation);
        
        // Make zombie "rummage" through chest
        zombie.swingMainHand();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (zombie.isValid() && !zombie.isDead()) {
                    zombie.swingOffHand();
                }
            }
        }.runTaskLater(plugin, 10L);
        
        // Schedule task to steal items one by one with delay
        new BukkitRunnable() {
            private List<ItemStack> itemsToSteal = new ArrayList<>();
            private int currentIndex = 0;
            
            @Override
            public void run() {
                // On first run, identify all items to steal
                if (currentIndex == 0) {
                    // Smarter zombies prioritize more valuable items
                    Map<Material, Integer> valueMap = new HashMap<>();
                    for (Material mat : stealableItems) {
                        // Assign value based on material
                        int value = 1;
                        if (mat.name().contains("DIAMOND")) value = 5;
                        else if (mat.name().contains("NETHERITE")) value = 6;
                        else if (mat.name().contains("EMERALD")) value = 4;
                        else if (mat.name().contains("GOLD")) value = 3;
                        else if (mat.name().contains("IRON")) value = 2;
                        
                        valueMap.put(mat, value);
                    }
                    
                    // Find all stealable items in chest
                    List<Map.Entry<Integer, ItemStack>> potentialItems = new ArrayList<>();
                    for (int i = 0; i < chest.getInventory().getSize(); i++) {
                        ItemStack item = chest.getInventory().getItem(i);
                        if (item != null && stealableItems.contains(item.getType())) {
                            potentialItems.add(new AbstractMap.SimpleEntry<>(i, item));
                        }
                    }
                    
                    // Sort by value for intelligent zombies
                    if (intelligence > 1) {
                        potentialItems.sort((entry1, entry2) -> {
                            int value1 = valueMap.getOrDefault(entry1.getValue().getType(), 1);
                            int value2 = valueMap.getOrDefault(entry2.getValue().getType(), 1);
                            return value2 - value1; // Higher value first
                        });
                    }
                    
                    // Process items by category (with limit)
                    Map<Material, Integer> itemsStolen = new HashMap<>();
                    for (Map.Entry<Integer, ItemStack> entry : potentialItems) {
                        int slotIndex = entry.getKey();
                        ItemStack item = entry.getValue();
                        Material material = item.getType();
                        
                        int stolenSoFar = itemsStolen.getOrDefault(material, 0);
                        if (stolenSoFar >= maxItemsPerCategory) continue;
                        
                        int amountToSteal = Math.min(item.getAmount(), maxItemsPerCategory - stolenSoFar);
                        
                        if (amountToSteal > 0) {
                            // Add to items to steal
                            ItemStack stealItem = item.clone();
                            stealItem.setAmount(amountToSteal);
                            itemsToSteal.add(stealItem);
                            
                            // Remove from original stack
                            item.setAmount(item.getAmount() - amountToSteal);
                            if (item.getAmount() <= 0) {
                                chest.getInventory().setItem(slotIndex, null);
                            }
                            
                            itemsStolen.put(material, stolenSoFar + amountToSteal);
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
                        15,  // more particles
                        0.3, 0.3, 0.3,  // offset
                        0.05,  // speed
                        currentItem  // data - shows particles of the actual item
                    );
                    
                    // Draw line of particles from chest to zombie
                    Vector direction = zombie.getLocation().add(0, 0.5, 0).subtract(effectLocation).toVector().normalize();
                    double distance = zombie.getLocation().distance(effectLocation);
                    for (double d = 0.5; d < distance; d += 0.3) {
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
                    float pitch = 1.0f + (currentIndex * 0.1f); // Vary pitch for each item
                    chest.getWorld().playSound(effectLocation, Sound.ENTITY_ITEM_PICKUP, 0.7f, pitch);
                    
                    // Show happy particles from zombie
                    zombie.getWorld().spawnParticle(
                        Particle.VILLAGER_HAPPY,
                        zombie.getLocation().add(0, 1.0, 0),
                        5, 0.3, 0.3, 0.3, 0.05
                    );
                    
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
                        
                        // Make zombie flee with special animation based on intelligence
                        zombie.swingMainHand();
                        zombie.getWorld().spawnParticle(
                            Particle.CLOUD,
                            zombie.getLocation(),
                            10, 0.3, 0.5, 0.3, 0.05
                        );
                        
                        // Make zombie laugh by name-tagging temporarily
                        String originalName = zombie.getCustomName();
                        String laughEmote = intelligence > 2 ? "<dark_red>Hehehehe!" : "<dark_red>Hehe!";
                        
                        zombie.setCustomName(laughEmote);
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                if (zombie.isValid() && !zombie.isDead()) {
                                    zombie.setCustomName(originalName);
                                }
                            }
                        }.runTaskLater(plugin, 30L);
                        
                        // Make zombie flee after stealing
                        initiateZombieEscape(zombie, raid);
                        
                        // Cancel this task
                        this.cancel();
                    }
                } else {
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 8L); // Run slightly faster
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
        
        // Specialized miners can mine more blocks
        if (isSpecializedMiner(zombie)) {
            maxTheftsPerRaider = maxTheftsPerRaider * 2; // Double the limit for specialized miners
        }
        
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
        
        // Specialized stealers can steal more items
        if (isSpecializedStealer(zombie)) {
            maxSteals = maxSteals * 2; // Double the limit for specialized stealers
        }
        
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
     * Update the zombie AI to direct it toward the closest chest
     * @return true if a chest was found and targeted
     */
    public boolean directZombieTowardChest(Zombie zombie, ActiveRaid raid) {
        if (zombie == null || !zombie.isValid() || zombie.isDead()) {
            return false;
        }
        
        // Check if zombie is already fleeing
        NamespacedKey fleeingKey = new NamespacedKey(plugin, "fleeing");
        if (zombie.getPersistentDataContainer().has(fleeingKey, PersistentDataType.BYTE)) {
            return false;
        }
        
        Town town = plugin.getTownyHandler().getTownByName(raid.getTownName());
        if (town == null) return false;
        
        // Get or find chest locations
        List<Location> chestLocations;
        if (!raid.hasMetadata("chest_locations")) {
            chestLocations = findTownChests(town, 20);
            raid.setMetadata("chest_locations", chestLocations);
        } else {
            chestLocations = (List<Location>) raid.getMetadata("chest_locations");
        }
        
        if (chestLocations.isEmpty()) {
            return false;
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
            return false;
        }
        
        // Rest of the method for creating pathfinding
        // ...
        
        return true;  // Successfully found and targeted a chest
    }

    // Add these methods to enhance stealing abilities for specialized roles

    /**
     * Check if an entity is a specialized stealer
     */
    public boolean isSpecializedStealer(Entity entity) {
        if (!(entity instanceof LivingEntity)) return false;
        
        NamespacedKey specializationKey = new NamespacedKey(plugin, "specialized_stealer");
        return ((LivingEntity)entity).getPersistentDataContainer()
            .has(specializationKey, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /**
     * Check if an entity is a specialized miner
     */
    public boolean isSpecializedMiner(Entity entity) {
        if (!(entity instanceof LivingEntity)) return false;
        
        NamespacedKey specializationKey = new NamespacedKey(plugin, "specialized_miner");
        return ((LivingEntity)entity).getPersistentDataContainer()
            .has(specializationKey, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    // Add a specialized method for miners to target high-value blocks specifically
    public void directMinerToValuableBlock(Zombie zombie, ActiveRaid raid) {
        if (!isSpecializedMiner(zombie)) return;
        
        // Define priority materials (from highest to lowest)
        Material[] priorityBlocks = {
            Material.NETHERITE_BLOCK,
            Material.DIAMOND_BLOCK,
            Material.EMERALD_BLOCK,
            Material.GOLD_BLOCK,
            Material.IRON_BLOCK,
            Material.LAPIS_BLOCK,
            Material.REDSTONE_BLOCK
        };
        
        // Search for these blocks specifically
        Block targetBlock = null;
        Location location = zombie.getLocation();
        int searchRadius = 30;
        
        // First try to find highest priority blocks
        for (Material material : priorityBlocks) {
            targetBlock = findSpecificBlockNearby(location, material, searchRadius);
            if (targetBlock != null) {
                break;
            }
        }
        
        // If none found, fall back to regular valuable blocks
        if (targetBlock == null) {
            targetBlock = findNearbyValuableBlock(location, searchRadius);
        }
        
        // If block found, set as target
        if (targetBlock != null) {
            // Apply movement behavior toward block
            plugin.getPathfindingManager().navigateTo((org.bukkit.entity.Mob)zombie, targetBlock.getLocation(), 1.1);
        }
    }

    // Helper method to find a specific block type
    private Block findSpecificBlockNearby(Location center, Material material, int radius) {
        for (int x = -radius; x <= radius; x += 3) {
            for (int y = -radius/2; y <= radius/2; y += 2) {
                for (int z = -radius; z <= radius; z += 3) {
                    Location checkLoc = center.clone().add(x, y, z);
                    Block block = checkLoc.getBlock();
                    
                    if (block.getType() == material) {
                        return block;
                    }
                }
            }
        }
        return null;
    }
}