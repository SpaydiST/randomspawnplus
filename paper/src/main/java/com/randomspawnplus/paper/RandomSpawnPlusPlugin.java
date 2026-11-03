package com.randomspawnplus.paper;

import com.randomspawnplus.core.SafeSpawnService;
import com.randomspawnplus.core.SpawnConfig;
import com.randomspawnplus.core.SpawnMode;
import com.randomspawnplus.core.SpawnOptions;
import com.randomspawnplus.core.SpawnPoint;
import com.randomspawnplus.core.SpawnWorkQueue;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class RandomSpawnPlusPlugin extends JavaPlugin implements Listener {

    private static final String[] RULE_NAMES = {"respawn_radius", "randomSpawnRadius"};

    private SpawnConfig spawnConfig;
    private SafeSpawnService spawnService;
    private SpawnWorkQueue workQueue;
    private List<String> worlds;
    private FileConfiguration zones;
    private File zonesFile;
    private NamespacedKey firstJoinPendingKey;
    private boolean firstJoinAtCoordinates;
    private double firstJoinX;
    private double firstJoinY;
    private double firstJoinZ;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reload();
        zonesFile = new File(getDataFolder(), "spawnzones.yml");
        zones = YamlConfiguration.loadConfiguration(zonesFile);
        firstJoinPendingKey = new NamespacedKey(this, "first_join_pending");
        getServer().getPluginManager().registerEvents(this, this);

        getCommand("rspawn").setExecutor(this::onCommand);
        getCommand("rspawn").setTabCompleter(this::onTabComplete);
        getServer().getScheduler().runTaskTimer(this, this::tickSearches, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (workQueue != null) workQueue.cancelAll();
        saveZones();
    }

    public void reload() {
        reloadConfig();
        FileConfiguration config = getConfig();
        int defaultRadius = config.getInt("default-radius", 5000);
        firstJoinAtCoordinates = config.getBoolean("first-join-at-coordinates",
                config.getBoolean("first-join-at-origin", false));
        firstJoinX = config.getDouble("first-join-x", 35.16);
        firstJoinY = config.getDouble("first-join-y", 128.0);
        firstJoinZ = config.getDouble("first-join-z", -103.34);
        SpawnOptions options = new SpawnOptions(
                config.getInt("first-join-min-radius", 500),
                config.getInt("first-join-max-radius", defaultRadius),
                config.getInt("respawn-min-radius", 0),
                config.getInt("respawn-max-radius", defaultRadius),
                config.getBoolean("first-join-enabled", true),
                config.getBoolean("respawn-enabled", true),
                config.getString("performance-profile", "small"),
                config.getInt("player-spacing", 128),
                config.getInt("lava-safety-radius", 1),
                config.getBoolean("avoid-trees", true),
                config.getBoolean("avoid-narrow-ledges", false),
                Set.copyOf(config.getStringList("excluded-biomes")),
                config.getInt("reserve-points", 4)
        );
        spawnConfig = new SpawnConfig(
                config.getInt("border-margin", 50),
                config.getInt("search-attempts", 10),
                config.getInt("safe-search-radius", 20),
                config.getInt("height-offset", 1),
                defaultRadius,
                config.getBoolean("verbose", false),
                Set.copyOf(config.getStringList("hazards")),
                options
        );
        spawnService = new SafeSpawnService(spawnConfig, new Random(), this::logVerbose);
        if (workQueue != null) workQueue.cancelAll();
        workQueue = new SpawnWorkQueue(spawnService, spawnConfig);
        List<String> loadedWorlds = new ArrayList<>();
        for (String w : config.getStringList("worlds")) {
            if (w != null && !w.isBlank()) {
                loadedWorlds.add(w.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        worlds = List.copyOf(loadedWorlds);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean pending = Byte.valueOf((byte) 1).equals(player.getPersistentDataContainer()
                .get(firstJoinPendingKey, PersistentDataType.BYTE));
        if ((player.hasPlayedBefore() && !pending) || !isEnabledWorld(player)
                || !spawnConfig.enabled(SpawnMode.FIRST_JOIN)) {
            return;
        }
        player.getPersistentDataContainer().set(firstJoinPendingKey, PersistentDataType.BYTE, (byte) 1);
        queueTeleport(player, SpawnMode.FIRST_JOIN);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (event.isBedSpawn() || event.isAnchorSpawn() || !isEnabledWorld(player)
                || !spawnConfig.enabled(SpawnMode.RESPAWN)) {
            return;
        }
        queueTeleport(player, SpawnMode.RESPAWN);
    }

    private void queueTeleport(Player player, SpawnMode mode) {
        World world = player.getWorld();
        if (mode == SpawnMode.FIRST_JOIN && firstJoinAtCoordinates) {
            Location current = player.getLocation();
            Location destination = new Location(world, firstJoinX, firstJoinY, firstJoinZ,
                    current.getYaw(), current.getPitch());
            if (player.isOnline() && player.getWorld() == world && player.teleport(destination)) {
                player.setFallDistance(0.0F);
                player.getPersistentDataContainer().remove(firstJoinPendingKey);
            }
            return;
        }

        BukkitWorldAdapter adapter = new BukkitWorldAdapter(world, spawnConfig);
        int min = spawnConfig.minRadius(mode);
        int max = getSpawnRadius(world, mode);
        java.util.function.Consumer<SpawnPoint> completion = point -> {
            if (point == null || !player.isOnline() || player.getWorld() != world) return;
            Location location = new Location(world, point.x() + 0.5, point.y(), point.z() + 0.5,
                    point.yaw(), 0.0F);
            if (player.teleport(location)) {
                player.setFallDistance(0.0F);
                if (mode == SpawnMode.FIRST_JOIN) {
                    player.getPersistentDataContainer().remove(firstJoinPendingKey);
                }
            }
        };
        workQueue.request(world.getName(), adapter, mode, min, max, completion);
    }

    private void tickSearches() {
        if (workQueue == null || !isRandomSpawnEnabled()) return;
        for (World world : getServer().getWorlds()) {
            if (!isEnabledWorld(world)) continue;
            if (spawnConfig.enabled(SpawnMode.FIRST_JOIN) && !firstJoinAtCoordinates) {
                workQueue.prepare(world.getName(), new BukkitWorldAdapter(world, spawnConfig),
                        SpawnMode.FIRST_JOIN, spawnConfig.minRadius(SpawnMode.FIRST_JOIN),
                        getSpawnRadius(world, SpawnMode.FIRST_JOIN));
            }
            if (spawnConfig.enabled(SpawnMode.RESPAWN)) {
                workQueue.prepare(world.getName(), new BukkitWorldAdapter(world, spawnConfig),
                        SpawnMode.RESPAWN, spawnConfig.minRadius(SpawnMode.RESPAWN),
                        getSpawnRadius(world, SpawnMode.RESPAWN));
            }
        }
        workQueue.tick();
    }

    private boolean isRandomSpawnEnabled() {
        return spawnConfig.enabled(SpawnMode.FIRST_JOIN) || spawnConfig.enabled(SpawnMode.RESPAWN);
    }

    // --- gamerule interception (backward compat) ---

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (handleGamerule(event.getMessage(), event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        if (!getServer().getWorlds().isEmpty()
                && handleGamerule(event.getCommand(), event.getSender())) {
            event.setCancelled(true);
        }
    }

    private boolean handleGamerule(String message, CommandSender sender) {
        String command = message.startsWith("/") ? message.substring(1) : message;
        String[] parts = command.split("\\s+");
        String name = parts.length > 0 ? parts[0] : "";
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        if (parts.length < 2
                || !name.equalsIgnoreCase("gamerule")
                || !isRadiusRule(parts[1])) {
            return false;
        }
        if (!sender.hasPermission("randomspawnplus.admin")
                && !sender.hasPermission("minecraft.command.gamerule")) {
            sender.sendMessage("No permission");
            return true;
        }
        World world = resolveWorld(sender);
        if (world == null) {
            sender.sendMessage("[RandomSpawnPlus] No world found");
            return true;
        }

        if (parts.length == 2) {
            sender.sendMessage(parts[1] + " = " + getSpawnRadius(world));
            return true;
        }

        if (parts.length != 3) {
            sender.sendMessage("Invalid arguments for " + parts[1] + ". Usage: /gamerule " + parts[1] + " <radius>");
            return true;
        }

        int value;
        try {
            value = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("Invalid integer value \"" + parts[2] + "\"");
            return true;
        }
        if (value < 0) {
            sender.sendMessage("Radius must be >= 0");
            return true;
        }

        setSpawnRadius(world, value);
        sender.sendMessage("Game rule " + parts[1] + " set to " + value);
        return true;
    }

    // --- /rspawn command ---

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /rspawn reload | radius get|set|reset | stats | check [world] | profile solo|small|large | group");
            return true;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission("randomspawnplus.admin")) {
                    sender.sendMessage("No permission");
                    return true;
                }
                reload();
                sender.sendMessage("[RandomSpawnPlus] Config reloaded");
                return true;
            }
            case "radius" -> {
                if (!sender.hasPermission("randomspawnplus.admin")) {
                    sender.sendMessage("No permission");
                    return true;
                }
                return handleRadius(sender, args);
            }
            case "stats" -> {
                if (!sender.hasPermission("randomspawnplus.admin")) {
                    sender.sendMessage("No permission");
                    return true;
                }
                var stats = spawnService.stats();
                sender.sendMessage("[RandomSpawnPlus] searches=" + stats.searches() + " success=" + stats.succeeded()
                        + " failed=" + stats.failed() + " avg=" + stats.averageMillis() + "ms"
                        + " columns=" + stats.candidateColumns() + " chunks=" + stats.generatedChunks()
                        + " fallback=" + stats.fallbackHits() + " cached=" + stats.cacheHits()
                        + " pending=" + workQueue.pendingJobs());
                return true;
            }
            case "check" -> {
                if (!sender.hasPermission("randomspawnplus.admin")) {
                    sender.sendMessage("No permission");
                    return true;
                }
                World world = args.length >= 2 ? getServer().getWorld(args[1]) : resolveWorld(sender);
                if (world == null) {
                    sender.sendMessage("World not found");
                    return true;
                }
                List<String> issues = checkConfiguration(world);
                if (issues.isEmpty()) sender.sendMessage("[RandomSpawnPlus] Configuration looks valid for " + world.getName());
                else for (String issue : issues) sender.sendMessage("[RandomSpawnPlus] " + issue);
                return true;
            }
            case "profile" -> {
                if (!sender.hasPermission("randomspawnplus.admin")) {
                    sender.sendMessage("No permission");
                    return true;
                }
                if (args.length != 2 || !Set.of("solo", "small", "large").contains(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    sender.sendMessage("Usage: /rspawn profile solo|small|large");
                    return true;
                }
                getConfig().set("performance-profile", args[1].toLowerCase(java.util.Locale.ROOT));
                saveConfig();
                reload();
                sender.sendMessage("[RandomSpawnPlus] Performance profile set to " + spawnConfig.options().profile());
                return true;
            }
            case "group" -> {
                if (!sender.hasPermission("randomspawnplus.admin")) {
                    sender.sendMessage("No permission");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Run /rspawn group in-game");
                    return true;
                }
                queueGroup(player);
                return true;
            }
            default -> {
                sender.sendMessage("Usage: /rspawn reload | radius get|set|reset | stats | check [world] | profile solo|small|large | group");
                return true;
            }
        }
    }

    private boolean handleRadius(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /rspawn radius get [world] | /rspawn radius set <radius> [world] | /rspawn radius reset [world]");
            return true;
        }

        String action = args[1].toLowerCase(java.util.Locale.ROOT);

        if (action.equals("get")) {
            World world = args.length >= 3 ? getServer().getWorld(args[2]) : resolveWorld(sender);
            if (world == null) {
                sender.sendMessage("World not found");
                return true;
            }
            sender.sendMessage("[" + world.getName() + "] random spawn radius = " + getSpawnRadius(world));
            return true;
        }

        if (action.equals("reset")) {
            World world = args.length >= 3 ? getServer().getWorld(args[2]) : resolveWorld(sender);
            if (world == null) {
                sender.sendMessage("World not found");
                return true;
            }
            clearSpawnRadius(world);
            sender.sendMessage("[" + world.getName() + "] random spawn radius reset (default)");
            return true;
        }

        if (action.equals("set")) {
            if (args.length < 3) {
                sender.sendMessage("Usage: /rspawn radius set <radius> [world]");
                return true;
            }
            int value;
            try {
                value = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid integer radius: \"" + args[2] + "\"");
                return true;
            }
            if (value < 0) {
                sender.sendMessage("Radius must be >= 0");
                return true;
            }
            World world = args.length >= 4 ? getServer().getWorld(args[3]) : resolveWorld(sender);
            if (world == null) {
                sender.sendMessage("World not found");
                return true;
            }
            setSpawnRadius(world, value);
            sender.sendMessage("[" + world.getName() + "] random spawn radius set to " + value);
            return true;
        }

        sender.sendMessage("Unknown action \"" + action + "\"");
        return true;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("randomspawnplus.admin")) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        if (args.length == 1) {
            candidates.add("reload");
            candidates.add("radius");
            candidates.add("stats");
            candidates.add("check");
            candidates.add("profile");
            candidates.add("group");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("radius")) {
            candidates.add("get");
            candidates.add("set");
            candidates.add("reset");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("radius")
                && (args[1].equalsIgnoreCase("get") || args[1].equalsIgnoreCase("reset"))) {
            for (World world : getServer().getWorlds()) {
                candidates.add(world.getName());
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("radius")
                && args[1].equalsIgnoreCase("set")) {
            for (World world : getServer().getWorlds()) {
                candidates.add(world.getName());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("profile")) {
            candidates.addAll(List.of("solo", "small", "large"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            for (World world : getServer().getWorlds()) candidates.add(world.getName());
        }
        String prefix = args.length > 0 ? args[args.length - 1].toLowerCase(java.util.Locale.ROOT) : "";
        List<String> completions = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                completions.add(c);
            }
        }
        return completions;
    }

    private World resolveWorld(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        return getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
    }

    private boolean isRadiusRule(String name) {
        for (String rule : RULE_NAMES) {
            if (rule.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEnabledWorld(Player player) {
        return isEnabledWorld(player.getWorld());
    }

    private boolean isEnabledWorld(World world) {
        if (worlds.isEmpty()) {
            return true;
        }
        // Принимаем и голое имя ("world" / "overworld"), и namespaced-имя ("minecraft:overworld" —
        // такой же формат, какой моды пишут в конфиге), без учёта регистра.
        String worldName = world.getName().toLowerCase(java.util.Locale.ROOT);
        String keyFull = world.getKey().toString().toLowerCase(java.util.Locale.ROOT);
        String keyPath = world.getKey().getKey().toLowerCase(java.util.Locale.ROOT);
        return worlds.contains(worldName)
                || worlds.contains(keyFull)
                || worlds.contains(keyPath);
    }

    private int getSpawnRadius(World world) {
        return getSpawnRadius(world, SpawnMode.FIRST_JOIN);
    }

    private int getSpawnRadius(World world, SpawnMode mode) {
        String path = "worlds." + world.getName();
        return zones.contains(path) ? zones.getInt(path) : spawnConfig.maxRadius(mode);
    }

    private List<String> checkConfiguration(World world) {
        List<String> issues = new ArrayList<>();
        if (spawnConfig.borderMargin() * 2.0 >= world.getWorldBorder().getSize()) {
            issues.add("Border margin leaves no spawnable area in " + world.getName());
        }
        if (getSpawnRadius(world, SpawnMode.FIRST_JOIN) > 0
                && spawnConfig.minRadius(SpawnMode.FIRST_JOIN) > getSpawnRadius(world, SpawnMode.FIRST_JOIN)) {
            issues.add("First-join minimum radius exceeds its maximum");
        }
        if (getSpawnRadius(world, SpawnMode.RESPAWN) > 0
                && spawnConfig.minRadius(SpawnMode.RESPAWN) > getSpawnRadius(world, SpawnMode.RESPAWN)) {
            issues.add("Respawn minimum radius exceeds its maximum");
        }
        if (spawnConfig.searchAttempts() > 50) issues.add("Search attempts are capped at 100; consider a smaller value");
        if (spawnConfig.safeSearchRadius() >= 40) issues.add("Safe-search radius is high and may use the full per-search budget");
        if (!spawnConfig.enabled(SpawnMode.FIRST_JOIN) && !spawnConfig.enabled(SpawnMode.RESPAWN)) {
            issues.add("Random spawning is disabled for first joins and respawns");
        }
        return issues;
    }

    private void queueGroup(Player leader) {
        World world = leader.getWorld();
        Location origin = leader.getLocation();
        List<Player> members = new ArrayList<>();
        double groupRadiusSquared = 32.0 * 32.0;
        for (Player candidate : world.getPlayers()) {
            if (candidate.getLocation().distanceSquared(origin) <= groupRadiusSquared) members.add(candidate);
        }
        if (members.isEmpty()) members.add(leader);
        BukkitWorldAdapter adapter = new BukkitWorldAdapter(world, spawnConfig);
        int min = spawnConfig.minRadius(SpawnMode.FIRST_JOIN);
        int max = getSpawnRadius(world, SpawnMode.FIRST_JOIN);
        workQueue.request(world.getName(), adapter, SpawnMode.FIRST_JOIN, min, max, anchor -> {
            if (anchor == null) {
                leader.sendMessage("[RandomSpawnPlus] No safe group location found");
                return;
            }
            List<SpawnPoint> points = spawnService.findGroupPoints(adapter, anchor, members.size(), 16, min, max);
            int moved = 0;
            for (int i = 0; i < Math.min(points.size(), members.size()); i++) {
                Player member = members.get(i);
                if (!member.isOnline() || member.getWorld() != world) continue;
                SpawnPoint point = points.get(i);
                member.teleport(new Location(world, point.x() + 0.5, point.y(), point.z() + 0.5,
                        point.yaw(), 0.0F));
                member.setFallDistance(0.0F);
                moved++;
            }
            leader.sendMessage("[RandomSpawnPlus] Group spawn moved " + moved + " of " + members.size()
                    + " nearby players");
        });
    }

    private void setSpawnRadius(World world, int value) {
        zones.set("worlds." + world.getName(), value);
        saveZones();
    }

    private void clearSpawnRadius(World world) {
        zones.set("worlds." + world.getName(), null);
        saveZones();
    }

    private void saveZones() {
        if (zones == null || zonesFile == null) {
            return;
        }
        try {
            zones.save(zonesFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save spawnzones.yml: " + e.getMessage());
        }
    }

    private void logVerbose(String message) {
        getLogger().info(message);
    }
}
