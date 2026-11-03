package com.randomspawnplus.modcommon;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RespawnAnchorBlock;

/**
 * Хуки для NeoForge 1.21.11 (именование Mojang до массовых переименований:
 * location(), getRespawnPosition(), getSharedSpawnPos(), getMinBuildHeight()).
 */
public final class McHooksImpl implements McHooks {

    @Override
    public String worldName(ServerLevel level) {
        return level.dimension().location().toString();
    }

    @Override
    public String levelName(ServerLevel level) {
        return level.getServer().getWorldData().getLevelName();
    }

    @Override
    public boolean hasBedSpawn(ServerPlayer player) {
        BlockPos pos = player.getRespawnPosition();
        if (pos == null) {
            return false;
        }
        if (player.isRespawnForced()) {
            return true;
        }
        // Сломанная/взорванная кровать оставляет координаты в NBT игрока —
        // ванильный сервер потом не может туда заспавнить и молча сажает на
        // мировой спавн. Проверяем сам блок в целевом измерении: если кровати/якоря
        // нет, случайный спавн должен работать.
        ServerLevel level = ((ServerLevel) player.level()).getServer().getLevel(player.getRespawnDimension());
        if (level == null) {
            return false;
        }
        Block block = level.getBlockState(pos).getBlock();
        return block instanceof BedBlock || block instanceof RespawnAnchorBlock;
    }

    @Override
    public BlockPos worldSpawn(ServerLevel level) {
        return level.getSharedSpawnPos();
    }

    @Override
    public int minY(ServerLevel level) {
        return level.getMinBuildHeight();
    }

    @Override
    public String biomeId(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.location().toString()).orElse("");
    }

    @Override
    public boolean hasPermissionLevel(CommandSourceStack source, int level) {
        return source.hasPermission(level);
    }
}
