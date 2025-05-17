package id.rnggagib.command;

import id.rnggagib.TownyRaider;
import id.rnggagib.message.MessageManager;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TownyRaiderCommand implements CommandExecutor, TabCompleter {
    private final TownyRaider plugin;
    private final Map<String, SubCommand> subCommands = new HashMap<>();

    public TownyRaiderCommand(TownyRaider plugin) {
        this.plugin = plugin;
        registerSubCommands();
    }

    private void registerSubCommands() {
        subCommands.put("reload", new ReloadCommand(plugin));
        subCommands.put("status", new StatusCommand(plugin));
        subCommands.put("toggle", new ToggleCommand(plugin));
        subCommands.put("help", new HelpCommand(plugin, subCommands));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            subCommands.get("help").execute(sender, args);
            return true;
        }

        String subCommandName = args[0].toLowerCase();
        if (subCommands.containsKey(subCommandName)) {
            SubCommand subCommand = subCommands.get(subCommandName);
            
            if (sender.hasPermission(subCommand.getPermission())) {
                String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
                subCommand.execute(sender, subArgs);
            } else {
                Map<String, String> placeholders = MessageManager.createPlaceholders(
                    "permission", subCommand.getPermission()
                );
                plugin.getMessageManager().send(sender, "no-permission", placeholders);
            }
        } else {
            Map<String, String> placeholders = MessageManager.createPlaceholders(
                "command", subCommandName
            );
            plugin.getMessageManager().send(sender, "unknown-command", placeholders);
        }
        
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return subCommands.keySet().stream()
                .filter(cmd -> cmd.startsWith(args[0].toLowerCase()))
                .filter(cmd -> sender.hasPermission(subCommands.get(cmd).getPermission()))
                .collect(Collectors.toList());
        } else if (args.length > 1) {
            String subCommandName = args[0].toLowerCase();
            if (subCommands.containsKey(subCommandName)) {
                SubCommand subCommand = subCommands.get(subCommandName);
                if (sender.hasPermission(subCommand.getPermission())) {
                    return subCommand.tabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
                }
            }
        }
        
        return new ArrayList<>();
    }
}