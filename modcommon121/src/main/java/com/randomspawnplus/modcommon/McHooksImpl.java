package com.randomspawnplus.modcommon;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.storage.LevelData;

/**
 * Хуки для Minecraft 1.21.11 (переименования Mojang в этом релизе уже совпадают с 26.x).
 */
public final class McHooksImpl implements McHooks {

    @Override
    public String worldName(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    @Override
    public String levelName(ServerLevel level) {
        return level.getServer().getWorldData().getLevelName();
    }

    @Override
    public boolean hasBedSpawn(ServerPlayer player) {
        // Сломанная/взорванная кровать оставляет настройку спавна в профиле игрока,
        // хотя ванильный сервер уже не сможет туда заспавнить. Проверяем реальный
        // блок в измерении назначения: кровати/якоря нет — разрешаем случайный спавн.
        var cfg = player.getRespawnConfig();
        if (cfg == null) {
            return false;
        }
        if (cfg.forced()) {
            return true;
        }
        LevelData.RespawnData data = cfg.respawnData();
        ServerLevel level = ((ServerLevel) player.level()).getServer().getLevel(data.dimension());
        if (level == null) {
            return false;
        }
        Block block = level.getBlockState(data.pos()).getBlock();
        return block instanceof BedBlock || block instanceof RespawnAnchorBlock;
    }

    @Override
    public BlockPos worldSpawn(ServerLevel level) {
        return level.getRespawnData().pos();
    }

    @Override
    public int minY(ServerLevel level) {
        return level.getMinY();
    }

    @Override
    public String biomeId(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.identifier().toString()).orElse("");
    }

    @Override
    public boolean hasPermissionLevel(CommandSourceStack source, int level) {
        return !(source.permissions() instanceof LevelBasedPermissionSet set)
                || set.level().isEqualOrHigherThan(PermissionLevel.byId(level));
    }
}
