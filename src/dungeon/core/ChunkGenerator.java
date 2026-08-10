package dungeon.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区块生成器，复刻 infdev 版 ChunkProviderGenerate（provideChunk 完整地形 + 洞穴）
 * 与 WorldGenDungeons（地牢判定）。地牢生成依赖 3D 地形，因此逐区块模拟完整地形。
 *
 * 线程安全：FractalNoise 不可变；地形缓存与地牢缓存均为线程安全/受锁保护。
 */
public final class ChunkGenerator {
    // 方块 ID（infdev 版）
    private static final int AIR = 0, STONE = 1, GRASS = 2, DIRT = 3, COBBLE = 4,
            BEDROCK = 7, WATER_MOVING = 8, WATER = 9, LAVA = 10, SAND = 12, GRAVEL = 13,
            MOSSY = 48, SPAWNER = 52, CRATE = 54;

    private static final long CHUNK_SEED_X = 341873128712L;
    private static final long CHUNK_SEED_Z = 132897987541L;

    private final long worldSeed;
    private final GameVersion version;
    private final ScaledFractalNoise noiseGen1;
    private final ScaledFractalNoise noiseGen2;
    private final ScaledFractalNoise noiseGen3;
    private final ScaledFractalNoise noiseGen4;
    private final ScaledFractalNoise noiseGen5;
    private final ScaledFractalNoise noiseGen6;
    private final ScaledFractalNoise noiseGen7;

    // 地形缓存（LRU，受锁保护）
    // 瓦片会并发生成并共享邻接区块地形；缓存过小会导致多线程下重复生成同一区块。
    // 8192 × 32KB ≈ 256MB，可容纳数千区块的邻接复用，显著减少重复计算。
    private static final int MAX_TERRAIN = 8192;
    private final Object terrainLock = new Object();
    private final LinkedHashMap<Long, byte[]> terrainCache =
            new LinkedHashMap<>(1024, 0.75f, true);
    private int terrainCount = 0;

    // 地牢结果缓存（每区块 → 地牢位置 [x,y,z,怪物id]）
    private final Map<Long, List<int[]>> dungeonCache = new ConcurrentHashMap<>();

    // 临时磁盘缓存（仅会话内）：把已计算的区块地牢结果写到磁盘，切回同一种子时可秒载；
    // 程序退出时由应用层删除缓存文件。后台线程定期增量追加。
    private final File diskFile;
    private final boolean diskEnabled;
    private final Set<Long> dirty = new HashSet<>();
    private volatile boolean diskRunning;
    private Thread diskFlusher;

    public ChunkGenerator(long seed) {
        this(seed, GameVersion.INF_20100625, null);
    }

    public ChunkGenerator(long seed, File diskCacheFile) {
        this(seed, GameVersion.INF_20100625, diskCacheFile);
    }

    public ChunkGenerator(long seed, GameVersion version, File diskCacheFile) {
        this.worldSeed = seed;
        this.version = version == null ? GameVersion.INF_20100625 : version;
        this.diskFile = diskCacheFile;
        this.diskEnabled = diskCacheFile != null;
        java.util.Random random = new java.util.Random(seed);
        noiseGen1 = new ScaledFractalNoise(random, 684.412, 684.412, 16);
        noiseGen2 = new ScaledFractalNoise(random, 684.412, 684.412, 16);
        noiseGen3 = new ScaledFractalNoise(random, 8.555150000000001, 4.277575000000001, 8);
        noiseGen4 = new ScaledFractalNoise(random, 1.0, 1.0, 4);
        noiseGen5 = new ScaledFractalNoise(random, 1.0, 1.0, 4);
        noiseGen6 = new ScaledFractalNoise(random, 1.0, 0.0, 10);
        noiseGen7 = new ScaledFractalNoise(random, 100.0, 0.0, 16);
        ScaledFractalNoise.discardNoise(random, 8); // mobSpawnerNoise（仅用于推进随机流）
        if (diskEnabled) {
            loadDiskCache();
            diskRunning = true;
            diskFlusher = new Thread(this::diskFlushLoop, "dungeon-disk-flusher");
            diskFlusher.setDaemon(true);
            diskFlusher.start();
        }
    }

    /** 构造时若磁盘缓存文件已存在（上次异常退出残留），读入内存，避免重算。 */
    private void loadDiskCache() {
        if (diskFile == null || !diskFile.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(diskFile)))) {
            while (true) {
                long key = in.readLong();
                int n = in.readInt();
                List<int[]> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(new int[]{in.readInt(), in.readInt(), in.readInt(), in.readInt()});
                }
                dungeonCache.put(key, list);
            }
        } catch (EOFException ignored) {
            // 正常读完
        } catch (IOException ignored) {
            // 文件损坏/不可读则忽略，重新计算
        }
    }

    /** 后台线程：定期把新计算的区块追加写入磁盘缓存。 */
    private void diskFlushLoop() {
        while (diskRunning) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                break;
            }
            flushDirty();
        }
        flushDirty();
    }

    /** 把脏区块增量追加写入磁盘缓存文件。 */
    private void flushDirty() {
        if (!diskEnabled) return;
        List<Long> keys;
        synchronized (dirty) {
            if (dirty.isEmpty()) return;
            keys = new ArrayList<>(dirty);
            dirty.clear();
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(diskFile, true)))) {
            for (long key : keys) {
                List<int[]> list = dungeonCache.get(key);
                if (list == null) continue;
                out.writeLong(key);
                out.writeInt(list.size());
                for (int[] d : list) {
                    out.writeInt(d[0]);
                    out.writeInt(d[1]);
                    out.writeInt(d[2]);
                    out.writeInt(d[3]);
                }
            }
            out.flush();
        } catch (IOException ignored) {
            // 磁盘写入失败不阻塞主流程
        }
    }

    /**
     * 停止后台刷新线程并落盘收尾。磁盘缓存文件由应用层在程序退出时删除
     * （切种子时保留，以便切回同一种子复用）。
     */
    public void close() {
        diskRunning = false;
        if (diskFlusher != null) {
            try {
                diskFlusher.join(3000);
            } catch (InterruptedException ignored) {
            }
        }
        flushDirty();
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** 采样一个 3D 噪声 cuboid，返回 resX*resY*resZ 数组（x,z,y 序，与原版一致）。 */
    private static double[] genNoise(ScaledFractalNoise sn, int cx, int cz,
                                     int resX, int resY, int resZ,
                                     double sx, double sy, double sz) {
        double[] buf = new double[resX * resY * resZ];
        SamplingCuboid cub = new SamplingCuboid(
                new Coord.SamplePos3D(cx * 4, 0, cz * 4), resX, resY, resZ, sx, sy, sz);
        sn.fractalNoise().beginSamplingInto(cub, buf).sampleAll();
        return buf;
    }

    /** 获取某区块的地形（含洞穴），带 LRU 缓存。 */
    public byte[] terrain(int cx, int cz) {
        long key = pack(cx, cz);
        synchronized (terrainLock) {
            byte[] t = terrainCache.get(key);
            if (t != null) return t;
        }
        byte[] t = generateTerrain(cx, cz);
        synchronized (terrainLock) {
            byte[] existing = terrainCache.get(key);
            if (existing != null) return existing;
            terrainCache.put(key, t);
            if (++terrainCount > MAX_TERRAIN) {
                java.util.Iterator<Map.Entry<Long, byte[]>> it = terrainCache.entrySet().iterator();
                it.next();
                it.remove();
                terrainCount--;
            }
            return t;
        }
    }

    /** 生成完整地形（噪声密度 → 三线性插值 → 地表 → 洞穴），32KB 方块数组。 */
    private byte[] generateTerrain(int cx, int cz) {
        double[] noise6 = genNoise(noiseGen6, cx, cz, 5, 1, 5, 1.0, 0.0, 1.0);
        double[] noise7 = genNoise(noiseGen7, cx, cz, 5, 1, 5, 100.0, 0.0, 100.0);
        double[] noise3 = genNoise(noiseGen3, cx, cz, 5, 17, 5, 8.555150000000001, 4.277575000000001, 8.555150000000001);
        double[] noise1 = genNoise(noiseGen1, cx, cz, 5, 17, 5, 684.412, 684.412, 684.412);
        double[] noise2 = genNoise(noiseGen2, cx, cz, 5, 17, 5, 684.412, 684.412, 684.412);

        JavaRandom rand = new JavaRandom((long) cx * CHUNK_SEED_X + (long) cz * CHUNK_SEED_Z);
        byte[] b3 = new byte[32768];

        // ===== 密度数组（5x5x17 = 425）=====
        double[] d6 = new double[425];
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                int n56 = x * 5 + z;
                double d65;
                if ((d65 = (noise6[n56] + 256.0) / 512.0) > 1.0) d65 = 1.0;
                double d69;
                if ((d69 = noise7[n56] / 8000.0) < 0.0) d69 = -d69;
                if ((d69 = d69 * 3.0 - 3.0) < 0.0) {
                    if ((d69 /= 2.0) < -1.0) d69 = -1.0;
                    d69 = (d69 /= 1.4) / 2.0;
                    d65 = 0.0;
                } else {
                    if (d69 > 1.0) d69 = 1.0;
                    d69 /= 6.0;
                }
                d65 += 0.5;
                d69 = d69 * 17.0 / 16.0;
                double d71 = 8.5 + d69 * 4.0;
                for (int y = 0; y < 17; y++) {
                    int i10 = (x * 5 + z) * 17 + y;
                    double d76;
                    if ((d76 = ((double) y - d71) * 12.0 / d65) < 0.0) d76 *= 4.0;
                    double d78 = noise1[i10] / 512.0;
                    double d80 = noise2[i10] / 512.0;
                    double d74, d82;
                    if ((d82 = (noise3[i10] / 10.0 + 1.0) / 2.0) < 0.0) d74 = d78;
                    else if (d82 > 1.0) d74 = d80;
                    else d74 = d78 + (d80 - d78) * d82;
                    d74 -= d76;
                    if (y > 13) {
                        float d84 = (float) (y - 13) / 3.0F;
                        d74 = d74 * (1.0 - d84) + d84 * -10.0;
                    }
                    d6[i10] = d74;
                }
            }
        }

        // ===== 三线性插值 → 16x16x128 =====
        for (int i87 = 0; i87 < 4; i87++) {
            for (int i88 = 0; i88 < 4; i88++) {
                for (int y7 = 0; y7 < 16; y7++) {
                    int base = (i87 * 5 + i88) * 17 + y7;
                    double d93 = d6[base];
                    double d12 = d6[base + 17];
                    double d14 = d6[base + 85];
                    double d16 = d6[base + 85 + 17];
                    double d18 = (d6[base + 1] - d93) * 0.125;
                    double d20 = (d6[base + 17 + 1] - d12) * 0.125;
                    double d22 = (d6[base + 85 + 1] - d14) * 0.125;
                    double d24 = (d6[base + 85 + 17 + 1] - d16) * 0.125;
                    for (int y90 = 0; y90 < 8; y90++) {
                        double d27 = d93, d29 = d12;
                        double d31 = (d14 - d93) * 0.25;
                        double d33 = (d16 - d12) * 0.25;
                        for (int i9 = 0; i9 < 4; i9++) {
                            int i26 = (i9 + (i87 << 2)) << 11 | (i88 << 2) << 7 | ((y7 << 3) + y90);
                            double d37 = d27, d39 = (d29 - d27) * 0.25;
                            for (int i35 = 0; i35 < 4; i35++) {
                                int i36 = 0;
                                if ((y7 << 3) + y90 < 64) i36 = WATER;
                                if (d37 > 0.0) i36 = STONE;
                                b3[i26] = (byte) i36;
                                i26 += 128;
                                d37 += d39;
                            }
                            d27 += d31;
                            d29 += d33;
                        }
                        d93 += d18;
                        d12 += d20;
                        d14 += d22;
                        d16 += d24;
                    }
                }
            }
        }

        // ===== 地表 pass =====
        for (int i87 = 0; i87 < 16; i87++) {
            for (int i88 = 0; i88 < 16; i88++) {
                double d89 = (double) ((cx << 4) + i87);
                double d92 = (double) ((cz << 4) + i88);
                boolean z13 = noiseGen4.sample3dPoint(d89 * 8.0 / 256.0, d92 * 8.0 / 256.0, 0.0)
                        + rand.nextDouble() * 0.2 > 0.0;
                boolean z94 = noiseGen4.sample3dPoint(d92 * 8.0 / 256.0, 109.0134, d89 * 8.0 / 256.0)
                        + rand.nextDouble() * 0.2 > 3.0;
                int i15 = (int) (noiseGen5.sample2dPoint(d89 * 8.0 / 256.0 * 2.0, d92 * 8.0 / 256.0 * 2.0)
                        / 3.0 + 3.0 + rand.nextDouble() * 0.25);
                int i95 = -1;
                int i17 = GRASS;
                int i96 = DIRT;
                int i19 = 0;
                int i97;
                for (i97 = i87 << 11 | i88 << 7; i19 < 128 && (b3[i97] == 0 || b3[i97] == WATER); ++i97) {
                    b3[i97] = 0;
                    ++i19;
                }
                i97 = i87 << 11 | i88 << 7 | 127;
                for (int i21 = 127; i21 >= i19; --i21) {
                    if (i21 <= i19 + rand.nextInt(6) - 1) {
                        b3[i97] = (byte) BEDROCK;
                    } else if (b3[i97] == 0) {
                        i95 = -1;
                    } else if (b3[i97] == STONE) {
                        if (i95 == -1) {
                            if (i15 <= 0) {
                                i17 = 0;
                                i96 = STONE;
                            } else if (i21 >= 60 && i21 <= 65) {
                                i17 = GRASS;
                                i96 = DIRT;
                                if (z94) i17 = 0;
                                if (z94) i96 = GRAVEL;
                                if (z13) i17 = SAND;
                                if (z13) i96 = SAND;
                            }
                            if (i21 < 64 && i17 == 0) i17 = WATER;
                            i95 = i15;
                            if (i21 >= 63) b3[i97] = (byte) i17;
                            else b3[i97] = (byte) i96;
                        } else if (i95 > 0) {
                            --i95;
                            b3[i97] = (byte) i96;
                        }
                    }
                    --i97;
                }
            }
        }

        // ===== 洞穴 =====
        JavaRandom caveRand = new JavaRandom(worldSeed);
        long j98 = (caveRand.nextLong() / 2L << 1) + 1L;
        long j99 = (caveRand.nextLong() / 2L << 1) + 1L;
        for (int i1 = cx - 8; i1 <= cx + 8; i1++) {
            for (int i2 = cz - 8; i2 <= cz + 8; i2++) {
                caveRand.setSeed((long) i1 * j98 + (long) i2 * j99 ^ worldSeed);
                int i86 = caveRand.nextInt(caveRand.nextInt(caveRand.nextInt(40) + 1) + 1);
                if (caveRand.nextInt(15) != 0) i86 = 0;
                for (int c = 0; c < i86; c++) {
                    double d100 = (double) ((i1 << 4) + caveRand.nextInt(16));
                    double d101 = (double) caveRand.nextInt(caveRand.nextInt(120) + 8);
                    double d60 = (double) ((i2 << 4) + caveRand.nextInt(16));
                    int i62 = 1;
                    if (caveRand.nextInt(4) == 0) {
                        generateCaves(cx, cz, b3, d100, d101, d60,
                                1.0F + caveRand.nextFloat() * 6.0F, 0.0F, 0.0F, -1, -1, 0.5, caveRand);
                        i62 = 1 + caveRand.nextInt(4);
                    }
                    for (int g = 0; g < i62; g++) {
                        float f64 = caveRand.nextFloat() * (float) Math.PI * 2.0F;
                        float f102 = (caveRand.nextFloat() - 0.5F) * 2.0F / 8.0F;
                        float f66 = caveRand.nextFloat() * 2.0F + caveRand.nextFloat();
                        generateCaves(cx, cz, b3, d100, d101, d60, f66, f64, f102, 0, 0, 1.0, caveRand);
                    }
                }
            }
        }

        return b3;
    }

    /** 递归洞穴生成，复刻 ChunkProviderGenerate.generateCaves。 */
    private static void generateCaves(int cx, int cz, byte[] b3, double d4, double d6, double d8,
                                      float f10, float f11, float f12, int i13, int i14,
                                      double d15, JavaRandom rand) {
        label204:
        while (true) {
            double d17 = (double) ((cx << 4) + 8);
            double d19 = (double) ((cz << 4) + 8);
            float f21 = 0.0F, f22 = 0.0F;
            JavaRandom random23 = new JavaRandom(rand.nextLong());
            if (i14 <= 0) i14 = 112 - random23.nextInt(28);
            boolean z24 = false;
            if (i13 == -1) {
                i13 = i14 / 2;
                z24 = true;
            }
            int i25 = random23.nextInt(i14 / 2) + i14 / 4;
            for (boolean z26 = random23.nextInt(6) == 0; i13 < i14; ++i13) {
                double d27;
                double d29 = (d27 = 1.5 + (double) (MathHelper.sin((float) i13 * (float) Math.PI / (float) i14) * f10)) * d15;
                float f31 = MathHelper.cos(f12);
                float f32 = MathHelper.sin(f12);
                d4 += (double) (MathHelper.cos(f11) * f31);
                d6 += (double) f32;
                d8 += (double) (MathHelper.sin(f11) * f31);
                if (z26) f12 *= 0.92F;
                else f12 *= 0.7F;
                f12 += f22 * 0.1F;
                f11 += f21 * 0.1F;
                f22 *= 0.9F;
                f21 *= 0.75F;
                f22 += (random23.nextFloat() - random23.nextFloat()) * random23.nextFloat() * 2.0F;
                f21 += (random23.nextFloat() - random23.nextFloat()) * random23.nextFloat() * 4.0F;
                if (!z24 && i13 == i25 && f10 > 1.0F) {
                    generateCaves(cx, cz, b3, d4, d6, d8,
                            random23.nextFloat() * 0.5F + 0.5F, f11 - (float) Math.PI / 2F, f12 / 3.0F,
                            i13, i14, 1.0, rand);
                    float f10007 = random23.nextFloat() * 0.5F + 0.5F;
                    float f10008 = f11 + (float) Math.PI / 2F;
                    float f10009 = f12 / 3.0F;
                    d15 = 1.0;
                    f12 = f10009;
                    f11 = f10008;
                    f10 = f10007;
                    continue label204;
                }
                if (z24 || random23.nextInt(4) != 0) {
                    double d33 = d4 - d17;
                    double d35 = d8 - d19;
                    double d37 = (double) (i14 - i13);
                    double d39 = (double) (f10 + 2.0F + 16.0F);
                    if (d33 * d33 + d35 * d35 - d37 * d37 > d39 * d39) return;
                    if (d4 >= d17 - 16.0 - d27 * 2.0 && d8 >= d19 - 16.0 - d27 * 2.0
                            && d4 <= d17 + 16.0 + d27 * 2.0 && d8 <= d19 + 16.0 + d27 * 2.0) {
                        int i53 = MathHelper.floor_double(d4 - d27) - (cx << 4) - 1;
                        int i34 = MathHelper.floor_double(d4 + d27) - (cx << 4) + 1;
                        int i55 = MathHelper.floor_double(d6 - d29) - 1;
                        int i36 = MathHelper.floor_double(d6 + d29) + 1;
                        int i56 = MathHelper.floor_double(d8 - d27) - (cz << 4) - 1;
                        int i38 = MathHelper.floor_double(d8 + d27) - (cz << 4) + 1;
                        if (i53 < 0) i53 = 0;
                        if (i34 > 16) i34 = 16;
                        if (i55 <= 0) i55 = 1;
                        if (i36 > 120) i36 = 120;
                        if (i56 < 0) i56 = 0;
                        if (i38 > 16) i38 = 16;
                        boolean z57 = false;
                        for (int i40 = i53; !z57 && i40 < i34; i40++) {
                            for (int i41 = i56; !z57 && i41 < i38; i41++) {
                                for (int i42 = i36 + 1; !z57 && i42 >= i55 - 1; --i42) {
                                    int i51 = ((i40 << 4) + i41 << 7) + i42;
                                    if (i42 >= 0 && i42 < 128) {
                                        if (b3[i51] == WATER_MOVING || b3[i51] == WATER) z57 = true;
                                        if (i42 != i55 - 1 && i40 != i53 && i40 != i34 - 1
                                                && i41 != i56 && i41 != i38 - 1) i42 = i55;
                                    }
                                }
                            }
                        }
                        if (!z57) {
                            for (int i40 = i53; i40 < i34; i40++) {
                                double d59 = ((double) (i40 + (cx << 4)) + 0.5 - d4) / d27;
                                for (int i51 = i56; i51 < i38; i51++) {
                                    double d44 = ((double) (i51 + (cz << 4)) + 0.5 - d8) / d27;
                                    int i52 = ((i40 << 4) + i51 << 7) + i36;
                                    boolean z54 = false;
                                    for (int i58 = i36 - 1; i58 >= i55; --i58) {
                                        double d49;
                                        if ((d49 = ((double) i58 + 0.5 - d6) / d29) > -0.7
                                                && d59 * d59 + d49 * d49 + d44 * d44 < 1.0) {
                                            byte b43 = b3[i52];
                                            if (b43 == GRASS) z54 = true;
                                            if (b43 == STONE || b43 == DIRT || b43 == GRASS) {
                                                if (i58 < 10) b3[i52] = (byte) LAVA;
                                                else {
                                                    b3[i52] = 0;
                                                    if (z54 && b3[i52 - 1] == DIRT) b3[i52 - 1] = (byte) GRASS;
                                                }
                                            }
                                        }
                                        --i52;
                                    }
                                }
                            }
                            if (z24) break;
                        }
                    }
                }
            }
            return;
        }
    }

    // ==================== 地牢判定 ====================

    /** 获取某区块在各次 populate 尝试中放置的地牢位置列表（每组 [x,y,z,刷怪笼怪物id]）。已缓存。 */
    public List<int[]> getDungeons(int cx, int cz) {
        long key = pack(cx, cz);
        List<int[]> cached = dungeonCache.get(key);
        if (cached != null) return cached;
        List<int[]> result = computeDungeons(cx, cz);
        dungeonCache.put(key, result);
        if (diskEnabled) {
            synchronized (dirty) {
                dirty.add(key);
            }
        }
        return result;
    }

    /** 模拟该区块四次 populate 地牢尝试（使用 4 个邻接地形的副本，避免污染缓存）。 */
    private List<int[]> computeDungeons(int cx, int cz) {
        byte[] t00 = terrain(cx, cz).clone();
        byte[] t10 = terrain(cx + 1, cz).clone();
        byte[] t01 = terrain(cx, cz + 1).clone();
        byte[] t11 = terrain(cx + 1, cz + 1).clone();

        JavaRandom rand = new JavaRandom(worldSeed);
        long j6 = (rand.nextLong() / 2L << 1) + 1L;
        long j8 = (rand.nextLong() / 2L << 1) + 1L;
        rand.setSeed((long) cx * j6 + (long) cz * j8 ^ worldSeed);

        List<int[]> result = new ArrayList<>(4);
        for (int a = 0; a < 4; a++) {
            int x = cx * 16 + rand.nextInt(16) + 8;
            int y = rand.nextInt(128);
            int z = cz * 16 + rand.nextInt(16) + 8;
            int mob = dungeonGenerate(t00, t10, t01, t11, cx, cz, rand, x, y, z);
            if (mob >= 0) {
                result.add(new int[]{x, y, z, mob});
            }
        }
        return result;
    }

    private byte[] select(byte[] t00, byte[] t10, byte[] t01, byte[] t11,
                          int cx, int cz, int x, int z) {
        int cxc = x >> 4, czc = z >> 4;
        if (cxc == cx && czc == cz) return t00;
        if (cxc == cx + 1 && czc == cz) return t10;
        if (cxc == cx && czc == cz + 1) return t01;
        if (cxc == cx + 1 && czc == cz + 1) return t11;
        return null;
    }

    private byte getBlock(byte[] t00, byte[] t10, byte[] t01, byte[] t11,
                          int cx, int cz, int x, int y, int z) {
        if (y < 0 || y >= 128) return 0;
        byte[] t = select(t00, t10, t01, t11, cx, cz, x, z);
        if (t == null) return 0;
        return t[(x & 15) << 11 | (z & 15) << 7 | y];
    }

    private void setBlock(byte[] t00, byte[] t10, byte[] t01, byte[] t11,
                          int cx, int cz, int x, int y, int z, int id) {
        if (y < 0 || y >= 128) return;
        byte[] t = select(t00, t10, t01, t11, cx, cz, x, z);
        if (t == null) return;
        t[(x & 15) << 11 | (z & 15) << 7 | y] = (byte) id;
    }

    /** 方块是否实心（Material.isSolid）。 */
    private static boolean isSolid(byte id) {
        return id == STONE || id == GRASS || id == DIRT || id == COBBLE || id == BEDROCK
                || id == SAND || id == GRAVEL || id == MOSSY || id == SPAWNER || id == CRATE;
    }

    private boolean isSolidBlock(byte[] t00, byte[] t10, byte[] t01, byte[] t11,
                                 int cx, int cz, int x, int y, int z) {
        return isSolid(getBlock(t00, t10, t01, t11, cx, cz, x, y, z));
    }

    /** 复刻 WorldGenDungeons.generate。返回刷怪笼怪物 id（0=Skeleton,1/2=Zombie,3=Spider），未放置返回 -1。 */
    private int dungeonGenerate(byte[] t00, byte[] t10, byte[] t01, byte[] t11,
                                int cx, int cz, JavaRandom rand, int x, int y, int z) {
        int i6 = rand.nextInt(2) + 2;
        int i7 = rand.nextInt(2) + 2;
        int i8 = 0;
        for (int i9 = x - i6 - 1; i9 <= x + i6 + 1; i9++) {
            for (int i10 = y - 1; i10 <= y + 3 + 1; i10++) {
                for (int i11 = z - i7 - 1; i11 <= z + i7 + 1; i11++) {
                    if (i10 == y - 1 && !isSolidBlock(t00, t10, t01, t11, cx, cz, i9, i10, i11)) return -1;
                    if (i10 == y + 3 + 1 && !isSolidBlock(t00, t10, t01, t11, cx, cz, i9, i10, i11)) return -1;
                    if ((i9 == x - i6 - 1 || i9 == x + i6 + 1 || i11 == z - i7 - 1 || i11 == z + i7 + 1)
                            && i10 == y
                            && getBlock(t00, t10, t01, t11, cx, cz, i9, i10, i11) == 0
                            && getBlock(t00, t10, t01, t11, cx, cz, i9, i10 + 1, i11) == 0) i8++;
                }
            }
        }

        if (i8 > 0 && i8 <= 5) {
            // 放置房间（石砖/苔石、内部挖空）
            for (int i9 = x - i6 - 1; i9 <= x + i6 + 1; i9++) {
                for (int i10 = y + 3; i10 >= y - 1; --i10) {
                    for (int i11 = z - i7 - 1; i11 <= z + i7 + 1; i11++) {
                        if (i9 != x - i6 - 1 && i10 != y - 1 && i11 != z - i7 - 1
                                && i9 != x + i6 + 1 && i10 != y + 3 + 1 && i11 != z + i7 + 1) {
                            setBlock(t00, t10, t01, t11, cx, cz, i9, i10, i11, 0);
                        } else if (i10 >= 0 && !isSolidBlock(t00, t10, t01, t11, cx, cz, i9, i10 - 1, i11)) {
                            setBlock(t00, t10, t01, t11, cx, cz, i9, i10, i11, 0);
                        } else if (isSolidBlock(t00, t10, t01, t11, cx, cz, i9, i10, i11)) {
                            if (i10 == y - 1 && rand.nextInt(4) != 0) {
                                setBlock(t00, t10, t01, t11, cx, cz, i9, i10, i11, MOSSY);
                            } else {
                                setBlock(t00, t10, t01, t11, cx, cz, i9, i10, i11, COBBLE);
                            }
                        }
                    }
                }
            }

            // 箱子（消耗 RNG；写 crate 方块）
            outer:
            for (int ci9 = 0; ci9 < 2; ci9++) {
                for (int ci10 = 0; ci10 < 3; ci10++) {
                    int cxL = x + rand.nextInt((i6 << 1) + 1) - i6;
                    int czL = z + rand.nextInt((i7 << 1) + 1) - i7;
                    if (getBlock(t00, t10, t01, t11, cx, cz, cxL, y, czL) == 0) {
                        int i14 = 0;
                        if (isSolidBlock(t00, t10, t01, t11, cx, cz, cxL - 1, y, czL)) i14++;
                        if (isSolidBlock(t00, t10, t01, t11, cx, cz, cxL + 1, y, czL)) i14++;
                        if (isSolidBlock(t00, t10, t01, t11, cx, cz, cxL, y, czL - 1)) i14++;
                        if (isSolidBlock(t00, t10, t01, t11, cx, cz, cxL, y, czL + 1)) i14++;
                        if (i14 == 1) {
                            setBlock(t00, t10, t01, t11, cx, cz, cxL, y, czL, CRATE);
                            // 箱子内容（需精确消费 RNG 以对齐刷怪笼 mobID）。
                            // 复刻各版本 pickCheckLootItem 的惰性求值：仅当物品生成成功才消费槽位 nextInt(27)。
                            int items = 0;
                            while (true) {
                                if (items >= 8) continue outer;
                                int r = rand.nextInt(version.lootTypes());
                                boolean setContent;
                                if (r == 0 || r == 2 || r == 6) {
                                    setContent = true; // saddle/bread/bucket
                                } else if (r == 1 || r == 3 || r == 4 || r == 5) {
                                    rand.nextInt(4); // 数量（铁/麦/火药/丝线）
                                    setContent = true;
                                } else if (r == 7) {
                                    setContent = rand.nextInt(100) == 0; // 金苹果
                                } else if (r == 8 && version.hasRedstone()) {
                                    setContent = rand.nextInt(2) == 0; // 红石
                                    if (setContent) rand.nextInt(4); // 红石数量
                                } else if (r == 9 && version.hasRecord()) {
                                    setContent = rand.nextInt(10) == 0; // 唱片
                                    if (setContent) rand.nextInt(2); // 唱片编号
                                } else if (version.hasCocoa() && r == 10) {
                                    setContent = true; // 可可豆
                                } else {
                                    setContent = false; // 超出本版本支持范围：null，不消费槽位
                                }
                                if (setContent) rand.nextInt(27); // 箱内槽位
                                items++;
                            }
                        }
                    }
                }
            }

            // 刷怪笼
            setBlock(t00, t10, t01, t11, cx, cz, x, y, z, SPAWNER);
            int mob = rand.nextInt(4); // mobID: 0=Skeleton,1/2=Zombie,3=Spider
            return mob;
        }
        return -1;
    }

    /** 收集 [cx0..cx1] x [cz0..cz1] 区块范围内所有地牢位置（[x,y,z]）到 out。 */
    public void collectDungeons(int cx0, int cz0, int cx1, int cz1, List<int[]> out) {
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                List<int[]> ds = getDungeons(cx, cz);
                if (!ds.isEmpty()) out.addAll(ds);
            }
        }
    }

    /**
     * 在 (bx,bz) 周围搜索最近的地牢（含刷怪笼怪物 id）。返回 [x,y,z,怪物id]；
     * 若最近地牢超出 radiusBlocks 方块则返回 null。
     */
    public int[] findNearestDungeon(int bx, int bz, int radiusBlocks) {
        int cxc = bx >> 4, czc = bz >> 4;
        int r = (radiusBlocks >> 4) + 1;
        int[] best = null;
        long bestDist = Long.MAX_VALUE;
        for (int cx = cxc - r; cx <= cxc + r; cx++) {
            for (int cz = czc - r; cz <= czc + r; cz++) {
                for (int[] d : getDungeons(cx, cz)) {
                    long dx = (long) d[0] - bx;
                    long dz = (long) d[2] - bz;
                    long dist = dx * dx + dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = d;
                    }
                }
            }
        }
        if (best == null || bestDist > (long) radiusBlocks * radiusBlocks) return null;
        return best;
    }
}