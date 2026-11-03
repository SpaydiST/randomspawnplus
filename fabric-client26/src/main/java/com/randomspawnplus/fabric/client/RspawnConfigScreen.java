package com.randomspawnplus.fabric.client;

import com.randomspawnplus.modcommon.ModManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Settings screen for Minecraft 1.21.11. */
public final class RspawnConfigScreen extends Screen {

    private static final List<List<String>> PAGES = List.of(
            List.of("border-margin", "hazards", "worlds"),
            List.of("search-attempts", "safe-search-radius", "height-offset", "performance-profile", "reserve-points"),
            List.of("first-join-enabled", "first-join-min-radius", "first-join-max-radius"),
            List.of("respawn-enabled", "respawn-min-radius", "respawn-max-radius", "player-spacing"),
            List.of("lava-safety-radius", "avoid-trees", "avoid-narrow-ledges", "excluded-biomes", "verbose")
    );
    private static final List<String> PAGE_NAMES = List.of("general", "search", "first_join", "respawn", "safety");
    private static final List<String> INTEGER_KEYS = List.of(
            "border-margin", "search-attempts", "safe-search-radius", "height-offset",
            "first-join-min-radius", "first-join-max-radius", "respawn-min-radius",
            "respawn-max-radius", "player-spacing", "lava-safety-radius", "reserve-points");
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("border-margin", "border_margin"),
            Map.entry("search-attempts", "search_attempts"),
            Map.entry("safe-search-radius", "safe_spot_search_radius"),
            Map.entry("height-offset", "height_above_ground"),
            Map.entry("hazards", "hazard_blocks"),
            Map.entry("worlds", "worlds"),
            Map.entry("first-join-enabled", "first_join_enabled"),
            Map.entry("first-join-min-radius", "first_join_min_radius"),
            Map.entry("first-join-max-radius", "first_join_max_radius"),
            Map.entry("respawn-enabled", "respawn_enabled"),
            Map.entry("respawn-min-radius", "respawn_min_radius"),
            Map.entry("respawn-max-radius", "respawn_max_radius"),
            Map.entry("performance-profile", "performance_profile"),
            Map.entry("player-spacing", "player_spacing"),
            Map.entry("lava-safety-radius", "lava_safety_radius"),
            Map.entry("avoid-trees", "avoid_trees"),
            Map.entry("avoid-narrow-ledges", "avoid_narrow_ledges"),
            Map.entry("excluded-biomes", "excluded_biomes"),
            Map.entry("reserve-points", "reserve_points"),
            Map.entry("verbose", "verbose")
    );
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("border-margin", "50"),
            Map.entry("search-attempts", "10"),
            Map.entry("safe-search-radius", "20"),
            Map.entry("height-offset", "1"),
            Map.entry("hazards", "water,lava,cactus,magma_block,fire,sweet_berry_bush,powder_snow,cobweb"),
            Map.entry("worlds", ""),
            Map.entry("first-join-enabled", "true"),
            Map.entry("first-join-min-radius", "500"),
            Map.entry("first-join-max-radius", "3000"),
            Map.entry("respawn-enabled", "true"),
            Map.entry("respawn-min-radius", "0"),
            Map.entry("respawn-max-radius", "1000"),
            Map.entry("performance-profile", "small"),
            Map.entry("player-spacing", "128"),
            Map.entry("lava-safety-radius", "1"),
            Map.entry("avoid-trees", "true"),
            Map.entry("avoid-narrow-ledges", "false"),
            Map.entry("excluded-biomes", ""),
            Map.entry("reserve-points", "4"),
            Map.entry("verbose", "false")
    );

    private final Screen parent;
    private final Properties values = new Properties();
    private final Map<String, EditBox> editBoxes = new LinkedHashMap<>();
    private final Map<String, CycleButton<?>> cycleButtons = new LinkedHashMap<>();
    private boolean valuesLoaded;
    private int page;
    private int panelLeft;
    private int panelWidth;
    private String error;

    public RspawnConfigScreen(Screen parent) {
        super(Component.literal("RandomSpawnPlus"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!valuesLoaded) {
            values.putAll(load());
            String legacyRadius = values.getProperty("default-radius", "5000");
            try {
                legacyRadius = String.valueOf(Math.max(0, Integer.parseInt(legacyRadius.trim())));
            } catch (NumberFormatException ignored) {
                legacyRadius = "5000";
            }
            if (values.getProperty("first-join-max-radius") == null) values.setProperty("first-join-max-radius", legacyRadius);
            if (values.getProperty("respawn-max-radius") == null) values.setProperty("respawn-max-radius", legacyRadius);
            DEFAULTS.forEach((key, value) -> {
                if (values.getProperty(key) == null) values.setProperty(key, value);
            });
            String profile = values.getProperty("performance-profile", "small").trim().toLowerCase(java.util.Locale.ROOT);
            if (!List.of("solo", "small", "large").contains(profile)) profile = "small";
            values.setProperty("performance-profile", profile);
            valuesLoaded = true;
        }
        editBoxes.clear();
        cycleButtons.clear();
        panelWidth = Math.min(520, Math.max(160, width - 24));
        panelLeft = (width - panelWidth) / 2;

        addRenderableWidget(Button.builder(Component.translatable("randomspawnplus.screen.previous"), b -> changePage(-1))
                .bounds(width / 2 - 132, 45, 70, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("randomspawnplus.screen.next"), b -> changePage(1))
                .bounds(width / 2 + 62, 45, 70, 18).build());

        int row = 66;
        for (String key : PAGES.get(page)) {
            int y = row;
            String labelKey = labelKey(key);
            String descriptionKey = labelKey + ".desc";
            if (isBoolean(key)) {
                CycleButton<Boolean> button = CycleButton.onOffBuilder(Boolean.parseBoolean(values.getProperty(key)))
                        .withTooltip(value -> Tooltip.create(Component.translatable(descriptionKey)))
                        .create(panelLeft, y + 2, panelWidth, 20, Component.translatable(labelKey));
                addRenderableWidget(button);
                cycleButtons.put(key, button);
            } else if ("performance-profile".equals(key)) {
                CycleButton<String> button = CycleButton.<String>builder(value ->
                                Component.translatable("randomspawnplus.setting.performance_profile." + value),
                                values.getProperty(key, "small"))
                        .withValues(List.of("solo", "small", "large"))
                        .withTooltip(value -> Tooltip.create(Component.translatable(descriptionKey)))
                        .create(panelLeft, y + 2, panelWidth, 20, Component.translatable(labelKey));
                addRenderableWidget(button);
                cycleButtons.put(key, button);
            } else {
                EditBox box = new EditBox(font, panelLeft, y + 9, panelWidth, 18, Component.translatable(labelKey));
                box.setMaxLength(512);
                box.setValue(values.getProperty(key, ""));
                box.setTooltip(Tooltip.create(Component.translatable(descriptionKey)));
                addRenderableWidget(box);
                editBoxes.put(key, box);
            }
            row += 25;
        }

        addRenderableWidget(Button.builder(Component.translatable("randomspawnplus.screen.cancel"), b -> onClose())
                .bounds(width / 2 - 155, height - 27, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("randomspawnplus.screen.save"), b -> save())
                .bounds(width / 2 + 5, height - 27, 150, 20).build());
    }

    private String labelKey(String key) {
        return "randomspawnplus.setting." + LABELS.get(key);
    }

    private boolean isBoolean(String key) {
        return "first-join-enabled".equals(key) || "respawn-enabled".equals(key)
                || "avoid-trees".equals(key) || "avoid-narrow-ledges".equals(key) || "verbose".equals(key);
    }

    private void changePage(int direction) {
        capturePage();
        page = Math.floorMod(page + direction, PAGES.size());
        error = null;
        rebuildWidgets();
    }

    private void capturePage() {
        editBoxes.forEach((key, box) -> values.setProperty(key, box.getValue().trim()));
        cycleButtons.forEach((key, button) -> values.setProperty(key, String.valueOf(button.getValue())));
    }

    private Properties load() {
        Properties properties = new Properties();
        if (Files.isReadable(configPath())) {
            try (InputStream in = Files.newInputStream(configPath())) {
                properties.load(in);
            } catch (IOException ignored) {
            }
        }
        return properties;
    }

    private Path configPath() {
        return ModManager.get().configFile();
    }

    private void save() {
        capturePage();
        for (String key : INTEGER_KEYS) {
            try {
                if (Integer.parseInt(values.getProperty(key).trim()) < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                setError("randomspawnplus.error.invalid_number", labelKey(key));
                return;
            }
        }
        if (!validateRange("search-attempts", 1, 100)
                || !validateRange("safe-search-radius", 0, 64)
                || !validateRange("height-offset", 1, Integer.MAX_VALUE)
                || !validateRange("lava-safety-radius", 0, 16)
                || !validateRange("reserve-points", 0, 32)
                || !validatePair("first-join-min-radius", "first-join-max-radius")
                || !validatePair("respawn-min-radius", "respawn-max-radius")) return;

        Properties properties = load();
        for (String key : DEFAULTS.keySet()) properties.setProperty(key, values.getProperty(key, DEFAULTS.get(key)).trim());
        try {
            Files.createDirectories(configPath().getParent());
            try (OutputStream out = Files.newOutputStream(configPath())) {
                properties.store(out, "RandomSpawnPlus configuration");
            }
            ModManager.get().reload();
        } catch (IOException e) {
            error = Component.translatable("randomspawnplus.error.save_failed", e.getMessage()).getString();
            return;
        }
        onClose();
    }

    private boolean validateRange(String key, int minimum, int maximum) {
        int value = Integer.parseInt(values.getProperty(key).trim());
        if (value >= minimum && value <= maximum) return true;
        error = Component.translatable("randomspawnplus.error.invalid_range",
                Component.translatable(labelKey(key)).getString(), minimum, maximum).getString();
        return false;
    }

    private boolean validatePair(String minimumKey, String maximumKey) {
        int minimum = Integer.parseInt(values.getProperty(minimumKey).trim());
        int maximum = Integer.parseInt(values.getProperty(maximumKey).trim());
        if (minimum <= maximum) return true;
        error = Component.translatable("randomspawnplus.error.min_exceeds_max",
                Component.translatable(labelKey(minimumKey)).getString(),
                Component.translatable(labelKey(maximumKey)).getString()).getString();
        return false;
    }

    private void setError(String translationKey, String labelKey) {
        error = Component.translatable(translationKey, Component.translatable(labelKey).getString()).getString();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        String title = Component.translatable("randomspawnplus.screen.title").getString();
        graphics.text(font, title, width / 2 - font.width(title) / 2, 18, 0xFFFFFF);
        String info = Component.translatable("randomspawnplus.screen.info").getString();
        graphics.text(font, info, width / 2 - font.width(info) / 2, 34, 0xA0A0A0);
        String pageTitle = Component.translatable("randomspawnplus.screen.page." + PAGE_NAMES.get(page)).getString();
        graphics.text(font, pageTitle, width / 2 - font.width(pageTitle) / 2, 50, 0xFFFFFF);
        for (Map.Entry<String, EditBox> entry : editBoxes.entrySet()) {
            graphics.text(font, Component.translatable(labelKey(entry.getKey())).getString(),
                    panelLeft, entry.getValue().getY() - 9, 0xFFFFFF);
        }
        if (error != null) graphics.text(font, error, 16, height - 42, 0xFF5555);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
