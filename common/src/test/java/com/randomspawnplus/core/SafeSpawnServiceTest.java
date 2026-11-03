package com.randomspawnplus.core;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты алгоритма поиска безопасной точки спавна на чистой логике,
 * без Minecraft-окружения (через {@link PlatformWorld}).
 */
class SafeSpawnServiceTest {

    private SafeSpawnService service(int defaultRadius) {
        SpawnConfig config = new SpawnConfig(
                50,     // border-margin
                20,     // search-attempts
                20,     // safe-search-radius
                1,      // height-offset
                defaultRadius,
                false,  // verbose
                Set.of("water", "lava", "cactus")
        );
        return new SafeSpawnService(config, new Random(42), msg -> {
        });
    }

    /** Как {@link #service(int)}, но без воды/лавы в списке опасных блоков. */
    private SafeSpawnService serviceNoLiquidHazards(int defaultRadius) {
        SpawnConfig config = new SpawnConfig(
                50, 20, 20, 1, defaultRadius, false, Set.of("cactus")
        );
        return new SafeSpawnService(config, new Random(42), msg -> {
        });
    }

    @Test
    void findsPointInLoadedWorldWithinBorder() {
        FakeWorld world = new FakeWorld("w", 0, 0, 1000, 0, 0, true);
        SpawnPoint p = service(0).find(world, 0);
        assertNotNull(p);
        // border = -450..450 after margin 50
        assertTrue(p.x() >= -450 && p.x() <= 450, "x within border: " + p.x());
        assertTrue(p.z() >= -450 && p.z() <= 450, "z within border: " + p.z());
        // surface y=4 + offset 1
        assertEquals(5, p.y());
    }

    @Test
    void respectsRadiusAroundWorldSpawn() {
        FakeWorld world = new FakeWorld("w", 0, 0, 1000, 0, 0, true);
        SpawnPoint p = service(0).find(world, 100);
        assertNotNull(p);
        double dist = Math.sqrt((p.x() - world.spawnX()) * (p.x() - world.spawnX())
                + (p.z() - world.spawnZ()) * (p.z() - world.spawnZ()));
        assertTrue(dist <= 100, "dist within radius: " + dist);
    }

    @Test
    void avoidsHazardColumns() {
        FakeWorld world = new FakeWorld("w", 0, 0, 400, 0, 0, true);
        // Ловушка по всей зоне поиска (граница -150..150 и fallback-скан 48),
        // кроме одной безопасной колонки (30,0) — её и должен вернуть поиск.
        for (int x = -200; x <= 200; x++) {
            for (int z = -200; z <= 200; z++) {
                if (x != 30 || z != 0) {
                    world.hazard(x, z);
                }
            }
        }
        SpawnPoint p = service(0).find(world, 0);
        assertNotNull(p);
        assertEquals(30, p.x());
        assertEquals(0, p.z());
    }

    @Test
    void fallsBackNearWorldSpawn() {
        // Мир с ловушками по всей зоне поиска, поиск должен упасть в фолбэк
        // возле спавна (единственная безопасная колонка (0,0)).
        FakeWorld world = new FakeWorld("w", 0, 0, 400, 0, 0, true);
        for (int x = -200; x <= 200; x++) {
            for (int z = -200; z <= 200; z++) {
                if (x != 0 || z != 0) {
                    world.hazard(x, z);
                }
            }
        }
        SpawnPoint p = service(0).find(world, 0);
        assertNotNull(p);
        assertEquals(0, p.x());
        assertEquals(0, p.z());
    }

    @Test
    void returnsNullWhenNothingSafeAndNoFallback() {
        FakeWorld world = new FakeWorld("w", 0, 0, 40, 0, 0, true);
        // Вся зона, достижимая и случайным поиском, и fallback-сканом (48),
        // — сплошная ловушка. Вернуть должно null.
        for (int x = -120; x <= 120; x++) {
            for (int z = -120; z <= 120; z++) {
                world.hazard(x, z);
            }
        }
        SpawnPoint p = service(0).find(world, 0);
        assertNull(p);
    }

    @Test
    void findWithoutGenerateSkipsUnloadedChunks() {
        FakeWorld world = new FakeWorld("w", 0, 0, 1000, 0, 0, false);
        SpawnPoint p = service(0).find(world, 0, false);
        assertNull(p);
    }

    @Test
    void findGeneratingLoadsUnloadedChunks() {
        FakeWorld world = new FakeWorld("w", 0, 0, 1000, 0, 0, false);
        SpawnPoint p = service(0).findGenerating(world, 0);
        assertNotNull(p);
    }

    @Test
    void spawnsOnSnowCappedSurface() {
        // Каждый столбец прикрыт несплошным блоком (снег): topY указывает
        // на снег, но точка спавна должна встать на твёрдую землю под ним.
        FakeWorld world = new FakeWorld("w", 0, 0, 400, 0, 0, true);
        for (int x = -200; x <= 200; x++) {
            for (int z = -200; z <= 200; z++) {
                world.snowColumn(x, z, 1);
            }
        }
        SpawnPoint p = service(0).find(world, 0);
        assertNotNull(p);
        assertEquals(5, p.y());
    }

    @Test
    void skipsFloodedColumnsToReachDryLand() {
        // Вода (опасная) поверх всего, кроме колонки (30,30) мирового спавна.
        FakeWorld world = new FakeWorld("w", 0, 0, 400, 30, 30, true);
        for (int x = -220; x <= 220; x++) {
            for (int z = -220; z <= 220; z++) {
                if (x == 30 && z == 30) {
                    continue;
                }
                world.waterColumn(x, z, 2);
            }
        }
        SpawnPoint p = service(0).find(world, 0);
        assertNotNull(p);
        assertEquals(30, p.x());
        assertEquals(30, p.z());
    }

    @Test
    void heightOffsetIsClampedToAtLeastOne() {
        SpawnConfig zero = new SpawnConfig(50, 20, 20, 0, 0, false, Set.of("water"));
        assertEquals(1, zero.heightOffset());
    }

    @Test
    void fallbackRespectsSmallRadius() {
        // Вся зона — ловушка, кроме колонки (28,0). При радиусе 5 случайный поиск
        // до неё не дотянется (сэмпл ≤ r5 + safe-search-radius 20 < 28) — значит
        // точку может вернуть только фолбэк. Фолбэк же обязан не выходить за радиус:
        // раньше сканировал до 48 и возвращал её.
        FakeWorld world = new FakeWorld("w", 0, 0, 400, 0, 0, true);
        for (int x = -200; x <= 200; x++) {
            for (int z = -200; z <= 200; z++) {
                if (x != 28 || z != 0) {
                    world.hazard(x, z);
                }
            }
        }
        SpawnPoint p = service(0).find(world, 5);
        assertNull(p);
    }

    @Test
    void fallbackRespectsBorderMargin() {
        // Спавн у границы (45,0 при границе -50..50 после margin 50): безопасная
        // колонка (80,0) недостижима случайным поиском (край границы 50 +
        // safe-search-radius 20 < 80), но попадает в радиус фолбэк-скана (48)
        // — фолбэк обязан пропустить её как точку за границей мира.
        FakeWorld world = new FakeWorld("w", 0, 0, 200, 45, 0, true);
        for (int x = -200; x <= 200; x++) {
            for (int z = -200; z <= 200; z++) {
                if (x != 80 || z != 0) {
                    world.hazard(x, z);
                }
            }
        }
        SpawnPoint p = service(0).find(world, 0);
        assertNull(p);
    }

    @Test
    void skipsLiquidColumnsEvenWithoutWaterInHazards() {
        // Вода заливает всё, кроме (30,0), но в hazards воды нет —
        // отсев должен обеспечить жёсткая проверка isLiquid.
        FakeWorld world = new FakeWorld("w", 0, 0, 400, 0, 0, true);
        world.hazardSet(Set.of("cactus"));
        for (int x = -220; x <= 220; x++) {
            for (int z = -220; z <= 220; z++) {
                if (x == 30 && z == 0) {
                    continue;
                }
                world.waterColumn(x, z, 2);
            }
        }
        SpawnPoint p = serviceNoLiquidHazards(0).find(world, 0);
        assertNotNull(p);
        assertEquals(30, p.x());
        assertEquals(0, p.z());
    }

    @Test
    void skipsVoidColumnsBoundedByMinY() {
        FakeWorld world = new FakeWorld("w", 0, 0, 400, 0, 0, true);
        for (int x = -200; x <= 200; x++) {
            for (int z = -200; z <= 200; z++) {
                if (x != 12 || z != -8) {
                    world.voidColumn(x, z);
                }
            }
        }
        SpawnPoint p = service(0).find(world, 0);
        assertNotNull(p);
        assertEquals(12, p.x());
        assertEquals(-8, p.z());
    }

    @Test
    void findGeneratingEnsuresNeighborChunksWhenScanningForShore() {
        // Все чанки изначально выгружены, и вся суша затоплена, кроме (35,0),
        // которая находится за пределами стартового чанка (0,0) на дистанции 35 блоков
        // (в пределах GENERATE_SCAN = 40, но дальше 1 соседнего чанка).
        FakeWorld world = new FakeWorld("w", 0, 0, 200, 0, 0, false);
        for (int x = -200; x <= 200; x++) {
            for (int z = -200; z <= 200; z++) {
                if (x != 35 || z != 0) {
                    world.waterColumn(x, z, 2);
                }
            }
        }
        SpawnPoint p = service(0).findGenerating(world, 5);
        assertNotNull(p);
        assertEquals(35, p.x());
        assertEquals(0, p.z());
    }
}