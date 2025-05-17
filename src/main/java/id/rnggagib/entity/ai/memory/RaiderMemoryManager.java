package id.rnggagib.entity.ai.memory;

import id.rnggagib.TownyRaider;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RaiderMemoryManager {
    private final TownyRaider plugin;
    private final Map<String, TownMemory> townMemories = new HashMap<>();
    private final File memoryFile;
    
    public RaiderMemoryManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.memoryFile = new File(plugin.getDataFolder(), "raid_memories.yml");
        loadMemories();
    }
    
    /**
     * Record successful loot location
     */
    public void recordSuccessfulLoot(String townName, Location location, boolean isChest) {
        TownMemory memory = townMemories.computeIfAbsent(townName, k -> new TownMemory());
        if (isChest) {
            memory.addSuccessfulChestLocation(location);
        } else {
            memory.addSuccessfulValuableLocation(location);
        }
        saveMemories();
    }
    
    /**
     * Record dangerous location (where raiders died)
     */
    public void recordDangerousLocation(String townName, Location location) {
        TownMemory memory = townMemories.computeIfAbsent(townName, k -> new TownMemory());
        memory.addDangerousLocation(location);
        saveMemories();
    }
    
    /**
     * Get remembered loot locations
     */
    public List<Location> getRememberedLootLocations(String townName) {
        TownMemory memory = townMemories.get(townName);
        if (memory == null) return new ArrayList<>();
        return memory.getSuccessfulLootLocations();
    }
    
    /**
     * Get remembered dangerous locations to avoid
     */
    public List<Location> getRememberedDangerLocations(String townName) {
        TownMemory memory = townMemories.get(townName);
        if (memory == null) return new ArrayList<>();
        return memory.getDangerousLocations();
    }
    
    /**
     * Load memories from file
     */
    private void loadMemories() {
        if (!memoryFile.exists()) return;
        
        FileConfiguration config = YamlConfiguration.loadConfiguration(memoryFile);
        for (String townName : config.getKeys(false)) {
            TownMemory memory = new TownMemory();
            
            // Load chest locations
            List<?> chestLocs = config.getList(townName + ".chests");
            if (chestLocs != null) {
                for (Object obj : chestLocs) {
                    if (obj instanceof Location) {
                        memory.addSuccessfulChestLocation((Location) obj);
                    }
                }
            }
            
            // Load valuable block locations
            List<?> valuableLocs = config.getList(townName + ".valuables");
            if (valuableLocs != null) {
                for (Object obj : valuableLocs) {
                    if (obj instanceof Location) {
                        memory.addSuccessfulValuableLocation((Location) obj);
                    }
                }
            }
            
            // Load dangerous locations
            List<?> dangerLocs = config.getList(townName + ".dangers");
            if (dangerLocs != null) {
                for (Object obj : dangerLocs) {
                    if (obj instanceof Location) {
                        memory.addDangerousLocation((Location) obj);
                    }
                }
            }
            
            townMemories.put(townName, memory);
        }
    }
    
    /**
     * Save memories to file
     */
    public void saveMemories() {
        FileConfiguration config = new YamlConfiguration();
        
        for (Map.Entry<String, TownMemory> entry : townMemories.entrySet()) {
            String townName = entry.getKey();
            TownMemory memory = entry.getValue();
            
            config.set(townName + ".chests", memory.getSuccessfulChestLocations());
            config.set(townName + ".valuables", memory.getSuccessfulValuableLocations());
            config.set(townName + ".dangers", memory.getDangerousLocations());
        }
        
        try {
            config.save(memoryFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save raid memories: " + e.getMessage());
        }
    }
    
    /**
     * Town memory storage class
     */
    public static class TownMemory {
        private final List<Location> successfulChestLocations = new ArrayList<>();
        private final List<Location> successfulValuableLocations = new ArrayList<>();
        private final List<Location> dangerousLocations = new ArrayList<>();
        
        public void addSuccessfulChestLocation(Location location) {
            if (!containsNearbyLocation(successfulChestLocations, location, 2)) {
                successfulChestLocations.add(location);
            }
        }
        
        public void addSuccessfulValuableLocation(Location location) {
            if (!containsNearbyLocation(successfulValuableLocations, location, 2)) {
                successfulValuableLocations.add(location);
            }
        }
        
        public void addDangerousLocation(Location location) {
            if (!containsNearbyLocation(dangerousLocations, location, 5)) {
                dangerousLocations.add(location);
            }
        }
        
        public List<Location> getSuccessfulChestLocations() {
            return successfulChestLocations;
        }
        
        public List<Location> getSuccessfulValuableLocations() {
            return successfulValuableLocations;
        }
        
        public List<Location> getSuccessfulLootLocations() {
            List<Location> combined = new ArrayList<>(successfulChestLocations);
            combined.addAll(successfulValuableLocations);
            return combined;
        }
        
        public List<Location> getDangerousLocations() {
            return dangerousLocations;
        }
        
        private boolean containsNearbyLocation(List<Location> locations, Location check, double radius) {
            for (Location loc : locations) {
                if (loc.getWorld().equals(check.getWorld()) && 
                    loc.distanceSquared(check) <= radius * radius) {
                    return true;
                }
            }
            return false;
        }
    }
}