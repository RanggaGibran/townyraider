package id.rnggagib.command;

import id.rnggagib.TownyRaider;
import org.bukkit.command.CommandSender;

public class ReloadCommand implements SubCommand {
    private final TownyRaider plugin;

    public ReloadCommand(TownyRaider plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.reloadPlugin();
        plugin.getMessageManager().send(sender, "config-reloaded");
    }

    @Override
    public String getPermission() {
        return "townyraider.reload";
    }

    @Override
    public String getDescription() {
        return "Reloads the plugin configuration";
    }

    @Override
    public String getUsage() {
        return "/townyraider reload";
    }
}