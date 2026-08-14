package dungeon.core;

/**
 * 地牢生成的版本差异，复刻各版本 ChunkProviderGenerate.populate + WorldGenDungeons。
 *
 * 差异维度（对照各源码实测）：
 *   1. loot 类型数：inf 为 nextInt(10)，其余版本均为 nextInt(11)（红石/唱片/可可豆
 *      分支后内容不同，但类型上限都是 11）。
 *   2. 新增分支：红石(i2==8)、唱片(i2==9)、可可豆(i2==10) —— 具体哪些存在随版本。
 *   3. 地牢尝试次数：inf/a1.0.1 = 4 次，a1.0.14 起 = 8 次。
 *   4. 湖泊：a1.2.6/b1.4 起 populate 在尝试前有 水湖(nextInt(4)==0) 与 熔岩湖
 *      (nextInt(8)==0) 生成，会消费 RNG 并修改地形，从而影响地牢位置。
 *
 * 参考源码：
 *   inf-20100625-1917 : src-minecraft/
 *   a1.0.1            : src-minecraft-a101/
 *   a1.0.14           : src-minecraft-a1014/
 *   a1.2.6            : src-minecraft-a126/
 *   b1.4              : src-minecraft-b14/（生成逻辑与 b1.7.3 完全一致，覆盖至 b1.7.3）
 */
public enum GameVersion {
    INF_20100625("inf-20100625-1917", 10, false, false, false, 4, false),
    ALPHA_1_0_1("a1.0.1", 11, true, false, false, 4, false),
    ALPHA_1_0_14("a1.0.14", 11, true, true, false, 8, false),
    ALPHA_1_2_6("a1.2.6", 11, true, true, false, 8, true),
    BETA_1_4("b1.4", 11, true, true, true, 8, true);

    private final String label;
    private final int lootTypes;
    private final boolean hasRedstone;
    private final boolean hasRecord;
    private final boolean hasCocoa;
    private final int dungeonTries;
    private final boolean hasLakes;

    GameVersion(String label, int lootTypes, boolean hasRedstone, boolean hasRecord,
                boolean hasCocoa, int dungeonTries, boolean hasLakes) {
        this.label = label;
        this.lootTypes = lootTypes;
        this.hasRedstone = hasRedstone;
        this.hasRecord = hasRecord;
        this.hasCocoa = hasCocoa;
        this.dungeonTries = dungeonTries;
        this.hasLakes = hasLakes;
    }

    public String label() {
        return label;
    }

    public int lootTypes() {
        return lootTypes;
    }

    public boolean hasRedstone() {
        return hasRedstone;
    }

    public boolean hasRecord() {
        return hasRecord;
    }

    public boolean hasCocoa() {
        return hasCocoa;
    }

    public int dungeonTries() {
        return dungeonTries;
    }

    public boolean hasLakes() {
        return hasLakes;
    }
}