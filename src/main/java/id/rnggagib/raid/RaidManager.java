package id.rnggagib.raid;

import id.rnggagib.TownyRaider;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RaidManager {
    private final TownyRaider plugin;
    private final Map<UUID, ActiveRaid> activeRaids = new ConcurrentHashMap<>();
    private final List<RaidHistory> raidHistory = new ArrayList<>();
    private boolean raidsEnabled = true;
    private BukkitTask schedulerTask;
    
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public RaidManager(TownyRaider plugin) {
        this.plugin = plugin;
        startRaidScheduler();
    }

    public void startRaidScheduler() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
        }
        
        if (!plugin.getConfigManager().isRaidScheduleEnabled()) {
            plugin.getLogger().info("Raid scheduling is disabled in config");
            return;
        }
        
        schedulerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkScheduledRaids, 20L, 20L * 60); // Check every minute
        plugin.getLogger().info("Raid scheduler started");
    }

    private void checkScheduledRaids() {
        if (!raidsEnabled) {
            return;
        }
        
        LocalDateTime now = LocalDateTime.now();
        String currentTime = now.format(TIME_FORMATTER);
        
        if (plugin.getConfigManager().isUsingCron()) {
            String cronExpression = plugin.getConfigManager().getCronExpression();
            if (CronExpression.matches(cronExpression, now)) {
                startNewRaid();
            }
        } else {
            List<String> scheduledTimes = plugin.getConfigManager().getFixedRaidTimes();
            if (scheduledTimes.contains(currentTime)) {
                startNewRaid();
            }
        }
    }

    public void startNewRaid() {
        if (!raidsEnabled || hasActiveRaids()) {
            return;
        }
        
        List<String> eligibleTowns = findEligibleTowns();
        if (eligibleTowns.isEmpty()) {
            plugin.getLogger().info("No eligible towns found for raid");
            return;
        }
        
        String townName = selectRandomTown(eligibleTowns);
        if (townName != null) {
            schedulePreRaidWarning(townName);
        }
    }

    private void schedulePreRaidWarning(String townName) {
        int warningTime = plugin.getConfigManager().getPreRaidWarningTime();
        
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            sendRaidWarning(townName);
            scheduleRaidStart(townName);
        }, 20L * 60 * warningTime);
    }

    private void scheduleRaidStart(String townName) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            startRaid(townName);
        }, 20L * 60 * plugin.getConfigManager().getPreRaidWarningTime());
    }

    private void sendRaidWarning(String townName) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("minutes", String.valueOf(plugin.getConfigManager().getPreRaidWarningTime()));
        placeholders.put("town", townName);
        
        plugin.getMessageManager().broadcast("pre-raid-warning", placeholders);
    }

    private void startRaid(String townName) {
        ActiveRaid raid = new ActiveRaid(UUID.randomUUID(), townName);
        activeRaids.put(raid.getId(), raid);
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("town", townName);
        plugin.getMessageManager().broadcast("raid-start", placeholders);
        
        int raidDuration = plugin.getConfigManager().getRaidDuration();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            endRaid(raid.getId());
        }, 20L * 60 * raidDuration);
    }

    public void endRaid(UUID raidId) {
        ActiveRaid raid = activeRaids.remove(raidId);
        if (raid != null) {
            boolean successful = raid.getStolenItems() > 0;
            RaidHistory history = new RaidHistory(
                raid.getId(), 
                raid.getTownName(), 
                LocalDateTime.now(), 
                raid.getStartTime(), 
                raid.getStolenItems(),
                successful
            );
            raidHistory.add(history);
            
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("town", raid.getTownName());
            placeholders.put("status", successful ? 
                plugin.getConfigManager().getMessage("raid-successful") : 
                plugin.getConfigManager().getMessage("raid-defended"));
            placeholders.put("stolen", String.valueOf(raid.getStolenItems()));
            
            plugin.getMessageManager().broadcast("raid-end", placeholders);
        }
    }

    private List<String> findEligibleTowns() {
        List<String> eligibleTowns = new ArrayList<>();
        // This will be replaced with actual town eligibility checking
        // when we implement TownyHandler
        eligibleTowns.add("TestTown");
        return eligibleTowns;
    }

    private String selectRandomTown(List<String> eligibleTowns) {
        if (eligibleTowns.isEmpty()) {
            return null;
        }
        int randomIndex = (int) (Math.random() * eligibleTowns.size());
        return eligibleTowns.get(randomIndex);
    }

    public boolean hasActiveRaids() {
        return !activeRaids.isEmpty();
    }

    public List<ActiveRaid> getActiveRaids() {
        return new ArrayList<>(activeRaids.values());
    }

    public List<RaidHistory> getRaidHistory() {
        return new ArrayList<>(raidHistory);
    }

    public boolean isRaidsEnabled() {
        return raidsEnabled;
    }

    public void setRaidsEnabled(boolean enabled) {
        this.raidsEnabled = enabled;
    }

    public void toggleRaids() {
        this.raidsEnabled = !this.raidsEnabled;
    }

    public void shutdown() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
        }
        
        for (ActiveRaid raid : activeRaids.values()) {
            endRaid(raid.getId());
        }
    }
}