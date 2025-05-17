package id.rnggagib.command.admin;

import id.rnggagib.TownyRaider;
import id.rnggagib.command.SubCommand;
import id.rnggagib.message.MessageManager;
import id.rnggagib.raid.RaidManager;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class StartRaidCommand implements SubCommand {
    private final TownyRaider plugin;
    
    public StartRaidCommand(TownyRaider plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            plugin.getMessageManager().send(sender, "admin-command-usage", 
                Map.of("usage", getUsage()));
            return;
        }
        
        String townName = args[0];
        Town town = plugin.getTownyHandler().getTownByName(townName);
        
        if (town == null) {
            plugin.getMessageManager().send(sender, "admin-town-not-found", 
                Map.of("town", townName));
            return;
        }
        
        // Check if raid is already in progress on this town
        if (plugin.getRaidManager().isTownUnderRaid(town.getName())) {
            plugin.getMessageManager().send(sender, "admin-raid-already-active", 
                Map.of("town", town.getName()));
            return;
        }
        
        // Start the raid
        boolean success = plugin.getRaidManager().startRaidOnTown(town);
        
        if (success) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("town", town.getName());
            placeholders.put("admin", sender.getName());
            
            plugin.getMessageManager().send(sender, "admin-raid-started", placeholders);
        } else {
            plugin.getMessageManager().send(sender, "admin-raid-failed", 
                Map.of("town", town.getName()));
        }
    }
    
    @Override
    public String getPermission() {
        return "townyraider.admin.startraid";
    }
    
    @Override
    public String getDescription() {
        return "Manually start a raid on a specific town";
    }
    
    @Override
    public String getUsage() {
        return "/townyraider admin startraid <town>";
    }
    
    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            // Return list of towns
            return TownyAPI.getInstance().getTowns().stream()
                .map(Town::getName)
                .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}