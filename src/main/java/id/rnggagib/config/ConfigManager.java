package id.rnggagib.config;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import id.rnggagib.TownyRaider;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConfigManager {

    private final TownyRaider plugin;
    private FileConfiguration config;
    private File configFile;
    
    private final Set<Material> stealableBlocks = new HashSet<>();
    private final Set<Material> stealableItems = new HashSet<>();
    private final List<String> raidTimes = new ArrayList<>();
    private Set<Material> protectedMaterials = new HashSet<>();
    private Set<Material> defensiveBlocks = new HashSet<>();

    public ConfigManager(TownyRaider plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        if (configFile == null) {
            configFile = new File(plugin.getDataFolder(), "config.yml");
        }
        
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        
        config = YamlConfiguration.loadConfiguration(configFile);
        
        Reader defConfigStream = new InputStreamReader(
                plugin.getResource("config.yml"), StandardCharsets.UTF_8);
        YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(defConfigStream);
        config.setDefaults(defConfig);
        
        loadStealableBlocks();
        loadStealableItems();
        loadRaidTimes();
        loadProtectedMaterials();
        loadDefensiveBlocks();
    }
    
    public void saveConfig() {
        if (config == null || configFile == null) {
            return;
        }
        
        try {
            config.save(configFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save config to " + configFile);
        }
    }
    
    public void reloadConfig() {
        loadConfig();
        plugin.getLogger().info("Configuration reloaded.");
    }
    
    private void loadStealableBlocks() {
        stealableBlocks.clear();
        List<String> blockStrings = config.getStringList("stealable.blocks");
        for (String blockString : blockStrings) {
            try {
                Material material = Material.valueOf(blockString);
                stealableBlocks.add(material);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material in stealable blocks: " + blockString);
            }
        }
    }
    
    private void loadStealableItems() {
        stealableItems.clear();
        List<String> itemStrings = config.getStringList("stealable.chest-stealing.valuable-items");
        for (String itemString : itemStrings) {
            try {
                Material material = Material.valueOf(itemString);
                stealableItems.add(material);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material in stealable items: " + itemString);
            }
        }
    }
    
    private void loadRaidTimes() {
        raidTimes.clear();
        
        if (config.getBoolean("raids.schedule.use-cron")) {
            String cronExpression = config.getString("raids.schedule.cron");
            if (cronExpression != null && !cronExpression.isEmpty()) {
                raidTimes.add(cronExpression);
            }
        } else {
            raidTimes.addAll(config.getStringList("raids.schedule.fixed-times"));
        }
    }
    
    private void loadProtectedMaterials() {
        protectedMaterials.clear();
        List<String> materialStrings = config.getStringList("protection.protected-materials");
        for (String materialString : materialStrings) {
            try {
                Material material = Material.valueOf(materialString);
                protectedMaterials.add(material);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material in protected materials: " + materialString);
            }
        }
    }

    private void loadDefensiveBlocks() {
        defensiveBlocks.clear();
        List<String> blockStrings = config.getStringList("protection.defensive-blocks");
        for (String blockString : blockStrings) {
            try {
                Material material = Material.valueOf(blockString);
                defensiveBlocks.add(material);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material in defensive blocks: " + blockString);
            }
        }
    }
    
    public boolean isDebugEnabled() {
        return config.getBoolean("general.debug", false);
    }
    
    public boolean isMetricsEnabled() {
        return config.getBoolean("general.metrics", true);
    }
    
    public boolean isRaidScheduleEnabled() {
        return config.getBoolean("raids.schedule.enabled", true);
    }
    
    public boolean isUsingCron() {
        return config.getBoolean("raids.schedule.use-cron", false);
    }
    
    public String getCronExpression() {
        return config.getString("raids.schedule.cron", "0 */3 * * *");
    }
    
    public List<String> getFixedRaidTimes() {
        return raidTimes;
    }
    
    public int getMinimumOnlineTownMembers() {
        return config.getInt("raids.conditions.minimum-online-town-members", 1);
    }
    
    public int getMaximumChunkDistance() {
        return config.getInt("raids.conditions.maximum-chunk-distance", 5);
    }
    
    public double getMinimumTownWealth() {
        return config.getDouble("raids.conditions.minimum-town-wealth", 1000);
    }
    
    public int getMinimumTownAgeDays() {
        return config.getInt("raids.conditions.minimum-town-age-days", 1);
    }
    
    public int getRaidDuration() {
        return config.getInt("raids.duration", 10);
    }
    
    public int getTownCooldown() {
        return config.getInt("raids.town-cooldown", 24);
    }
    
    public Set<Material> getStealableBlocks() {
        return stealableBlocks;
    }
    
    public Set<Material> getStealableItems() {
        return stealableItems;
    }
    
    public boolean isChestStealingEnabled() {
        return config.getBoolean("stealable.chest-stealing.enabled", true);
    }
    
    public int getMaxItemsPerCategory() {
        return config.getInt("stealable.chest-stealing.max-items-per-category", 5);
    }
    
    public int getPreRaidWarningTime() {
        return config.getInt("notifications.pre-raid-warning", 5);
    }
    
    public int getNotificationRadius() {
        return config.getInt("notifications.radius", 3);
    }
    
    public Sound getPreRaidSound() {
        try {
            return Sound.valueOf(config.getString("notifications.sounds.pre-raid", "ENTITY_WITHER_SPAWN"));
        } catch (IllegalArgumentException e) {
            return Sound.ENTITY_WITHER_SPAWN;
        }
    }
    
    public Sound getRaidStartSound() {
        try {
            return Sound.valueOf(config.getString("notifications.sounds.raid-start", "ENTITY_ENDER_DRAGON_GROWL"));
        } catch (IllegalArgumentException e) {
            return Sound.ENTITY_ENDER_DRAGON_GROWL;
        }
    }
    
    public Sound getRaidEndSound() {
        try {
            return Sound.valueOf(config.getString("notifications.sounds.raid-end", "ENTITY_PLAYER_LEVELUP"));
        } catch (IllegalArgumentException e) {
            return Sound.ENTITY_PLAYER_LEVELUP;
        }
    }
    
    public String getPrefix() {
        return config.getString("messages.prefix", "<dark_red>[<gold>TownyRaider<dark_red>]</gold> ");
    }
    
    public String getMessage(String path) {
        return config.getString("messages." + path, "Message not found: " + path);
    }
    
    public ConfigurationSection getMobConfig(String mobType) {
        return config.getConfigurationSection("mobs." + mobType);
    }
    
    public FileConfiguration getConfig() {
        return config;
    }
    
    public Set<Material> getProtectedMaterials() {
        return protectedMaterials;
    }

    public Set<Material> getAllowedDefensiveBlocks() {
        return defensiveBlocks;
    }

    public int getRaidProtectionRadius() {
        return config.getInt("protection.raid-protection-radius", 50);
    }

    public boolean isRaidDefenseBonusEnabled() {
        return config.getBoolean("protection.enable-defense-bonus", true);
    }
}