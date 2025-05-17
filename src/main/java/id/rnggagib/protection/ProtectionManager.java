package id.rnggagib.protection;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ProtectionManager implements Listener {
    private final TownyRaider plugin;
    private final TownyAPI townyAPI;
    private final Map<String, List<Location>> raidProtectedLocations = new HashMap<>();
    private static final String METADATA_RAID_PROTECTED = "townyraider.raid_protected";
    
    public ProtectionManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.townyAPI = TownyAPI.getInstance();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }
    
    public void addProtectedRaidArea(String townName, Location location) {
        if (!raidProtectedLocations.containsKey(townName)) {
            raidProtectedLocations.put(townName, new ArrayList<>());
        }
        raidProtectedLocations.get(townName).add(location);
    }
    
    public void clearRaidProtection(String townName) {
        raidProtectedLocations.remove(townName);
    }
    
    public boolean isLocationInRaidZone(Location location) {
        for (List<Location> locations : raidProtectedLocations.values()) {
            for (Location raidLoc : locations) {
                if (raidLoc.getWorld().equals(location.getWorld()) &&
                    raidLoc.distance(location) <= plugin.getConfigManager().getRaidProtectionRadius()) {
                    return true;
                }
            }
        }
        return false;
    }
    
    public boolean isBlockProtectedDuringRaid(Block block) {
        if (!isLocationInRaidZone(block.getLocation())) {
            return false;
        }
        
        Material material = block.getType();
        
        return block.getState() instanceof InventoryHolder || 
               plugin.getConfigManager().getProtectedMaterials().contains(material);
    }
    
    public boolean canEntityBypass(Entity entity) {
        return plugin.getRaiderEntityManager().isRaider(entity);
    }
    
    /**
     * Check if an entity is allowed to place blocks (for bridge building)
     */
    public boolean canEntityPlaceBlock(Entity entity, Block block) {
        // Check if the entity is a raider
        if (!canEntityBypass(entity)) {
            return false;
        }
        
        // Ensure the block is in a raid zone
        if (!isLocationInRaidZone(block.getLocation())) {
            return false;
        }
        
        // Ensure bridging is enabled in config
        if (!plugin.getConfigManager().isBridgeBuildingEnabled()) {
            return false;
        }
        
        // Prevent placing blocks on certain materials
        Block blockBelow = block.getRelative(BlockFace.DOWN);
        Material belowType = blockBelow.getType();
        
        // Only allow building on certain materials
        return belowType == Material.WATER || 
               belowType == Material.LAVA || 
               belowType == Material.AIR ||
               belowType.toString().contains("LEAVES");
    }
    
    public void setupProtectionForRaid(ActiveRaid raid) {
        Town town = plugin.getTownyHandler().getTownByName(raid.getTownName());
        if (town == null) return;
        
        Location raidLocation = raid.getLocation();
        if (raidLocation == null) return;
        
        addProtectedRaidArea(town.getName(), raidLocation);
        
        if (plugin.getConfigManager().isRaidDefenseBonusEnabled()) {
            applyDefenseBonusToTown(town);
        }
    }
    
    private void applyDefenseBonusToTown(Town town) {
        List<Player> townResidents = plugin.getTownyHandler().getOnlineTownMembers(town);
        for (Player resident : townResidents) {
            resident.setMetadata("townyraider.defender", new FixedMetadataValue(plugin, true));
        }
    }
    
    public void removeDefenseBonus(Town town) {
        List<Player> townResidents = plugin.getTownyHandler().getOnlineTownMembers(town);
        for (Player resident : townResidents) {
            resident.removeMetadata("townyraider.defender", plugin);
        }
    }
    
    public void cleanupRaidProtection(ActiveRaid raid) {
        Town town = plugin.getTownyHandler().getTownByName(raid.getTownName());
        if (town != null) {
            removeDefenseBonus(town);
        }
        clearRaidProtection(raid.getTownName());
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        
        Block block = event.getBlock();
        Player player = event.getPlayer();
        
        if (isBlockProtectedDuringRaid(block)) {
            Town town = townyAPI.getTown(block.getLocation());
            if (town != null && isTownResident(player, town)) {
                return;
            }
            
            event.setCancelled(true);
            plugin.getMessageManager().send(player, "raid-protection-block-break");
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) return;
        
        Block block = event.getBlock();
        Player player = event.getPlayer();
        
        if (isLocationInRaidZone(block.getLocation())) {
            Town town = townyAPI.getTown(block.getLocation());
            if (town != null && isTownResident(player, town)) {
                return;
            }
            
            if (plugin.getConfigManager().getAllowedDefensiveBlocks().contains(block.getType())) {
                return;
            }
            
            event.setCancelled(true);
            plugin.getMessageManager().send(player, "raid-protection-block-place");
        }
    }
    
    private boolean isTownResident(Player player, Town town) {
        try {
            return town.hasResident(player.getName());
        } catch (Exception e) {
            return false;
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityBreakDoor(EntityBreakDoorEvent event) {
        if (event.isCancelled()) return;
        
        Entity entity = event.getEntity();
        Block block = event.getBlock();
        
        if (canEntityBypass(entity)) {
            return;
        }
        
        if (isLocationInRaidZone(block.getLocation())) {
            event.setCancelled(true);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.isCancelled()) return;
        
        Entity entity = event.getEntity();
        Block block = event.getBlock();
        
        if (canEntityBypass(entity)) {
            return;
        }
        
        if (isLocationInRaidZone(block.getLocation())) {
            event.setCancelled(true);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityInteract(EntityInteractEvent event) {
        if (event.isCancelled()) return;
        
        Entity entity = event.getEntity();
        Block block = event.getBlock();
        
        if (canEntityBypass(entity)) {
            return;
        }
        
        if (isBlockProtectedDuringRaid(block)) {
            event.setCancelled(true);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.isCancelled()) return;
        
        Entity entity = event.getEntity();
        Location location = event.getLocation();
        
        if (canEntityBypass(entity)) {
            event.blockList().removeIf(this::isBlockProtectedDuringRaid);
            return;
        }
        
        if (isLocationInRaidZone(location)) {
            event.setCancelled(true);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        
        Entity damager = event.getDamager();
        Entity damaged = event.getEntity();
        
        if (canEntityBypass(damager)) {
            return;
        }
        
        if (damaged instanceof Player) {
            Player player = (Player) damaged;
            if (player.hasMetadata("townyraider.defender")) {
                event.setDamage(event.getDamage() * 0.8);
            }
            return;
        }
        
        if (isLocationInRaidZone(damaged.getLocation())) {
            if (!(damager instanceof Player)) {
                event.setCancelled(true);
            }
        }
    }
    
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled()) return;
        
        Player player = event.getPlayer();
        Location to = event.getTo();
        Location from = event.getFrom();
        
        if (!isLocationInRaidZone(from) && isLocationInRaidZone(to)) {
            plugin.getMessageManager().send(player, "entering-raid-zone");
        }
    }
    
    public void cleanup() {
        raidProtectedLocations.clear();
    }
    
    public int getRaidProtectionRadius() {
        return plugin.getConfigManager().getRaidProtectionRadius();
    }
}