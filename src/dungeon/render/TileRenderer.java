package dungeon.render;

import dungeon.core.ChunkGenerator;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 瓦片渲染器：把一个区块（16x16 方块）渲染成 16x16 的 BufferedImage（1 方块 = 1 像素）。
 *
 * 地牢判定依赖 3D 完整地形（含洞穴），通过 {@link ChunkGenerator#collectDungeons}
 * 收集地牢列，再在其房间范围内散点标记为红色。背景为深色。
 */
public final class TileRenderer {
    /** 每瓦片边长（像素 == 方块数 == 一个区块）。 */
    public static final int TILE_SIZE = 16;

    private TileRenderer() {}

    /** 背景色（深灰蓝）。 */
    private static final int BG = 0xFF2A2A30;
    /** 地牢标记色（红）。 */
    private static final int DUNGEON = 0xFFFF2020;
    /** 以刷怪笼为中心、以方块为单位的标记半径（房间约 9x9，即半径 4）。 */
    private static final int MARK_RADIUS = 4;

    /**
     * 渲染一个区块瓦片。
     *
     * @param gen     区块生成器
     * @param chunkX  区块 X 索引
     * @param chunkZ  区块 Z 索引
     * @return 16x16 的 ARGB 图像
     */
    public static BufferedImage renderTile(ChunkGenerator gen, int chunkX, int chunkZ) {
        BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int pz = 0; pz < TILE_SIZE; pz++) {
            for (int px = 0; px < TILE_SIZE; px++) {
                img.setRGB(px, pz, BG);
            }
        }

        // 房间可能跨入本区块的生成区块为 2x2 区域 [chunkX-1..chunkX, chunkZ-1..chunkZ]。
        // getDungeons 按区块缓存，相邻瓦片共享，故收集成本可忽略。
        List<int[]> dungeons = new ArrayList<>();
        gen.collectDungeons(chunkX - 1, chunkZ - 1, chunkX, chunkZ, dungeons);

        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        for (int[] d : dungeons) {
            int x = d[0], z = d[2];
            for (int dx = -MARK_RADIUS; dx <= MARK_RADIUS; dx++) {
                for (int dz = -MARK_RADIUS; dz <= MARK_RADIUS; dz++) {
                    int px = x + dx - baseX;
                    int pz = z + dz - baseZ;
                    if (px >= 0 && px < TILE_SIZE && pz >= 0 && pz < TILE_SIZE) {
                        img.setRGB(px, pz, DUNGEON);
                    }
                }
            }
        }
        return img;
    }
}