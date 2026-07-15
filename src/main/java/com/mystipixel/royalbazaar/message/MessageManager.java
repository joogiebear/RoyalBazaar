package com.mystipixel.royalbazaar.message;

import com.mystipixel.royalbazaar.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

/**
 * Loads user-facing text from {@code messages.yml} (every key has an inline fallback, so the file is
 * optional) and renders it through Adventure. Reloadable via {@code /bazaar reload}.
 */
public final class MessageManager {

    private final JavaPlugin plugin;
    private FileConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        saveDefault();
        reload();
    }

    public void reload() {
        this.messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
    }

    private void saveDefault() {
        if (!new File(plugin.getDataFolder(), "messages.yml").exists()) {
            plugin.saveResource("messages.yml", false);
        }
    }

    public String prefix() {
        return messages.getString("prefix", "");
    }

    public String get(String key, String fallback) {
        return messages.getString(key, fallback);
    }

    public String format(String key, String fallback, Map<String, String> placeholders) {
        String value = get(key, fallback);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            value = value.replace("{" + e.getKey() + "}", e.getValue());
        }
        return value;
    }

    public Component component(String key, String fallback) {
        return Text.chat(prefix() + get(key, fallback));
    }

    public Component component(String key, String fallback, Map<String, String> placeholders) {
        return Text.chat(prefix() + format(key, fallback, placeholders));
    }

    public void send(CommandSender sender, String key, String fallback) {
        sender.sendMessage(component(key, fallback));
    }

    public void send(CommandSender sender, String key, String fallback, Map<String, String> placeholders) {
        sender.sendMessage(component(key, fallback, placeholders));
    }
}
