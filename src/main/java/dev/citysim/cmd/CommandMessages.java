package dev.citysim.cmd;

import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class CommandMessages {

    private CommandMessages() {
    }

    public static Component usage(String message) {
        return AdventureMessages.colored(message, NamedTextColor.WHITE);
    }

    public static Component help(String message) {
        return AdventureMessages.colored(message, NamedTextColor.WHITE);
    }
}
