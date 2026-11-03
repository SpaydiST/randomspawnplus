package com.randomspawnplus.neoforge.client;

import net.neoforged.fml.ModList;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Код, зависящий от клиента: регистрирует экран настроек RandomSpawnPlus
 * в списке модов NeoForge (кнопка Config).
 * Загружается только на клиенте (см. NeoForgeModEntrypoint).
 */
public final class NeoForgeClientRegistrar {

    private NeoForgeClientRegistrar() {
    }

    public static void register() {
        ModList.get().getModContainerById("randomspawnplus").ifPresent(container ->
                container.registerExtensionPoint(IConfigScreenFactory.class,
                        (ModContainer modContainer, net.minecraft.client.gui.screens.Screen parent) ->
                                new RspawnConfigScreen(parent)));
    }
}