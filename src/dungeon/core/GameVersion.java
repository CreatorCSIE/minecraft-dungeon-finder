package dungeon.core;

/**
 * 地牢箱子 loot 的版本差异。各版本 pickCheckLootItem 的类型范围与额外分支不同，
 * 会改变 RNG 消费顺序，从而影响刷怪笼 mobID。
 *
 * 参考源码：
 *   inf-20100625-1917 : src-minecraft/（nextInt(10)，无额外分支）
 *   a1.0.1           : src-minecraft-a101/（nextInt(11)，新增红石）
 *   a1.0.14          : src-minecraft-a1014/（nextInt(12)，新增唱片）
 *   b1.4             : src-minecraft-b14/（nextInt(13)，新增可可豆）
 */
public enum GameVersion {
    INF_20100625("inf-20100625-1917", 10, false, false, false),
    ALPHA_1_0_1("a1.0.1", 11, true, false, false),
    ALPHA_1_0_14("a1.0.14", 12, true, true, false),
    BETA_1_4("b1.4", 13, true, true, true);

    private final String label;
    private final int lootTypes;
    private final boolean hasRedstone;
    private final boolean hasRecord;
    private final boolean hasCocoa;

    GameVersion(String label, int lootTypes, boolean hasRedstone, boolean hasRecord, boolean hasCocoa) {
        this.label = label;
        this.lootTypes = lootTypes;
        this.hasRedstone = hasRedstone;
        this.hasRecord = hasRecord;
        this.hasCocoa = hasCocoa;
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
}