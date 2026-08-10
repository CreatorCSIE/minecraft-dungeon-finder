package dungeon.core;

/**
 * Minecraft 旧版的单层 3D Perlin 噪声，复刻 perlin.rs。
 * 注意：Minecraft 相关版本使用了"错误"的 perlin 算法，本实现保留该行为
 * （仅在 cubeY 变化时重算 lerp0-3，忽略 yPos 依赖，与原版一致）。
 */
public final class PerlinNoise {
    public static final double RESULT_RANGE = 1.0;

    private final int[] permutations = new int[512];
    private final double xOffset;
    private final double yOffset;
    private final double zOffset;

    public PerlinNoise(java.util.Random random) {
        xOffset = random.nextDouble() * 256.0;
        yOffset = random.nextDouble() * 256.0;
        zOffset = random.nextDouble() * 256.0;
        for (int i = 0; i < 256; i++) {
            permutations[i] = i;
        }
        for (int i = 0; i < 256; i++) {
            int n = random.nextInt(256 - i) + i;
            int tmp = permutations[i];
            permutations[i] = permutations[n];
            permutations[n] = tmp;
        }
        for (int i = 256; i < 512; i++) {
            permutations[i] = permutations[i - 256];
        }
    }

    double xOffset() { return xOffset; }
    double yOffset() { return yOffset; }
    double zOffset() { return zOffset; }

    private static double lerp(double sel, double low, double high) {
        return low + sel * (high - low);
    }

    /**
     * 对正负值均正确的快速向下取整，返回 long。等价于 Math.floor 在
     * 可表示范围内的结果（位精确），但避免 Math.floor 的额外开销。
     */
    private static long floorL(double d) {
        long l = (long) d;
        double dl = (double) l;
        return d < dl ? l - 1 : l;
    }

    private static double grad(int hash, double x, double y, double z) {
        hash &= 0xF;
        double u = hash < 8 ? x : y;
        double v = hash < 4 ? y : (hash == 12 || hash == 14 ? x : z);
        return ((hash & 0x1) == 0 ? u : -u) + ((hash & 0x2) == 0 ? v : -v);
    }

    /** 采样一个 cuboid 的噪声。结果乘以 intensity 累加到 arr。
     * 点 (x,y,z) 存储在 arr[y + resY*(z + resZ*x)]。
     * scaleFactor = 1/intensity 用于缩放采样坐标（等价于对 cuboid 逐 octave scaleAll，
     * 但避免每 octave 分配新对象）。算术顺序与原 scaleAll 后逐点采样完全一致。
     */
    public void sampleCuboid(double[] arr, SamplingCuboid cuboid, double intensity, double scaleFactor) {
        int outputIdx = 0;
        double lerp0 = 0.0, lerp1 = 0.0, lerp2 = 0.0, lerp3 = 0.0;
        int lastCubeY = -1;
        // 先算 xScale*factor（与原 scaleAll 一致），再乘坐标，保证逐位相同
        double sx = cuboid.xScale * scaleFactor;
        double sy = cuboid.yScale * scaleFactor;
        double sz = cuboid.zScale * scaleFactor;
        int startX = cuboid.startPos.x;
        int startY = cuboid.startPos.y;
        int startZ = cuboid.startPos.z;
        for (int xIdx = 0; xIdx < cuboid.xExtent; xIdx++) {
            double xBase = (startX + xIdx) * sx;
            for (int zIdx = 0; zIdx < cuboid.zExtent; zIdx++) {
                double zBase = (startZ + zIdx) * sz;
                for (int yIdx = 0; yIdx < cuboid.yExtent; yIdx++) {
                    double xPosBase = xBase + xOffset;
                    double yPosBase = (startY + yIdx) * sy + yOffset;
                    double zPosBase = zBase + zOffset;
                    long fx = floorL(xPosBase);
                    long fy = floorL(yPosBase);
                    long fz = floorL(zPosBase);
                    int cubeX = (int) fx & 0xFF;
                    int cubeY = (int) fy & 0xFF;
                    int cubeZ = (int) fz & 0xFF;
                    double xPos = xPosBase - (double) fx;
                    double yPos = yPosBase - (double) fy;
                    double zPos = zPosBase - (double) fz;
                    double u = xPos * xPos * xPos * (xPos * (xPos * 6.0 - 15.0) + 10.0);
                    double v = yPos * yPos * yPos * (yPos * (yPos * 6.0 - 15.0) + 10.0);
                    double w = zPos * zPos * zPos * (zPos * (zPos * 6.0 - 15.0) + 10.0);
                    double xm1 = xPos - 1.0;
                    double ym1 = yPos - 1.0;
                    double zm1 = zPos - 1.0;

                    // 与原版 identical 的"错误"缓存：仅当 cubeY 变化时才重算
                    if (yIdx == 0 || cubeY != lastCubeY) {
                        lastCubeY = cubeY;
                        int bigA = permutations[cubeX] + cubeY;
                        int bigAA = permutations[bigA] + cubeZ;
                        int bigAB = permutations[bigA + 1] + cubeZ;
                        int bigB = permutations[cubeX + 1] + cubeY;
                        int bigBA = permutations[bigB] + cubeZ;
                        int bigBB = permutations[bigB + 1] + cubeZ;

                        lerp0 = lerp(u,
                                grad(permutations[bigAA], xPos, yPos, zPos),
                                grad(permutations[bigBA], xm1, yPos, zPos));
                        lerp1 = lerp(u,
                                grad(permutations[bigAB], xPos, ym1, zPos),
                                grad(permutations[bigBB], xm1, ym1, zPos));
                        lerp2 = lerp(u,
                                grad(permutations[bigAA + 1], xPos, yPos, zm1),
                                grad(permutations[bigBA + 1], xm1, yPos, zm1));
                        lerp3 = lerp(u,
                                grad(permutations[bigAB + 1], xPos, ym1, zm1),
                                grad(permutations[bigBB + 1], xm1, ym1, zm1));
                    }
                    arr[outputIdx] += lerp(w, lerp(v, lerp0, lerp1), lerp(v, lerp2, lerp3)) * intensity;
                    outputIdx++;
                }
            }
        }
    }

    /**
     * 采样单个点（1x1x1），用于快速路径。逻辑与 sampleCuboid 的 yIdx=0 完全一致
     * （yIdx==0 时总是重算 lerp0-3，因此无需缓存分支）。
     */
    double sampleSingle(double xPosBase, double yPosBase, double zPosBase) {
        long fx = floorL(xPosBase);
        long fy = floorL(yPosBase);
        long fz = floorL(zPosBase);
        int cubeX = (int) fx & 0xFF;
        int cubeY = (int) fy & 0xFF;
        int cubeZ = (int) fz & 0xFF;
        double xPos = xPosBase - (double) fx;
        double yPos = yPosBase - (double) fy;
        double zPos = zPosBase - (double) fz;
        double u = xPos * xPos * xPos * (xPos * (xPos * 6.0 - 15.0) + 10.0);
        double v = yPos * yPos * yPos * (yPos * (yPos * 6.0 - 15.0) + 10.0);
        double w = zPos * zPos * zPos * (zPos * (zPos * 6.0 - 15.0) + 10.0);
        double xm1 = xPos - 1.0;
        double ym1 = yPos - 1.0;
        double zm1 = zPos - 1.0;
        int bigA = permutations[cubeX] + cubeY;
        int bigAA = permutations[bigA] + cubeZ;
        int bigAB = permutations[bigA + 1] + cubeZ;
        int bigB = permutations[cubeX + 1] + cubeY;
        int bigBA = permutations[bigB] + cubeZ;
        int bigBB = permutations[bigB + 1] + cubeZ;
        double lerp0 = lerp(u, grad(permutations[bigAA], xPos, yPos, zPos),
                grad(permutations[bigBA], xm1, yPos, zPos));
        double lerp1 = lerp(u, grad(permutations[bigAB], xPos, ym1, zPos),
                grad(permutations[bigBB], xm1, ym1, zPos));
        double lerp2 = lerp(u, grad(permutations[bigAA + 1], xPos, yPos, zm1),
                grad(permutations[bigBA + 1], xm1, yPos, zm1));
        double lerp3 = lerp(u, grad(permutations[bigAB + 1], xPos, ym1, zm1),
                grad(permutations[bigBB + 1], xm1, ym1, zm1));
        return lerp(w, lerp(v, lerp0, lerp1), lerp(v, lerp2, lerp3));
    }
}