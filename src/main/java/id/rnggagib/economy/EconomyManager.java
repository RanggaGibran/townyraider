package id.rnggagib.economy;

import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;
import id.rnggagib.towny.TownyHandler;

import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownyEconomyHandler;

import org.bukkit.entity.Player;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EconomyManager {
    private final TownyRaider plugin;
    private final TownyHandler townyHandler;
    private Economy economy;
    
    private static final String TRANSACTION_RAID_REWARD = "raid_reward";
    private static final String TRANSACTION_RAID_PENALTY = "raid_penalty";
    private static final String TRANSACTION_RAID_COMPENSATION = "raid_compensation";
    private static final String TRANSACTION_DEFENDER_BONUS = "defender_bonus";
    
    private final Map<UUID, Map<String, Double>> playerTransactionHistory = new HashMap<>();
    private final Map<String, Map<String, Double>> townTransactionHistory = new HashMap<>();
    
    public EconomyManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.townyHandler = plugin.getTownyHandler();
        setupEconomy();
    }
    
    private boolean setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault not found! Economy features will be disabled.");
            return false;
        }
        
        Economy econ = townyHandler.getEconomy();
        if (econ == null) {
            plugin.getLogger().warning("No economy provider found! Economy features will be disabled.");
            return false;
        }
        
        this.economy = econ;
        plugin.getLogger().info("Economy provider found: " + economy.getName());
        return true;
    }
    
    /**
     * Process economic transactions at the end of a raid
     */
    public void processRaidEconomy(ActiveRaid raid, boolean successful) {
        if (economy == null) return;
        
        Town town = townyHandler.getTownByName(raid.getTownName());
        if (town == null) return;
        
        // Calculate raid values
        int stolenItems = raid.getStolenItems();
        double stolenValue = calculateStolenItemsValue(stolenItems);
        double baseReward = plugin.getConfigManager().getRaidBaseReward();
        double basePenalty = plugin.getConfigManager().getRaidBasePenalty();
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("town", town.getName());
        
        // Process raid outcomes based on success or failure
        if (successful) {
            // Raiders were successful - apply penalty to town
            double townPenalty = basePenalty + (stolenValue * plugin.getConfigManager().getRaidPenaltyMultiplier());
            
            if (applyTownPenalty(town, townPenalty)) {
                placeholders.put("amount", String.format("%.2f", townPenalty));
                plugin.getMessageManager().sendToTown(town, "economy-raid-penalty", placeholders);
                
                // Track transaction
                recordTownTransaction(town.getName(), TRANSACTION_RAID_PENALTY, townPenalty);
                
                // Process defender compensation if enabled
                if (plugin.getConfigManager().isDefenderCompensationEnabled()) {
                    distributeDefenderCompensation(town, stolenValue);
                }
            }
        } else {
            // Town successfully defended - give reward
            double townReward = baseReward * plugin.getConfigManager().getRaidRewardMultiplier();
            
            if (applyTownReward(town, townReward)) {
                placeholders.put("amount", String.format("%.2f", townReward));
                plugin.getMessageManager().sendToTown(town, "economy-raid-reward", placeholders);
                
                // Track transaction
                recordTownTransaction(town.getName(), TRANSACTION_RAID_REWARD, townReward);
                
                // Process defender bonuses if enabled
                if (plugin.getConfigManager().isDefenderBonusEnabled()) {
                    distributeDefenderBonuses(town);
                }
            }
        }
    }
    
    /**
     * Calculate the monetary value of stolen items
     */
    private double calculateStolenItemsValue(int stolenItems) {
        double baseItemValue = plugin.getConfigManager().getBaseItemValue();
        return baseItemValue * stolenItems;
    }
    
    /**
     * Apply a penalty to a town's account
     */
    private boolean applyTownPenalty(Town town, double amount) {
        if (economy == null) return false;
        
        String townAccount = town.getAccount().getName();
        double townBalance = economy.getBalance(townAccount);
        double safeAmount = Math.min(amount, townBalance * plugin.getConfigManager().getMaxPenaltyPercentage());
        
        if (safeAmount <= 0) return false;
        
        EconomyResponse response = economy.withdrawPlayer(townAccount, safeAmount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Failed to apply penalty to town " + town.getName() + ": " + response.errorMessage);
            return false;
        }
        
        return true;
    }
    
    /**
     * Give a reward to a town's account
     */
    private boolean applyTownReward(Town town, double amount) {
        if (economy == null) return false;
        
        String townAccount = town.getAccount().getName();
        
        EconomyResponse response = economy.depositPlayer(townAccount, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Failed to reward town " + town.getName() + ": " + response.errorMessage);
            return false;
        }
        
        return true;
    }
    
    /**
     * Distribute compensation to town residents for their losses
     */
    private void distributeDefenderCompensation(Town town, double stolenValue) {
        if (economy == null) return;
        
        List<Player> onlinePlayers = townyHandler.getOnlineTownMembers(town);
        if (onlinePlayers.isEmpty()) return;
        
        double compensationMultiplier = plugin.getConfigManager().getCompensationMultiplier();
        double compensationTotal = stolenValue * compensationMultiplier;
        double compensationPerPlayer = compensationTotal / onlinePlayers.size();
        
        if (compensationPerPlayer < 1.0) return;
        
        for (Player player : onlinePlayers) {
            EconomyResponse response = economy.depositPlayer(player, compensationPerPlayer);
            if (response.transactionSuccess()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("amount", String.format("%.2f", compensationPerPlayer));
                plugin.getMessageManager().send(player, "economy-raid-compensation", placeholders);
                
                recordPlayerTransaction(player.getUniqueId(), TRANSACTION_RAID_COMPENSATION, compensationPerPlayer);
            }
        }
    }
    
    /**
     * Distribute bonuses to players who defended their town
     */
    private void distributeDefenderBonuses(Town town) {
        if (economy == null) return;
        
        List<Player> onlinePlayers = townyHandler.getOnlineTownMembers(town);
        if (onlinePlayers.isEmpty()) return;
        
        double bonusPerPlayer = plugin.getConfigManager().getDefenderBonus();
        
        if (bonusPerPlayer < 1.0) return;
        
        for (Player player : onlinePlayers) {
            if (player.hasMetadata("townyraider.defender")) {
                EconomyResponse response = economy.depositPlayer(player, bonusPerPlayer);
                if (response.transactionSuccess()) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("amount", String.format("%.2f", bonusPerPlayer));
                    plugin.getMessageManager().send(player, "economy-defender-bonus", placeholders);
                    
                    recordPlayerTransaction(player.getUniqueId(), TRANSACTION_DEFENDER_BONUS, bonusPerPlayer);
                }
            }
        }
    }
    
    /**
     * Record a transaction for a player
     */
    private void recordPlayerTransaction(UUID playerId, String type, double amount) {
        playerTransactionHistory.computeIfAbsent(playerId, k -> new HashMap<>())
                                .merge(type, amount, Double::sum);
    }
    
    /**
     * Record a transaction for a town
     */
    private void recordTownTransaction(String townName, String type, double amount) {
        townTransactionHistory.computeIfAbsent(townName, k -> new HashMap<>())
                             .merge(type, amount, Double::sum);
    }
    
    /**
     * Get total transaction amount for a player by type
     */
    public double getPlayerTransactionTotal(UUID playerId, String type) {
        return playerTransactionHistory.getOrDefault(playerId, new HashMap<>())
                                      .getOrDefault(type, 0.0);
    }
    
    /**
     * Get total transaction amount for a town by type
     */
    public double getTownTransactionTotal(String townName, String type) {
        return townTransactionHistory.getOrDefault(townName, new HashMap<>())
                                    .getOrDefault(type, 0.0);
    }
    
    /**
     * Check if economy is enabled and available
     */
    public boolean isEconomyEnabled() {
        return economy != null;
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        playerTransactionHistory.clear();
        townTransactionHistory.clear();
    }
}