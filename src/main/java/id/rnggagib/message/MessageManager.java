package id.rnggagib.message;

import id.rnggagib.TownyRaider;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
        TagResolver.Builder builder = TagResolver.builder();
        
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            builder.resolver(Placeholder.parsed(entry.getKey(), entry.getValue()));
        }
        
        return miniMessage.deserialize(message, builder.build());
    }

    public Component getPrefixedMessage(String messageKey, Map<String, String> placeholders) {
        String prefix = plugin.getConfigManager().getPrefix();
        String message = plugin.getConfigManager().getMessage(messageKey);
        
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

    public void sendTitle(Player player, String titleKey, String subtitleKey) {
        sendTitle(player, titleKey, subtitleKey, new HashMap<>());
    }

    public void sendTitle(Player player, String titleKey, String subtitleKey, Map<String, String> placeholders) {
        Component title = format(plugin.getConfigManager().getMessage(titleKey), placeholders);
        Component subtitle = format(plugin.getConfigManager().getMessage(subtitleKey), placeholders);
        
        Title.Times times = Title.Times.of(
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
}