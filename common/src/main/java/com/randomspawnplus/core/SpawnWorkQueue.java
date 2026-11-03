package com.randomspawnplus.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Fair server-thread queue for incremental player searches and spare-point preparation. */
public final class SpawnWorkQueue {
    private final SafeSpawnService service;
    private final SpawnConfig config;
    private final Deque<Job> jobs = new ArrayDeque<>();
    private final Map<Key, Deque<ReservedPoint>> spare = new HashMap<>();
    private final Map<Key, Integer> preparing = new HashMap<>();
    private final Map<String, Deque<UsedPoint>> recentlyUsed = new HashMap<>();

    public SpawnWorkQueue(SafeSpawnService service, SpawnConfig config) {
        this.service = service;
        this.config = config;
    }

    public void request(String worldName, PlatformWorld world, SpawnMode mode, int minRadius, int maxRadius,
                        Consumer<SpawnPoint> completion) {
        Key key = new Key(worldName, mode, minRadius, maxRadius);
        Deque<ReservedPoint> points = spare.get(key);
        while (points != null && !points.isEmpty()) {
            ReservedPoint reserved = points.removeFirst();
            SpawnPoint point = reserved.point;
            if (service.isStillSafe(world, point, minRadius, maxRadius) && !tooRecent(worldName, point)) {
                reserved.world.finishSearch(point);
                service.recordCacheHit();
                remember(worldName, point);
                try {
                    completion.accept(point);
                } finally {
                    reserved.world.discardSearch();
                }
                return;
            }
            reserved.world.discardSearch();
        }
        jobs.addLast(new Job(key, world, service.start(world, minRadius, maxRadius, true), completion, false, 0));
    }

    /** Slowly fills a small pool of points while the server is already ticking. */
    public void prepare(String worldName, PlatformWorld world, SpawnMode mode, int minRadius, int maxRadius) {
        int target = config.options().reservePoints();
        if (target <= 0) return;
        Key key = new Key(worldName, mode, minRadius, maxRadius);
        int present = spare.getOrDefault(key, new ArrayDeque<>()).size() + preparing.getOrDefault(key, 0);
        if (present >= target) return;
        preparing.merge(key, 1, Integer::sum);
        jobs.addLast(new Job(key, world, service.start(world, minRadius, maxRadius, true), null, true, 0));
    }

    /** Run exactly once per server tick. */
    public void tick() {
        if (jobs.isEmpty()) return;
        int columnsLeft = config.columnsPerTick();
        int chunksLeft = config.maxChunksPerTick();
        int noProgress = 0;
        while (!jobs.isEmpty() && columnsLeft > 0 && noProgress < jobs.size()) {
            Job job = jobs.removeFirst();
            int slice = Math.min(columnsLeft, Math.max(8, config.columnsPerTick() / 4));
            SafeSpawnService.Step step = job.session.advance(job.world, slice, chunksLeft);
            columnsLeft -= step.columnsUsed();
            chunksLeft -= step.chunksUsed();
            if (step.done()) {
                complete(job, step.point());
                noProgress = step.columnsUsed() == 0 && step.chunksUsed() == 0 ? noProgress + 1 : 0;
            } else {
                jobs.addLast(job);
                noProgress = step.columnsUsed() == 0 && step.chunksUsed() == 0 ? noProgress + 1 : 0;
            }
            if (chunksLeft == 0 && noProgress >= jobs.size()) break;
        }
    }

    public void cancelAll() {
        for (Job job : jobs) {
            job.session.cancel();
            if (job.preparing) decrementPreparing(job.key);
            job.world.discardSearch();
        }
        for (Deque<ReservedPoint> points : spare.values()) {
            for (ReservedPoint point : points) point.world.discardSearch();
        }
        jobs.clear();
        spare.clear();
        preparing.clear();
        recentlyUsed.clear();
    }

    public int pendingJobs() { return jobs.size(); }

    private void complete(Job job, SpawnPoint point) {
        if (job.preparing) {
            decrementPreparing(job.key);
            if (point != null) {
                Deque<ReservedPoint> points = spare.computeIfAbsent(job.key, ignored -> new ArrayDeque<>());
                int target = config.options().reservePoints();
                boolean farEnough = points.stream().allMatch(existing -> distance(existing.point, point)
                        >= Math.max(config.options().playerSpacing(), 64));
                if (points.size() < target && farEnough && !tooRecent(job.key.worldName, point)) {
                    job.world.finishSearch(point);
                    points.addLast(new ReservedPoint(point, job.world));
                } else job.world.discardSearch();
            } else job.world.discardSearch();
        } else if (job.completion != null) {
            if (point != null && tooRecent(job.key.worldName, point)) {
                job.world.discardSearch();
                if (job.retries < 2) {
                    jobs.addLast(new Job(job.key, job.world,
                            service.start(job.world, job.key.minRadius, job.key.maxRadius, true),
                            job.completion, false, job.retries + 1));
                } else job.completion.accept(null);
                return;
            }
            job.world.finishSearch(point);
            try {
                if (point != null) remember(job.key.worldName, point);
                job.completion.accept(point);
            } finally {
                job.world.discardSearch();
            }
        }
    }

    private void decrementPreparing(Key key) {
        int count = preparing.getOrDefault(key, 0);
        if (count <= 1) preparing.remove(key);
        else preparing.put(key, count - 1);
    }

    private static double distance(SpawnPoint a, SpawnPoint b) {
        long dx = (long) a.x() - b.x();
        long dz = (long) a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean tooRecent(String worldName, SpawnPoint point) {
        Deque<UsedPoint> used = recentlyUsed.get(worldName);
        if (used == null) return false;
        long now = System.nanoTime();
        while (!used.isEmpty() && now - used.peekFirst().atNanos > 10 * 60 * 1_000_000_000L) used.removeFirst();
        int spacing = Math.max(64, config.options().playerSpacing());
        return used.stream().anyMatch(entry -> distance(entry.point, point) < spacing);
    }

    private void remember(String worldName, SpawnPoint point) {
        Deque<UsedPoint> used = recentlyUsed.computeIfAbsent(worldName, ignored -> new ArrayDeque<>());
        used.addLast(new UsedPoint(point, System.nanoTime()));
        while (used.size() > 256) used.removeFirst();
    }

    private record Key(String worldName, SpawnMode mode, int minRadius, int maxRadius) { }
    private record ReservedPoint(SpawnPoint point, PlatformWorld world) { }
    private record Job(Key key, PlatformWorld world, SafeSpawnService.SearchSession session,
                       Consumer<SpawnPoint> completion, boolean preparing, int retries) { }
    private record UsedPoint(SpawnPoint point, long atNanos) { }
}
