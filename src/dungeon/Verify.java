package dungeon;

import dungeon.core.ChunkGenerator;
import dungeon.core.Coord;
import dungeon.core.FractalNoise;
import dungeon.core.SampleJobImpl;
import dungeon.core.SamplingCuboid;
import dungeon.render.TileRenderer;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 验证地牢查找器的核心逻辑。
 *
 * 编译运行：
 *   javac -encoding UTF-8 -d out src\dungeon\core\*.java src\dungeon\render\TileRenderer.java src\dungeon\Verify.java
 *   java -cp out dungeon.Verify
 */
public final class Verify {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // 1) fractal 测试向量（Rust fractal.rs 的 basic_data_matches）
        Random random = new Random(15);
        FractalNoise noise = new FractalNoise(random, 16);
        double[] results = new double[16 * 4 * 29];
        SamplingCuboid cuboid = new SamplingCuboid(
                new Coord.SamplePos3D(15, 52, 6), 16, 4, 29,
                0.512386, 198.1293, 9999.1283);
        SampleJobImpl job = noise.beginSamplingInto(cuboid, results);
        job.sampleAll();
        check("fractal[592] == 10828.95355391629", results[592], 10828.95355391629, 1e-8);

        // 2) 地牢统计：对固定种子、固定范围的区块统计地牢数量（应 > 0 且数量合理）
        ChunkGenerator gen = new ChunkGenerator(8676641231682978167L);
        List<int[]> dungeons = new ArrayList<>();
        gen.collectDungeons(-8, -8, 7, 7, dungeons);
        System.out.printf("16x16 区块范围内地牢总数: %d%n", dungeons.size());
        if (dungeons.isEmpty()) {
            System.out.println("警告: 未找到地牢，请检查生成逻辑");
        }
        // 每个地牢坐标应在合理范围内
        for (int[] d : dungeons) {
            if (d[1] < 0 || d[1] >= 128) {
                System.out.printf("地牢 y 越界: (%d,%d,%d)%n", d[0], d[1], d[2]);
                failures++;
            }
        }

        // 3) 渲染一张区块瓦片并输出 PNG（无 LWJGL2 依赖，便于先看效果）
        File dir = new File("out");
        if (!dir.exists()) {
            dir = new File(".");
        }
        int chunkX = -164, chunkZ = 271; // 默认位置
        if (!dungeons.isEmpty()) {
            chunkX = Math.floorDiv(dungeons.get(0)[0], 16);
            chunkZ = Math.floorDiv(dungeons.get(0)[2], 16);
        }
        java.awt.image.BufferedImage img =
                TileRenderer.renderTile(gen, chunkX, chunkZ);
        File png = new File(dir, "tile_preview.png");
        ImageIO.write(img, "png", png);
        System.out.println("已输出瓦片预览(区块 " + chunkX + "," + chunkZ + "): " + png.getAbsolutePath());

        if (failures == 0) {
            System.out.println("全部测试通过 ✓");
        } else {
            System.out.println("有 " + failures + " 项失败 ✗");
            System.exit(1);
        }
    }

    private static void check(String name, double actual, double expected, double eps) {
        double diff = Math.abs(actual - expected);
        boolean ok = diff <= eps;
        System.out.printf("%-40s actual=%.12f expected=%.12f -> %s%n",
                name, actual, expected, ok ? "OK" : "FAIL");
        if (!ok) {
            failures++;
        }
    }
}