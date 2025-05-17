package id.rnggagib.raid;

import java.time.LocalDateTime;
import java.util.UUID;

public class RaidHistory {
    private final UUID id;
    private final String townName;
    private final LocalDateTime endTime;
    private final LocalDateTime startTime;
    private final int stolenItems;
    private final boolean successful;

    public RaidHistory(UUID id, String townName, LocalDateTime endTime, LocalDateTime startTime, int stolenItems, boolean successful) {
        this.id = id;
        this.townName = townName;
        this.endTime = endTime;
        this.startTime = startTime;
        this.stolenItems = stolenItems;
        this.successful = successful;
    }

    public UUID getId() {
        return id;
    }

    public String getTownName() {
        return townName;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public int getStolenItems() {
        return stolenItems;
    }

    public boolean isSuccessful() {
        return successful;
    }
}