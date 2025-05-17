package id.rnggagib.command.admin;

import id.rnggagib.TownyRaider;
import id.rnggagib.command.SubCommand;
import id.rnggagib.message.MessageManager;
import id.rnggagib.raid.ActiveRaid;
import id.rnggagib.raid.RaidManager;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class StopRaidCommand implements SubCommand {
    private final TownyRaider plugin;
    
    public StopRaidCommand(TownyRaider plugin) {
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
        
        if (option.equals("all")) {
            // Stop all active raids
            List<ActiveRaid> activeRaids = new ArrayList<>(plugin.getRaidManager().getActiveRaids());
            
            if (activeRaids.isEmpty()) {
                plugin.getMessageManager().send(sender, "admin-no-active-raids");
                return;
            }
            
            for (ActiveRaid raid : activeRaids) {
                plugin.getRaidManager().endRaid(raid.getId());
            }
            
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("count", String.valueOf(activeRaids.size()));
            placeholders.put("admin", sender.getName());
            
            plugin.getMessageManager().send(sender, "admin-all-raids-stopped", placeholders);
            
        } else {
            // Try to stop a specific raid by town name
            String townName = option;
            
            ActiveRaid raid = plugin.getRaidManager().getActiveRaids().stream()
                .filter(r -> r.getTownName().equalsIgnoreCase(townName))
                .findFirst()
                .orElse(null);
            
            if (raid == null) {
                plugin.getMessageManager().send(sender, "admin-raid-not-found", 
                    Map.of("town", townName));
                return;
            }
            
            plugin.getRaidManager().endRaid(raid.getId());
            
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("town", townName);
            placeholders.put("admin", sender.getName());
            
            plugin.getMessageManager().send(sender, "admin-raid-stopped", placeholders);
        }
    }
    
    @Override
    public String getPermission() {
        return "townyraider.admin.stopraid";
    }
    
    @Override
    public String getDescription() {
        return "Manually stop an active raid on a specific town or all raids";
    }
    
    @Override
    public String getUsage() {
        return "/townyraider admin stopraid <town|all>";
    }
    
    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("all");
            
            // Add active raided towns
            options.addAll(plugin.getRaidManager().getActiveRaids().stream()
                .map(ActiveRaid::getTownName)
                .collect(Collectors.toList()));
            
            return options.stream()
                .filter(opt -> opt.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}