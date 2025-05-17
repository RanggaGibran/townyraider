package id.rnggagib;

import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import id.rnggagib.config.ConfigManager;
import id.rnggagib.message.MessageManager;
import id.rnggagib.command.CommandManager;
import id.rnggagib.raid.RaidManager;
import id.rnggagib.towny.TownyHandler;
import id.rnggagib.entity.RaiderEntityManager;
import id.rnggagib.entity.StealingManager;
import id.rnggagib.entity.RaiderEntityListener;

public class TownyRaider extends JavaPlugin {
    private static final Logger LOGGER = Logger.getLogger("townyraider");
    private ConfigManager configManager;
    private MessageManager messageManager;
    private CommandManager commandManager;
    private RaidManager raidManager;
    private TownyHandler townyHandler;
    private RaiderEntityManager raiderEntityManager;
    private StealingManager stealingManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        messageManager = new MessageManager(this);
        commandManager = new CommandManager(this);
        
        raiderEntityManager = new RaiderEntityManager(this);
        stealingManager = new StealingManager(this);
        
        if (getServer().getPluginManager().isPluginEnabled("Towny")) {
            townyHandler = new TownyHandler(this);
            raidManager = new RaidManager(this);
            
            getServer().getPluginManager().registerEvents(new RaiderEntityListener(this), this);
            stealingManager.startStealingTasks();
            
            LOGGER.info("Towny found and hooked successfully");
        } else {
            LOGGER.severe("Towny plugin not found or not enabled! TownyRaider functionality will be limited.");
        }
        
        LOGGER.info("TownyRaider enabled successfully");
        
        if (configManager.isDebugEnabled()) {
            LOGGER.info("Debug mode enabled");
        }
    }

    @Override
    public void onDisable() {
        if (raiderEntityManager != null) {
            raiderEntityManager.removeAllRaidMobs();
        }
        
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
    
    public TownyHandler getTownyHandler() {
        return townyHandler;
    }
    
    public RaiderEntityManager getRaiderEntityManager() {
        return raiderEntityManager;
    }
    
    public StealingManager getStealingManager() {
        return stealingManager;
    }
    
    public void reloadPlugin() {
        configManager.reloadConfig();
        
        if (raidManager != null) {
            raidManager.startRaidScheduler();
        }
        
        LOGGER.info("TownyRaider configuration reloaded");
    }
}
