package com.randomspawnplus.core;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/** Safe-spawn search with bounded, resumable work and shared diagnostics. */
public final class SafeSpawnService {
    private static final int GENERATE_SCAN = 40;
    private static final int SPAWN_SCAN = 48;

    private final SpawnConfig config;
    private final Random random;
    private final Consumer<String> log;
    private long searches, succeeded, failed, candidateColumns, generatedChunks, fallbackHits, cacheHits, elapsedNanos;

    public SafeSpawnService(SpawnConfig config, Random random, Consumer<String> log) {
        this.config = config;
        this.random = random;
        this.log = log;
    }

    /** Synchronous compatibility entry point. Platform handlers should use {@link #start}. */
    public SpawnPoint find(PlatformWorld world, int radius) {
        return find(world, 0, radius, false);
    }

    public SpawnPoint find(PlatformWorld world, int radius, boolean generate) {
        return find(world, 0, radius, generate);
    }

    /** Searches a band around world spawn. A zero maximum means border-limited. */
    public SpawnPoint find(PlatformWorld world, int minRadius, int maxRadius, boolean generate) {
        SearchSession session = start(world, minRadius, maxRadius, generate);
        while (!session.done()) session.advance(world, config.maxColumnsPerSearch(), config.maxChunksPerSearch());
        return session.result();
    }

    /** Legacy Paper entry point, now bounded by the selected performance profile. */
    public SpawnPoint findGenerating(PlatformWorld world, int radius) {
        return find(world, 0, radius, true);
    }

    /** Begins a resumable search. Advance it from a platform server-tick callback. */
    public SearchSession start(PlatformWorld world, int minRadius, int maxRadius, boolean generate) {
        return new SearchSession(Math.max(0, minRadius), Math.max(0, maxRadius), generate);
    }

    /** Revalidates a reserved point without loading or generating its chunk. */
    public boolean isStillSafe(PlatformWorld world, SpawnPoint point, int minRadius, int maxRadius) {
        return point != null
                && withinAllowedRange(world, minRadius, maxRadius, point.x(), point.z())
                && !tooNearPlayer(world, point.x(), point.z())
                && safeSpot(world, point.x(), point.z()) != null;
    }

    public void recordCacheHit() { cacheHits++; }

    /** Finds a bounded cluster of distinct safe points around an already validated group anchor. */
    public List<SpawnPoint> findGroupPoints(PlatformWorld world, SpawnPoint anchor, int count, int spreadRadius,
                                           int minRadius, int maxRadius) {
        List<SpawnPoint> points = new ArrayList<>();
        if (anchor == null || count <= 0) return points;
        points.add(anchor);
        int max = Math.min(Math.max(1, spreadRadius), 24);
        int examined = 0;
        int budget = config.columnsPerTick();
        for (int radius = 1; radius <= max && points.size() < count && examined < budget; radius++) {
            for (int x = anchor.x() - radius; x <= anchor.x() + radius && points.size() < count && examined < budget; x++) {
                for (int z = anchor.z() - radius; z <= anchor.z() + radius && points.size() < count && examined < budget; z++) {
                    if (Math.max(Math.abs(x - anchor.x()), Math.abs(z - anchor.z())) != radius
                            || !withinAllowedRange(world, minRadius, maxRadius, x, z)) continue;
                    examined++;
                    candidateColumns++;
                    if (!world.hasChunk(x >> 4, z >> 4)) continue;
                    SpawnPoint candidate = safeSpot(world, x, z);
                    if (candidate == null || points.stream().anyMatch(existing -> squaredDistance(existing, candidate) < 9)) continue;
                    points.add(candidate);
                }
            }
        }
        return List.copyOf(points);
    }

    public SpawnStats stats() {
        return new SpawnStats(searches, succeeded, failed, candidateColumns,
                generatedChunks, fallbackHits, cacheHits, elapsedNanos);
    }

    public record SpawnStats(long searches, long succeeded, long failed, long candidateColumns,
                             long generatedChunks, long fallbackHits, long cacheHits, long elapsedNanos) {
        public long averageMillis() {
            long finished = succeeded + failed;
            return finished == 0 ? 0 : elapsedNanos / finished / 1_000_000L;
        }
    }

    public record Step(SpawnPoint point, boolean done, int columnsUsed, int chunksUsed) { }

    public final class SearchSession {
        private final int minRadius;
        private final int maxRadius;
        private final boolean generate;
        private final long startedAt = System.nanoTime();
        private final Set<Long> attemptedChunks = new HashSet<>();
        private int attempt = -1;
        private int ringRadius;
        private int edgePosition;
        private int columns;
        private int chunkAttempts;
        private int centerX, centerZ;
        private boolean canGenerateAttempt;
        private boolean fallback;
        private boolean done;
        private SpawnPoint result;
        private String currentWorld = "unknown";

        private SearchSession(int minRadius, int maxRadius, boolean generate) {
            this.minRadius = maxRadius > 0 ? Math.min(minRadius, maxRadius) : minRadius;
            this.maxRadius = maxRadius;
            this.generate = generate;
            searches++;
        }

        public boolean done() { return done; }
        public SpawnPoint result() { return result; }
        public int columns() { return columns; }
        public int chunksGenerated() { return chunkAttempts; }

        /**
         * Uses at most the given number of candidate columns and chunk-generation attempts. If a
         * needed chunk does not fit the remaining budget, that candidate is retried next tick.
         */
        public Step advance(PlatformWorld world, int columnBudget, int chunkBudget) {
            if (done) return new Step(result, true, 0, 0);
            currentWorld = world.name();
            int usedColumns = 0;
            int usedChunks = 0;
            int maxColumns = config.maxColumnsPerSearch();
            int budget = Math.max(1, columnBudget);

            while (!done && usedColumns < budget) {
                if (columns >= maxColumns) {
                    finish(null);
                    break;
                }

                Candidate candidate = nextCandidate(world);
                if (candidate == null) {
                    if (!fallback) {
                        fallback = true;
                        ringRadius = 0;
                        edgePosition = 0;
                        chooseFallbackCenter(world);
                        continue;
                    }
                    finish(null);
                    break;
                }

                columns++;
                candidateColumns++;
                usedColumns++;
                int x = candidate.x;
                int z = candidate.z;
                if (!withinAllowedRange(world, minRadius, maxRadius, x, z)) continue;

                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                if (!world.hasChunk(chunkX, chunkZ)) {
                    long key = pack(chunkX, chunkZ);
                    if (!generate || !candidate.mayGenerate || attemptedChunks.contains(key)
                            || chunkAttempts >= config.maxChunksPerSearch()) continue;
                    if (usedChunks >= Math.max(0, chunkBudget)) {
                        unadvanceCandidate();
                        columns--;
                        candidateColumns--;
                        usedColumns--;
                        return new Step(null, false, usedColumns, usedChunks);
                    }
                    attemptedChunks.add(key);
                    chunkAttempts++;
                    usedChunks++;
                    if (!world.ensureChunk(chunkX, chunkZ) || !world.hasChunk(chunkX, chunkZ)) continue;
                    generatedChunks++;
                }

                SpawnPoint point = safeSpot(world, x, z);
                if (point == null || tooNearPlayer(world, x, z)) continue;
                if (fallback) {
                    fallbackHits++;
                    log("Fallback: safe spot near fallback center (" + x + "," + z + ")");
                }
                result = new SpawnPoint(point.x(), point.y(), point.z(), random.nextFloat() * 360.0F);
                finish(result);
            }
            return new Step(result, done, usedColumns, usedChunks);
        }

        private Candidate nextCandidate(PlatformWorld world) {
            if (fallback) {
                int radiusLimit = generate ? GENERATE_SCAN : config.safeSearchRadius();
                return nextRingPoint(centerX, centerZ, radiusLimit, generate);
            }
            if (attempt < 0 || ringRadius > scanRadiusForAttempt()) {
                attempt++;
                if (attempt >= config.searchAttempts()) return null;
                ringRadius = 0;
                edgePosition = 0;
                double half = world.size() / 2.0 - config.borderMargin();
                if (half <= 0) {
                    centerX = world.spawnX();
                    centerZ = world.spawnZ();
                } else if (maxRadius > 0) {
                    double minSquared = (double) minRadius * minRadius;
                    double maxSquared = (double) maxRadius * maxRadius;
                    double r = Math.sqrt(minSquared + random.nextDouble() * Math.max(0, maxSquared - minSquared));
                    double angle = random.nextDouble() * Math.PI * 2.0;
                    centerX = (int) Math.floor(world.spawnX() + r * Math.cos(angle));
                    centerZ = (int) Math.floor(world.spawnZ() + r * Math.sin(angle));
                } else {
                    double minX = world.centerX() - half;
                    double maxX = world.centerX() + half;
                    double minZ = world.centerZ() - half;
                    double maxZ = world.centerZ() + half;
                    centerX = (int) Math.floor(minX + random.nextDouble() * (maxX - minX));
                    centerZ = (int) Math.floor(minZ + random.nextDouble() * (maxZ - minZ));
                }
                // Chunk generation is permitted throughout the search, but its global per-tick
                // and per-search budgets keep it bounded even when the first candidate is remote.
                canGenerateAttempt = generate;
            }
            Candidate point = nextRingPoint(centerX, centerZ, scanRadiusForAttempt(), canGenerateAttempt);
            if (point != null) return point;
            ringRadius = scanRadiusForAttempt() + 1;
            return nextCandidate(world);
        }

        private void chooseFallbackCenter(PlatformWorld world) {
            double half = Math.max(0.0, world.size() / 2.0 - config.borderMargin());
            double borderRadius = Math.sqrt(2.0) * half;
            double low = minRadius;
            double high = maxRadius > 0 ? maxRadius : borderRadius;
            high = Math.max(low, Math.min(high, borderRadius));
            double lowSquared = low * low;
            double radius = Math.sqrt(lowSquared + random.nextDouble()
                    * Math.max(0.0, high * high - lowSquared));
            double angle = random.nextDouble() * Math.PI * 2.0;
            centerX = (int) Math.floor(world.spawnX() + radius * Math.cos(angle));
            centerZ = (int) Math.floor(world.spawnZ() + radius * Math.sin(angle));
            canGenerateAttempt = generate;
        }

        private int scanRadiusForAttempt() {
            return canGenerateAttempt ? Math.max(config.safeSearchRadius(), GENERATE_SCAN) : config.safeSearchRadius();
        }

        private Candidate nextRingPoint(int cx, int cz, int maxRing, boolean mayGenerate) {
            while (ringRadius <= maxRing) {
                int r = ringRadius;
                if (r == 0) {
                    ringRadius = 1;
                    edgePosition = 0;
                    return new Candidate(cx, cz, mayGenerate);
                }
                int count = r * 8;
                if (edgePosition >= count) {
                    ringRadius++;
                    edgePosition = 0;
                    continue;
                }
                int p = edgePosition++;
                int x;
                int z;
                if (p < 2 * r + 1) {
                    x = cx - r + p;
                    z = cz - r;
                } else if (p < 4 * r + 1) {
                    x = cx + r;
                    z = cz - r + 1 + p - (2 * r + 1);
                } else if (p < 6 * r + 1) {
                    x = cx + r - 1 - (p - (4 * r + 1));
                    z = cz + r;
                } else {
                    x = cx - r;
                    z = cz + r - 1 - (p - (6 * r + 1));
                }
                return new Candidate(x, z, mayGenerate);
            }
            return null;
        }

        private void unadvanceCandidate() {
            if (edgePosition > 0) edgePosition--;
            else if (ringRadius > 0) {
                ringRadius--;
                edgePosition = ringRadius == 0 ? 0 : ringRadius * 8 - 1;
            }
        }

        public void cancel() {
            if (!done) finish(null);
        }

        private void finish(SpawnPoint point) {
            if (done) return;
            done = true;
            result = point;
            if (point == null) failed++;
            else succeeded++;
            elapsedNanos += Math.max(0, System.nanoTime() - startedAt);
            if (point == null) log("No safe spot found in '" + currentWorld + "' after " + columns + " columns");
        }

        private record Candidate(int x, int z, boolean mayGenerate) { }
    }

    private boolean tooNearPlayer(PlatformWorld world, int x, int z) {
        int spacing = config.options().playerSpacing();
        return spacing > 0 && world.hasNearbyPlayer(x, z, spacing);
    }

    private long squaredDistance(SpawnPoint a, SpawnPoint b) {
        long dx = (long) a.x() - b.x();
        long dz = (long) a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private boolean withinAllowedRange(PlatformWorld world, int minRadius, int maxRadius, int x, int z) {
        double half = world.size() / 2.0 - config.borderMargin();
        if (half < 0 || x < world.centerX() - half || x > world.centerX() + half
                || z < world.centerZ() - half || z > world.centerZ() + half) return false;
        long dx = (long) x - world.spawnX();
        long dz = (long) z - world.spawnZ();
        long distanceSquared = dx * dx + dz * dz;
        if (minRadius > 0 && distanceSquared < (long) minRadius * minRadius) return false;
        return maxRadius <= 0 || distanceSquared <= (long) maxRadius * maxRadius;
    }

    private SpawnPoint safeSpot(PlatformWorld world, int x, int z) {
        if (!world.hasChunk(x >> 4, z >> 4)) return null;
        int ground = world.topY(x, z);
        if (ground == Integer.MIN_VALUE) return null;
        int minY = world.minY();
        PlatformWorld.BlockInfo groundInfo = world.blockInfo(x, ground, z);
        while (ground > minY && !groundInfo.solid()) groundInfo = world.blockInfo(x, --ground, z);
        if (!groundInfo.solid() || groundInfo.hazard()) return null;
        int feet = ground + config.heightOffset();
        int head = feet + 1;
        if (config.options().avoidTrees() && groundInfo.tree()) return null;

        PlatformWorld.BlockInfo support = feet - 1 == ground ? groundInfo : world.blockInfo(x, feet - 1, z);
        if (!support.solid()) return null;
        PlatformWorld.BlockInfo feetInfo = feet == ground ? groundInfo : world.blockInfo(x, feet, z);
        if (feetInfo.solid() || feetInfo.hazard() || feetInfo.liquid()
                || (config.options().avoidTrees() && feetInfo.tree())) return null;
        PlatformWorld.BlockInfo headInfo = world.blockInfo(x, head, z);
        if (headInfo.solid() || headInfo.hazard() || headInfo.liquid()
                || (config.options().avoidTrees() && headInfo.tree())) return null;

        if (config.options().avoidNarrowLedges()) {
            int supported = 0;
            if (world.blockInfo(x + 1, feet - 1, z).solid()) supported++;
            if (world.blockInfo(x - 1, feet - 1, z).solid()) supported++;
            if (world.blockInfo(x, feet - 1, z + 1).solid()) supported++;
            if (world.blockInfo(x, feet - 1, z - 1).solid()) supported++;
            if (supported < 2) return null;
        }
        if (!config.options().excludedBiomes().isEmpty()
                && config.options().excludedBiomes().contains(world.biomeId(x, feet, z).toLowerCase(Locale.ROOT))) return null;
        if (config.options().lavaSafetyRadius() > 0
                && nearLava(world, x, feet, z, config.options().lavaSafetyRadius())) return null;
        return new SpawnPoint(x, feet, z, 0.0F);
    }

    /** Cardinal samples bound the extra work while checking for surface lava around a candidate. */
    private boolean nearLava(PlatformWorld world, int x, int y, int z, int radius) {
        for (int distance = 1; distance <= radius; distance++) {
            if (world.blockInfo(x + distance, y, z).lava()
                    || world.blockInfo(x - distance, y, z).lava()
                    || world.blockInfo(x, y, z + distance).lava()
                    || world.blockInfo(x, y, z - distance).lava()) return true;
        }
        return false;
    }

    private static long pack(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    private void log(String message) {
        if (config.verbose()) log.accept(message);
    }
}
