package dev.citysim.stats.jobs;

import org.bukkit.Material;
import org.bukkit.entity.Villager.Profession;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class JobSiteAssignments {
    private static final JobSiteAssignments EMPTY = new JobSiteAssignments(new HashMap<>(), new EnumMap<>(Material.class));

    private final Map<Profession, EnumSet<Material>> byProfession;
    private final EnumMap<Material, Profession> byMaterial;
    private final EnumSet<Material> trackedMaterials;

    private JobSiteAssignments(Map<Profession, EnumSet<Material>> byProfession,
                               EnumMap<Material, Profession> byMaterial) {
        this.byProfession = byProfession;
        this.byMaterial = byMaterial;
        EnumSet<Material> tracked = EnumSet.noneOf(Material.class);
        tracked.addAll(byMaterial.keySet());
        this.trackedMaterials = tracked;
    }

    public static JobSiteAssignments empty() {
        return EMPTY;
    }

    public static JobSiteAssignments of(Map<Profession, EnumSet<Material>> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            return EMPTY;
        }
        Map<Profession, EnumSet<Material>> byProfession = new HashMap<>();
        EnumMap<Material, Profession> byMaterial = new EnumMap<>(Material.class);
        for (Map.Entry<Profession, EnumSet<Material>> entry : assignments.entrySet()) {
            Profession profession = entry.getKey();
            if (profession == null) {
                continue;
            }
            EnumSet<Material> materials = entry.getValue();
            if (materials == null || materials.isEmpty()) {
                byProfession.remove(profession);
                continue;
            }
            EnumSet<Material> copy = EnumSet.copyOf(materials);
            byProfession.put(profession, copy);
            for (Material material : copy) {
                if (material == null) {
                    continue;
                }
                byMaterial.put(material, profession);
            }
        }
        if (byMaterial.isEmpty()) {
            return EMPTY;
        }
        return new JobSiteAssignments(byProfession, byMaterial);
    }

    public boolean isTracked(Material material) {
        return material != null && trackedMaterials.contains(material);
    }

    public Profession professionFor(Material material) {
        if (material == null) {
            return null;
        }
        return byMaterial.get(material);
    }

    public boolean isEmpty() {
        return trackedMaterials.isEmpty();
    }

    public Set<Material> trackedMaterials() {
        if (trackedMaterials.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(trackedMaterials);
    }

    public Map<Profession, EnumSet<Material>> asMap() {
        Map<Profession, EnumSet<Material>> copy = new HashMap<>();
        for (Map.Entry<Profession, EnumSet<Material>> entry : byProfession.entrySet()) {
            copy.put(entry.getKey(), EnumSet.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }
}
