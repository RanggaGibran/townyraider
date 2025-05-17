package id.rnggagib.raid;

import org.bukkit.Location;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ActiveRaid {
    private final UUID id;
    private final String townName;
    private final LocalDateTime startTime;
    private int stolenItems;
    private final List<UUID> raiderEntities;
    private Location location;

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

    public int getStolenItems() {
        return stolenItems;
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
}