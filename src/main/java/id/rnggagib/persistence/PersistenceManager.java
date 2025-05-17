package id.rnggagib.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.palmergames.bukkit.towny.object.Town;

import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;
import id.rnggagib.raid.RaidHistory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PersistenceManager {
    private final TownyRaider plugin;
    private final File dataFolder;
    private final File raidDataFile;
    private final File cooldownDataFile;
    private final File historyDataFile;
    private final Gson gson;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public PersistenceManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "data");
        this.raidDataFile = new File(dataFolder, "active_raids.json");
        this.cooldownDataFile = new File(dataFolder, "cooldowns.json");
        this.historyDataFile = new File(dataFolder, "history.json");
        
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }
    
    public void saveActiveRaids(List<ActiveRaid> raids) {
        JsonArray raidsArray = new JsonArray();
        
        for (ActiveRaid raid : raids) {
            JsonObject raidObj = new JsonObject();
            raidObj.addProperty("id", raid.getId().toString());
            raidObj.addProperty("townName", raid.getTownName());
            raidObj.addProperty("startTime", raid.getStartTime().format(DATE_FORMATTER));
            raidObj.addProperty("stolenItems", raid.getStolenItems());
            
            JsonArray raiderEntities = new JsonArray();
            for (UUID entityId : raid.getRaiderEntities()) {
                raiderEntities.add(entityId.toString());
            }
            raidObj.add("raiderEntities", raiderEntities);
            
            Location location = raid.getLocation();
            if (location != null) {
                JsonObject locObj = new JsonObject();
                locObj.addProperty("world", location.getWorld().getName());
                locObj.addProperty("x", location.getX());
                locObj.addProperty("y", location.getY());
                locObj.addProperty("z", location.getZ());
                locObj.addProperty("yaw", location.getYaw());
                locObj.addProperty("pitch", location.getPitch());
                raidObj.add("location", locObj);
            }
            
            // Save metadata that can be serialized
            if (!raid.getAllMetadata().isEmpty()) {
                JsonObject metadataObj = new JsonObject();
                
                for (Map.Entry<String, Object> entry : raid.getAllMetadata().entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    
                    // Handle different types that can be serialized
                    if (value instanceof String) {
                        metadataObj.addProperty(key + "_string", (String)value);
                    } 
                    else if (value instanceof Number) {
                        metadataObj.addProperty(key + "_number", (Number)value);
                    }
                    else if (value instanceof Boolean) {
                        metadataObj.addProperty(key + "_boolean", (Boolean)value);
                    }
                    else if (value instanceof List) {
                        // For location lists (common in this plugin)
                        if (!((List<?>) value).isEmpty() && ((List<?>) value).get(0) instanceof Location) {
                            JsonArray locArray = new JsonArray();
                            for (Location loc : (List<Location>)value) {
                                JsonObject locObj = new JsonObject();
                                locObj.addProperty("world", loc.getWorld().getName());
                                locObj.addProperty("x", loc.getX());
                                locObj.addProperty("y", loc.getY());
                                locObj.addProperty("z", loc.getZ());
                                locArray.add(locObj);
                            }
                            metadataObj.add(key + "_locations", locArray);
                        }
                    }
                }
                
                if (metadataObj.size() > 0) {
                    raidObj.add("metadata", metadataObj);
                }
            }
            
            raidsArray.add(raidObj);
        }
        
        writeJsonToFile(raidsArray, raidDataFile);
    }
    
    public List<ActiveRaid> loadActiveRaids() {
        List<ActiveRaid> raids = new ArrayList<>();
        
        if (!raidDataFile.exists()) {
            return raids;
        }
        
        try {
            JsonArray raidsArray = JsonParser.parseReader(new FileReader(raidDataFile)).getAsJsonArray();
            
            for (JsonElement element : raidsArray) {
                JsonObject raidObj = element.getAsJsonObject();
                
                UUID id = UUID.fromString(raidObj.get("id").getAsString());
                String townName = raidObj.get("townName").getAsString();
                
                ActiveRaid raid = new ActiveRaid(id, townName);
                raid.setStartTime(LocalDateTime.parse(raidObj.get("startTime").getAsString(), DATE_FORMATTER));
                
                if (raidObj.has("stolenItems")) {
                    int stolenItems = raidObj.get("stolenItems").getAsInt();
                    raid.setStolenItems(stolenItems);
                }
                
                if (raidObj.has("location")) {
                    JsonObject locObj = raidObj.get("location").getAsJsonObject();
                    World world = Bukkit.getWorld(locObj.get("world").getAsString());
                    
                    if (world != null) {
                        double x = locObj.get("x").getAsDouble();
                        double y = locObj.get("y").getAsDouble();
                        double z = locObj.get("z").getAsDouble();
                        float yaw = locObj.get("yaw").getAsFloat();
                        float pitch = locObj.get("pitch").getAsFloat();
                        
                        Location location = new Location(world, x, y, z, yaw, pitch);
                        raid.setLocation(location);
                    }
                }
                
                if (raidObj.has("raiderEntities")) {
                    JsonArray entitiesArray = raidObj.get("raiderEntities").getAsJsonArray();
                    for (JsonElement entityElement : entitiesArray) {
                        UUID entityId = UUID.fromString(entityElement.getAsString());
                        raid.addRaiderEntity(entityId);
                    }
                }
                
                // Load metadata
                if (raidObj.has("metadata")) {
                    JsonObject metadataObj = raidObj.get("metadata").getAsJsonObject();
                    
                    for (String key : metadataObj.keySet()) {
                        // Parse the key to determine the type
                        if (key.endsWith("_string")) {
                            String actualKey = key.substring(0, key.length() - 7);
                            raid.setMetadata(actualKey, metadataObj.get(key).getAsString());
                        }
                        else if (key.endsWith("_number")) {
                            String actualKey = key.substring(0, key.length() - 7);
                            raid.setMetadata(actualKey, metadataObj.get(key).getAsNumber());
                        }
                        else if (key.endsWith("_boolean")) {
                            String actualKey = key.substring(0, key.length() - 8);
                            raid.setMetadata(actualKey, metadataObj.get(key).getAsBoolean());
                        }
                        else if (key.endsWith("_locations")) {
                            String actualKey = key.substring(0, key.length() - 10);
                            JsonArray locArray = metadataObj.get(key).getAsJsonArray();
                            List<Location> locations = new ArrayList<>();
                            
                            for (JsonElement locElement : locArray) {
                                JsonObject locObj = locElement.getAsJsonObject();
                                String worldName = locObj.get("world").getAsString();
                                World world = Bukkit.getWorld(worldName);
                                
                                if (world != null) {
                                    double x = locObj.get("x").getAsDouble();
                                    double y = locObj.get("y").getAsDouble();
                                    double z = locObj.get("z").getAsDouble();
                                    
                                    Location location = new Location(world, x, y, z);
                                    locations.add(location);
                                }
                            }
                            
                            raid.setMetadata(actualKey, locations);
                        }
                    }
                }
                
                raids.add(raid);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load active raids: " + e.getMessage());
            e.printStackTrace();
        }
        
        return raids;
    }
    
    public void saveTownCooldowns(Map<String, LocalDateTime> townCooldowns) {
        JsonObject cooldownsObj = new JsonObject();
        
        for (Map.Entry<String, LocalDateTime> entry : townCooldowns.entrySet()) {
            cooldownsObj.addProperty(entry.getKey(), entry.getValue().format(DATE_FORMATTER));
        }
        
        writeJsonToFile(cooldownsObj, cooldownDataFile);
    }
    
    public Map<String, LocalDateTime> loadTownCooldowns() {
        Map<String, LocalDateTime> cooldowns = new HashMap<>();
        
        if (!cooldownDataFile.exists()) {
            return cooldowns;
        }
        
        try {
            JsonObject cooldownsObj = JsonParser.parseReader(new FileReader(cooldownDataFile)).getAsJsonObject();
            
            for (Map.Entry<String, JsonElement> entry : cooldownsObj.entrySet()) {
                String townName = entry.getKey();
                LocalDateTime cooldownEnd = LocalDateTime.parse(entry.getValue().getAsString(), DATE_FORMATTER);
                cooldowns.put(townName, cooldownEnd);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load town cooldowns: " + e.getMessage());
            e.printStackTrace();
        }
        
        return cooldowns;
    }
    
    public void saveRaidHistory(List<RaidHistory> raidHistory) {
        JsonArray historyArray = new JsonArray();
        
        for (RaidHistory history : raidHistory) {
            JsonObject historyObj = new JsonObject();
            historyObj.addProperty("id", history.getId().toString());
            historyObj.addProperty("townName", history.getTownName());
            historyObj.addProperty("startTime", history.getStartTime().format(DATE_FORMATTER));
            historyObj.addProperty("endTime", history.getEndTime().format(DATE_FORMATTER));
            historyObj.addProperty("stolenItems", history.getStolenItems());
            historyObj.addProperty("successful", history.isSuccessful());
            
            historyArray.add(historyObj);
        }
        
        writeJsonToFile(historyArray, historyDataFile);
    }
    
    public List<RaidHistory> loadRaidHistory() {
        List<RaidHistory> history = new ArrayList<>();
        
        if (!historyDataFile.exists()) {
            return history;
        }
        
        try {
            JsonArray historyArray = JsonParser.parseReader(new FileReader(historyDataFile)).getAsJsonArray();
            
            for (JsonElement element : historyArray) {
                JsonObject historyObj = element.getAsJsonObject();
                
                UUID id = UUID.fromString(historyObj.get("id").getAsString());
                String townName = historyObj.get("townName").getAsString();
                LocalDateTime startTime = LocalDateTime.parse(historyObj.get("startTime").getAsString(), DATE_FORMATTER);
                LocalDateTime endTime = LocalDateTime.parse(historyObj.get("endTime").getAsString(), DATE_FORMATTER);
                int stolenItems = historyObj.get("stolenItems").getAsInt();
                boolean successful = historyObj.get("successful").getAsBoolean();
                
                RaidHistory raidHistory = new RaidHistory(id, townName, endTime, startTime, stolenItems, successful);
                history.add(raidHistory);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load raid history: " + e.getMessage());
            e.printStackTrace();
        }
        
        return history;
    }
    
    private void writeJsonToFile(JsonElement jsonElement, File file) {
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(jsonElement, writer);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to write data to " + file.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void cleanupExpiredCooldowns() {
        Map<String, LocalDateTime> cooldowns = loadTownCooldowns();
        Map<String, LocalDateTime> updatedCooldowns = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        
        for (Map.Entry<String, LocalDateTime> entry : cooldowns.entrySet()) {
            if (entry.getValue().isAfter(now)) {
                updatedCooldowns.put(entry.getKey(), entry.getValue());
            }
        }
        
        if (updatedCooldowns.size() < cooldowns.size()) {
            saveTownCooldowns(updatedCooldowns);
        }
    }
}