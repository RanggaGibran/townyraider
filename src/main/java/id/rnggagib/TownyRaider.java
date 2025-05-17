package id.rnggagib;

import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import id.rnggagib.config.ConfigManager;
import id.rnggagib.message.MessageManager;
import id.rnggagib.command.CommandManager;
import id.rnggagib.raid.RaidManager;

public class TownyRaider extends JavaPlugin {
    private static final Logger LOGGER = Logger.getLogger("townyraider");
    private ConfigManager configManager;
    private MessageManager messageManager;
    private CommandManager commandManager;
    private RaidManager raidManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        messageManager = new MessageManager(this);
        commandManager = new CommandManager(this);
        raidManager = new RaidManager(this);
        
        LOGGER.info("TownyRaider enabled successfully");
        
        if (configManager.isDebugEnabled()) {
            LOGGER.info("Debug mode enabled");
        }
    }

    @Override
    public void onDisable() {
        if (raidManager != null) {
            raidManager.shutdown();
        }
        
        if (messageManager != null) {
            messageManager.cleanupAdvence();
        }
        
        LOGGER.info("TownyRaider disabled");
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public MessageManager getMessageManager() {
        return messageManager;
    }
    
    public CommandManager getCommandManager() {
        return commandManager;
    }
    
    public RaidManager getRaidManager() {
        return raidManager;
    }
    
    public void reloadPlugin() {
        configManager.reloadConfig();
        
        if (raidManager != null) {
            raidManager.startRaidScheduler();
        }
        
        LOGGER.info("TownyRaider configuration reloaded");
    }
}
