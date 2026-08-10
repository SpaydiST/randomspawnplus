package org.example1.randomspawn;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;

public final class Randomspawn extends JavaPlugin implements Listener {

    private static final String[] RULE_NAMES = {"respawn_radius", "randomSpawnRadius"};

    private SpawnFinder spawnFinder;
    private List<String> worlds;
    private FileConfiguration zones;
    private File zonesFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        spawnFinder = new SpawnFinder(
                config.getInt("border-margin", 50),
                config.getInt("search-attempts", 10),
                config.getInt("safe-search-radius", 20)
        );
        worlds = config.getStringList("worlds");
        zonesFile = new File(getDataFolder(), "spawnzones.yml");
        zones = YamlConfiguration.loadConfiguration(zonesFile);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore() || !isEnabledWorld(player)) {
            return;
        }
        World world = player.getWorld();
        Location location = spawnFinder.find(world, getSpawnRadius(world));
        if (location != null) {
            player.teleport(location);
            player.setFallDistance(0.0F);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (event.isBedSpawn() || event.isAnchorSpawn() || !isEnabledWorld(player)) {
            getLogger().info("[DEBUG] Respawn: vanilla (bed=" + event.isBedSpawn() + ", anchor=" + event.isAnchorSpawn() + ")");
            return;
        }
        World world = player.getWorld();
        Location location = spawnFinder.find(world, getSpawnRadius(world));
        if (location != null) {
            event.setRespawnLocation(location);
            getLogger().info("[DEBUG] Respawn: random -> " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
        } else {
            getLogger().info("[DEBUG] Respawn: no safe spot found, keeping vanilla");
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (handleGamerule(event.getMessage(), event.getPlayer(), event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        World world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
        if (world != null && handleGamerule(event.getCommand(), event.getSender(), world)) {
            event.setCancelled(true);
        }
    }

    private boolean isRadiusRule(String name) {
        for (String rule : RULE_NAMES) {
            if (rule.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean handleGamerule(String message, CommandSender sender, World world) {
        String command = message.startsWith("/") ? message.substring(1) : message;
        String[] parts = command.split("\\s+");
        if (parts.length < 2
                || !parts[0].equalsIgnoreCase("gamerule")
                || !isRadiusRule(parts[1])) {
            return false;
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

        setSpawnRadius(world, value);
        sender.sendMessage("Game rule " + parts[1] + " set to " + value);
        return true;
    }

    private int getSpawnRadius(World world) {
        return zones.getInt("worlds." + world.getName(), 0);
    }

    private void setSpawnRadius(World world, int value) {
        zones.set("worlds." + world.getName(), value);
        try {
            zones.save(zonesFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save spawnzones.yml: " + e.getMessage());
        }
    }

    private boolean isEnabledWorld(Player player) {
        return worlds.isEmpty() || worlds.contains(player.getWorld().getName());
    }
}
