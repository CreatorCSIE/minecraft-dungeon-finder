package dungeon.core;

/** 采样任务接口，对应 Rust 的 SamplingJob。 */
public interface SamplingJob {
    void sampleOnce();

    SamplingStatus status();

    double[] results();

    int remainingSteps();

    double remainingVariation();

    boolean isDone();

    default void sampleN(int n) {
        for (int i = 0; i < n; i++) {
            sampleOnce();
        }
    }
}