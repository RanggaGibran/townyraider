package id.rnggagib.raid;

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
    private final Map<String, Object> metadata = new HashMap<>();

    public ActiveRaid(UUID id, String townName) {
        this.id = id;
        this.townName = townName;
        this.startTime = LocalDateTime.now();
        this.stolenItems = 0;
        this.raiderEntities = new ArrayList<>();
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

    public int getStolenItems() {
        return stolenItems;
    }
    
    public void setStolenItems(int stolenItems) {
        this.stolenItems = stolenItems;
    }

    public void incrementStolenItems(int amount) {
        this.stolenItems += amount;
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

    /**
     * Checks if this raid has metadata with the given key
     * @param key The metadata key
     * @return true if the metadata exists, false otherwise
     */
    public boolean hasMetadata(String key) {
        return metadata.containsKey(key);
    }

    /**
     * Sets metadata for this raid
     * @param key The metadata key
     * @param value The metadata value
     */
    public void setMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    /**
     * Gets metadata for this raid
     * @param key The metadata key
     * @return The metadata value, or null if not found
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * Gets metadata as a specific type with a default value if not found
     * @param <T> The expected type of the metadata value
     * @param key The metadata key
     * @param defaultValue The default value to return if the key doesn't exist
     * @return The metadata value cast to type T, or defaultValue if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, T defaultValue) {
        Object value = metadata.get(key);
        if (value == null) return defaultValue;
        
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    /**
     * Remove metadata with the specified key
     * @param key The metadata key to remove
     * @return The previous value associated with the key, or null if none
     */
    public Object removeMetadata(String key) {
        return metadata.remove(key);
    }

    /**
     * Gets a map of all metadata
     * @return Unmodifiable map of metadata
     */
    public Map<String, Object> getAllMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * Clear all metadata
     */
    public void clearMetadata() {
        metadata.clear();
    }
}