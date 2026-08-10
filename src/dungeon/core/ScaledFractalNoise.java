package dungeon.core;

/**
 * 固定缩放常量的分形噪声，对应 worldgen/scaled_noise.rs。
 * 提供了一个便捷的 sample2d/sample3d 接口。
 */
public final class ScaledFractalNoise {
    private final FractalNoise noise;
    private final double scaleXZ;
    private final double scaleY;

    public ScaledFractalNoise(java.util.Random random, double scaleXZ, double scaleY, int octaves) {
        this.noise = new FractalNoise(random, octaves);
        this.scaleXZ = scaleXZ;
        this.scaleY = scaleY;
    }

    /** 构造一个分形噪声然后丢弃，用于推进随机流（对应 Rust discard_noise）。 */
    public static void discardNoise(java.util.Random random, int octaves) {
        new FractalNoise(random, octaves);
    }

    double scaleXZ() {
        return scaleXZ;
    }

    FractalNoise fractalNoise() {
        return noise;
    }

    public SampleJobImpl sample2d(Coord.SamplePos2D pos, int resX, int resZ, double[] resultBuf) {
        return sample3d(pos.atY(0), resX, 1, resZ, resultBuf);
    }

    public SampleJobImpl sample3d(Coord.SamplePos3D startPos, int resX, int resY, int resZ,
                                  double[] resultBuf) {
        return noise.beginSamplingInto(
                new SamplingCuboid(startPos, resX, resY, resZ, scaleXZ, scaleY, scaleXZ),
                resultBuf);
    }

    /** 单点 3D 分形噪声，对应原版 NoiseGeneratorOctaves.generateNoiseOctaves(d1,d3,d5)。 */
    public double sample3dPoint(double x, double y, double z) {
        PerlinNoise[] octaves = noise.octaves();
        int total = octaves.length;
        double value = 0.0;
        for (int a = 0; a < total; a++) {
            double scale = noise.scaleAt(a);
            PerlinNoise p = octaves[a];
            value += p.sampleSingle(x * scale + p.xOffset(),
                    y * scale + p.yOffset(),
                    z * scale + p.zOffset()) * noise.intensityAt(a);
        }
        return value;
    }

    /** 单点 2D 分形噪声（y=0），对应原版 generateNoiseOctaves(d1,d3)。 */
    public double sample2dPoint(double x, double z) {
        return sample3dPoint(x, 0.0, z);
    }
}