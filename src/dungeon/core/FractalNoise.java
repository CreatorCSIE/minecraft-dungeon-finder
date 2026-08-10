package dungeon.core;

/**
 * 多 octave 噪声，合并指数衰减强度的若干层 perlin，对应 fractal.rs。
 * 构造时按顺序生成 octaves，再反转存储（与原版一致，影响随机流消费顺序）。
 */
public final class FractalNoise {
    private final PerlinNoise[] octaves;
    // 逐 octave 预计算强度(2^(n-1-a))与缩放(1/强度)，避免每 octave 调 Math.pow
    private final double[] intensities;
    private final double[] scales;

    public FractalNoise(java.util.Random random, int octaveCount) {
        PerlinNoise[] tmp = new PerlinNoise[octaveCount];
        for (int i = 0; i < octaveCount; i++) {
            tmp[i] = new PerlinNoise(random);
        }
        this.octaves = new PerlinNoise[octaveCount];
        for (int i = 0; i < octaveCount; i++) {
            this.octaves[i] = tmp[octaveCount - 1 - i];
        }
        this.intensities = new double[octaveCount];
        this.scales = new double[octaveCount];
        for (int i = 0; i < octaveCount; i++) {
            double intensity = Math.pow(2.0, octaveCount - 1 - i);
            intensities[i] = intensity;
            scales[i] = 1.0 / intensity;
        }
    }

    PerlinNoise[] octaves() {
        return octaves;
    }

    double intensityAt(int a) {
        return intensities[a];
    }

    double scaleAt(int a) {
        return scales[a];
    }

    public SampleJobImpl beginSamplingInto(SamplingCuboid cuboid, double[] results) {
        return new SampleJobImpl(octaves, results, cuboid);
    }
}