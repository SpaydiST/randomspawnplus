package com.randomspawnplus.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Синтетический мир для тестов: плоская поверхность на y=4 с опциональными
 * ловушками в конкретных колонках. Чанки либо загружены сразу, либо
 * включаются методом ensureChunk (симуляция догенерации).
 */
final class FakeWorld implements PlatformWorld {

    private final String name;
    private final int centerX;
    private final int centerZ;
    private final double size;
    private final int spawnX;
    private final int spawnZ;
    private final int surfaceY = 4;
    private final Map<Long, Boolean> hazards = new HashMap<>();
    private final Map<Long, Integer> nonSolidTop = new HashMap<>();
    private final Map<Long, Integer> fluidHazardTop = new HashMap<>();
    private final boolean chunksLoaded;
    private java.util.Set<String> hazardBlocks = java.util.Set.of("water", "lava");

    FakeWorld(String name, int centerX, int centerZ, int size, int spawnX, int spawnZ, boolean chunksLoaded) {
        this.name = name;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.size = size;
        this.spawnX = spawnX;
        this.spawnZ = spawnZ;
        this.chunksLoaded = chunksLoaded;
    }

    FakeWorld hazard(int x, int z) {
        hazards.put(blockKey(x, z), Boolean.TRUE);
        return this;
    }

    /** Задаёт, какие жидкости считаются «ловушкой» (по умолчанию вода и лава). */
    FakeWorld hazardSet(java.util.Set<String> blocks) {
        this.hazardBlocks = blocks;
        return this;
    }

    /**
     * Столбец с несплошной «крышей» (снег, трава-высокая и т.п.) поверх
     * твёрдой земли: {@link #topY} указывает на несплошной верхний блок,
     * как это даёт MOTION_BLOCKING-хейтмапа для снега.
     */
    FakeWorld snowColumn(int x, int z, int depth) {
        nonSolidTop.put(blockKey(x, z), depth);
        return this;
    }

    /**
     * Столбец, залитый опасной жидкостью (вода/лава) поверх твёрдой земли.
     * Верхние {@code depth} блоков несплошные и опасные.
     */
    FakeWorld waterColumn(int x, int z, int depth) {
        snowColumn(x, z, depth);
        fluidHazardTop.put(blockKey(x, z), depth);
        return this;
    }

    private final java.util.Set<Long> voidColumns = new java.util.HashSet<>();

    FakeWorld voidColumn(int x, int z) {
        voidColumns.add(blockKey(x, z));
        return this;
    }

    boolean isHazardCol(int x, int z) {
        return Boolean.TRUE.equals(hazards.get(blockKey(x, z)));
    }

    private static long blockKey(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    private static long chunkKey(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int centerX() {
        return centerX;
    }

    @Override
    public int centerZ() {
        return centerZ;
    }

    @Override
    public double size() {
        return size;
    }

    @Override
    public int spawnX() {
        return spawnX;
    }

    @Override
    public int spawnZ() {
        return spawnZ;
    }

    @Override
    public int topY(int x, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return Integer.MIN_VALUE;
        }
        return surfaceY + nonSolidTop.getOrDefault(blockKey(x, z), 0);
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4) || voidColumns.contains(blockKey(x, z))) {
            return false;
        }
        return y <= surfaceY;
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return false;
        }
        long key = blockKey(x, z);
        if (y == surfaceY && isHazardCol(x, z)) {
            return true;
        }
        int fluidTop = fluidHazardTop.getOrDefault(key, 0);
        return y > surfaceY && y <= surfaceY + fluidTop && hazardBlocks.contains("water");
    }

    @Override
    public boolean isLiquid(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return false;
        }
        int fluidTop = fluidHazardTop.getOrDefault(blockKey(x, z), 0);
        return y > surfaceY && y <= surfaceY + fluidTop;
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return chunksLoaded || loadedChunks.contains(chunkKey(x, z));
    }

    private final java.util.Set<Long> loadedChunks = new java.util.HashSet<>();

    @Override
    public boolean ensureChunk(int chunkX, int chunkZ) {
        loadedChunks.add(chunkKey(chunkX, chunkZ));
        return hasChunk(chunkX, chunkZ);
    }
}