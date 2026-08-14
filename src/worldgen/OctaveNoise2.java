package worldgen;

import java.util.Arrays;
import java.util.Random;

/**
 * 复刻 NoiseGeneratorOctaves2：多层 simplex 2D 采样，用于温/湿度噪声
 * （WorldChunkManager 的 field_4194_e / field_4193_f / field_4192_g）。
 * sample 对应 func_4112_a → func_4111_a：每 octave 频率乘 persist、幅度按 0.5 衰减。
 */
public final class OctaveNoise2 {
    private final SimplexNoise[] gens;

    public OctaveNoise2(Random random, int count) {
        gens = new SimplexNoise[count];
        for (int i = 0; i < count; i++) {
            gens[i] = new SimplexNoise(random);
        }
    }

    public double[] sample(double[] buf, double x, double z, int nx, int nz,
                           double fx, double fz, double persist) {
        fx /= 1.5;
        fz /= 1.5;
        if (buf == null || buf.length < nx * nz) {
            buf = new double[nx * nz];
        } else {
            Arrays.fill(buf, 0.0);
        }
        double d21 = 1.0;
        double d18 = 1.0;
        for (SimplexNoise s : gens) {
            s.sample2D(buf, x, z, nx, nz, fx * d18, fz * d18, 0.55 / d21);
            d18 *= persist;
            d21 *= 0.5;
        }
        return buf;
    }
}