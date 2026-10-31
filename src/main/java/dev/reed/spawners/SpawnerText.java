package dev.reed.spawners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

final class SpawnerText {
    final TextColor accent;
    final TextColor muted;
    final TextColor error;
    private final boolean smallCaps;
    private final FileConfiguration config;

    SpawnerText(FileConfiguration config) {
        this.config = config;
        smallCaps = config.getBoolean("style.small-caps", true);
        accent = color("accent");
        muted = color("text");
        error = color("error");
    }

    private TextColor color(String key) {
        TextColor color = TextColor.fromHexString(config.getString("style." + key, ""));
        if (color == null) throw new IllegalArgumentException("Invalid style." + key + " color");
        return color;
    }

    Component line(String text, TextColor color, String... values) {
        return format(text, color, smallCaps, values);
    }

    void reply(CommandSender sender, String key, String... values) {
        send(sender, key, muted, values);
    }

    void error(CommandSender sender, String key, String... values) {
        send(sender, key, error, values);
    }

    private void send(CommandSender sender, String key, TextColor color, String... values) {
        boolean small = smallCaps && sender instanceof Player;
        String message = config.getString("messages." + key, key);
        sender.sendMessage(format(message, color, small, values));
    }

    private Component format(String text, TextColor color, boolean small, String... values) {
        Component component = Component.text(small ? small(text) : text, color).decoration(TextDecoration.ITALIC, false);
        for (int i = 0; i < values.length; i++) {
            String token = "{" + i + "}";
            Component value = Component.text(values[i], accent);
            component = component.replaceText(builder -> builder.matchLiteral(token).replacement(value));
        }
        return component;
    }

    private String small(String text) {
        String letters = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') c = (char) (c + 'a' - 'A');
            result.append(c >= 'a' && c <= 'z' ? letters.charAt(c - 'a') : c);
        }
        return result.toString();
    }
}
