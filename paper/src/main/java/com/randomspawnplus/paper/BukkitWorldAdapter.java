package com.randomspawnplus.paper;

import com.randomspawnplus.core.PlatformWorld;
import com.randomspawnplus.core.SpawnConfig;
import com.randomspawnplus.core.SpawnPoint;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Адаптер Bukkit-мира к платформо-независимому {@link PlatformWorld}.
 * Все обращения к блокам/чанкам идут только для уже загруженных чанков.
 * Чанки, догруженные {@link #ensureChunk}, учитываются и могут быть выгружены
 * через {@link #unloadTemporaryChunks(SpawnPoint)} сразу после поиска.
 * Поиск выполняется порциями в основном потоке; Folia не поддерживается.
 */
public final class BukkitWorldAdapter implements PlatformWorld {

    private final World world;
    private final SpawnConfig config;
    private final Set<Long> loadedTemporarily = new LinkedHashSet<>();

    public BukkitWorldAdapter(World world, SpawnConfig config) {
        this.world = world;
        this.config = config;
    }

    @Override
    public String name() {
        return world.getName();
    }

    @Override
    public int centerX() {
        return (int) Math.floor(world.getWorldBorder().getCenter().getX());
    }

    @Override
    public int centerZ() {
        return (int) Math.floor(world.getWorldBorder().getCenter().getZ());
    }

    @Override
    public double size() {
        return world.getWorldBorder().getSize();
    }

    @Override
    public int spawnX() {
        return world.getSpawnLocation().getBlockX();
    }

    @Override
    public int spawnZ() {
        return world.getSpawnLocation().getBlockZ();
    }

    @Override
    public int minY() {
        return world.getMinHeight();
    }

    @Override
    public int topY(int x, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return Integer.MIN_VALUE;
        }
        int min = world.getMinHeight();
        int y = world.getHighestBlockYAt(x, z);
        while (y >= min) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isSolid()) {
                if (type == Material.BEDROCK) {
                    return findSubCeilingFloor(x, y - 1, z, min);
                }
                return y;
            }
            y--;
        }
        return Integer.MIN_VALUE;
    }

    private int findSubCeilingFloor(int x, int startY, int z, int min) {
        for (int y = startY; y >= min; y--) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (type.isSolid() && type != Material.BEDROCK
                    && !world.getBlockAt(x, y + 1, z).getType().isSolid()
                    && !world.getBlockAt(x, y + 2, z).getType().isSolid()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return false;
        }
        return world.getBlockAt(x, y, z).getType().isSolid();
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return false;
        }
        return config.isHazard(world.getBlockAt(x, y, z).getType().getKey().getKey());
    }

    @Override
    public boolean isLiquid(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return false;
        }
        return world.getBlockAt(x, y, z).isLiquid();
    }

    @Override
    public BlockInfo blockInfo(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return new BlockInfo(false, false, false, false, false);
        }
        Block block = world.getBlockAt(x, y, z);
        Material type = block.getType();
        return new BlockInfo(
                type.isSolid(),
                config.isHazard(type.getKey().getKey()),
                block.isLiquid(),
                type == Material.LAVA,
                Tag.LEAVES.isTagged(type) || Tag.LOGS.isTagged(type)
        );
    }

    @Override
    public String biomeId(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return "";
        }
        return world.getBiome(x, y, z).getKey().toString();
    }

    @Override
    public boolean hasNearbyPlayer(int x, int z, int distance) {
        if (distance <= 0) {
            return false;
        }
        double distanceSquared = (double) distance * distance;
        for (Player player : world.getPlayers()) {
            Location location = player.getLocation();
            double dx = location.getX() - x;
            double dz = location.getZ() - z;
            if (dx * dx + dz * dz < distanceSquared) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public boolean ensureChunk(int chunkX, int chunkZ) {
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            return true;
        }
        boolean loaded = world.loadChunk(chunkX, chunkZ, true);
        if (loaded) {
            loadedTemporarily.add(pack(chunkX, chunkZ));
        }
        return loaded && world.isChunkLoaded(chunkX, chunkZ);
    }

    public void unloadTemporaryChunks() {
        unloadTemporaryChunks(null);
    }

    /**
     * Выгружает с сохранением чанки, загруженные через {@link #ensureChunk} во время
     * последнего поиска, кроме чанка с выбранной точкой {@code keep}, куда телепортируется
     * игрок. Ранее уже загруженные чанки не трогает.
     */
    public void unloadTemporaryChunks(SpawnPoint keep) {
        long keepKey = keep != null ? pack(keep.x() >> 4, keep.z() >> 4) : Long.MIN_VALUE;
        for (long packed : loadedTemporarily) {
            if (keep != null && packed == keepKey) {
                continue;
            }
            world.unloadChunk((int) (packed >> 32), (int) (packed & 0xFFFFFFFFL), true);
        }
        if (keep == null) loadedTemporarily.clear();
        else loadedTemporarily.removeIf(packed -> packed != keepKey);
    }

    @Override
    public void finishSearch(SpawnPoint keep) {
        unloadTemporaryChunks(keep);
    }

    @Override
    public void discardSearch() {
        unloadTemporaryChunks(null);
    }

    private static long pack(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }
}
