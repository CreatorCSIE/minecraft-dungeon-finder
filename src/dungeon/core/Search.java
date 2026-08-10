package dungeon.core;

/**
 * 约束搜索，对应 finder/search_constraint.rs。
 * 利用 remaining_variation 在不需要采样全部 octave 时提前判定结果。
 */
public final class Search {
    private Search() {}

    /** 搜索结果：Found/NotFound 确定，Unknown 需要继续采样。 */
    public enum Result { FOUND, NOT_FOUND, UNKNOWN }

    /** 约束接口，value 为当前累计值，remainingVariation 为剩余可能变化量。 */
    public interface Constraint {
        Result isFound(double value, double remainingVariation);
    }

    /** 对应 absolute_greater_equals(bound)。 */
    public static Constraint absoluteGreaterEquals(double bound) {
        return (value, rem) -> {
            double lowest = Math.abs(value) - rem;
            double highest = Math.abs(value) + rem;
            if (bound <= lowest) {
                return Result.FOUND;
            }
            if (highest < bound || !Double.isFinite(value)) {
                return Result.NOT_FOUND;
            }
            return Result.UNKNOWN;
        };
    }

    /** 对应 less_constraint(bound)。 */
    public static Constraint less(double bound) {
        return (value, rem) -> {
            double dist = value - bound;
            if (dist < -rem) {
                return Result.FOUND;
            }
            if (dist >= rem || !isNormal(dist)) {
                return Result.NOT_FOUND;
            }
            return Result.UNKNOWN;
        };
    }

    /** Rust is_normal()：非 0、非 subnormal、非 inf、非 NaN。 */
    private static boolean isNormal(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d) || d == 0.0) {
            return false;
        }
        return Math.abs(d) >= Double.MIN_NORMAL;
    }

    /** 对应 Rust 的 SearchResult::and。 */
    private static Result and(Result acc, Result other) {
        switch (acc) {
            case FOUND:
                return other == Result.UNKNOWN ? other : acc;
            case UNKNOWN:
                return acc;
            case NOT_FOUND:
            default:
                return other;
        }
    }

    /**
     * 反复采样直到约束对所有元素可判定。返回是否找到。
     * 推理同 Rust search()。
     */
    public static boolean search(SamplingJob job, Constraint constraint) {
        while (true) {
            Result acc = Result.NOT_FOUND;
            for (double v : job.results()) {
                acc = and(acc, constraint.isFound(v, job.remainingVariation()));
            }
            switch (acc) {
                case FOUND:
                    return true;
                case NOT_FOUND:
                    return false;
                case UNKNOWN:
                default:
                    job.sampleOnce();
            }
        }
    }
}