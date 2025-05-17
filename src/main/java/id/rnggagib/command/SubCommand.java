package id.rnggagib.command;

import org.bukkit.command.CommandSender;
import java.util.ArrayList;
import java.util.List;

public interface SubCommand {
    void execute(CommandSender sender, String[] args);
    String getPermission();
    String getDescription();
    String getUsage();
    
    default List<String> tabComplete(CommandSender sender, String[] args) {
        return new ArrayList<>();
    }
}