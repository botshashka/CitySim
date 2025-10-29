package dev.citysim.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Arrays;

public final class AdventureMessages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private AdventureMessages() {
    }

    public static Component mini(String miniMessage) {
        return MINI.deserialize(miniMessage);
    }

    public static Component colored(String message, NamedTextColor color) {
        return Component.text(message, color);
    }

    public static Component joinLines(Component... components) {
        return Component.join(JoinConfiguration.newlines(), Arrays.asList(components));
    }
}
