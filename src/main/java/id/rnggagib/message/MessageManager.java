package id.rnggagib.message;

import id.rnggagib.TownyRaider;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import com.palmergames.bukkit.towny.object.Town;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MessageManager {
    private final TownyRaider plugin;
    private final BukkitAudiences adventure;
    private final MiniMessage miniMessage;

    public MessageManager(TownyRaider plugin) {
        this.plugin = plugin;
        this.adventure = BukkitAudiences.create(plugin);
        this.miniMessage = MiniMessage.miniMessage();
    }

    public Component format(String message) {
        return miniMessage.deserialize(message);
    }

    public Component format(String message, Map<String, String> placeholders) {
        // First replace traditional placeholders in format {key}
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        
        // Then build the TagResolver for MiniMessage formatting
        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            builder.resolver(Placeholder.parsed(entry.getKey(), entry.getValue()));
        }
        
        return miniMessage.deserialize(message, builder.build());
    }

    public Component getPrefixedMessage(String messageKey, Map<String, String> placeholders) {
        String prefix = plugin.getConfigManager().getPrefix();
        String message = plugin.getConfigManager().getMessage(messageKey);
        
        // First replace traditional placeholders in format {key}
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        
        // Then apply MiniMessage formatting
        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            builder.resolver(Placeholder.parsed(entry.getKey(), entry.getValue()));
        }
        
        return miniMessage.deserialize(prefix + message, builder.build());
    }

    public void send(CommandSender sender, String messageKey) {
        send(sender, messageKey, new HashMap<>());
    }

    public void send(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        Component message = getPrefixedMessage(messageKey, placeholders);
        
        if (sender instanceof Player) {
            adventure.player((Player) sender).sendMessage(message);
        } else {
            adventure.sender(sender).sendMessage(message);
        }
    }

    public void broadcast(String messageKey) {
        broadcast(messageKey, new HashMap<>());
    }

    public void broadcast(String messageKey, Map<String, String> placeholders) {
        Component message = getPrefixedMessage(messageKey, placeholders);
        adventure.all().sendMessage(message);
    }

    public void sendToPlayers(Collection<? extends Player> players, String messageKey, Map<String, String> placeholders) {
        Component message = getPrefixedMessage(messageKey, placeholders);
        
        for (Player player : players) {
            adventure.player(player).sendMessage(message);
        }
    }

    /**
     * Send a message to all online members of a town
     */
    public void sendToTown(Town town, String messageKey, Map<String, String> placeholders) {
        List<Player> townMembers = plugin.getTownyHandler().getOnlineTownMembers(town);
        sendToPlayers(townMembers, messageKey, placeholders);
    }

    public void sendTitle(Player player, String titleKey, String subtitleKey) {
        sendTitle(player, titleKey, subtitleKey, new HashMap<>());
    }

    public void sendTitle(Player player, String titleKey, String subtitleKey, Map<String, String> placeholders) {
        Component title = format(plugin.getConfigManager().getMessage(titleKey), placeholders);
        Component subtitle = format(plugin.getConfigManager().getMessage(subtitleKey), placeholders);
        
        Title.Times times = Title.Times.times(
            Duration.ofMillis(500),
            Duration.ofMillis(3500),
            Duration.ofMillis(1000)
        );
        
        adventure.player(player).showTitle(Title.title(title, subtitle, times));
    }

    public void sendActionBar(Player player, String messageKey, Map<String, String> placeholders) {
        Component message = format(plugin.getConfigManager().getMessage(messageKey), placeholders);
        adventure.player(player).sendActionBar(message);
    }

    public void playSound(Player player, Sound sound) {
        player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    public void cleanupAdvence() {
        if (adventure != null) {
            adventure.close();
        }
    }

    public static HashMap<String, String> createPlaceholders(Object... args) {
        HashMap<String, String> placeholders = new HashMap<>();
        
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 < args.length) {
                String key = args[i].toString();
                String value = args[i + 1].toString();
                placeholders.put(key, value);
            }
        }
        
        return placeholders;
    }

    /**
     * Converts a Component to legacy string format with color codes
     * @param component The component to convert
     * @return A legacy string representation with color codes
     */
    public String toLegacy(Component component) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
            .serialize(component);
    }
}