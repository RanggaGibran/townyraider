package id.rnggagib.entity.ai.coordination;

import id.rnggagib.TownyRaider;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ThreatAssessmentManager {
    private final TownyRaider plugin;
    private final Map<UUID, Double> entityThreatLevels = new HashMap<>();
    
    public ThreatAssessmentManager(TownyRaider plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Calculate threat level of a target based on multiple factors
     */
    public double calculateThreatLevel(LivingEntity target) {
        double threat = 10.0; // Base threat
        
        // Players are more threatening
        if (target instanceof Player) {
            Player player = (Player) target;
            threat += 20.0;
            
            // Equipped items increase threat
            if (player.getEquipment() != null) {
                if (player.getEquipment().getHelmet() != null) threat += 5.0;
                if (player.getEquipment().getChestplate() != null) threat += 7.0;
                if (player.getEquipment().getLeggings() != null) threat += 5.0;
                if (player.getEquipment().getBoots() != null) threat += 3.0;
                
                // Weapon threat
                if (player.getEquipment().getItemInMainHand() != null) {
                    switch (player.getEquipment().getItemInMainHand().getType().name()) {
                        case "DIAMOND_SWORD": threat += 15.0; break;
                        case "IRON_SWORD": threat += 10.0; break;
                        case "STONE_SWORD": threat += 7.0; break;
                        case "BOW": threat += 12.0; break;
                        case "CROSSBOW": threat += 15.0; break;
                    }
                }
            }
            
            // Health factor - wounded players are less threatening
            double healthRatio = player.getHealth() / player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
            threat *= healthRatio;
        }
        
        return threat;
    }
    
    /**
     * Update threat level of an entity
     */
    public void updateThreatLevel(UUID entityId, double threat) {
        entityThreatLevels.put(entityId, threat);
    }
    
    /**
     * Increase threat level when entity damages raiders
     */
    public void increaseThreatOnDamage(UUID entityId, double damage) {
        double current = entityThreatLevels.getOrDefault(entityId, 10.0);
        entityThreatLevels.put(entityId, current + (damage * 2.0));
    }
    
    /**
     * Get highest threat entity from a list
     */
    public Entity getHighestThreatEntity(List<LivingEntity> entities) {
        if (entities.isEmpty()) return null;
        
        Entity highest = entities.get(0);
        double highestThreat = entityThreatLevels.getOrDefault(highest.getUniqueId(), 
                                                              calculateThreatLevel((LivingEntity)highest));
        
        for (Entity entity : entities) {
            double threat = entityThreatLevels.getOrDefault(entity.getUniqueId(), 
                                                           calculateThreatLevel((LivingEntity)entity));
            if (threat > highestThreat) {
                highestThreat = threat;
                highest = entity;
            }
        }
        
        return highest;
    }
    
    /**
     * Clear threat data for a raid when it ends
     */
    public void clearRaidThreats() {
        entityThreatLevels.clear();
    }
}