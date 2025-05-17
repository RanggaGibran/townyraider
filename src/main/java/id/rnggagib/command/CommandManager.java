package id.rnggagib.command;

import id.rnggagib.TownyRaider;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

import java.util.HashMap;
import java.util.Map;

public class CommandManager {
    private final TownyRaider plugin;
    private final Map<String, CommandExecutor> commandExecutors = new HashMap<>();
    private final Map<String, TabCompleter> tabCompleters = new HashMap<>();

    public CommandManager(TownyRaider plugin) {
        this.plugin = plugin;
        registerCommands();
    }

    private void registerCommands() {
        TownyRaiderCommand mainCommand = new TownyRaiderCommand(plugin);
        register("townyraider", mainCommand, mainCommand);
    }

    private void register(String commandName, CommandExecutor executor, TabCompleter tabCompleter) {
        PluginCommand command = plugin.getCommand(commandName);
        
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(tabCompleter);
            
            commandExecutors.put(commandName, executor);
            tabCompleters.put(commandName, tabCompleter);
        } else {
            plugin.getLogger().warning("Could not register command: " + commandName);
        }
    }

    public CommandExecutor getCommandExecutor(String commandName) {
        return commandExecutors.get(commandName);
    }

    public TabCompleter getTabCompleter(String commandName) {
        return tabCompleters.get(commandName);
    }
}