package id.rnggagib.command;

import id.rnggagib.TownyRaider;
import id.rnggagib.message.MessageManager;
import id.rnggagib.raid.ActiveRaid;
import org.bukkit.command.CommandSender;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StatusCommand implements SubCommand {
    private final TownyRaider plugin;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public StatusCommand(TownyRaider plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        boolean raidsEnabled = plugin.getRaidManager().isRaidsEnabled();
        List<ActiveRaid> activeRaids = plugin.getRaidManager().getActiveRaids();
        
        Map<String, String> placeholders = MessageManager.createPlaceholders(
            "enabled", raidsEnabled ? "enabled" : "disabled",
            "active_count", String.valueOf(activeRaids.size())
        );
        
        plugin.getMessageManager().send(sender, "status-header", placeholders);
        
        if (!activeRaids.isEmpty()) {
            for (ActiveRaid raid : activeRaids) {
                Map<String, String> raidPlaceholders = MessageManager.createPlaceholders(
                    "town", raid.getTownName(),
                    "start_time", raid.getStartTime().format(FORMATTER),
                    "stolen", String.valueOf(raid.getStolenItems())
                );
                plugin.getMessageManager().send(sender, "status-raid", raidPlaceholders);
            }
        } else {
            plugin.getMessageManager().send(sender, "status-no-raids");
        }
    }

    @Override
    public String getPermission() {
        return "townyraider.command";
    }

    @Override
    public String getDescription() {
        return "Shows the current raid status";
    }

    @Override
    public String getUsage() {
        return "/townyraider status";
    }
}