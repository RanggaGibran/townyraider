package id.rnggagib.raid;

import id.rnggagib.TownyRaider;
import org.bukkit.Location;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ActiveRaid {
    private final UUID id;
    private final String townName;
    private LocalDateTime startTime;
    private int stolenItems;
    private final List<UUID> raiderEntities;
    private Location location;
    private final Map<String, Object> metadata;
    private final TownyRaider plugin;
    
    // Static empty list for optimization
    private static final List<Location> EMPTY_LOCATION_LIST = Collections.emptyList();
    
    public ActiveRaid(UUID id, String townName, TownyRaider plugin) {
        this.id = id;
        this.townName = townName;
        this.plugin = plugin;
        this.startTime = LocalDateTime.now();
        this.stolenItems = 0;
        this.raiderEntities = new ArrayList<>();
        this.metadata = new HashMap<>();
    }
    
    public UUID getId() {
        return id;
    }
    
    public String getTownName() {
        return townName;
    }
    
    public LocalDateTime getStartTime() {
        return startTime;
    }
    
    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }
    
    public List<UUID> getRaiderEntities() {
        return raiderEntities;
    }
    
    public void addRaiderEntity(UUID entityId) {
        raiderEntities.add(entityId);
    }
    
    public void removeRaiderEntity(UUID entityId) {
        raiderEntities.remove(entityId);
    }
    
    public boolean isRaiderEntity(UUID entityId) {
        return raiderEntities.contains(entityId);
    }
    
    public Location getLocation() {
        return location;
    }
    
    public void setLocation(Location location) {
        this.location = location;
    }
    
    public int getStolenItems() {
        return stolenItems;
    }
    
    public void setStolenItems(int stolenItems) {
        this.stolenItems = stolenItems;
    }
    
    /**
     * Increment stolen items counter by a specified amount
     * @param amount The amount to increment by
     */
    public void incrementStolenItems(int amount) {
        stolenItems += amount;
    }

    /**
     * Increment stolen items counter by 1
     */
    public void incrementStolenItems() {
        stolenItems++;
    }
    
    public void setMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    public Object getMetadata(String key) {
        return metadata.get(key);
    }
    
    public Map<String, Object> getAllMetadata() {
        return metadata;
    }
    
    /**
     * Checks if metadata with the specified key exists
     * @param key The metadata key to check
     * @return true if metadata with the key exists, false otherwise
     */
    public boolean hasMetadata(String key) {
        return metadata.containsKey(key);
    }
    
    /**
     * Gets a Boolean metadata value
     * @param key The metadata key
     * @param defaultValue Value to return if not found or wrong type
     * @return The metadata value as Boolean, or defaultValue if not found
     */
    public boolean getBooleanMetadata(String key, boolean defaultValue) {
        Object value = getMetadata(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    /**
     * Gets a List of Locations metadata value with improved performance
     * @param key The metadata key
     * @return The metadata value as List of Locations, or empty list if not found
     */
    @SuppressWarnings("unchecked")
    public List<Location> getLocationListMetadata(String key) {
        Object value = getMetadata(key);
        if (value instanceof List && !((List<?>) value).isEmpty() && ((List<?>) value).get(0) instanceof Location) {
            return (List<Location>) value;
        }
        // Return static empty list instead of creating a new ArrayList
        return EMPTY_LOCATION_LIST;
    }

    /**
     * Starts the raid and organizes raid squads.
     */
    public void startRaid() {
        // After all raid entities are spawned
        plugin.getCoordinationManager().organizeRaidSquads(this);
    }
}