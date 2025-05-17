package id.rnggagib.raid;

import org.bukkit.Location;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ActiveRaid {
    private final UUID id;
    private final String townName;
    private LocalDateTime startTime;
    private int stolenItems;
    private final List<UUID> raiderEntities;
    private Location location;
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private final Map<String, Object> metadataCache = new ConcurrentHashMap<>();
    private final ReadWriteLock metadataLock = new ReentrantReadWriteLock();
    private static final List<Location> EMPTY_LOCATION_LIST = Collections.emptyList();

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
     * Sets metadata for this raid with improved performance
     * @param key The metadata key
     * @param value The metadata value
     */
    public void setMetadata(String key, Object value) {
        metadata.put(key, value);
        
        // Update cache if this is a frequently accessed key
        if (key.equals("chest_locations") || key.equals("spawn_locations")) {
            metadataCache.put(key, value);
        } else {
            // Clear cache entry if it exists but isn't in the frequently accessed list
            metadataCache.remove(key);
        }
    }

    /**
     * Gets metadata for this raid with improved performance
     * @param key The metadata key
     * @return The metadata value, or null if not found
     */
    public Object getMetadata(String key) {
        // Check cache first
        Object cachedValue = metadataCache.get(key);
        if (cachedValue != null) {
            return cachedValue;
        }
        
        // Get from main storage
        Object value = metadata.get(key);
        
        // Cache frequently accessed keys (you can customize this logic)
        if (value != null && (key.equals("chest_locations") || key.equals("spawn_locations"))) {
            metadataCache.put(key, value);
        }
        
        return value;
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
     * Bulk set multiple metadata values atomically
     * @param metadataMap Map of metadata key-value pairs to set
     */
    public void setMultipleMetadata(Map<String, Object> metadataMap) {
        metadataLock.writeLock().lock();
        try {
            for (Map.Entry<String, Object> entry : metadataMap.entrySet()) {
                setMetadata(entry.getKey(), entry.getValue());
            }
        } finally {
            metadataLock.writeLock().unlock();
        }
    }

    /**
     * Clear all metadata with improved performance
     */
    public void clearMetadata() {
        metadataLock.writeLock().lock();
        try {
            metadata.clear();
            metadataCache.clear();
        } finally {
            metadataLock.writeLock().unlock();
        }
    }

    /**
     * Gets a String metadata value
     * @param key The metadata key
     * @param defaultValue Value to return if not found or wrong type
     * @return The metadata value as String, or defaultValue if not found
     */
    public String getStringMetadata(String key, String defaultValue) {
        Object value = getMetadata(key);
        return value instanceof String ? (String)value : defaultValue;
    }

    /**
     * Gets an Integer metadata value
     * @param key The metadata key
     * @param defaultValue Value to return if not found or wrong type
     * @return The metadata value as Integer, or defaultValue if not found
     */
    public int getIntMetadata(String key, int defaultValue) {
        Object value = getMetadata(key);
        if (value instanceof Number) {
            return ((Number)value).intValue();
        }
        return defaultValue;
    }

    /**
     * Gets a Double metadata value
     * @param key The metadata key
     * @param defaultValue Value to return if not found or wrong type
     * @return The metadata value as Double, or defaultValue if not found
     */
    public double getDoubleMetadata(String key, double defaultValue) {
        Object value = getMetadata(key);
        if (value instanceof Number) {
            return ((Number)value).doubleValue();
        }
        return defaultValue;
    }

    /**
     * Gets a Boolean metadata value
     * @param key The metadata key
     * @param defaultValue Value to return if not found or wrong type
     * @return The metadata value as Boolean, or defaultValue if not found
     */
    public boolean getBooleanMetadata(String key, boolean defaultValue) {
        Object value = getMetadata(key);
        return value instanceof Boolean ? (Boolean)value : defaultValue;
    }

    /**
     * Gets a List of Locations metadata value with improved performance
     * @param key The metadata key
     * @return The metadata value as List of Locations, or empty list if not found
     */
    @SuppressWarnings("unchecked")
    public List<Location> getLocationListMetadata(String key) {
        Object value = getMetadata(key);
        if (value instanceof List && !((List<?>)value).isEmpty() && ((List<?>)value).get(0) instanceof Location) {
            return (List<Location>)value;
        }
        // Return static empty list instead of creating a new ArrayList
        return EMPTY_LOCATION_LIST;
    }
}