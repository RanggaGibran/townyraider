package id.rnggagib.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyWorld;
import id.rnggagib.TownyRaider;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TownyHandler {
    private final TownyRaider plugin;
    private final TownyAPI townyAPI;
    private Economy economy;
    private final Map<String, LocalDateTime> townRaidCooldowns = new HashMap<>();

    public TownyHandler(TownyRaider plugin) {
        this.plugin = plugin;
        this.townyAPI = TownyAPI.getInstance();
        setupEconomy();
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        
        economy = rsp.getProvider();
        return economy != null;
    }

    /**
     * Get the economy provider
     */
    public Economy getEconomy() {
        return economy;
    }

    public List<Town> getEligibleTownsForRaid() {
        List<Town> eligibleTowns = new ArrayList<>();
        
        for (Town town : townyAPI.getTowns()) {
            if (isTownEligibleForRaid(town)) {
                eligibleTowns.add(town);
            }
        }
        
        return eligibleTowns;
    }
    
    public boolean isTownEligibleForRaid(Town town) {
        int minOnlineMembers = plugin.getConfigManager().getMinimumOnlineTownMembers();
        double minTownWealth = plugin.getConfigManager().getMinimumTownWealth();
        int minTownAgeDays = plugin.getConfigManager().getMinimumTownAgeDays();
        
        if (getOnlineTownMembers(town).size() < minOnlineMembers) {
            return false;
        }
        
        if (getTownWealth(town) < minTownWealth) {
            return false;
        }
        
        if (getTownAgeInDays(town) < minTownAgeDays) {
            return false;
        }
        
        if (isOnCooldown(town)) {
            return false;
        }
        
        return true;
    }
    
    public List<Player> getOnlineTownMembers(Town town) {
        List<Player> onlinePlayers = new ArrayList<>();
        
        for (Resident resident : town.getResidents()) {
            Player player = Bukkit.getPlayer(resident.getUUID());
            if (player != null && player.isOnline()) {
                onlinePlayers.add(player);
            }
        }
        
        return onlinePlayers;
    }
    
    public double getTownWealth(Town town) {
        if (economy != null) {
            return economy.getBalance(town.getAccount().getName());
        }
        return 0;
    }
    
    public int getTownAgeInDays(Town town) {
        try {
            // Towny returns registration time as a long timestamp
            long registeredTime = town.getRegistered();
            if (registeredTime > 0) {
                // Convert timestamp to LocalDateTime
                LocalDateTime regTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(registeredTime),
                    ZoneId.systemDefault()
                );
                return (int) ChronoUnit.DAYS.between(regTime, LocalDateTime.now());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error calculating town age: " + e.getMessage());
        }
        // Default to a value that will allow raids if there's an error
        return plugin.getConfigManager().getMinimumTownAgeDays();
    }
    
    public boolean isOnCooldown(Town town) {
        if (townRaidCooldowns.containsKey(town.getName())) {
            LocalDateTime cooldownEnd = townRaidCooldowns.get(town.getName());
            return LocalDateTime.now().isBefore(cooldownEnd);
        }
        return false;
    }
    
    public void putTownOnCooldown(Town town) {
        int cooldownHours = plugin.getConfigManager().getTownCooldown();
        LocalDateTime cooldownEnd = LocalDateTime.now().plusHours(cooldownHours);
        townRaidCooldowns.put(town.getName(), cooldownEnd);
    }
    
    public Location getTownSpawnLocation(Town town) {
        try {
            return town.getSpawn();
        } catch (Exception e) {
            return null;
        }
    }
    
    public List<Location> getTownBorderPoints(Town town, int buffer) {
        List<Location> borderPoints = new ArrayList<>();
        
        try {
            // Convert Collection to Set
            Set<TownBlock> townBlocks = new HashSet<>(town.getTownBlocks());
            Set<TownBlock> borderBlocks = findBorderBlocks(townBlocks);
            
            for (TownBlock block : borderBlocks) {
                World world = Bukkit.getWorld(block.getWorld().getName());
                if (world != null) {
                    int x = block.getX() * 16;
                    int z = block.getZ() * 16;
                    
                    Location center = new Location(world, x + 8, world.getHighestBlockYAt(x + 8, z + 8), z + 8);
                    borderPoints.add(center);
                }
            }
            
        } catch (Exception e) {
            plugin.getLogger().warning("Error getting town border points: " + e.getMessage());
        }
        
        return borderPoints;
    }
    
    private Set<TownBlock> findBorderBlocks(Set<TownBlock> townBlocks) {
        Set<TownBlock> borderBlocks = new HashSet<>();
        
        for (TownBlock block : townBlocks) {
            if (isBorderBlock(block, townBlocks)) {
                borderBlocks.add(block);
            }
        }
        
        return borderBlocks;
    }
    
    private boolean isBorderBlock(TownBlock block, Set<TownBlock> townBlocks) {
        int x = block.getX();
        int z = block.getZ();
        TownyWorld world = block.getWorld();
        
        try {
            TownBlock north = world.getTownBlock(x, z + 1);
            TownBlock east = world.getTownBlock(x + 1, z);
            TownBlock south = world.getTownBlock(x, z - 1);
            TownBlock west = world.getTownBlock(x - 1, z);
            
            boolean hasNonTownNeighbor = 
                (!townBlocks.contains(north) || !north.hasTown()) ||
                (!townBlocks.contains(east) || !east.hasTown()) ||
                (!townBlocks.contains(south) || !south.hasTown()) ||
                (!townBlocks.contains(west) || !west.hasTown());
                
            return hasNonTownNeighbor;
        } catch (NotRegisteredException e) {
            return true;
        }
    }
    
    public Location findSuitableRaidLocation(Town town) {
        List<Location> borderPoints = getTownBorderPoints(town, 1);
        if (borderPoints.isEmpty()) {
            return getTownSpawnLocation(town);
        }
        
        int randomIndex = (int) (Math.random() * borderPoints.size());
        return borderPoints.get(randomIndex);
    }
    
    public void notifyTownMembers(Town town, String messageKey, Map<String, String> placeholders) {
        List<Player> townMembers = getOnlineTownMembers(town);
        plugin.getMessageManager().sendToPlayers(townMembers, messageKey, placeholders);
        
        Sound sound = null;
        if (messageKey.equals("pre-raid-warning")) {
            sound = plugin.getConfigManager().getPreRaidSound();
        } else if (messageKey.equals("raid-start")) {
            sound = plugin.getConfigManager().getRaidStartSound();
        } else if (messageKey.equals("raid-end")) {
            sound = plugin.getConfigManager().getRaidEndSound();
        }
        
        if (sound != null) {
            for (Player player : townMembers) {
                plugin.getMessageManager().playSound(player, sound);
            }
        }
    }
    
    public Town getTownByName(String townName) {
        try {
            return townyAPI.getTown(townName);
        } catch (Exception e) {
            return null;
        }
    }
    
    public boolean isTownyEnabled() {
        return townyAPI != null && Bukkit.getPluginManager().isPluginEnabled("Towny");
    }
    
    public Town getTownAt(Location location) {
        return townyAPI.getTown(location);
    }
    
    public boolean isLocationInTown(Location location, Town town) {
        Town locationTown = getTownAt(location);
        return locationTown != null && locationTown.getName().equals(town.getName());
    }

    public Map<String, LocalDateTime> getTownRaidCooldowns() {
        return new HashMap<>(townRaidCooldowns);
    }

    public void setTownRaidCooldowns(Map<String, LocalDateTime> cooldowns) {
        townRaidCooldowns.clear();
        townRaidCooldowns.putAll(cooldowns);
    }

    /**
     * Get the town at a specific location
     * @param location the location
     * @return the town at the location, or null if there is no town
     */
    public Town getTownAtLocation(Location location) {
        if (location == null) {
            return null;
        }
        
        try {
            TownBlock townBlock = TownyAPI.getInstance().getTownBlock(location);
            if (townBlock != null) {
                return townBlock.getTown();
            }
        } catch (Exception e) {
            // Ignore - location is not in a town
        }
        
        return null;
    }

    /**
     * Check if a town is on raid cooldown
     * @param town the town to check
     * @return true if the town is on cooldown, false otherwise
     */
    public boolean isTownOnRaidCooldown(Town town) {
        return isOnCooldown(town);
    }

    /**
     * Get a random location within a town
     * @param town the town to get a location in
     * @return a random location in the town, or null if no suitable location is found
     */
    public Location getRandomLocationInTown(Town town) {
        try {
            // Get all town blocks for this town
            Set<TownBlock> townBlocks = new HashSet<>(town.getTownBlocks());
            if (townBlocks.isEmpty()) {
                return getTownSpawnLocation(town);
            }
            
            // Try multiple blocks in case some are not suitable
            List<TownBlock> blockList = new ArrayList<>(townBlocks);
            Collections.shuffle(blockList); // Randomize the list
            
            for (TownBlock townBlock : blockList) {
                World world = Bukkit.getWorld(townBlock.getWorld().getName());
                if (world == null) continue;
                
                // Calculate block center coordinates
                int blockX = townBlock.getX() * 16 + 8; // Center of the block
                int blockZ = townBlock.getZ() * 16 + 8;
                
                // Get the highest block at this location for safety
                int blockY = world.getHighestBlockYAt(blockX, blockZ);
                
                Location loc = new Location(world, blockX, blockY + 1.5, blockZ);
                
                // Check if location is safe for spawning
                Block block = loc.getBlock();
                Block blockBelow = loc.clone().add(0, -1, 0).getBlock();
                
                if (!block.isLiquid() && !blockBelow.isLiquid() && 
                    blockBelow.getType().isSolid() &&
                    loc.clone().add(0, 1, 0).getBlock().isEmpty()) {
                    
                    return loc;
                }
            }
            
            // Fallback to town spawn if no suitable location found
            return getTownSpawnLocation(town);
        } catch (Exception e) {
            plugin.getLogger().warning("Error getting random town location: " + e.getMessage());
            return getTownSpawnLocation(town);
        }
    }

    /**
     * Gets the town's boundary coordinates
     * @param town The town to get boundaries for
     * @return An array with [minX, minZ, maxX, maxZ] or null if unable to determine
     */
    public int[] getTownBounds(Town town) {
        if (town == null || town.getTownBlocks().isEmpty()) {
            return null;
        }
        
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        
        // Calculate town boundaries from town blocks
        for (TownBlock townBlock : town.getTownBlocks()) {
            int x = townBlock.getX() * 16; // Convert to block coordinates
            int z = townBlock.getZ() * 16;
            
            if (x < minX) minX = x;
            if (z < minZ) minZ = z;
            if (x + 16 > maxX) maxX = x + 16; // Add 16 for the full block width
            if (z + 16 > maxZ) maxZ = z + 16;
        }
        
        return new int[] { minX, minZ, maxX, maxZ };
    }

    /**
     * Gets the center location of a town
     * @param town The town to get center for
     * @return The center location of the town or null if it couldn't be determined
     */
    public Location getTownCenter(Town town) {
        if (town == null) {
            return null;
        }
        
        // First try to use town spawn as center if available
        try {
            Location spawn = town.getSpawn();
            if (spawn != null) {
                return spawn;
            }
        } catch (Exception ignored) {
            // Ignore any exceptions here and fall back to calculating center
        }
        
        // Calculate geographic center if no spawn available
        int[] bounds = getTownBounds(town);
        if (bounds == null) {
            return null;
        }
        
        int centerX = (bounds[0] + bounds[2]) / 2;
        int centerZ = (bounds[1] + bounds[3]) / 2;
        
        World world = Bukkit.getWorld(town.getWorld().getName());
        if (world == null) {
            return null;
        }
        
        // Find a safe Y coordinate by getting highest block
        int centerY = world.getHighestBlockYAt(centerX, centerZ);
        return new Location(world, centerX + 0.5, centerY + 1, centerZ + 0.5);
    }
}