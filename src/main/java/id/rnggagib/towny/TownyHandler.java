package id.rnggagib.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyWorld;
import id.rnggagib.TownyRaider;
import id.rnggagib.message.MessageManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
}