package dungeon.core;

/**
 * 复刻原版 util/MathHelper 的三角函数查表与向下取整。
 * 洞穴生成依赖其逐位一致的 sin/cos 结果。
 */
public final class MathHelper {
    private static final float[] SIN_TABLE = new float[65536];

    private MathHelper() {}

    public static float sin(float f) {
        return SIN_TABLE[(int) (f * 10430.378F) & 65535];
    }

    public static float cos(float f) {
        return SIN_TABLE[(int) (f * 10430.378F + 16384.0F) & 65535];
    }

    public static int floor_double(double d) {
        int i = (int) d;
        return d < (double) i ? i - 1 : i;
    }

    static {
        for (int i = 0; i < 65536; i++) {
            SIN_TABLE[i] = (float) Math.sin((double) i * Math.PI * 2.0D / 65536.0D);
        }
    }
}