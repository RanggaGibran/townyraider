package id.rnggagib.command.admin;

import id.rnggagib.TownyRaider;
import id.rnggagib.command.SubCommand;
import id.rnggagib.message.MessageManager;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DebugCommand implements SubCommand {
    private final TownyRaider plugin;
    
    public DebugCommand(TownyRaider plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            plugin.getMessageManager().send(sender, "admin-command-usage", 
                Map.of("usage", getUsage()));
            return;
        }
        
        String option = args[0].toLowerCase();
        
        switch (option) {
            case "toggle":
                toggleDebugMode(sender);
                break;
            case "raids":
                showRaidDebugInfo(sender);
                break;
            case "town":
                if (args.length < 2) {
                    plugin.getMessageManager().send(sender, "admin-command-usage", 
                        Map.of("usage", "/townyraider admin debug town <town_name>"));
                    return;
                }
                showTownDebugInfo(sender, args[1]);
                break;
            case "economy":
                showEconomyDebugInfo(sender);
                break;
            case "location":
                if (!(sender instanceof Player)) {
                    plugin.getMessageManager().send(sender, "admin-player-only-command");
                    return;
                }
                showLocationDebugInfo((Player) sender);
                break;
            default:
                plugin.getMessageManager().send(sender, "admin-debug-invalid-option", 
                    Map.of("option", option));
        }
    }
    
    private void toggleDebugMode(CommandSender sender) {
        FileConfiguration config = plugin.getConfigManager().getConfig();
        boolean currentDebug = config.getBoolean("general.debug", false);
        boolean newDebug = !currentDebug;
        
        config.set("general.debug", newDebug);
        plugin.getConfigManager().saveConfig();
        plugin.getConfigManager().loadConfig();
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("status", newDebug ? "enabled" : "disabled");
        plugin.getMessageManager().send(sender, "admin-debug-toggled", placeholders);
    }
    
    private void showRaidDebugInfo(CommandSender sender) {
        plugin.getMessageManager().send(sender, "admin-debug-raids-header");
        
        List<ActiveRaid> raids = plugin.getRaidManager().getActiveRaids();
        if (raids.isEmpty()) {
            plugin.getMessageManager().send(sender, "admin-no-active-raids");
            return;
        }
        
        for (ActiveRaid raid : raids) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("town", raid.getTownName());
            placeholders.put("id", raid.getId().toString());
            placeholders.put("start", raid.getStartTime().toString());
            placeholders.put("stolen", String.valueOf(raid.getStolenItems()));
            placeholders.put("raiders", String.valueOf(raid.getRaiderEntities().size()));
            
            plugin.getMessageManager().send(sender, "admin-debug-raid-info", placeholders);
        }
    }
    
    private void showTownDebugInfo(CommandSender sender, String townName) {
        com.palmergames.bukkit.towny.object.Town town = plugin.getTownyHandler().getTownByName(townName);
        
        if (town == null) {
            plugin.getMessageManager().send(sender, "admin-town-not-found", 
                Map.of("town", townName));
            return;
        }
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("town", town.getName());
        placeholders.put("balance", String.format("%.2f", town.getAccount().getHoldingBalance()));
        placeholders.put("residents", String.valueOf(town.getNumResidents()));
        placeholders.put("under_raid", String.valueOf(plugin.getRaidManager().isTownUnderRaid(town.getName())));
        placeholders.put("on_cooldown", String.valueOf(plugin.getTownyHandler().isTownOnRaidCooldown(town)));
        
        plugin.getMessageManager().send(sender, "admin-debug-town-header", placeholders);
        
        // Show town economy stats if available
        double raidRewards = plugin.getEconomyManager().getTownTransactionTotal(town.getName(), "raid_reward");
        double raidPenalties = plugin.getEconomyManager().getTownTransactionTotal(town.getName(), "raid_penalty");
        
        placeholders.put("rewards", String.format("%.2f", raidRewards));
        placeholders.put("penalties", String.format("%.2f", raidPenalties));
        placeholders.put("net", String.format("%.2f", raidRewards - raidPenalties));
        
        plugin.getMessageManager().send(sender, "admin-debug-town-economy", placeholders);
    }
    
    private void showEconomyDebugInfo(CommandSender sender) {
        plugin.getMessageManager().send(sender, "admin-debug-economy-header");
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("enabled", String.valueOf(plugin.getEconomyManager().isEconomyEnabled()));
        placeholders.put("base_item_value", String.format("%.2f", plugin.getConfigManager().getBaseItemValue()));
        placeholders.put("raid_base_reward", String.format("%.2f", plugin.getConfigManager().getRaidBaseReward()));
        placeholders.put("raid_base_penalty", String.format("%.2f", plugin.getConfigManager().getRaidBasePenalty()));
        
        plugin.getMessageManager().send(sender, "admin-debug-economy-info", placeholders);
    }
    
    private void showLocationDebugInfo(Player player) {
        plugin.getMessageManager().send(player, "admin-debug-location-header");
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("world", player.getWorld().getName());
        placeholders.put("x", String.format("%.2f", player.getLocation().getX()));
        placeholders.put("y", String.format("%.2f", player.getLocation().getY()));
        placeholders.put("z", String.format("%.2f", player.getLocation().getZ()));
        
        // Towny-specific location info
        com.palmergames.bukkit.towny.object.Town town = plugin.getTownyHandler().getTownAtLocation(player.getLocation());
        placeholders.put("in_town", town != null ? town.getName() : "none");
        placeholders.put("under_raid", town != null ? 
            String.valueOf(plugin.getRaidManager().isTownUnderRaid(town.getName())) : "false");
        
        plugin.getMessageManager().send(player, "admin-debug-location-info", placeholders);
    }
    
    @Override
    public String getPermission() {
        return "townyraider.admin.debug";
    }
    
    @Override
    public String getDescription() {
        return "Toggle debug mode or show debug information";
    }
    
    @Override
    public String getUsage() {
        return "/townyraider admin debug <toggle|raids|town|economy|location>";
    }
    
    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("toggle", "raids", "town", "economy", "location").stream()
                .filter(opt -> opt.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("town")) {
            return com.palmergames.bukkit.towny.TownyAPI.getInstance().getTowns().stream()
                .map(town -> town.getName())
                .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}