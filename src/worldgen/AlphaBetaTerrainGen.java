package worldgen;

import dungeon.core.JavaRandom;
import dungeon.core.MathHelper;

import java.util.Random;

/**
 * alpha 1.2.6 ~ beta 1.7.3 的地形生成器（独立命名空间，与 dungeon 层平级）。
 * 复刻该版本段 ChunkProviderGenerate.provideChunk 的完整地形：
 *
 *   温/湿度噪声（WorldChunkManager，simplex）
 *   → 带海洋修正的密度场 func_4061_a
 *   → 三线性插值 + 水/冰
 *   → replaceBlocksForBiome（地表水/沙/碎石/基岩/砂岩）
 *   → MapGenCaves 洞穴
 *
 * 版本差异（已对照 src-minecraft-a126 / b14 / b173 源码实测）：
 *   - 三者密度场、插值、温湿度、洞穴逐行一致；
 *   - 仅两点不同：地表水方块 ID（a1.2.6 = 8 流动水，b1.4+ = 9 静止水）、
 *     replaceBlocks 是否含砂岩层（仅 b1.4+）。
 *   - b1.4 与 b1.7.3 生成器代码逐行相同（仅生物群系类名不同）。
 *
 * 说明：生物群系表面类型（草/沙/碎石）在地牢几何判定中均视为实心，不影响
 * 地牢判定结果，此处固定为 草地/泥土，避免重复移植整套群系查表。
 */
public final class AlphaBetaTerrainGen {
    /** A126 = a1.2.6；B14_PLUS = b1.4 / b1.7.3。 */
    public enum Kind { A126, B14_PLUS }

    private static final int AIR = 0, STONE = 1, GRASS = 2, DIRT = 3, BEDROCK = 7,
            WATER_MOVING = 8, WATER_STILL = 9, GRAVEL = 13, SAND = 12, SANDSTONE = 24,
            ICE = 79;
    private static final long CHUNK_SEED_X = 341873128712L;
    private static final long CHUNK_SEED_Z = 132897987541L;

    private final long worldSeed;
    private final int waterId;
    private final boolean hasSandstone;

    // 地形噪声（构造顺序与 a1.2.6/b1.4/b1.7.3 的 ChunkProviderGenerate 一致）
    private final OctaveNoise noise912, noise911, noise910, noise909, noise908, noise922, noise921;
    // 温/湿度噪声（WorldChunkManager 构造）
    private final OctaveNoise2 tempNoise, rainNoise, detailNoise;

    private final JavaRandom rand = new JavaRandom(0);

    public AlphaBetaTerrainGen(long worldSeed, Kind kind) {
        this.worldSeed = worldSeed;
        this.waterId = kind == Kind.A126 ? WATER_MOVING : WATER_STILL;
        this.hasSandstone = kind == Kind.B14_PLUS;
        Random random = new Random(worldSeed);
        noise912 = new OctaveNoise(random, 16); // field_912_k
        noise911 = new OctaveNoise(random, 16); // field_911_l
        noise910 = new OctaveNoise(random, 8);  // field_910_m
        noise909 = new OctaveNoise(random, 4);  // field_909_n
        noise908 = new OctaveNoise(random, 4);  // field_908_o
        noise922 = new OctaveNoise(random, 10); // field_922_a
        noise921 = new OctaveNoise(random, 16); // field_921_b
        new OctaveNoise(random, 8);             // mobSpawnerNoise（仅推进随机流）
        tempNoise = new OctaveNoise2(new Random(worldSeed * 9871L), 4);
        rainNoise = new OctaveNoise2(new Random(worldSeed * 39811L), 4);
        detailNoise = new OctaveNoise2(new Random(worldSeed * 543321L), 2);
    }

    /** 生成区块完整地形（含洞穴），返回 x<<11 | z<<7 | y 布局的 32768 字节数组。 */
    public byte[] generate(int cx, int cz) {
        byte[] b3 = new byte[32768];
        rand.setSeed((long) cx * CHUNK_SEED_X + (long) cz * CHUNK_SEED_Z);

        double x0 = (double) (cx << 4);
        double z0 = (double) (cz << 4);
        double[] temp = tempNoise.sample(null, x0, z0, 16, 16,
                0.02500000037252903D, 0.02500000037252903D, 0.25D);
        double[] hum = rainNoise.sample(null, x0, z0, 16, 16,
                0.05F, 0.05F, 0.3333333333333333D);
        double[] detail = detailNoise.sample(null, x0, z0, 16, 16,
                0.25D, 0.25D, 0.5882352941176471D);
        for (int i = 0; i < 256; i++) {
            double d9 = detail[i] * 1.1D + 0.5D;
            double d11 = 0.01D;
            double d13 = 1.0D - d11;
            double d15 = (temp[i] * 0.15D + 0.7D) * d13 + d9 * d11;
            d11 = 0.002D;
            d13 = 1.0D - d11;
            double d17 = (hum[i] * 0.15D + 0.5D) * d13 + d9 * d11;
            d15 = 1.0D - (1.0D - d15) * (1.0D - d15);
            if (d15 < 0.0D) d15 = 0.0D;
            if (d15 > 1.0D) d15 = 1.0D;
            if (d17 < 0.0D) d17 = 0.0D;
            if (d17 > 1.0D) d17 = 1.0D;
            temp[i] = d15;
            hum[i] = d17;
        }

        double[] q = density(cx * 4, 0, cz * 4, temp, hum);
        interpolate(b3, q, temp);
        replaceBlocks(b3, cx, cz);
        generateCaves(b3, cx, cz);
        return b3;
    }

    /** func_4061_a：5x5x17 密度数组（带温湿度的海洋修正 d25）。 */
    private double[] density(int x0, int y0, int z0, double[] temp, double[] hum) {
        double[] d = new double[5 * 17 * 5];
        double f = 684.412D;
        double[] g = noise922.planar(null, x0, z0, 5, 5, 1.121D, 1.121D);
        double[] h = noise921.planar(null, x0, z0, 5, 5, 200.0D, 200.0D);
        double[] d5 = noise910.volume(null, x0, y0, z0, 5, 17, 5, f / 80.0D, f / 160.0D, f / 80.0D);
        double[] e = noise912.volume(null, x0, y0, z0, 5, 17, 5, f, f, f);
        double[] f3 = noise911.volume(null, x0, y0, z0, 5, 17, 5, f, f, f);

        int i16 = 16 / 5;
        int i14 = 0;
        int i15 = 0;
        for (int i17 = 0; i17 < 5; i17++) {
            int i18 = i17 * i16 + i16 / 2;
            for (int i19 = 0; i19 < 5; i19++) {
                int i20 = i19 * i16 + i16 / 2;
                double d21 = temp[i18 * 16 + i20];
                double d23 = hum[i18 * 16 + i20] * d21;
                double d25 = 1.0D - d23;
                d25 *= d25;
                d25 *= d25;
                d25 = 1.0D - d25;
                double d27 = (g[i15] + 256.0D) / 512.0D;
                d27 *= d25;
                if (d27 > 1.0D) d27 = 1.0D;
                double d29 = h[i15] / 8000.0D;
                if (d29 < 0.0D) d29 = -d29 * 0.3D;
                d29 = d29 * 3.0D - 2.0D;
                if (d29 < 0.0D) {
                    d29 /= 2.0D;
                    if (d29 < -1.0D) d29 = -1.0D;
                    d29 /= 1.4D;
                    d29 /= 2.0D;
                    d27 = 0.0D;
                } else {
                    if (d29 > 1.0D) d29 = 1.0D;
                    d29 /= 8.0D;
                }
                if (d27 < 0.0D) d27 = 0.0D;
                d27 += 0.5D;
                d29 = d29 * 17.0D / 16.0D;
                double d31 = 8.5D + d29 * 4.0D;
                i15++;
                for (int i33 = 0; i33 < 17; i33++) {
                    double d34 = 0.0D;
                    double d36 = ((double) i33 - d31) * 12.0D / d27;
                    if (d36 < 0.0D) d36 *= 4.0D;
                    double d38 = e[i14] / 512.0D;
                    double d40 = f3[i14] / 512.0D;
                    double d42 = (d5[i14] / 10.0D + 1.0D) / 2.0D;
                    if (d42 < 0.0D) d34 = d38;
                    else if (d42 > 1.0D) d34 = d40;
                    else d34 = d38 + (d40 - d38) * d42;
                    d34 -= d36;
                    if (i33 > 13) {
                        double d44 = (double) ((float) (i33 - 13) / 3.0F);
                        d34 = d34 * (1.0D - d44) + -10.0D * d44;
                    }
                    d[i14] = d34;
                    i14++;
                }
            }
        }
        return d;
    }

    /** generateTerrain 主体：三线性插值 → 石头 / 水（冰）/ 空气。 */
    private void interpolate(byte[] b3, double[] q, double[] temp) {
        for (int i11 = 0; i11 < 4; i11++) {
            for (int i12 = 0; i12 < 4; i12++) {
                for (int i13 = 0; i13 < 16; i13++) {
                    int base = (i11 * 5 + i12) * 17 + i13;
                    double d16 = q[base];
                    double d18 = q[base + 17];
                    double d20 = q[base + 85];
                    double d22 = q[base + 85 + 17];
                    double d24 = (q[base + 1] - d16) * 0.125D;
                    double d26 = (q[base + 17 + 1] - d18) * 0.125D;
                    double d28 = (q[base + 85 + 1] - d20) * 0.125D;
                    double d30 = (q[base + 85 + 17 + 1] - d22) * 0.125D;
                    for (int i32 = 0; i32 < 8; i32++) {
                        double d35 = d16;
                        double d37 = d18;
                        double d39 = (d20 - d16) * 0.25D;
                        double d41 = (d22 - d18) * 0.25D;
                        int y = i13 * 8 + i32;
                        for (int i43 = 0; i43 < 4; i43++) {
                            int i44 = (i11 * 4 + i43) << 11 | i12 * 4 << 7 | y;
                            double d48 = d35;
                            double d50 = (d37 - d35) * 0.25D;
                            int i52;
                            for (i52 = 0; i52 < 4; i52++) {
                                double d53 = temp[(i11 * 4 + i43) * 16 + i12 * 4 + i52];
                                int i55 = 0;
                                if (y < 64) {
                                    if (d53 < 0.5D && y >= 63) i55 = ICE;
                                    else i55 = waterId;
                                }
                                if (d48 > 0.0D) i55 = STONE;
                                b3[i44] = (byte) i55;
                                i44 += 128;
                                d48 += d50;
                            }
                            d35 += d39;
                            d37 += d41;
                        }
                        d16 += d24;
                        d18 += d26;
                        d20 += d28;
                        d22 += d30;
                    }
                }
            }
        }
    }

    /** replaceBlocksForBiome：沙/碎石/基岩/砂岩 + 海平面以下补水。 */
    private void replaceBlocks(byte[] b3, int cx, int cz) {
        double d6 = 8.0D / 256D;
        double x = (double) (cx << 4);
        double z = (double) (cz << 4);
        double[] sandNoise = noise909.volume(null, x, z, 0.0D, 16, 16, 1, d6, d6, 1.0D);
        double[] gravelNoise = noise909.volume(null, z, 109.0134D, x, 16, 1, 16, d6, 1.0D, d6);
        double[] stoneNoise = noise908.volume(null, x, z, 0.0D, 16, 16, 1, d6 * 2.0D, d6 * 2.0D, d6 * 2.0D);

        for (int i8 = 0; i8 < 16; i8++) {
            for (int i9 = 0; i9 < 16; i9++) {
                boolean z11 = sandNoise[i8 + i9 * 16] + rand.nextDouble() * 0.2D > 0.0D;
                boolean z12 = gravelNoise[i8 + i9 * 16] + rand.nextDouble() * 0.2D > 3.0D;
                int i13 = (int) (stoneNoise[i8 + i9 * 16] / 3.0D + 3.0D + rand.nextDouble() * 0.25D);
                int i14 = -1;
                int b15 = GRASS, b16 = DIRT;
                for (int i17 = 127; i17 >= 0; i17--) {
                    int i18 = (i9 * 16 + i8) * 128 + i17;
                    if (i17 <= rand.nextInt(5)) {
                        b3[i18] = (byte) BEDROCK;
                    } else {
                        byte b19 = b3[i18];
                        if (b19 == 0) {
                            i14 = -1;
                        } else if (b19 == STONE) {
                            if (i14 == -1) {
                                if (i13 <= 0) {
                                    b15 = 0;
                                    b16 = STONE;
                                } else if (i17 >= 60 && i17 <= 65) {
                                    b15 = GRASS;
                                    b16 = DIRT;
                                    if (z12) b15 = 0;
                                    if (z12) b16 = GRAVEL;
                                    if (z11) b15 = SAND;
                                    if (z11) b16 = SAND;
                                }
                                if (i17 < 64 && b15 == 0) b15 = waterId;
                                i14 = i13;
                                if (i17 >= 63) b3[i18] = (byte) b15;
                                else b3[i18] = (byte) b16;
                            } else if (i14 > 0) {
                                --i14;
                                b3[i18] = (byte) b16;
                                if (hasSandstone && i14 == 0 && b16 == SAND) {
                                    i14 = rand.nextInt(4);
                                    b16 = SANDSTONE;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** MapGenCaves：a1.2.6/b1.4/b1.7.3 版本（递归分支「两次递归 + return」）。 */
    private void generateCaves(byte[] b3, int cx, int cz) {
        JavaRandom r = new JavaRandom(worldSeed);
        long j7 = r.nextLong() / 2L * 2L + 1L;
        long j9 = r.nextLong() / 2L * 2L + 1L;
        for (int i1 = cx - 8; i1 <= cx + 8; i1++) {
            for (int i2 = cz - 8; i2 <= cz + 8; i2++) {
                JavaRandom r2 = new JavaRandom((long) i1 * j7 + (long) i2 * j9 ^ worldSeed);
                int i7 = r2.nextInt(r2.nextInt(r2.nextInt(40) + 1) + 1);
                if (r2.nextInt(15) != 0) i7 = 0;
                for (int i8 = 0; i8 < i7; i8++) {
                    double d9 = (double) ((i1 << 4) + r2.nextInt(16));
                    double d11 = (double) r2.nextInt(r2.nextInt(120) + 8);
                    double d13 = (double) ((i2 << 4) + r2.nextInt(16));
                    int i15 = 1;
                    if (r2.nextInt(4) == 0) {
                        carve(b3, cx, cz, d9, d11, d13,
                                1.0F + r2.nextFloat() * 6.0F, 0.0F, 0.0F, -1, -1, 0.5D, r2);
                        i15 += r2.nextInt(4);
                    }
                    for (int i16 = 0; i16 < i15; i16++) {
                        float f17 = r2.nextFloat() * (float) Math.PI * 2.0F;
                        float f18 = (r2.nextFloat() - 0.5F) * 2.0F / 8.0F;
                        float f19 = r2.nextFloat() * 2.0F + r2.nextFloat();
                        carve(b3, cx, cz, d9, d11, d13, f19, f17, f18, 0, 0, 1.0D, r2);
                    }
                }
            }
        }
    }

    /** MapGenCaves.func_869_a：递归洞穴挖掘（a1.2.6+ 版）。 */
    private void carve(byte[] b3, int cx, int cz, double d4, double d6, double d8,
                       float f10, float f11, float f12, int i13, int i14, double d15,
                       JavaRandom rand) {
        double d17 = (double) (cx * 16 + 8);
        double d19 = (double) (cz * 16 + 8);
        float f21 = 0.0F, f22 = 0.0F;
        JavaRandom random23 = new JavaRandom(rand.nextLong());
        if (i14 <= 0) {
            int i24 = 8 * 16 - 16;
            i14 = i24 - random23.nextInt(i24 / 4);
        }
        boolean z52 = false;
        if (i13 == -1) {
            i13 = i14 / 2;
            z52 = true;
        }
        int i25 = random23.nextInt(i14 / 2) + i14 / 4;
        for (boolean z26 = random23.nextInt(6) == 0; i13 < i14; i13++) {
            double d27 = 1.5D + (double) (MathHelper.sin((float) i13 * (float) Math.PI / (float) i14) * f10);
            double d29 = d27 * d15;
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
            if (!z52 && i13 == i25 && f10 > 1.0F) {
                carve(b3, cx, cz, d4, d6, d8, random23.nextFloat() * 0.5F + 0.5F,
                        f11 - (float) Math.PI / 2F, f12 / 3.0F, i13, i14, 1.0D, rand);
                carve(b3, cx, cz, d4, d6, d8, random23.nextFloat() * 0.5F + 0.5F,
                        f11 + (float) Math.PI / 2F, f12 / 3.0F, i13, i14, 1.0D, rand);
                return;
            }
            if (z52 || random23.nextInt(4) != 0) {
                double d33 = d4 - d17;
                double d35 = d8 - d19;
                double d37 = (double) (i14 - i13);
                double d39 = (double) (f10 + 2.0F + 16.0F);
                if (d33 * d33 + d35 * d35 - d37 * d37 > d39 * d39) return;
                if (d4 >= d17 - 16.0D - d27 * 2.0D && d8 >= d19 - 16.0D - d27 * 2.0D
                        && d4 <= d17 + 16.0D + d27 * 2.0D && d8 <= d19 + 16.0D + d27 * 2.0D) {
                    int i40_min = MathHelper.floor_double(d4 - d27) - cx * 16 - 1;
                    int i40_max = MathHelper.floor_double(d4 + d27) - cx * 16 + 1;
                    int i42_min = MathHelper.floor_double(d6 - d29) - 1;
                    int i42_max = MathHelper.floor_double(d6 + d29) + 1;
                    int i41_min = MathHelper.floor_double(d8 - d27) - cz * 16 - 1;
                    int i41_max = MathHelper.floor_double(d8 + d27) - cz * 16 + 1;
                    if (i40_min < 0) i40_min = 0;
                    if (i40_max > 16) i40_max = 16;
                    if (i42_min < 1) i42_min = 1;
                    if (i42_max > 120) i42_max = 120;
                    if (i41_min < 0) i41_min = 0;
                    if (i41_max > 16) i41_max = 16;
                    boolean z56 = false;
                    for (int i40 = i40_min; !z56 && i40 < i40_max; i40++) {
                        for (int i41 = i41_min; !z56 && i41 < i41_max; i41++) {
                            for (int i42 = i42_max + 1; !z56 && i42 >= i42_min - 1; --i42) {
                                int i43 = (i40 * 16 + i41) * 128 + i42;
                                if (i42 >= 0 && i42 < 128) {
                                    if (b3[i43] == WATER_STILL || b3[i43] == WATER_MOVING) z56 = true;
                                    if (i42 != i42_min - 1 && i40 != i40_min && i40 != i40_max - 1
                                            && i41 != i41_min && i41 != i41_max - 1) i42 = i42_min;
                                }
                            }
                        }
                    }
                    if (!z56) {
                        for (int i40 = i40_min; i40 < i40_max; i40++) {
                            double d57 = ((double) (i40 + cx * 16) + 0.5D - d4) / d27;
                            for (int i41 = i41_min; i41 < i41_max; i41++) {
                                double d44 = ((double) (i41 + cz * 16) + 0.5D - d8) / d27;
                                int i46 = (i40 * 16 + i41) * 128 + i42_max;
                                boolean z47 = false;
                                for (int i48 = i42_max - 1; i48 >= i42_min; --i48) {
                                    double d49 = ((double) i48 + 0.5D - d6) / d29;
                                    if (d49 > -0.7D && d57 * d57 + d49 * d49 + d44 * d44 < 1.0D) {
                                        byte b51 = b3[i46];
                                        if (b51 == GRASS) z47 = true;
                                        if (b51 == STONE || b51 == DIRT || b51 == GRASS) {
                                            if (i48 < 10) b3[i46] = (byte) 10; // 熔岩
                                            else {
                                                b3[i46] = 0;
                                                if (z47 && b3[i46 - 1] == DIRT) b3[i46 - 1] = (byte) GRASS;
                                            }
                                        }
                                    }
                                    --i46;
                                }
                            }
                        }
                        if (z52) break;
                    }
                }
            }
        }
    }
}