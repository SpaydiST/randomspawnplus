package org.example1.randomspawn;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;

import java.util.Random;
import java.util.logging.Logger;

public final class SpawnFinder {

    private final Random random = new Random();
    private final int margin;
    private final int attempts;
    private final int safeSearchRadius;
    private final Logger logger;

    public SpawnFinder(int margin, int attempts, int safeSearchRadius) {
        this.margin = margin;
        this.attempts = attempts;
        this.safeSearchRadius = safeSearchRadius;
        this.logger = Logger.getLogger("RandomSpawnPlus");
    }

    public Location find(World world, int spawnRadius) {
        WorldBorder border = world.getWorldBorder();
        logger.info("[DEBUG] find: world=" + world.getName()
                + " borderCenter=" + border.getCenter()
                + " borderSize=" + border.getSize()
                + " margin=" + margin
                + " spawnRadius=" + spawnRadius
                + " spawn=" + world.getSpawnLocation());
        for (int i = 0; i < attempts; i++) {
            Location location = tryRandom(world, spawnRadius);
            if (location != null) {
                return location;
            }
        }
        return null;
    }

    private Location tryRandom(World world, int spawnRadius) {
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double half = border.getSize() / 2.0 - margin;

        int x;
        int z;
        if (spawnRadius > 0) {
            Location spawn = world.getSpawnLocation();
            double angle = random.nextDouble() * 2.0 * Math.PI;
            double r = spawnRadius * Math.sqrt(random.nextDouble());
            x = (int) Math.floor(spawn.getX() + r * Math.cos(angle));
            z = (int) Math.floor(spawn.getZ() + r * Math.sin(angle));
            if (x < center.getX() - half || x > center.getX() + half
                    || z < center.getZ() - half || z > center.getZ() + half) {
                logger.info("[DEBUG] tryRandom: (" + x + "," + z + ") outside border, retry");
                return null;
            }
        } else {
            double minX = center.getX() - half;
            double maxX = center.getX() + half;
            double minZ = center.getZ() - half;
            double maxZ = center.getZ() + half;

            if (maxX <= minX || maxZ <= minZ) {
                return null;
            }

            x = (int) Math.floor(minX + random.nextDouble() * (maxX - minX));
            z = (int) Math.floor(minZ + random.nextDouble() * (maxZ - minZ));
        }

        Block surface = world.getHighestBlockAt(x, z);
        Location safe = findSafeSpot(world, surface);
        logger.info("[DEBUG] tryRandom: (" + x + "," + z + ") surfaceY=" + surface.getY()
                + " block=" + surface.getType() + " -> " + (safe == null ? "null" : "safe"));
        if (safe != null) {
            safe.setYaw(random.nextFloat() * 360.0F);
        }
        return safe;
    }

    private Location findSafeSpot(World world, Block start) {
        for (int radius = 0; radius <= safeSearchRadius; radius++) {
            int minX = start.getX() - radius;
            int maxX = start.getX() + radius;
            int minZ = start.getZ() - radius;
            int maxZ = start.getZ() + radius;

            for (int x = minX; x <= maxX; x++) {
                if (!isSafeSpot(world, x, minZ)) continue;
                return blockCenter(world, x, minZ);
            }
            for (int x = minX; x <= maxX; x++) {
                if (!isSafeSpot(world, x, maxZ)) continue;
                return blockCenter(world, x, maxZ);
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                if (!isSafeSpot(world, minX, z)) continue;
                return blockCenter(world, minX, z);
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                if (!isSafeSpot(world, maxX, z)) continue;
                return blockCenter(world, maxX, z);
            }
        }
        return null;
    }

    private boolean isSafeSpot(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        Block block = world.getBlockAt(x, y, z);
        Block below = world.getBlockAt(x, y - 1, z);
        return !isHazard(block) && !isHazard(below)
                && below.getType().isSolid();
    }

    private boolean isHazard(Block block) {
        switch (block.getType()) {
            case WATER:
            case LAVA:
            case CACTUS:
            case MAGMA_BLOCK:
            case FIRE:
            case SWEET_BERRY_BUSH:
                return true;
            default:
                return false;
        }
    }

    private Location blockCenter(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        return new Location(world, x + 0.5, y + 1.0, z + 0.5);
    }
}
