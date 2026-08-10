package dungeon.core;

/** 描述要采样的 3D 噪声区域，对应 Rust 的 cuboid.rs。 */
public final class SamplingCuboid {
    public final Coord.SamplePos3D startPos;
    public final int xExtent;
    public final int yExtent;
    public final int zExtent;
    public final double xScale;
    public final double yScale;
    public final double zScale;

    public SamplingCuboid(Coord.SamplePos3D startPos, int xExtent, int yExtent, int zExtent,
                          double xScale, double yScale, double zScale) {
        this.startPos = startPos;
        this.xExtent = xExtent;
        this.yExtent = yExtent;
        this.zExtent = zExtent;
        this.xScale = xScale;
        this.yScale = yScale;
        this.zScale = zScale;
    }

    /** 采样总点数。 */
    public int len() {
        return xExtent * yExtent * zExtent;
    }
}