package com.randomspawnplus.modcommon;

import com.randomspawnplus.core.PlatformWorld;
import com.randomspawnplus.core.SpawnConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Адаптер Mojang-mapped {@link ServerLevel} к {@link PlatformWorld}.
 */
public final class MinecraftWorldAdapter implements PlatformWorld {

    private final ServerLevel level;
    private final SpawnConfig config;
    private final McHooks hooks;

    public MinecraftWorldAdapter(ServerLevel level, SpawnConfig config, McHooks hooks) {
        this.level = level;
        this.config = config;
        this.hooks = hooks;
    }

    @Override
    public String name() {
        return hooks.worldName(level);
    }

    @Override
    public int centerX() {
        return (int) Math.floor(level.getWorldBorder().getCenterX());
    }

    @Override
    public int centerZ() {
        return (int) Math.floor(level.getWorldBorder().getCenterZ());
    }

    @Override
    public double size() {
        return level.getWorldBorder().getSize();
    }

    @Override
    public int spawnX() {
        return hooks.worldSpawn(level).getX();
    }

    @Override
    public int spawnZ() {
        return hooks.worldSpawn(level).getZ();
    }

    @Override
    public int minY() {
        return hooks.minY(level);
    }

    @Override
    public int topY(int x, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return Integer.MIN_VALUE;
        }
        int min = hooks.minY(level);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        while (y >= min) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state.isSolid()) {
                if (state.is(Blocks.BEDROCK)) {
                    // Крыша Незера: ищем под потолком пол с двухблочным просветом над ним.
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
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state.isSolid() && !state.is(Blocks.BEDROCK)
                    && !level.getBlockState(new BlockPos(x, y + 1, z)).isSolid()
                    && !level.getBlockState(new BlockPos(x, y + 2, z)).isSolid()) {
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
        return level.getBlockState(new BlockPos(x, y, z)).isSolid();
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return false;
        }
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        return config.isHazard(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
    }

    @Override
    public boolean isLiquid(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return false;
        }
        return !level.getFluidState(new BlockPos(x, y, z)).isEmpty();
    }

    @Override
    public BlockInfo blockInfo(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return new BlockInfo(false, false, false, false, false);
        }
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        var fluid = state.getFluidState();
        return new BlockInfo(
                state.isSolid(),
                config.isHazard(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()),
                !fluid.isEmpty(),
                fluid.is(FluidTags.LAVA),
                state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
        );
    }

    @Override
    public String biomeId(int x, int y, int z) {
        if (!hasChunk(x >> 4, z >> 4)) {
            return "";
        }
        return hooks.biomeId(level, new BlockPos(x, y, z));
    }

    @Override
    public boolean hasNearbyPlayer(int x, int z, int distance) {
        if (distance <= 0) {
            return false;
        }
        double distanceSquared = (double) distance * distance;
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            if (dx * dx + dz * dz < distanceSquared) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return level.getChunkSource().hasChunk(chunkX, chunkZ);
    }

    @Override
    public boolean ensureChunk(int chunkX, int chunkZ) {
        level.getChunkSource().getChunk(chunkX, chunkZ, true);
        return hasChunk(chunkX, chunkZ);
    }
}
