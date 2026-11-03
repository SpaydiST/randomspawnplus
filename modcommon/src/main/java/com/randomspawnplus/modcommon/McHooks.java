package com.randomspawnplus.modcommon;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Версионно-зависимые обращения к Minecraft API, которые отличаются
 * между 1.21.x (обфусцированный, маппинги) и 26.x (необфусцированный).
 */
public interface McHooks {

    String worldName(ServerLevel level);

    /** Имя сохранения (папки уровня) — аналог hasPlayedBefore в одиночке и мультиплеере. */
    String levelName(ServerLevel level);

    boolean hasBedSpawn(ServerPlayer player);

    /** Мировой спавн (координаты, наилучшие доступные в текущей версии). */
    BlockPos worldSpawn(ServerLevel level);

    /** Нижняя граница мира (min build height). */
    int minY(ServerLevel level);

    /** Namespaced biome identifier at a block position. */
    String biomeId(ServerLevel level, BlockPos pos);

    /** Есть ли у источника команды права уровня не ниже level (аналог hasPermission(int)). */
    boolean hasPermissionLevel(CommandSourceStack source, int level);
}
