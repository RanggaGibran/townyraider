package id.rnggagib.entity;

import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;
import id.rnggagib.raid.RaidManager;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class StealingManager {
    private final TownyRaider plugin;
    private final Random random = new Random();
    private final Map<UUID, Integer> raiderThefts = new HashMap<>();

    public StealingManager(TownyRaider plugin) {
        this.plugin = plugin;
    }

    public void startStealingTasks() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (ActiveRaid raid : plugin.getRaidManager().getActiveRaids()) {
                    for (UUID entityId : raid.getRaiderEntities()) {
                        Entity entity = findEntityByUuid(entityId);
                        if (entity instanceof Zombie && plugin.getRaiderEntityManager().isRaiderZombie(entity)) {
                            attemptToSteal((Zombie) entity, raid);
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
        int stolenCount = 0;
        
        for (Material targetMaterial : stealableItems) {
            int itemsStolen = 0;
            
            for (int i = 0; i < chest.getInventory().getSize() && itemsStolen < maxItemsPerCategory; i++) {
                ItemStack item = chest.getInventory().getItem(i);
                if (item != null && item.getType() == targetMaterial) {
                    int amountToSteal = Math.min(item.getAmount(), maxItemsPerCategory - itemsStolen);
                    
                    if (amountToSteal > 0) {
                        item.setAmount(item.getAmount() - amountToSteal);
                        if (item.getAmount() <= 0) {
                            chest.getInventory().setItem(i, null);
                        }
                        
                        itemsStolen += amountToSteal;
                        stolenCount += amountToSteal;
                    }
                }
            }
        }
        
        if (stolenCount > 0) {
            int currentThefts = raiderThefts.getOrDefault(zombie.getUniqueId(), 0);
            raiderThefts.put(zombie.getUniqueId(), currentThefts + 1);
            
            raid.incrementStolenItems(stolenCount);
            
            // Show visual effects
            plugin.getVisualEffectsManager().showStealEffects(chest.getLocation());
            
            // Update raid progress
            plugin.getVisualEffectsManager().updateRaidProgress(raid);
            
            plugin.getLogger().info("Raider zombie stole " + stolenCount + " items from chest during raid " + raid.getId());
        }
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
}