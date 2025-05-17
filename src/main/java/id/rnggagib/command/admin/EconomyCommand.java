package id.rnggagib.command.admin;

import id.rnggagib.TownyRaider;
import id.rnggagib.command.SubCommand;
import id.rnggagib.config.ConfigManager;
import id.rnggagib.message.MessageManager;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class EconomyCommand implements SubCommand {
    private final TownyRaider plugin;
    private final List<String> validSettings = Arrays.asList(
        "base-item-value", 
        "raid-base-reward", 
        "raid-base-penalty",
        "raid-reward-multiplier", 
        "raid-penalty-multiplier", 
        "max-penalty-percentage",
        "defender-bonus", 
        "compensation-multiplier", 
        "defender-bonus-enabled", 
        "defender-compensation-enabled"
    );
    
    public EconomyCommand(TownyRaider plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            showCurrentSettings(sender);
            return;
        }
        
        String option = args[0].toLowerCase();
        
        if (option.equals("reload")) {
            plugin.getConfigManager().loadConfig();
            plugin.getMessageManager().send(sender, "admin-economy-reloaded");
            return;
        }
        
        if (args.length < 2) {
            plugin.getMessageManager().send(sender, "admin-command-usage", 
                Map.of("usage", getUsage()));
            return;
        }
        
        String setting = option;
        String value = args[1];
        
        if (!validSettings.contains(setting)) {
            plugin.getMessageManager().send(sender, "admin-economy-invalid-setting", 
                Map.of("setting", setting));
            return;
        }
        
        // Update the setting
        FileConfiguration config = plugin.getConfigManager().getConfig();
        String path = "economy." + setting;
        
        if (setting.endsWith("enabled")) {
            boolean boolValue = Boolean.parseBoolean(value);
            config.set(path, boolValue);
            
            updateSettingMessage(sender, setting, String.valueOf(boolValue));
        } else {
            try {
                double doubleValue = Double.parseDouble(value);
                config.set(path, doubleValue);
                
                updateSettingMessage(sender, setting, String.format("%.2f", doubleValue));
            } catch (NumberFormatException e) {
                plugin.getMessageManager().send(sender, "admin-economy-invalid-value", 
                    Map.of("value", value));
                return;
            }
        }
        
        plugin.getConfigManager().saveConfig();
        plugin.getConfigManager().loadConfig();
    }
    
    private void showCurrentSettings(CommandSender sender) {
        plugin.getMessageManager().send(sender, "admin-economy-settings-header");
        
        Map<String, String> placeholders = new HashMap<>();
        ConfigManager configManager = plugin.getConfigManager();
        
        placeholders.put("setting", "base-item-value");
        placeholders.put("value", String.format("%.2f", configManager.getBaseItemValue()));
        plugin.getMessageManager().send(sender, "admin-economy-setting", placeholders);
        
        placeholders.put("setting", "raid-base-reward");
        placeholders.put("value", String.format("%.2f", configManager.getRaidBaseReward()));
        plugin.getMessageManager().send(sender, "admin-economy-setting", placeholders);
        
        placeholders.put("setting", "raid-base-penalty");
        placeholders.put("value", String.format("%.2f", configManager.getRaidBasePenalty()));
        plugin.getMessageManager().send(sender, "admin-economy-setting", placeholders);
        
        placeholders.put("setting", "raid-reward-multiplier");
        placeholders.put("value", String.format("%.2f", configManager.getRaidRewardMultiplier()));
        plugin.getMessageManager().send(sender, "admin-economy-setting", placeholders);
        
        placeholders.put("setting", "raid-penalty-multiplier");
        placeholders.put("value", String.format("%.2f", configManager.getRaidPenaltyMultiplier()));
        plugin.getMessageManager().send(sender, "admin-economy-setting", placeholders);
        
        placeholders.put("setting", "max-penalty-percentage");
        placeholders.put("value", String.format("%.2f", configManager.getMaxPenaltyPercentage()));
        plugin.getMessageManager().send(sender, "admin-economy-setting", placeholders);
        
        placeholders.put("setting", "defender-bonus");
        placeholders.put("value", String.format("%.2f", configManager.getDefenderBonus()));
        plugin.getMessageManager().send(sender, "admin-economy-setting", placeholders);
        
        placeholders.put("setting", "compensation-multiplier");
        placeholders.put("value", String.format("%.2f", configManager.getCompensationMultiplier()));
        plugin.getMessageManager().send(sender, "admin-economy-setting", placeholders);
        
        placeholders.put("setting", "defender-bonus-enabled");
        placeholders.put("value", String.valueOf(configManager.isDefenderBonusEnabled()));
        plugin.getMessageManager().send(sender, "admin-economy-setting", placeholders);
        
        placeholders.put("setting", "defender-compensation-enabled");
        placeholders.put("value", String.valueOf(configManager.isDefenderCompensationEnabled()));
        plugin.getMessageManager().send(sender, "admin-economy-setting", placeholders);
        
        plugin.getMessageManager().send(sender, "admin-economy-settings-footer");
    }
    
    private void updateSettingMessage(CommandSender sender, String setting, String value) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("setting", setting);
        placeholders.put("value", value);
        plugin.getMessageManager().send(sender, "admin-economy-updated", placeholders);
    }
    
    @Override
    public String getPermission() {
        return "townyraider.admin.economy";
    }
    
    @Override
    public String getDescription() {
        return "View or modify economy settings for raids";
    }
    
    @Override
    public String getUsage() {
        return "/townyraider admin economy [setting] [value]";
    }
    
    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(validSettings);
            options.add("reload");
            
            return options.stream()
                .filter(opt -> opt.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        } else if (args.length == 2) {
            String setting = args[0].toLowerCase();
            
            if (setting.endsWith("enabled")) {
                return Arrays.asList("true", "false").stream()
                    .filter(v -> v.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
            }
        }
        return new ArrayList<>();
    }
}