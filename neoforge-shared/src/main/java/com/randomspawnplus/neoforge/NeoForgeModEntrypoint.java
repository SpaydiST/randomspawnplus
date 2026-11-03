package com.randomspawnplus.neoforge;

import com.randomspawnplus.modcommon.ModManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("randomspawnplus")
public final class NeoForgeModEntrypoint {

    public NeoForgeModEntrypoint() {
        ModManager.init(FMLPaths.CONFIGDIR.get());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(this);

        if (isClient()) {
            try {
                Class.forName("com.randomspawnplus.neoforge.client.NeoForgeClientRegistrar")
                        .getMethod("register").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to register RandomSpawnPlus config screen", e);
            }
        }
    }

    private static boolean isClient() {
        try {
            return "CLIENT".equals(FMLEnvironment.class.getMethod("getDist").invoke(null).toString());
        } catch (NoSuchMethodException e) {
            try {
                return "CLIENT".equals(FMLEnvironment.class.getField("dist").get(null).toString());
            } catch (ReflectiveOperationException ex) {
                return false;
            }
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        ModManager.get().reload();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        ModManager.get().tick(event.getServer());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(ModManager.rspawnCommand());
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModManager.get().onJoin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModManager.get().onRespawn(player, !event.isEndConquered());
        }
    }
}
