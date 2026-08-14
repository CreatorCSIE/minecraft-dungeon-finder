package worldgen;

import java.util.Random;

/**
 * 复刻 NoiseGenerator2：2D simplex perlin（温/湿度噪声 OctaveNoise2 的内核）。
 * sample2D 对应 func_4157_a；常量 field_4294_f / field_4293_g 与梯度表逐一照搬。
 */
public final class SimplexNoise {
    private static final int[][] GRAD = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}};
    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;

    private final int[] permutations = new int[512];
    private final double xo, yo, zo;

    public SimplexNoise(Random random) {
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

    /** func_4157_a：对 nx*nz 网格累加二维 simplex 噪声（+= 70 * 插值 * scale）。 */
    public void sample2D(double[] out, double x0, double z0, int nx, int nz,
                         double fx, double fz, double scale) {
        int i14 = 0;
        for (int i15 = 0; i15 < nx; i15++) {
            double d16 = (x0 + i15) * fx + xo;
            for (int i18 = 0; i18 < nz; i18++) {
                double d19 = (z0 + i18) * fz + yo;
                double d27 = (d16 + d19) * F2;
                int i29 = floor(d16 + d27);
                int i30 = floor(d19 + d27);
                double d31 = (double) (i29 + i30) * G2;
                double d33 = (double) i29 - d31;
                double d35 = (double) i30 - d31;
                double d37 = d16 - d33;
                double d39 = d19 - d35;
                byte b41, b42;
                if (d37 > d39) {
                    b41 = 1;
                    b42 = 0;
                } else {
                    b41 = 0;
                    b42 = 1;
                }
                double d43 = d37 - b41 + G2;
                double d45 = d39 - b42 + G2;
                double d47 = d37 - 1.0 + 2.0 * G2;
                double d49 = d39 - 1.0 + 2.0 * G2;
                int i51 = i29 & 255;
                int i52 = i30 & 255;
                int i53 = permutations[i51 + permutations[i52]] % 12;
                int i54 = permutations[i51 + b41 + permutations[i52 + b42]] % 12;
                int i55 = permutations[i51 + 1 + permutations[i52 + 1]] % 12;
                double d56 = 0.5 - d37 * d37 - d39 * d39;
                double d21;
                if (d56 < 0.0) {
                    d21 = 0.0;
                } else {
                    d56 *= d56;
                    d21 = d56 * d56 * dot(GRAD[i53], d37, d39);
                }
                double d58 = 0.5 - d43 * d43 - d45 * d45;
                double d23;
                if (d58 < 0.0) {
                    d23 = 0.0;
                } else {
                    d58 *= d58;
                    d23 = d58 * d58 * dot(GRAD[i54], d43, d45);
                }
                double d60 = 0.5 - d47 * d47 - d49 * d49;
                double d25;
                if (d60 < 0.0) {
                    d25 = 0.0;
                } else {
                    d60 *= d60;
                    d25 = d60 * d60 * dot(GRAD[i55], d47, d49);
                }
                out[i14++] += 70.0 * (d21 + d23 + d25) * scale;
            }
        }
    }

    private static int floor(double d) {
        return d > 0.0 ? (int) d : (int) d - 1;
    }

    private static double dot(int[] g, double a, double b) {
        return (double) g[0] * a + (double) g[1] * b;
    }
}