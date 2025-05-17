package id.rnggagib.raid;

import com.palmergames.bukkit.towny.object.Town;
import id.rnggagib.TownyRaider;
import id.rnggagib.towny.TownyHandler;
import id.rnggagib.persistence.PersistenceManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
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
    private DifficultyManager difficultyManager;
    
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public RaidManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.townyHandler = plugin.getTownyHandler();
        this.persistenceManager = plugin.getPersistenceManager();
        this.difficultyManager = new DifficultyManager(plugin);
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
        
        // Calculate difficulty score based on town properties
        double difficultyScore = difficultyManager.calculateDifficultyScore(town);
        
        UUID raidId = UUID.randomUUID();
        ActiveRaid raid = new ActiveRaid(raidId, town.getName(), plugin);
        raid.setLocation(raidLocation);
        
        // Store the difficulty score in raid metadata
        raid.setMetadata("difficulty_score", difficultyScore);
        
        activeRaids.put(raidId, raid);
        
        // Override config values with difficulty-scaled values
        ConfigurationSection zombieConfig = plugin.getConfigManager().getMobConfig("baby-zombie");
        ConfigurationSection skeletonConfig = plugin.getConfigManager().getMobConfig("skeleton");
        
        // Apply difficulty scaling to zombies
        int zombieCount = difficultyManager.getZombieCount(difficultyScore);
        double zombieHealth = zombieConfig.getDouble("health", 15.0) * difficultyManager.getHealthMultiplier(difficultyScore);
        double zombieSpeed = zombieConfig.getDouble("speed", 0.35) * difficultyManager.getSpeedMultiplier(difficultyScore);
        double zombieDamage = 2.0 * difficultyManager.getDamageMultiplier(difficultyScore);
        
        // Store scaled values in raid metadata
        raid.setMetadata("zombie_count", zombieCount);
        raid.setMetadata("zombie_health", zombieHealth);
        raid.setMetadata("zombie_speed", zombieSpeed);
        raid.setMetadata("zombie_damage", zombieDamage);
        
        // Apply difficulty scaling to skeletons
        int skeletonCount = difficultyManager.getSkeletonCount(difficultyScore);
        double skeletonHealth = skeletonConfig.getDouble("health", 30.0) * difficultyManager.getHealthMultiplier(difficultyScore);
        double skeletonSpeed = skeletonConfig.getDouble("speed", 0.25) * difficultyManager.getSpeedMultiplier(difficultyScore);
        double skeletonDamage = 3.0 * difficultyManager.getDamageMultiplier(difficultyScore);
        
        // Store scaled values in raid metadata
        raid.setMetadata("skeleton_count", skeletonCount);
        raid.setMetadata("skeleton_health", skeletonHealth);
        raid.setMetadata("skeleton_speed", skeletonSpeed);
        raid.setMetadata("skeleton_damage", skeletonDamage);
        
        // Spawn raid mobs at the location with difficulty scaling
        plugin.getRaiderEntityManager().spawnScaledRaidMobs(raid, raidLocation);
        
        // After spawning raid mobs, keep the chunks loaded
        plugin.getRaiderEntityManager().keepRaidChunksLoaded(raid);
        
        // Create visual effects for the raid
        plugin.getVisualEffectsManager().createRaidBossBar(raid);
        plugin.getVisualEffectsManager().createRaidBorderEffects(raid);
        
        // Setup protection for the raid
        plugin.getProtectionManager().setupProtectionForRaid(raid);
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("town", town.getName());
        placeholders.put("difficulty", String.format("%.1f", difficultyScore));
        townyHandler.notifyTownMembers(town, "raid-start", placeholders);
        
        townyHandler.putTownOnCooldown(town);
        
        // Scale raid duration based on difficulty
        int raidDuration = difficultyManager.getRaidDuration(difficultyScore);
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
            
            // Make sure to unload chunks at the end
            plugin.getRaiderEntityManager().unloadRaidChunks(raid);
            
            // Clean up visual effects
            plugin.getVisualEffectsManager().removeRaidBossBar(raidId);
            
            Town town = townyHandler.getTownByName(raid.getTownName());
            
            // Determine if raid was successful based on stolen items
            boolean successful = raid.getStolenItems() > 0;
            String outcome = successful ? "RAIDERS WON" : "TOWN DEFENDED";
            
            plugin.getLogger().info("Raid " + raidId + " on town " + raid.getTownName() + 
                                  " has ended. " + outcome + " - Items stolen: " + raid.getStolenItems());
            
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

    /**
     * Get an active raid by its ID
     * @param raidId The raid ID
     * @return The active raid or null if not found
     */
    public ActiveRaid getRaidById(UUID raidId) {
        return activeRaids.get(raidId);
    }
    
    /**
     * Get an active raid by its ID
     * @param raidId The UUID of the raid to retrieve
     * @return The ActiveRaid object, or null if not found
     */
    public ActiveRaid getActiveRaid(UUID raidId) {
        return getRaidById(raidId);
    }
    
    /**
     * Get all active raids
     * @return Map of active raids by their IDs
     */
    public Map<UUID, ActiveRaid> getActiveRaids() {
        return activeRaids;
    }
    
    /**
     * Get all active raids as a list
     * @return List of active raids
     */
    public List<ActiveRaid> getActiveRaidsList() {
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

    /**
     * Check if a town is currently under raid
     * @param townName the name of the town
     * @return true if the town is under raid, false otherwise
     */
    public boolean isTownUnderRaid(String townName) {
        return activeRaids.values().stream()
            .anyMatch(raid -> raid.getTownName().equalsIgnoreCase(townName));
    }

    /**
     * Manually start a raid on a specific town
     * @param town the town to raid
     * @return true if the raid was successfully started, false otherwise
     */
    public boolean startRaidOnTown(Town town) {
        if (town == null || !raidsEnabled) {
            return false;
        }
        
        if (isTownUnderRaid(town.getName())) {
            return false;
        }
        
        try {
            Location raidLocation = townyHandler.getRandomLocationInTown(town);
            if (raidLocation == null) {
                return false;
            }
            
            UUID raidId = UUID.randomUUID();
            ActiveRaid raid = new ActiveRaid(raidId, town.getName(), plugin);
            raid.setLocation(raidLocation);
            activeRaids.put(raidId, raid);
            
            plugin.getRaiderEntityManager().spawnRaidMobs(raid, raidLocation);
            
            plugin.getVisualEffectsManager().createRaidBossBar(raid);
            plugin.getVisualEffectsManager().createRaidBorderEffects(raid);
            
            plugin.getProtectionManager().setupProtectionForRaid(raid);
            
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("town", town.getName());
            townyHandler.notifyTownMembers(town, "raid-start", placeholders);
            
            // Don't put town on cooldown for admin-started raids
            
            int raidDuration = plugin.getConfigManager().getRaidDuration();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                endRaid(raid.getId());
            }, 20L * 60 * raidDuration);
            
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Error starting admin raid on town " + town.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}