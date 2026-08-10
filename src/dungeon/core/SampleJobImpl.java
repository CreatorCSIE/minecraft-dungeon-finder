package dungeon.core;

/**
 * 采样任务实现，对应 Rust 的 SampleJobImpl。
 * 每次 sampleOnce 应用一层 perlin（intensity = 2^(remaining-1)），
 * 并对 cuboid 缩放 1/intensity。
 */
public final class SampleJobImpl implements SamplingJob {
    private final PerlinNoise[] noise;
    private int appliedNoises = 0;
    private final double[] results;
    private final SamplingCuboid cuboid;
    // 逐 octave 预计算的强度与缩放，避免每 octave 调 Math.pow 与新建 cuboid
    private final double[] intensities;
    private final double[] scales;

    SampleJobImpl(PerlinNoise[] noise, double[] results, SamplingCuboid cuboid) {
        if (results.length != cuboid.len()) {
            throw new IllegalArgumentException("results 长度必须与 cuboid 维度匹配");
        }
        this.noise = noise;
        this.results = results;
        this.cuboid = cuboid;
        int n = noise.length;
        this.intensities = new double[n];
        this.scales = new double[n];
        for (int a = 0; a < n; a++) {
            double intensity = Math.pow(2.0, n - 1 - a);
            intensities[a] = intensity;
            scales[a] = 1.0 / intensity;
        }
    }

    public void sampleOnce() {
        if (appliedNoises < noise.length) {
            int idx = appliedNoises;
            noise[idx].sampleCuboid(results, cuboid, intensities[idx], scales[idx]);
            appliedNoises++;
        }
    }

    public double[] results() {
        return results;
    }

    public int remainingSteps() {
        return noise.length - appliedNoises;
    }

    public double remainingVariation() {
        return (Math.pow(2.0, remainingSteps()) - 1.0) * PerlinNoise.RESULT_RANGE;
    }

    public boolean isDone() {
        return appliedNoises == noise.length;
    }

    public SamplingStatus status() {
        if (appliedNoises == 0) {
            return SamplingStatus.NOT_STARTED;
        }
        if (appliedNoises == noise.length) {
            return SamplingStatus.DONE;
        }
        return SamplingStatus.STARTED;
    }

    /** 采样直到完成，返回结果数组。 */
    public double[] sampleAll() {
        while (!isDone()) {
            sampleOnce();
        }
        return results;
    }
}