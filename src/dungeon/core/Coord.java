package dungeon.core;

/**
 * 坐标类型，对应 Rust 的 coord.rs。
 * 采样坐标(sample)与方块坐标(block)相差 4 倍（每个采样点代表 4x4 方块）。
 */
public final class Coord {
    private Coord() {}

    /** 方块坐标（x, z），单位为方块。 */
    public static final class BlockPos2D {
        public final int x;
        public final int z;

        public BlockPos2D(int x, int z) {
            this.x = x;
            this.z = z;
        }

        /** block -> sample（整数除法，向零截断，与 Rust 一致）。 */
        public SamplePos2D toSample() {
            return new SamplePos2D(x / 4, z / 4);
        }
    }

    /** 采样坐标（x, z）。 */
    public static final class SamplePos2D {
        public final int x;
        public final int z;

        public SamplePos2D(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public SamplePos3D atY(int y) {
            return new SamplePos3D(x, y, z);
        }

        /** sample -> block（*4）。 */
        public BlockPos2D toBlock() {
            return new BlockPos2D(x * 4, z * 4);
        }
    }

    /** 采样坐标（x, y, z）。 */
    public static final class SamplePos3D {
        public final int x;
        public final int y;
        public final int z;

        public SamplePos3D(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}