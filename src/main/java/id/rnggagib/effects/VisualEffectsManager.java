package id.rnggagib.effects;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import com.palmergames.bukkit.towny.object.Town;

import id.rnggagib.TownyRaider;
import id.rnggagib.raid.ActiveRaid;

public class VisualEffectsManager {
    private final TownyRaider plugin;
    private final Map<UUID, BossBar> raidBossBars = new HashMap<>();
    private final Map<UUID, BukkitRunnable> particleEffects = new HashMap<>();
    
    public VisualEffectsManager(TownyRaider plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Apply glowing effect to a raider entity
     */
    public void applyGlowEffect(LivingEntity entity, String raiderType) {
        if (entity == null) return;
        
        // Check if glow effects are enabled
        if (!plugin.getConfigManager().getConfig().getBoolean("raids.effects.glow-enabled", true)) {
            return;
        }
        
        String colorName = plugin.getConfigManager().getMobConfig(raiderType).getString("glow-color", "RED");
        org.bukkit.scoreboard.Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        
        // Create or get the team
        String teamName = "raiders_" + raiderType.toLowerCase();
        org.bukkit.scoreboard.Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            try {
                org.bukkit.ChatColor color = org.bukkit.ChatColor.valueOf(colorName);
                team.setColor(color);
            } catch (IllegalArgumentException e) {
                team.setColor(org.bukkit.ChatColor.RED);
            }
        }
        
        // Add entity to team
        team.addEntry(entity.getUniqueId().toString());
        
        // Apply glowing effect
        entity.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, false, false));
    }
    
    /**
     * Remove glowing effect from an entity
     */
    public void removeGlowEffect(Entity entity) {
        if (entity instanceof LivingEntity) {
            ((LivingEntity) entity).removePotionEffect(PotionEffectType.GLOWING);
        }
    }
    
    /**
     * Create a boss bar for raid progress
     */
    public void createRaidBossBar(ActiveRaid raid) {
        // Check if boss bar is enabled
        if (!plugin.getConfigManager().getConfig().getBoolean("raids.effects.bossbar-enabled", true)) {
            return;
        }
        
        String townName = raid.getTownName();
        String title = plugin.getConfigManager().getMessage("raid-bossbar-title")
                .replace("{town}", townName);
        
        BossBar bossBar = Bukkit.createBossBar(
            title,
            BarColor.RED,
            BarStyle.SOLID
        );
        
        // Get players in town and add them to the boss bar
        List<Player> townPlayers = plugin.getTownyHandler().getOnlineTownMembers(
                plugin.getTownyHandler().getTownByName(townName));
        
        for (Player player : townPlayers) {
            bossBar.addPlayer(player);
        }
        
        raidBossBars.put(raid.getId(), bossBar);
        
        // Schedule boss bar update
        updateRaidProgress(raid);
    }
    
    /**
     * Update the raid boss bar progress
     */
    public void updateRaidProgress(ActiveRaid raid) {
        BossBar bossBar = raidBossBars.get(raid.getId());
        if (bossBar == null) return;
        
        int maxStolenItems = plugin.getConfigManager().getConfig().getInt("raids.max-stolen-items", 20);
        double progress = (double) raid.getStolenItems() / maxStolenItems;
        progress = Math.min(1.0, progress);
        
        bossBar.setProgress(progress);
        
        String title = plugin.getConfigManager().getMessage("raid-bossbar-title")
                .replace("{town}", raid.getTownName()) + 
                " - " + raid.getStolenItems() + " items stolen";
        
        bossBar.setTitle(title);
    }
    
    /**
     * Remove the raid boss bar
     */
    public void removeRaidBossBar(UUID raidId) {
        BossBar bossBar = raidBossBars.remove(raidId);
        if (bossBar != null) {
            bossBar.removeAll();
        }
        
        BukkitRunnable particleTask = particleEffects.remove(raidId);
        if (particleTask != null) {
            particleTask.cancel();
        }
    }
    
    /**
     * Show steal particles at a location
     */
    public void showStealEffects(Location location) {
        // Check if steal effects are enabled
        if (!plugin.getConfigManager().getConfig().getBoolean("raids.effects.steal-effects-enabled", true)) {
            return;
        }
        
        if (location == null || location.getWorld() == null) return;
        
        // Dust particles in a circular pattern
        location.getWorld().spawnParticle(
            Particle.REDSTONE, 
            location.clone().add(0, 1, 0), 
            30, 
            0.5, 0.5, 0.5, 
            new Particle.DustOptions(Color.RED, 1.0f)
        );
        
        // Small explosion effect
        location.getWorld().spawnParticle(
            Particle.SMOKE_NORMAL, 
            location.clone().add(0, 1, 0), 
            20, 
            0.3, 0.3, 0.3, 
            0.05
        );
    }
    
    /**
     * Create town border effects for a raid
     */
    public void createRaidBorderEffects(ActiveRaid raid) {
        // Check if border effects are enabled
        if (!plugin.getConfigManager().getConfig().getBoolean("raids.effects.border-effects-enabled", true)) {
            return;
        }
        
        Town town = plugin.getTownyHandler().getTownByName(raid.getTownName());
        if (town == null) return;
        
        List<Location> borderPoints = plugin.getTownyHandler().getTownBorderPoints(town, 1);
        if (borderPoints.isEmpty()) return;
        
        BukkitRunnable particleTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.getRaidManager().getActiveRaids().contains(raid)) {
                    this.cancel();
                    return;
                }
                
                for (Location point : borderPoints) {
                    // Using end rod particles instead of barrier which might not be available
                    point.getWorld().spawnParticle(
                        Particle.END_ROD,
                        point.clone().add(0, 20, 0),  // High up in the air
                        1,
                        0, 0, 0,
                        0
                    );
                    
                    // Beam from sky to ground
                    for (int y = 1; y < 20; y++) {
                        if (y % 5 == 0) {  // Every 5 blocks
                            point.getWorld().spawnParticle(
                                Particle.REDSTONE,
                                point.clone().add(0, y, 0),
                                1,
                                0, 0, 0,
                                new Particle.DustOptions(Color.RED, 1.5f)
                            );
                        }
                    }
                }
            }
        };
        
        particleTask.runTaskTimer(plugin, 0L, 40L);  // Every 2 seconds
        particleEffects.put(raid.getId(), particleTask);
    }
    
    /**
     * Clean up all visual effects
     */
    public void cleanup() {
        // Remove all boss bars
        for (BossBar bossBar : raidBossBars.values()) {
            bossBar.removeAll();
        }
        raidBossBars.clear();
        
        // Cancel all particle tasks
        for (BukkitRunnable task : particleEffects.values()) {
            task.cancel();
        }
        particleEffects.clear();
    }
}