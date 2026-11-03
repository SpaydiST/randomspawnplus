package com.randomspawnplus.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Настройки случайного спавна, общие для всех платформ.
 */
public final class SpawnConfig {

    private final int borderMargin;
    private final int searchAttempts;
    private final int safeSearchRadius;
    private final int heightOffset;
    private final int defaultRadius;
    private final boolean verbose;
    private final Set<String> hazards;
    private final SpawnOptions options;

    public SpawnConfig(int borderMargin, int searchAttempts, int safeSearchRadius,
                       int heightOffset, boolean verbose, Set<String> hazards) {
        this(borderMargin, searchAttempts, safeSearchRadius, heightOffset, 1000, verbose, hazards);
    }

    public SpawnConfig(int borderMargin, int searchAttempts, int safeSearchRadius,
                       int heightOffset, int defaultRadius, boolean verbose, Set<String> hazards) {
        this(borderMargin, searchAttempts, safeSearchRadius, heightOffset, defaultRadius, verbose, hazards,
                SpawnOptions.defaults(defaultRadius));
    }

    public SpawnConfig(int borderMargin, int searchAttempts, int safeSearchRadius,
                       int heightOffset, int defaultRadius, boolean verbose, Set<String> hazards,
                       SpawnOptions options) {
        this.borderMargin = Math.max(0, borderMargin);
        this.searchAttempts = Math.max(1, Math.min(100, searchAttempts));
        this.safeSearchRadius = Math.max(0, Math.min(64, safeSearchRadius));
        // 0 был бы 'ногами в твёрдом блоке': колонка проверяется как твёрдая на y,
        // а точка спавна ставится на y+0 — то есть внутрь блока. Минимум 1.
        this.heightOffset = Math.max(1, heightOffset);
        this.defaultRadius = Math.max(0, defaultRadius);
        this.verbose = verbose;
        this.hazards = towLower(hazards);
        this.options = options;
    }

    public int borderMargin() {
        return borderMargin;
    }

    public int searchAttempts() {
        return searchAttempts;
    }

    public int safeSearchRadius() {
        return safeSearchRadius;
    }

    /**
     * На сколько блоков поднимать ноги игрока над верхним твёрдым блоком (обычно 1).
     */
    public int heightOffset() {
        return heightOffset;
    }

    /**
     * Радиус по умолчанию, если для мира не задан свой через /rspawn radius set.
     * 0 = ограничен только мировой границей (очень широкая зона — обычно бесполезно).
     */
    public int defaultRadius() {
        return defaultRadius;
    }

    public boolean verbose() {
        return verbose;
    }

    public SpawnOptions options() {
        return options;
    }

    public int minRadius(SpawnMode mode) {
        return mode == SpawnMode.FIRST_JOIN ? options.firstJoinMinRadius() : options.respawnMinRadius();
    }

    public int maxRadius(SpawnMode mode) {
        return mode == SpawnMode.FIRST_JOIN ? options.firstJoinMaxRadius() : options.respawnMaxRadius();
    }

    public boolean enabled(SpawnMode mode) {
        return mode == SpawnMode.FIRST_JOIN ? options.firstJoinEnabled() : options.respawnEnabled();
    }

    public int columnsPerTick() {
        return switch (options.profile()) {
            case "solo" -> 48;
            case "large" -> 192;
            default -> 96;
        };
    }

    public int maxColumnsPerSearch() {
        return switch (options.profile()) {
            case "solo" -> 2048;
            case "large" -> 8192;
            default -> 4096;
        };
    }

    public int maxChunksPerTick() {
        return "large".equals(options.profile()) ? 2 : 1;
    }

    public int maxChunksPerSearch() {
        return switch (options.profile()) {
            case "solo" -> 3;
            case "large" -> 12;
            default -> 6;
        };
    }

    public boolean isHazard(String blockName) {
        return hazards.contains(blockName.toLowerCase(java.util.Locale.ROOT));
    }

    public Set<String> hazards() {
        return Collections.unmodifiableSet(hazards);
    }

    private static Set<String> towLower(Set<String> input) {
        Set<String> set = new LinkedHashSet<>();
        for (String s : input) {
            set.add(s.toLowerCase(java.util.Locale.ROOT));
        }
        return Collections.unmodifiableSet(set);
    }
}
