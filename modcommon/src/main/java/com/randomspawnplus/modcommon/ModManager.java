package com.randomspawnplus.modcommon;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.randomspawnplus.core.SafeSpawnService;
import com.randomspawnplus.core.SpawnConfig;
import com.randomspawnplus.core.SpawnMode;
import com.randomspawnplus.core.SpawnOptions;
import com.randomspawnplus.core.SpawnPoint;
import com.randomspawnplus.core.SpawnWorkQueue;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Общая логика мода: конфиг, радиусы по мирам, команды и события.
 * Полный паритет с логикой Paper-плагина:
 * <ul>
 *   <li>первый вход определяется персистентно по (мир, игрок) — аналог {@code player.hasPlayedBefore()}
 *       плагина; работает и в одиночке (каждое новое сохранение = новый вход);</li>
 *   <li>на входе и респавне используется {@link SafeSpawnService#findGenerating} —
 *       как в плагине (чанки догенерируются на каждой попытке);</li>
 *   <li>редирект-команды /gamerule respawn_radius и randomSpawnRadius не нужны —
 *       в модах им эквивалентна команда /rspawn radius.</li>
 * </ul>
 * Версионные обращения к Minecraft API вынесены в {@link McHooks}.
 */
public final class ModManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("RandomSpawnPlus");
    private static final McHooks HOOKS = new McHooksImpl();
    private static volatile ModManager instance;

    private static final int OP_LEVEL = 2;

    private final Path configFile;
    private final Path radiiFile;
    private final Path firstJoinFile;
    private final Set<String> firstJoinKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingFirstJoins = ConcurrentHashMap.newKeySet();
    private volatile SpawnConfig config;
    private volatile SafeSpawnService service;
    private volatile SpawnWorkQueue workQueue;
    private volatile Set<String> worlds = Set.of();
    private final Map<String, Integer> radii = new ConcurrentHashMap<>();

    private ModManager(Path configDir) {
        this.configFile = configDir.resolve("randomspawnplus.properties");
        this.radiiFile = configDir.resolve("randomspawnplus-radii.properties");
        this.firstJoinFile = configDir.resolve("randomspawnplus-firstjoins.properties");
        saveDefaultsIfMissing();
        loadRadii();
        loadFirstJoins();
        reload();
    }

    public Path configFile() {
        return configFile;
    }

    private void saveDefaultsIfMissing() {
        if (Files.exists(configFile)) {
            return;
        }
        try {
            Files.createDirectories(configFile.getParent());
            Properties p = new Properties();
            p.setProperty("border-margin", "50");
            p.setProperty("search-attempts", "10");
            p.setProperty("safe-search-radius", "20");
            p.setProperty("height-offset", "1");
            p.setProperty("default-radius", "5000");
            p.setProperty("first-join-min-radius", "500");
            p.setProperty("first-join-max-radius", "3000");
            p.setProperty("first-join-enabled", "true");
            p.setProperty("respawn-min-radius", "0");
            p.setProperty("respawn-max-radius", "1000");
            p.setProperty("respawn-enabled", "true");
            p.setProperty("performance-profile", "small");
            p.setProperty("player-spacing", "128");
            p.setProperty("lava-safety-radius", "1");
            p.setProperty("avoid-trees", "true");
            p.setProperty("avoid-narrow-ledges", "false");
            p.setProperty("excluded-biomes", "");
            p.setProperty("reserve-points", "4");
            p.setProperty("verbose", "false");
            p.setProperty("hazards", "water,lava,cactus,magma_block,fire,sweet_berry_bush,powder_snow,cobweb");
            p.setProperty("worlds", "");
            try (OutputStream out = Files.newOutputStream(configFile)) {
                p.store(out, "RandomSpawnPlus configuration");
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to write default config {}", configFile, e);
        }
    }

    public static ModManager init(Path configDir) {
        if (instance == null) {
            synchronized (ModManager.class) {
                if (instance == null) {
                    instance = new ModManager(configDir);
                }
            }
        }
        return instance;
    }

    public static ModManager get() {
        if (instance == null) {
            throw new IllegalStateException("ModManager not initialized");
        }
        return instance;
    }

    public synchronized void reload() {
        Properties p = new Properties();
        if (Files.isReadable(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                p.load(in);
            } catch (IOException e) {
                LOGGER.warn("Failed to read {}", configFile, e);
            }
        }

        int defaultRadius = parseInt(p, "default-radius", 5000);
        SpawnOptions options = new SpawnOptions(
                parseInt(p, "first-join-min-radius", 500),
                parseInt(p, "first-join-max-radius", defaultRadius),
                parseInt(p, "respawn-min-radius", 0),
                parseInt(p, "respawn-max-radius", defaultRadius),
                Boolean.parseBoolean(p.getProperty("first-join-enabled", "true")),
                Boolean.parseBoolean(p.getProperty("respawn-enabled", "true")),
                p.getProperty("performance-profile", "small"),
                parseInt(p, "player-spacing", 128),
                parseInt(p, "lava-safety-radius", 1),
                Boolean.parseBoolean(p.getProperty("avoid-trees", "true")),
                Boolean.parseBoolean(p.getProperty("avoid-narrow-ledges", "false")),
                parseSet(p, "excluded-biomes", ""),
                parseInt(p, "reserve-points", 4)
        );
        SpawnConfig cfg = new SpawnConfig(
                parseInt(p, "border-margin", 50),
                parseInt(p, "search-attempts", 10),
                parseInt(p, "safe-search-radius", 20),
                parseInt(p, "height-offset", 1),
                defaultRadius,
                Boolean.parseBoolean(p.getProperty("verbose", "false")),
                parseSet(p, "hazards", "water,lava,cactus,magma_block,fire,sweet_berry_bush,powder_snow,cobweb"),
                options
        );
        this.worlds = Set.copyOf(parseSet(p, "worlds", ""));
        this.config = cfg;
        if (workQueue != null) workQueue.cancelAll();
        this.service = new SafeSpawnService(cfg, new Random(), LOGGER::info);
        this.workQueue = new SpawnWorkQueue(service, cfg);
        LOGGER.info("RandomSpawnPlus config reloaded");
    }

    // --- события ---

    public void onJoin(ServerPlayer player) {
        String key = firstJoinKey(player);
        if (firstJoinKeys.contains(key) || !isEnabled(player) || !config.enabled(SpawnMode.FIRST_JOIN)) {
            return;
        }
        if (!pendingFirstJoins.add(key)) return;
        LOGGER.info("RandomSpawnPlus: first join of {} in {}, searching spawn point",
                player.getScoreboardName(), HOOKS.worldName((ServerLevel) player.level()));
        queueTeleport(player, SpawnMode.FIRST_JOIN);
    }

    /**
     * Handles a respawn only when the platform event represents a normal death
     * respawn.  Returning from the End must keep vanilla's destination.
     */
    public void onRespawn(ServerPlayer player, boolean randomSpawnRequired) {
        if (!randomSpawnRequired) {
            return;
        }
        if (!config.enabled(SpawnMode.RESPAWN)) return;
        if (HOOKS.hasBedSpawn(player)) {
            return;
        }
        if (!isEnabled(player)) {
            return;
        }
        LOGGER.info("RandomSpawnPlus: respawn without bed for {}, searching spawn point", player.getScoreboardName());
        queueTeleport(player, SpawnMode.RESPAWN);
    }

    private void queueTeleport(ServerPlayer player, SpawnMode mode) {
        ServerLevel level = (ServerLevel) player.level();
        MinecraftWorldAdapter adapter = new MinecraftWorldAdapter(level, config, HOOKS);
        int min = config.minRadius(mode);
        int max = radius(level, mode);
        workQueue.request(HOOKS.worldName(level), adapter, mode, min, max, point -> {
            if (point == null || player.isRemoved() || player.level() != level) {
                if (mode == SpawnMode.FIRST_JOIN) {
                    pendingFirstJoins.remove(firstJoinKey(player));
                }
                if (point == null) LOGGER.info("RandomSpawnPlus: no safe spot found for {} in {}", player.getScoreboardName(), HOOKS.worldName(level));
                return;
            }
            LOGGER.info("RandomSpawnPlus: teleporting {} to ({}, {}, {})", player.getScoreboardName(), point.x(), point.y(), point.z());
            player.teleportTo(point.x() + 0.5, point.y(), point.z() + 0.5);
            player.setYRot(point.yaw());
            player.resetFallDistance();
            if (mode == SpawnMode.FIRST_JOIN) {
                String key = firstJoinKey(player);
                pendingFirstJoins.remove(key);
                firstJoinKeys.add(key);
                saveFirstJoins();
            }
        });
    }

    /** Called from a platform server-tick hook; every world access remains on that thread. */
    public void tick(MinecraftServer server) {
        SpawnWorkQueue queue = workQueue;
        if (queue == null || server.isStopped()) return;
        for (ServerLevel level : server.getAllLevels()) {
            if (!isEnabledWorld(level)) continue;
            if (config.enabled(SpawnMode.FIRST_JOIN)) {
                queue.prepare(HOOKS.worldName(level), new MinecraftWorldAdapter(level, config, HOOKS),
                        SpawnMode.FIRST_JOIN, config.minRadius(SpawnMode.FIRST_JOIN), radius(level, SpawnMode.FIRST_JOIN));
            }
            if (config.enabled(SpawnMode.RESPAWN)) {
                queue.prepare(HOOKS.worldName(level), new MinecraftWorldAdapter(level, config, HOOKS),
                        SpawnMode.RESPAWN, config.minRadius(SpawnMode.RESPAWN), radius(level, SpawnMode.RESPAWN));
            }
        }
        queue.tick();
    }

    private boolean isEnabled(ServerPlayer player) {
        return isEnabledWorld((ServerLevel) player.level());
    }

    private boolean isEnabledWorld(ServerLevel level) {
        Set<String> enabledWorlds = this.worlds;
        if (enabledWorlds.isEmpty()) {
            return true;
        }
        String name = HOOKS.worldName(level).toLowerCase(java.util.Locale.ROOT);
        if (enabledWorlds.contains(name)) {
            return true;
        }
        int colon = name.indexOf(':');
        return colon >= 0 && enabledWorlds.contains(name.substring(colon + 1));
    }

    private int radius(ServerLevel level) {
        return radius(level, SpawnMode.FIRST_JOIN);
    }

    private int radius(ServerLevel level, SpawnMode mode) {
        String name = HOOKS.worldName(level);
        Integer r = radii.get(name);
        return r != null ? r : config.maxRadius(mode);
    }

    private String firstJoinKey(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        return HOOKS.levelName(level) + "|"
                + level.getSeed() + "|"
                + HOOKS.worldName(level) + "|"
                + player.getUUID();
    }

    // --- команды ---

    public static LiteralArgumentBuilder<CommandSourceStack> rspawnCommand() {
        return Commands.literal("rspawn")
                .requires(source -> HOOKS.hasPermissionLevel(source, OP_LEVEL))
                .then(Commands.literal("reload").executes(ctx -> {
                    get().reload();
                    ctx.getSource().sendSuccess(() -> Component.translatable("randomspawnplus.command.reloaded"), false);
                    return 1;
                }))
                .then(Commands.literal("radius")
                        .then(Commands.literal("get")
                                .executes(ctx -> getRadius(ctx, null))
                                .then(Commands.argument("world", StringArgumentType.greedyString())
                                        .suggests(ModManager::suggestWorlds)
                                        .executes(ctx -> getRadius(ctx, StringArgumentType.getString(ctx, "world")))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                        .executes(ctx -> setRadius(ctx, IntegerArgumentType.getInteger(ctx, "value"), null))
                                        .then(Commands.argument("world", StringArgumentType.greedyString())
                                                .suggests(ModManager::suggestWorlds)
                                                .executes(ctx -> setRadius(ctx, IntegerArgumentType.getInteger(ctx, "value"),
                                                        StringArgumentType.getString(ctx, "world"))))))
                        .then(Commands.literal("reset")
                                .executes(ctx -> resetRadius(ctx, null))
                                .then(Commands.argument("world", StringArgumentType.greedyString())
                                        .suggests(ModManager::suggestWorlds)
                                        .executes(ctx -> resetRadius(ctx, StringArgumentType.getString(ctx, "world"))))))
                .then(Commands.literal("stats").executes(ctx -> showStats(ctx.getSource())))
                .then(Commands.literal("check")
                        .executes(ctx -> checkConfig(ctx.getSource(), null))
                        .then(Commands.argument("world", StringArgumentType.greedyString())
                                .suggests(ModManager::suggestWorlds)
                                .executes(ctx -> checkConfig(ctx.getSource(), StringArgumentType.getString(ctx, "world")))))
                .then(Commands.literal("profile")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("solo").suggest("small").suggest("large");
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> setProfile(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("group").executes(ctx -> queueGroup(ctx.getSource())));
    }

    private static int showStats(CommandSourceStack source) {
        ModManager manager = get();
        SafeSpawnService.SpawnStats stats = manager.service.stats();
        source.sendSuccess(() -> Component.literal("[RandomSpawnPlus] searches=" + stats.searches()
                + " success=" + stats.succeeded() + " failed=" + stats.failed()
                + " avg=" + stats.averageMillis() + "ms columns=" + stats.candidateColumns()
                + " chunks=" + stats.generatedChunks() + " fallback=" + stats.fallbackHits()
                + " cached=" + stats.cacheHits() + " pending=" + manager.workQueue.pendingJobs()), false);
        return 1;
    }

    private static int checkConfig(CommandSourceStack source, String worldName) {
        ServerLevel level = resolveWorld(source, worldName);
        if (level == null) {
            source.sendFailure(Component.literal("World not found"));
            return 0;
        }
        ModManager manager = get();
        int firstMax = manager.radius(level, SpawnMode.FIRST_JOIN);
        int respawnMax = manager.radius(level, SpawnMode.RESPAWN);
        int borderMargin = manager.config.borderMargin();
        java.util.List<String> issues = new java.util.ArrayList<>();
        if (borderMargin * 2.0 >= level.getWorldBorder().getSize()) issues.add("border-margin leaves no spawnable area");
        if (firstMax > 0 && manager.config.minRadius(SpawnMode.FIRST_JOIN) > firstMax) issues.add("first-join minimum exceeds maximum radius");
        if (respawnMax > 0 && manager.config.minRadius(SpawnMode.RESPAWN) > respawnMax) issues.add("respawn minimum exceeds maximum radius");
        if (!manager.config.enabled(SpawnMode.FIRST_JOIN) && !manager.config.enabled(SpawnMode.RESPAWN)) issues.add("both random-spawn modes are disabled");
        if (manager.config.safeSearchRadius() >= 40) issues.add("safe-search-radius is high and can exhaust the work budget");
        if (issues.isEmpty()) source.sendSuccess(() -> Component.literal("[RandomSpawnPlus] Configuration looks valid for " + HOOKS.worldName(level)), false);
        else for (String issue : issues) source.sendFailure(Component.literal("[RandomSpawnPlus] " + issue));
        return issues.isEmpty() ? 1 : 0;
    }

    private static int setProfile(CommandSourceStack source, String profile) {
        String normalized = SpawnOptions.normalizeProfile(profile);
        if (!normalized.equalsIgnoreCase(profile)) {
            source.sendFailure(Component.literal("Usage: /rspawn profile solo|small|large"));
            return 0;
        }
        ModManager manager = get();
        Properties p = new Properties();
        if (Files.isReadable(manager.configFile)) {
            try (InputStream in = Files.newInputStream(manager.configFile)) { p.load(in); }
            catch (IOException e) { source.sendFailure(Component.literal("Could not read config: " + e.getMessage())); return 0; }
        }
        p.setProperty("performance-profile", normalized);
        try {
            Files.createDirectories(manager.configFile.getParent());
            try (OutputStream out = Files.newOutputStream(manager.configFile)) { p.store(out, "RandomSpawnPlus configuration"); }
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not save config: " + e.getMessage()));
            return 0;
        }
        manager.reload();
        source.sendSuccess(() -> Component.literal("[RandomSpawnPlus] Performance profile set to " + normalized), false);
        return 1;
    }

    private static int queueGroup(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer leader)) {
            source.sendFailure(Component.literal("Run /rspawn group in-game"));
            return 0;
        }
        ServerLevel level = (ServerLevel) leader.level();
        java.util.List<ServerPlayer> members = level.players().stream()
                .filter(player -> horizontalDistanceSquared(leader, player) <= 32L * 32L)
                .limit(16).toList();
        MinecraftWorldAdapter adapter = new MinecraftWorldAdapter(level, get().config, HOOKS);
        int min = get().config.minRadius(SpawnMode.FIRST_JOIN);
        int max = get().radius(level, SpawnMode.FIRST_JOIN);
        get().workQueue.request(HOOKS.worldName(level), adapter, SpawnMode.FIRST_JOIN, min, max, anchor -> {
            if (anchor == null) {
                leader.sendSystemMessage(Component.literal("[RandomSpawnPlus] No safe group location found"));
                return;
            }
            java.util.List<SpawnPoint> points = get().service.findGroupPoints(adapter, anchor, members.size(), 16, min, max);
            int moved = 0;
            for (int i = 0; i < Math.min(points.size(), members.size()); i++) {
                ServerPlayer member = members.get(i);
                if (member.isRemoved() || member.level() != level) continue;
                SpawnPoint point = points.get(i);
                member.teleportTo(point.x() + 0.5, point.y(), point.z() + 0.5);
                member.setYRot(point.yaw());
                member.resetFallDistance();
                moved++;
            }
            leader.sendSystemMessage(Component.literal("[RandomSpawnPlus] Group spawn moved " + moved
                    + " of " + members.size() + " nearby players"));
        });
        return 1;
    }

    private static long horizontalDistanceSquared(ServerPlayer a, ServerPlayer b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return (long) (dx * dx + dz * dz);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestWorlds(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
            builder.suggest(HOOKS.worldName(level));
        }
        return builder.buildFuture();
    }

    private static int getRadius(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String worldName) {
        ServerLevel level = resolveWorld(ctx, worldName);
        if (level == null) {
            ctx.getSource().sendFailure(Component.translatable("randomspawnplus.command.world_not_found", worldName));
            return 0;
        }
        int r = get().radius(level);
        ctx.getSource().sendSuccess(() -> Component.translatable("randomspawnplus.command.radius_get", HOOKS.worldName(level), r), false);
        return 1;
    }

    private static int setRadius(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, int value, String worldName) {
        ServerLevel level = resolveWorld(ctx, worldName);
        if (level == null) {
            ctx.getSource().sendFailure(Component.translatable("randomspawnplus.command.world_not_found", worldName));
            return 0;
        }
        get().radii.put(HOOKS.worldName(level), value);
        get().saveRadii();
        ctx.getSource().sendSuccess(() -> Component.translatable("randomspawnplus.command.radius_set", HOOKS.worldName(level), value), false);
        return 1;
    }

    private static int resetRadius(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String worldName) {
        ServerLevel level = resolveWorld(ctx, worldName);
        if (level == null) {
            ctx.getSource().sendFailure(Component.translatable("randomspawnplus.command.world_not_found", worldName));
            return 0;
        }
        get().radii.remove(HOOKS.worldName(level));
        get().saveRadii();
        ctx.getSource().sendSuccess(() -> Component.translatable("randomspawnplus.command.radius_reset", HOOKS.worldName(level)), false);
        return 1;
    }

    private static ServerLevel resolveWorld(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String worldName) {
        if (worldName == null) {
            if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
                return (ServerLevel) player.level();
            }
            return ctx.getSource().getLevel();
        }
        for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
            String name = HOOKS.worldName(level);
            if (name.equalsIgnoreCase(worldName) || name.equalsIgnoreCase("minecraft:" + worldName)) {
                return level;
            }
        }
        return null;
    }

    private static ServerLevel resolveWorld(CommandSourceStack source, String worldName) {
        if (worldName == null) {
            if (source.getEntity() instanceof ServerPlayer player) return (ServerLevel) player.level();
            return source.getLevel();
        }
        for (ServerLevel level : source.getServer().getAllLevels()) {
            String name = HOOKS.worldName(level);
            if (name.equalsIgnoreCase(worldName) || name.equalsIgnoreCase("minecraft:" + worldName)) return level;
        }
        return null;
    }

    // --- хранение радиусов ---

    private void loadRadii() {
        if (!Files.isReadable(radiiFile)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(radiiFile)) {
            p.load(in);
        } catch (IOException e) {
            LOGGER.warn("Failed to read {}", radiiFile, e);
            return;
        }
        for (String key : p.stringPropertyNames()) {
            try {
                radii.put(key, Integer.parseInt(p.getProperty(key).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void saveRadii() {
        try {
            Files.createDirectories(radiiFile.getParent());
            Properties p = new Properties();
            for (Map.Entry<String, Integer> e : radii.entrySet()) {
                p.setProperty(e.getKey(), String.valueOf(e.getValue()));
            }
            try (OutputStream out = Files.newOutputStream(radiiFile)) {
                p.store(out, "RandomSpawnPlus per-world spawn radius");
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save {}", radiiFile, e);
        }
    }

    // --- персистентная метка первого входа (аналог hasPlayedBefore) ---

    private void loadFirstJoins() {
        if (!Files.isReadable(firstJoinFile)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(firstJoinFile)) {
            p.load(in);
        } catch (IOException e) {
            LOGGER.warn("Failed to read {}", firstJoinFile, e);
            return;
        }
        firstJoinKeys.clear();
        firstJoinKeys.addAll(p.stringPropertyNames());
    }

    private void saveFirstJoins() {
        try {
            Files.createDirectories(firstJoinFile.getParent());
            Properties p = new Properties();
            for (String key : firstJoinKeys) {
                p.setProperty(key, "1");
            }
            try (OutputStream out = Files.newOutputStream(firstJoinFile)) {
                p.store(out, "RandomSpawnPlus first joins (world|player), like hasPlayedBefore");
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save {}", firstJoinFile, e);
        }
    }

    // --- утилиты конфига ---

    private static int parseInt(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid {} value: {}", key, v);
            return def;
        }
    }

    private static Set<String> parseSet(Properties p, String key, String def) {
        String v = p.getProperty(key);
        if (v == null) {
            v = def;
        }
        Set<String> set = new LinkedHashSet<>();
        for (String part : v.split(",")) {
            if (!part.isBlank()) {
                set.add(part.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return set;
    }
}
