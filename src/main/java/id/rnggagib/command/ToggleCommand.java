package id.rnggagib.command;

import id.rnggagib.TownyRaider;
import id.rnggagib.message.MessageManager;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToggleCommand implements SubCommand {
    private final TownyRaider plugin;

    public ToggleCommand(TownyRaider plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.getRaidManager().toggleRaids();
        
        Map<String, String> placeholders = MessageManager.createPlaceholders(
            "status", plugin.getRaidManager().isRaidsEnabled() ? "enabled" : "disabled"
        );
        
        plugin.getMessageManager().send(sender, "raids-toggled", placeholders);
    }

    @Override
    public String getPermission() {
        return "townyraider.toggle";
    }

    @Override
    public String getDescription() {
        return "Toggles raid functionality on/off";
    }

    @Override
    public String getUsage() {
        return "/townyraider toggle";
    }
}