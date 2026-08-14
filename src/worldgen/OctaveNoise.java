package worldgen;

import java.util.Arrays;
import java.util.Random;

/**
 * 复刻 a1.2.6 / b1.4 / b1.7.3 的 NoiseGeneratorOctaves（多层 perlin 叠加）。
 *
 * volume 对应 func_807_a（3D 体积采样，x0/z0 原点、nx*ny*nz 网格、频率 fx/fy/fz）；
 * planar 对应 func_4109_a（y 固定在 10.0、y 向频率为 1 的 "2D" 采样）。
 * 采样语义照搬 NoiseGeneratorPerlin.func_805_a：累加各 octave，振幅按 1/amp 归一。
 */
public final class OctaveNoise {
    private final Perlin[] gens;

    public OctaveNoise(Random random, int count) {
        gens = new Perlin[count];
        for (int i = 0; i < count; i++) {
            gens[i] = new Perlin(random);
        }
    }

    public double[] volume(double[] buf, double x, double y, double z,
                           int nx, int ny, int nz, double fx, double fy, double fz) {
        if (buf == null || buf.length < nx * ny * nz) {
            buf = new double[nx * ny * nz];
        } else {
            Arrays.fill(buf, 0.0);
        }
        double d20 = 1.0;
        for (Perlin g : gens) {
            g.sampleInto(buf, x, y, z, nx, ny, nz, fx * d20, fy * d20, fz * d20, d20);
            d20 /= 2.0;
        }
        return buf;
    }

    public double[] planar(double[] buf, double x, double z, int nx, int nz, double fx, double fz) {
        return volume(buf, x, 10.0, z, nx, 1, nz, fx, 1.0, fz);
    }

    /** NoiseGeneratorPerlin 复刻（512 置换表 + 3D perlin；sampleInto 对应 func_805_a）。 */
    static final class Perlin {
        private final int[] permutations = new int[512];
        private final double xo, yo, zo;

        Perlin(Random random) {
            xo = random.nextDouble() * 256.0;
            yo = random.nextDouble() * 256.0;
            zo = random.nextDouble() * 256.0;
            for (int i = 0; i < 256; permutations[i] = i++) {
            }
            for (int i = 0; i < 256; i++) {
                int j = random.nextInt(256 - i) + i;
                int t = permutations[i];
                permutations[i] = permutations[j];
                permutations[j] = t;
                permutations[i + 256] = permutations[i];
            }
        }

        void sampleInto(double[] out, double x0, double y0, double z0,
                        int nx, int ny, int nz, double fx, double fy, double fz, double amp) {
            if (ny == 1) {
                int i75 = 0;
                double d77 = 1.0 / amp;
                for (int i30 = 0; i30 < nx; i30++) {
                    double d31 = (x0 + i30) * fx + xo;
                    int i78 = (int) d31;
                    if (d31 < i78) --i78;
                    int i34 = i78 & 255;
                    d31 -= i78;
                    double d35 = d31 * d31 * d31 * (d31 * (d31 * 6.0 - 15.0) + 10.0);
                    for (int i37 = 0; i37 < nz; i37++) {
                        double d38 = (z0 + i37) * fz + zo;
                        int i40 = (int) d38;
                        if (d38 < i40) --i40;
                        int i41 = i40 & 255;
                        d38 -= i40;
                        double d42 = d38 * d38 * d38 * (d38 * (d38 * 6.0 - 15.0) + 10.0);
                        int i19 = permutations[i34];
                        int i66 = permutations[i19 + i41];
                        int i67 = permutations[i34 + 1];
                        int i22 = permutations[i67 + i41];
                        double d70 = lerp(d35, g2(permutations[i66], d31, d38),
                                grad(permutations[i22], d31 - 1.0, 0.0, d38));
                        double d73 = lerp(d35, grad(permutations[i66 + 1], d31, 0.0, d38 - 1.0),
                                grad(permutations[i22 + 1], d31 - 1.0, 0.0, d38 - 1.0));
                        out[i75++] += lerp(d42, d70, d73) * d77;
                    }
                }
            } else {
                int i19 = 0;
                double d20 = 1.0 / amp;
                int i22 = -1;
                double d29 = 0.0, d31 = 0.0, d33 = 0.0, d35 = 0.0;
                for (int i37 = 0; i37 < nx; i37++) {
                    double d38 = (x0 + i37) * fx + xo;
                    int i40 = (int) d38;
                    if (d38 < i40) --i40;
                    int i41 = i40 & 255;
                    d38 -= i40;
                    double d42 = d38 * d38 * d38 * (d38 * (d38 * 6.0 - 15.0) + 10.0);
                    for (int i44 = 0; i44 < nz; i44++) {
                        double d45 = (z0 + i44) * fz + zo;
                        int i47 = (int) d45;
                        if (d45 < i47) --i47;
                        int i48 = i47 & 255;
                        d45 -= i47;
                        double d49 = d45 * d45 * d45 * (d45 * (d45 * 6.0 - 15.0) + 10.0);
                        for (int i51 = 0; i51 < ny; i51++) {
                            double d52 = (y0 + i51) * fy + yo;
                            int i54 = (int) d52;
                            if (d52 < i54) --i54;
                            int i55 = i54 & 255;
                            d52 -= i54;
                            double d56 = d52 * d52 * d52 * (d52 * (d52 * 6.0 - 15.0) + 10.0);
                            if (i51 == 0 || i55 != i22) {
                                i22 = i55;
                                int i69 = permutations[i41] + i55;
                                int i71 = permutations[i69] + i48;
                                int i72 = permutations[i69 + 1] + i48;
                                int i74 = permutations[i41 + 1] + i55;
                                int i75 = permutations[i74] + i48;
                                int i76 = permutations[i74 + 1] + i48;
                                d29 = lerp(d42, grad(permutations[i71], d38, d52, d45),
                                        grad(permutations[i75], d38 - 1.0, d52, d45));
                                d31 = lerp(d42, grad(permutations[i72], d38, d52 - 1.0, d45),
                                        grad(permutations[i76], d38 - 1.0, d52 - 1.0, d45));
                                d33 = lerp(d42, grad(permutations[i71 + 1], d38, d52, d45 - 1.0),
                                        grad(permutations[i75 + 1], d38 - 1.0, d52, d45 - 1.0));
                                d35 = lerp(d42, grad(permutations[i72 + 1], d38, d52 - 1.0, d45 - 1.0),
                                        grad(permutations[i76 + 1], d38 - 1.0, d52 - 1.0, d45 - 1.0));
                            }
                            double d58 = lerp(d56, d29, d31);
                            double d60 = lerp(d56, d33, d35);
                            out[i19++] += lerp(d49, d58, d60) * d20;
                        }
                    }
                }
            }
        }

        private static double lerp(double d1, double d3, double d5) {
            return d3 + d1 * (d5 - d3);
        }

        private static double grad(int i1, double d2, double d4, double d6) {
            int i8 = i1 & 15;
            double d9 = i8 < 8 ? d2 : d4;
            double d11 = i8 < 4 ? d4 : (i8 != 12 && i8 != 14 ? d6 : d2);
            return ((i8 & 1) == 0 ? d9 : -d9) + ((i8 & 2) == 0 ? d11 : -d11);
        }

        private static double g2(int i1, double d2, double d4) {
            int i6 = i1 & 15;
            double d7 = (double) (1 - ((i6 & 8) >> 3)) * d2;
            double d9 = i6 < 4 ? 0.0 : (i6 != 12 && i6 != 14 ? d4 : d2);
            return ((i6 & 1) == 0 ? d7 : -d7) + ((i6 & 2) == 0 ? d9 : -d9);
        }
    }
}