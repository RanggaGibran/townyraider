package id.rnggagib.command.admin;

import id.rnggagib.TownyRaider;
import id.rnggagib.command.SubCommand;
import id.rnggagib.message.MessageManager;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AdminCommand implements SubCommand {
    private final TownyRaider plugin;
    private final Map<String, SubCommand> subCommands = new HashMap<>();
    
    public AdminCommand(TownyRaider plugin) {
        this.plugin = plugin;
        registerSubCommands();
    }
    
    private void registerSubCommands() {
        subCommands.put("startraid", new StartRaidCommand(plugin));
        subCommands.put("stopraid", new StopRaidCommand(plugin));
        subCommands.put("economy", new EconomyCommand(plugin));
        subCommands.put("debug", new DebugCommand(plugin));
    }
    
    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("townyraider.admin")) {
            plugin.getMessageManager().send(sender, "no-permission", 
                Map.of("permission", "townyraider.admin"));
            return;
        }
        
        if (args.length == 0) {
            showAdminHelp(sender);
            return;
        }
        
        String subCommandName = args[0].toLowerCase();
        if (!subCommands.containsKey(subCommandName)) {
            plugin.getMessageManager().send(sender, "admin-unknown-command", 
                Map.of("command", subCommandName));
            return;
        }
        
        SubCommand subCommand = subCommands.get(subCommandName);
        
        if (!sender.hasPermission(subCommand.getPermission())) {
            plugin.getMessageManager().send(sender, "no-permission", 
                Map.of("permission", subCommand.getPermission()));
            return;
        }
        
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        subCommand.execute(sender, subArgs);
    }
    
    private void showAdminHelp(CommandSender sender) {
        plugin.getMessageManager().send(sender, "admin-help-header");
        
        for (Map.Entry<String, SubCommand> entry : subCommands.entrySet()) {
            SubCommand subCommand = entry.getValue();
            
            if (sender.hasPermission(subCommand.getPermission())) {
                Map<String, String> placeholders = MessageManager.createPlaceholders(
                    "command", subCommand.getUsage(),
                    "description", subCommand.getDescription()
                );
                plugin.getMessageManager().send(sender, "help-command", placeholders);
            }
        }
        
        plugin.getMessageManager().send(sender, "admin-help-footer");
    }
    
    @Override
    public String getPermission() {
        return "townyraider.admin";
    }
    
    @Override
    public String getDescription() {
        return "Admin commands for TownyRaider";
    }
    
    @Override
    public String getUsage() {
        return "/townyraider admin <subcommand>";
    }
    
    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return subCommands.keySet().stream()
                .filter(cmd -> cmd.startsWith(args[0].toLowerCase()))
                .filter(cmd -> sender.hasPermission(subCommands.get(cmd).getPermission()))
                .collect(Collectors.toList());
        } else if (args.length > 1) {
            String subCommandName = args[0].toLowerCase();
            if (subCommands.containsKey(subCommandName) && 
                sender.hasPermission(subCommands.get(subCommandName).getPermission())) {
                return subCommands.get(subCommandName)
                    .tabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
            }
        }
        return new ArrayList<>();
    }
}