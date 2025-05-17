package id.rnggagib.entity;

import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;

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
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.isCancelled()) return;
        
        if (plugin.getRaiderEntityManager().isRaider(event.getEntity())) {
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                event.setCancelled(true);
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
    
    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (event.isCancelled()) return;
        
        Entity entity = event.getEntity();
        
        if (plugin.getRaiderEntityManager().isRaiderZombie(entity) && entity instanceof Zombie) {
            // Prevent zombies from targeting players
            if (event.getTarget() instanceof Player) {
                event.setCancelled(true);
            }
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