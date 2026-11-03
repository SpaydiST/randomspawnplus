package com.randomspawnplus.fabric;

import com.randomspawnplus.modcommon.ModManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

public final class FabricModEntrypoint implements ModInitializer {

    @Override
    public void onInitialize() {
        ModManager.init(FabricLoader.getInstance().getConfigDir());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(ModManager.rspawnCommand()));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (handler.player instanceof ServerPlayer player) {
                ModManager.get().onJoin(player);
            }
        });

        // Fabric's "alive" is true for a non-death respawn (for example, leaving
        // the End).  RandomSpawnPlus must run for deaths, not for that case.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                ModManager.get().onRespawn(newPlayer, !alive));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            ModManager.get().reload();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> ModManager.get().tick(server));
    }
}
