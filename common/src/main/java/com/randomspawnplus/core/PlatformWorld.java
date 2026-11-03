package com.randomspawnplus.core;

/**
 * Платформо-независимое представление мира для поиска безопасной точки спавна.
 */
public interface PlatformWorld {

    /** A single read of a block state, reused for all spawn checks at that position. */
    record BlockInfo(boolean solid, boolean hazard, boolean liquid, boolean lava, boolean tree) { }

    String name();

    /** Координата X центра мировой границы (world border). */
    int centerX();

    /** Координата Z центра мировой границы. */
    int centerZ();

    /** Размер стороны мировой границы в блоках. */
    double size();

    /** Координата X мирового спавна (для радиусного режима). */
    int spawnX();

    /** Координата Z мирового спавна. */
    int spawnZ();

    /**
     * Y верхнего движущего-блокирующего (твёрдого) блока в колонке,
     * или {@link Integer#MIN_VALUE}, если такового нет.
     */
    int topY(int x, int z);

    boolean isSolid(int x, int y, int z);

    boolean isHazard(int x, int y, int z);

    /** {@code true}, если в блоке есть жидкость (вода/лава) независимо от конфига. */
    boolean isLiquid(int x, int y, int z);

    /** Reads the state once where supported; the default keeps older adapters source-compatible. */
    default BlockInfo blockInfo(int x, int y, int z) {
        boolean hazard = isHazard(x, y, z);
        boolean liquid = isLiquid(x, y, z);
        return new BlockInfo(isSolid(x, y, z), hazard, liquid, false, false);
    }

    /** Namespaced biome ID at the supplied location. Empty means that no biome filter can be applied. */
    default String biomeId(int x, int y, int z) {
        return "";
    }

    /** True if another online player is closer than the requested horizontal distance. */
    default boolean hasNearbyPlayer(int x, int z, int distance) {
        return false;
    }

    /** Releases temporary chunk references after a request; adapters may keep the selected chunk. */
    default void finishSearch(SpawnPoint keep) { }

    /** Releases every chunk generated for a failed or discarded reserved point. */
    default void discardSearch() { finishSearch(null); }

    /** Нижняя граница мира (min build height). */
    default int minY() {
        return -64;
    }

    boolean hasChunk(int chunkX, int chunkZ);

    /**
     * Пытается синхронно догенерировать/загрузить чанк {@code (chunkX, chunkZ)}
     * на серверной нити. Используется как запасной вариант при телепорте
     * в свежие, ещё не сгенерированные области.
     *
     * @return {@code true}, если чанк после вызова доступен, иначе {@code false}.
     */
    default boolean ensureChunk(int chunkX, int chunkZ) {
        return false;
    }
}
