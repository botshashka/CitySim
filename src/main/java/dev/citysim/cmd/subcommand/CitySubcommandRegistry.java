package dev.citysim.cmd.subcommand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class CitySubcommandRegistry {

    private final Map<String, CitySubcommand> byKey = new HashMap<>();
    private final Map<String, CitySubcommand> byName = new HashMap<>();

    public void register(CitySubcommand subcommand) {
        Objects.requireNonNull(subcommand, "subcommand");
        String name = subcommand.name().toLowerCase(Locale.ROOT);
        byKey.put(name, subcommand);
        byName.put(name, subcommand);
        for (String alias : subcommand.aliases()) {
            if (alias == null || alias.isEmpty()) {
                continue;
            }
            byKey.put(alias.toLowerCase(Locale.ROOT), subcommand);
        }
    }

    public CitySubcommand get(String key) {
        if (key == null) {
            return null;
        }
        return byKey.get(key.toLowerCase(Locale.ROOT));
    }

    public Collection<CitySubcommand> all() {
        return Collections.unmodifiableCollection(new ArrayList<>(byName.values()));
    }
}
