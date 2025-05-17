package id.rnggagib.raid;

import com.palmergames.bukkit.towny.object.Town;
import id.rnggagib.TownyRaider;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Handles dynamic difficulty scaling for raids based on town properties
 */
public class DifficultyManager {
    private final TownyRaider plugin;
    
    // Base difficulty values
    private final int baseZombieCount;
    private final int baseSkeletonCount;
    private final double baseHealthMultiplier;
    private final double baseSpeedMultiplier;
    private final double baseDamageMultiplier;
    private final int baseRaidDuration;
    
    // Scaling factors
    private final double wealthScalingFactor;
    private final double residentScalingFactor;
    private final double landScalingFactor;
    private final double historyScalingFactor;
    private final int maxZombieCount;
    private final int maxSkeletonCount;
    
    public DifficultyManager(TownyRaider plugin) {
        this.plugin = plugin;
        
        ConfigurationSection config = plugin.getConfigManager().getConfig().getConfigurationSection("difficulty-scaling");
        
        if (config == null) {
            // Default values if no configuration exists
            baseZombieCount = 2;
            baseSkeletonCount = 2;
            baseHealthMultiplier = 1.0;
            baseSpeedMultiplier = 1.0;
            baseDamageMultiplier = 1.0;
            baseRaidDuration = 20;
            wealthScalingFactor = 0.00001;
            residentScalingFactor = 0.05;
            landScalingFactor = 0.02;
            historyScalingFactor = 0.1;
            maxZombieCount = 6;
            maxSkeletonCount = 12;
        } else {
            // Load values from configuration
            baseZombieCount = config.getInt("base-zombie-count", 2);
            baseSkeletonCount = config.getInt("base-skeleton-per-zombie", 2);
            baseHealthMultiplier = config.getDouble("base-health-multiplier", 1.0);
            baseSpeedMultiplier = config.getDouble("base-speed-multiplier", 1.0);
            baseDamageMultiplier = config.getDouble("base-damage-multiplier", 1.0);
            baseRaidDuration = config.getInt("base-raid-duration", 20);
            wealthScalingFactor = config.getDouble("wealth-scaling-factor", 0.00001);
            residentScalingFactor = config.getDouble("resident-scaling-factor", 0.05);
            landScalingFactor = config.getDouble("land-scaling-factor", 0.02);
            historyScalingFactor = config.getDouble("history-scaling-factor", 0.1);
            maxZombieCount = config.getInt("max-zombie-count", 6);
            maxSkeletonCount = config.getInt("max-skeleton-count", 12);
        }
    }
    
    /**
     * Calculates a difficulty score for a town based on various factors
     * @param town The town to evaluate
     * @return A difficulty score (1.0 = base difficulty)
     */
    public double calculateDifficultyScore(Town town) {
        if (town == null) return 1.0;
        
        double wealthFactor = calculateWealthFactor(town);
        double residentFactor = calculateResidentFactor(town);
        double landFactor = calculateLandFactor(town);
        double historyFactor = calculateHistoryFactor(town);
        
        // Calculate overall difficulty score with weighted factors
        double difficultyScore = 1.0 + (wealthFactor * 0.4) + (residentFactor * 0.3) + 
                                 (landFactor * 0.2) + (historyFactor * 0.1);
        
        // Apply minimum and maximum constraints
        difficultyScore = Math.max(0.5, Math.min(difficultyScore, 3.0));
        
        plugin.getLogger().info("Town " + town.getName() + " calculated difficulty score: " + difficultyScore);
        
        return difficultyScore;
    }
    
    private double calculateWealthFactor(Town town) {
        double townBalance = 0;
        try {
            townBalance = town.getAccount().getHoldingBalance();
        } catch (Exception e) {
            // In case of economic issues, default to 0
            return 0;
        }
        
        // Scale based on town wealth (more money = more difficult)
        return townBalance * wealthScalingFactor;
    }
    
    private double calculateResidentFactor(Town town) {
        int residentCount = town.getResidents().size();
        
        // Scale based on resident count (more residents = more difficult)
        return residentCount * residentScalingFactor;
    }
    
    private double calculateLandFactor(Town town) {
        int claimCount = town.getTownBlocks().size();
        
        // Scale based on land claims (more land = more difficult)
        return claimCount * landScalingFactor;
    }
    
    private double calculateHistoryFactor(Town town) {
        // Check past raid history to scale difficulty
        int successfulRaids = 0;
        int totalRaids = 0;
        
        for (RaidHistory history : plugin.getRaidManager().getRaidHistory()) {
            if (history.getTownName().equals(town.getName())) {
                totalRaids++;
                if (history.isSuccessful()) {
                    successfulRaids++;
                }
            }
        }
        
        if (totalRaids == 0) return 0;
        
        // Successful defense rate (high defense = more difficult future raids)
        double defenseRate = 1.0 - ((double) successfulRaids / totalRaids);
        return defenseRate * historyScalingFactor * totalRaids;
    }
    
    /**
     * Gets the appropriate zombie count for a raid based on town difficulty
     * @param difficultyScore The calculated difficulty score
     * @return The number of zombies to spawn
     */
    public int getZombieCount(double difficultyScore) {
        int count = (int) Math.round(baseZombieCount * difficultyScore);
        return Math.min(count, maxZombieCount);
    }
    
    /**
     * Gets the appropriate skeleton count per zombie based on town difficulty
     * @param difficultyScore The calculated difficulty score
     * @return The number of skeletons per zombie to spawn
     */
    public int getSkeletonCount(double difficultyScore) {
        int count = (int) Math.round(baseSkeletonCount * difficultyScore);
        return Math.min(count, maxSkeletonCount / getZombieCount(difficultyScore));
    }
    
    /**
     * Gets the health multiplier for raid mobs
     * @param difficultyScore The calculated difficulty score
     * @return Health multiplier for mobs
     */
    public double getHealthMultiplier(double difficultyScore) {
        return baseHealthMultiplier * difficultyScore;
    }
    
    /**
     * Gets the speed multiplier for raid mobs
     * @param difficultyScore The calculated difficulty score
     * @return Speed multiplier for mobs
     */
    public double getSpeedMultiplier(double difficultyScore) {
        return baseSpeedMultiplier * (difficultyScore * 0.8 + 0.2); // Less impact on speed
    }
    
    /**
     * Gets the damage multiplier for raid mobs
     * @param difficultyScore The calculated difficulty score
     * @return Damage multiplier for mobs
     */
    public double getDamageMultiplier(double difficultyScore) {
        return baseDamageMultiplier * difficultyScore;
    }
    
    /**
     * Gets the raid duration based on town difficulty
     * @param difficultyScore The calculated difficulty score
     * @return The raid duration in minutes
     */
    public int getRaidDuration(double difficultyScore) {
        return (int) Math.round(baseRaidDuration * Math.sqrt(difficultyScore));
    }
}