package id.rnggagib.command;

import id.rnggagib.TownyRaider;
import id.rnggagib.message.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.Map;

public class HelpCommand implements SubCommand {
    private final TownyRaider plugin;
    private final Map<String, SubCommand> subCommands;

    public HelpCommand(TownyRaider plugin, Map<String, SubCommand> subCommands) {
        this.plugin = plugin;
        this.subCommands = subCommands;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Map<String, String> placeholders = MessageManager.createPlaceholders(
            "version", plugin.getDescription().getVersion()
        );
        plugin.getMessageManager().send(sender, "help-header", placeholders);
        
        for (Map.Entry<String, SubCommand> entry : subCommands.entrySet()) {
            String commandName = entry.getKey();
            SubCommand subCommand = entry.getValue();
            
            if (sender.hasPermission(subCommand.getPermission())) {
                Map<String, String> cmdPlaceholders = MessageManager.createPlaceholders(
                    "command", subCommand.getUsage(),
                    "description", subCommand.getDescription()
                );
                plugin.getMessageManager().send(sender, "help-command", cmdPlaceholders);
            }
        }
        
        plugin.getMessageManager().send(sender, "help-footer");
    }

    @Override
    public String getPermission() {
        return "townyraider.command";
    }

    @Override
    public String getDescription() {
        return "Shows help information";
    }

    @Override
    public String getUsage() {
        return "/townyraider help";
    }
}