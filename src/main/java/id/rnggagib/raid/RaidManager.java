package id.rnggagib.raid;

import com.palmergames.bukkit.towny.object.Town;
import id.rnggagib.TownyRaider;
import id.rnggagib.towny.TownyHandler;
import id.rnggagib.persistence.PersistenceManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class RaidManager {
    private final TownyRaider plugin;
    private final Map<UUID, ActiveRaid> activeRaids = new ConcurrentHashMap<>();
    private final List<RaidHistory> raidHistory = new ArrayList<>();
    private boolean raidsEnabled = true;
    private BukkitTask schedulerTask;
    private TownyHandler townyHandler;
    private PersistenceManager persistenceManager;
    
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public RaidManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.townyHandler = plugin.getTownyHandler();
        this.persistenceManager = plugin.getPersistenceManager();
        loadPersistentData();
        startRaidScheduler();
    }

    private void loadPersistentData() {
        // Load active raids
        List<ActiveRaid> savedRaids = persistenceManager.loadActiveRaids();
        for (ActiveRaid raid : savedRaids) {
            activeRaids.put(raid.getId(), raid);
            plugin.getLogger().info("Loaded active raid for town: " + raid.getTownName());
        }
        
        // Load raid history
        List<RaidHistory> savedHistory = persistenceManager.loadRaidHistory();
        raidHistory.addAll(savedHistory);
        plugin.getLogger().info("Loaded " + savedHistory.size() + " historical raid records");
        
        // Load town cooldowns
        Map<String, LocalDateTime> savedCooldowns = persistenceManager.loadTownCooldowns();
        townyHandler.setTownRaidCooldowns(savedCooldowns);
        plugin.getLogger().info("Loaded " + savedCooldowns.size() + " town cooldown records");
        
        // Cleanup expired cooldowns
        persistenceManager.cleanupExpiredCooldowns();
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
        if (!raidsEnabled || !townyHandler.isTownyEnabled()) {
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
        if (!raidsEnabled || hasActiveRaids() || !townyHandler.isTownyEnabled()) {
            return;
        }
        
        List<Town> eligibleTowns = townyHandler.getEligibleTownsForRaid();
        if (eligibleTowns.isEmpty()) {
            plugin.getLogger().info("No eligible towns found for raid");
            return;
        }
        
        Town selectedTown = selectRandomTown(eligibleTowns);
        if (selectedTown != null) {
            schedulePreRaidWarning(selectedTown);
        }
    }

    private void schedulePreRaidWarning(Town town) {
        int warningTime = plugin.getConfigManager().getPreRaidWarningTime();
        
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            sendRaidWarning(town);
            scheduleRaidStart(town);
        }, 20L * 60 * warningTime);
    }

    private void scheduleRaidStart(Town town) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            startRaid(town);
        }, 20L * 60 * plugin.getConfigManager().getPreRaidWarningTime());
    }

    private void sendRaidWarning(Town town) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("minutes", String.valueOf(plugin.getConfigManager().getPreRaidWarningTime()));
        placeholders.put("town", town.getName());
        
        townyHandler.notifyTownMembers(town, "pre-raid-warning", placeholders);
    }

    private void startRaid(Town town) {
        Location raidLocation = townyHandler.findSuitableRaidLocation(town);
        if (raidLocation == null) {
            plugin.getLogger().warning("Could not find suitable location for raid in town: " + town.getName());
            return;
        }
        
        ActiveRaid raid = new ActiveRaid(UUID.randomUUID(), town.getName());
        raid.setLocation(raidLocation);
        activeRaids.put(raid.getId(), raid);
        
        // Spawn raid mobs at the location
        plugin.getRaiderEntityManager().spawnRaidMobs(raid, raidLocation);
        
        // Create visual effects for the raid
        plugin.getVisualEffectsManager().createRaidBossBar(raid);
        plugin.getVisualEffectsManager().createRaidBorderEffects(raid);
        
        // Setup protection for the raid
        plugin.getProtectionManager().setupProtectionForRaid(raid);
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("town", town.getName());
        townyHandler.notifyTownMembers(town, "raid-start", placeholders);
        
        townyHandler.putTownOnCooldown(town);
        
        int raidDuration = plugin.getConfigManager().getRaidDuration();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            endRaid(raid.getId());
        }, 20L * 60 * raidDuration);
    }

    public void endRaid(UUID raidId) {
        ActiveRaid raid = activeRaids.remove(raidId);
        if (raid != null) {
            // Cleanup raid protection
            plugin.getProtectionManager().cleanupRaidProtection(raid);
            
            // Clean up raid mobs
            plugin.getRaiderEntityManager().cleanupRaidMobs(raidId);
            
            // Clean up visual effects
            plugin.getVisualEffectsManager().removeRaidBossBar(raidId);
            
            Town town = townyHandler.getTownByName(raid.getTownName());
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
            
            // Process economy for the raid
            plugin.getEconomyManager().processRaidEconomy(raid, successful);
            
            if (town != null) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("town", raid.getTownName());
                placeholders.put("status", successful ? 
                    plugin.getConfigManager().getMessage("raid-successful") :
                    plugin.getConfigManager().getMessage("raid-defended"));
                placeholders.put("stolen_items", String.valueOf(raid.getStolenItems()));
                
                // Notify town members about raid end
                townyHandler.notifyTownMembers(town, "raid-end", placeholders);
            }
        }
    }

    private Town selectRandomTown(List<Town> eligibleTowns) {
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

    public TownyHandler getTownyHandler() {
        return townyHandler;
    }

    public void savePersistentData() {
        // Save active raids
        persistenceManager.saveActiveRaids(new ArrayList<>(activeRaids.values()));
        
        // Save raid history
        persistenceManager.saveRaidHistory(raidHistory);
        
        // Save town cooldowns
        persistenceManager.saveTownCooldowns(townyHandler.getTownRaidCooldowns());
        
        plugin.getLogger().info("Saved raid data to disk");
    }

    public void shutdown() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
        }
        
        savePersistentData();
        
        for (ActiveRaid raid : activeRaids.values()) {
            endRaid(raid.getId());
        }
    }
}