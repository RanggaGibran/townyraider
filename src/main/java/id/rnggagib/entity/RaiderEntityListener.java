package id.rnggagib.entity;

import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

import java.util.UUID;

public class RaiderEntityListener implements Listener {
    private final TownyRaider plugin;
    
    public RaiderEntityListener(TownyRaider plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        // Skip if already cancelled
        if (event.isCancelled()) return;
        
        // Check if entity is a raider
        if (plugin.getRaiderEntityManager().isRaider(event.getEntity())) {
            // Cancel fire and burning damage
            if (event.getCause() == EntityDamageEvent.DamageCause.FIRE || 
                event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK ||
                event.getCause() == EntityDamageEvent.DamageCause.LAVA ||
                event.getCause() == EntityDamageEvent.DamageCause.HOT_FLOOR) {
                
                event.setCancelled(true);
                
                // Ensure fire is visually removed
                event.getEntity().setFireTicks(0);
            }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        
        Entity damager = event.getDamager();
        Entity target = event.getEntity();
        
        if (plugin.getRaiderEntityManager().isRaider(damager) && target instanceof Player) {
            // Get real damager if it's a projectile
            if (damager instanceof Projectile) {
                Projectile projectile = (Projectile) damager;
                if (projectile.getShooter() instanceof Entity) {
                    damager = (Entity) projectile.getShooter();
                }
            }
            
            if (plugin.getRaiderEntityManager().isRaiderZombie(damager)) {
                // Prevent zombies from damaging players
                event.setCancelled(true);
                return;
            }
        }
        
        if (plugin.getRaiderEntityManager().isRaider(target)) {
            if (damager instanceof Player) {
                Player player = (Player) damager;
                UUID raidId = plugin.getRaiderEntityManager().getRaidId(target);
                
                if (raidId != null) {
                    ActiveRaid raid = getRaidById(raidId);
                    if (raid != null && plugin.getTownyHandler().getOnlineTownMembers(
                            plugin.getTownyHandler().getTownByName(raid.getTownName())).contains(player)) {
                        // Town members can damage raiders
                        return;
                    }
                }
            } else if (plugin.getRaiderEntityManager().isRaider(damager)) {
                // Prevent raiders from damaging each other
                event.setCancelled(true);
                return;
            }
        }
    }
    
    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityDamageByPlayer(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        
        Entity entity = event.getEntity();
        
        if (plugin.getRaiderEntityManager().isRaider(entity) && entity instanceof LivingEntity) {
            LivingEntity raider = (LivingEntity) entity;
            
            // If health drops below 30%, give regeneration for recovery
            if (raider.getHealth() - event.getFinalDamage() < raider.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() * 0.3) {
                raider.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.REGENERATION, 
                    100, // 5 seconds
                    1, // Regeneration II
                    false, 
                    true)
                );
            }
            
            // Cap damage to prevent one-shot kills
            double maxDamagePercent = 0.4; // Max 40% health in one hit
            double maxDamage = raider.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() * maxDamagePercent;
            
            if (event.getDamage() > maxDamage) {
                event.setDamage(maxDamage);
            }
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        
        if (plugin.getRaiderEntityManager().isRaider(entity)) {
            UUID raidId = plugin.getRaiderEntityManager().getRaidId(entity);
            if (raidId != null) {
                ActiveRaid raid = getRaidById(raidId);
                if (raid != null) {
                    raid.removeRaiderEntity(entity.getUniqueId());
                    
                    plugin.getLogger().info("Raider " + entity.getType() + 
                            " died during raid " + raid.getId() + 
                            ". Remaining raiders: " + raid.getRaiderEntities().size());
                }
            }
            
            plugin.getStealingManager().resetTheftCount(entity.getUniqueId());
            
            // Clear drops from raiders
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRaiderDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        
        // Check if this is a raider entity
        if (!plugin.getRaiderEntityManager().isRaider(entity)) {
            return;
        }
        
        // Get raid ID from entity
        UUID raidId = plugin.getRaiderEntityManager().getRaidId(entity);
        if (raidId == null) {
            return;
        }
        
        // Find the active raid
        ActiveRaid raid = getRaidById(raidId);
        if (raid == null) {
            return;
        }
        
        // Remove entity from raid
        raid.removeRaiderEntity(entity.getUniqueId());
        
        // Log the raider death
        String raiderType = entity instanceof Zombie ? "ZOMBIE" : "SKELETON";
        plugin.getLogger().info("Raider " + raiderType + " died during raid " + raidId + 
                               ". Remaining raiders: " + raid.getRaiderEntities().size());
        
        // Check if this was the last raider
        if (raid.getRaiderEntities().isEmpty()) {
            // All raiders eliminated - end the raid
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Small delay to allow for any final updates
                plugin.getLogger().info("All raiders eliminated for raid " + raidId + 
                                       ". Ending raid with " + raid.getStolenItems() + " items stolen.");
                plugin.getRaidManager().endRaid(raidId);
            });
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        Entity entity = event.getEntity();
        
        // Always cancel targeting for raider zombies
        if (plugin.getRaiderEntityManager().isRaiderZombie(entity)) {
            event.setCancelled(true);
        } else if (plugin.getRaiderEntityManager().isRaiderSkeleton(entity) && entity instanceof Skeleton) {
            // Let skeletons target players
            if (event.getTarget() instanceof Player) {
                // Allow targeting
            }
        }
    }
    
    private ActiveRaid getRaidById(UUID raidId) {
        for (ActiveRaid raid : plugin.getRaidManager().getActiveRaids()) {
            if (raid.getId().equals(raidId)) {
                return raid;
            }
        }
        return null;
    }
}