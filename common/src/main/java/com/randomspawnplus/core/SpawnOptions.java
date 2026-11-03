package com.randomspawnplus.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Optional policies shared by Paper, Fabric, and NeoForge. */
public record SpawnOptions(
        int firstJoinMinRadius,
        int firstJoinMaxRadius,
        int respawnMinRadius,
        int respawnMaxRadius,
        boolean firstJoinEnabled,
        boolean respawnEnabled,
        String profile,
        int playerSpacing,
        int lavaSafetyRadius,
        boolean avoidTrees,
        boolean avoidNarrowLedges,
        Set<String> excludedBiomes,
        int reservePoints
) {
    public SpawnOptions {
        firstJoinMinRadius = Math.max(0, firstJoinMinRadius);
        firstJoinMaxRadius = Math.max(0, firstJoinMaxRadius);
        respawnMinRadius = Math.max(0, respawnMinRadius);
        respawnMaxRadius = Math.max(0, respawnMaxRadius);
        profile = normalizeProfile(profile);
        playerSpacing = Math.max(0, playerSpacing);
        lavaSafetyRadius = Math.max(0, Math.min(16, lavaSafetyRadius));
        excludedBiomes = normalize(excludedBiomes);
        reservePoints = Math.max(0, Math.min(32, reservePoints));
    }

    public static SpawnOptions defaults(int defaultRadius) {
        return new SpawnOptions(500, Math.max(0, defaultRadius), 0, 1000,
                true, true, "small", 128, 4, true, false,
                Set.of(), 4);
    }

    public static String normalizeProfile(String value) {
        String name = value == null ? "small" : value.trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "solo", "small", "large" -> name;
            default -> "small";
        };
    }

    private static Set<String> normalize(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String id = value.trim().toLowerCase(Locale.ROOT);
            result.add(id.contains(":") ? id : "minecraft:" + id);
        }
        return Collections.unmodifiableSet(result);
    }
}
