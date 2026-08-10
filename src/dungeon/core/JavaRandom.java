package dungeon.core;

/**
 * java.util.Random 的 48 位线性同余生成器。
 * 逐位复刻 java-rand 0.2.0（多路复用于 Minecraft 旧版噪声）。
 *
 * state = (state * 0x5DEECE66D + 0xB) & ((1<<48)-1)
 */
public final class JavaRandom {
    private static final long M = (1L << 48) - 1;
    private static final long A = 0x5DEECE66DL;
    private static final long C = 0xBL;
    private static final double F64_DIV = (double) (1L << 53);

    private long state;

    public JavaRandom(long seed) {
        setSeed(seed);
    }

    public void setSeed(long seed) {
        this.state = (seed ^ A) & M;
    }

    /** 返回高 bits 位随机数（1..48）。 */
    public int next(int bits) {
        state = (state * A + C) & M;
        return (int) (state >>> (48 - bits));
    }

    public int nextInt() {
        return next(32);
    }

    /** 返回 [0, max) 的随机数，max > 0。 */
    public int nextInt(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("Maximum must be > 0");
        }
        if ((max & (max - 1)) == 0) {
            // 2 的幂：直接移位
            long m = max;
            return (int) ((m * (long) next(31)) >> 31);
        }
        int bits = next(31);
        int val = bits % max;
        while (bits - val + (max - 1) < 0) {
            bits = next(31);
            val = bits % max;
        }
        return val;
    }

    /** 返回 [0, 1) 的 double，与 Java nextDouble() 一致。 */
    public double nextDouble() {
        long high = ((long) next(26)) << 27;
        long low = next(27);
        return (double) (high + low) / F64_DIV;
    }

    /** 返回 64 位 long，与 Java Random.nextLong() 一致。 */
    public long nextLong() {
        return ((long) next(32) << 32) + next(32);
    }

    /** 返回 [0, 1) 的 float，与 Java Random.nextFloat() 一致。 */
    public float nextFloat() {
        return next(24) / (float) (1 << 24);
    }
}