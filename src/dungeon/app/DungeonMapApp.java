package dungeon.app;

import dungeon.core.ChunkGenerator;
import dungeon.core.GameVersion;
import dungeon.render.TextRenderer;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Dungeon Finder 渲染 App（LWJGL2 / OpenGL）。
 *
 * 瓦片地图：平移、缩放、切换种子。
 * 每个瓦片 = 一个区块（16x16 方块，1 方块/像素），缩放由方块/像素（bpp）决定，
 * 最远视野为窗口宽覆盖 WIDEST_BLOCKS 方块，可继续放大。
 *
 * 操作：
 *   - 鼠标左键拖拽：平移
 *   - 滚轮 / +/-：缩放（以鼠标位置为锚点）
 *   - 方向键 / WASD：平移
 *   - R：随机种子
 *   - Esc：退出
 *
 * 运行（需先导入 LWJGL2 jar 与 natives）：
 *   java -cp "out;lwjgl.jar;lwjgl_util.jar" -Djava.library.path=natives dungeon.app.DungeonMapApp [seed]
 */
public final class DungeonMapApp {
    private static final int WIN_W = 1024;
    private static final int WIN_H = 768;
    private int winW = WIN_W, winH = WIN_H;   // 当前窗口尺寸（可随缩放/最大化变化）
    private static final int TILE_SIZE = dungeon.render.TileRenderer.TILE_SIZE; // 16（每瓦片=1区块）
    // 最远视野：窗口宽覆盖的方块数（默认 1024 方块宽 = 64 个区块瓦片，即 chunkbase 一张预览）。
    private static final double WIDEST_BLOCKS = 1024.0;
    // 放大级数（每级 blocksPerPixel 减半，可拉近到 WIDEST_BLOCKS/2^ZOOM_STEPS 方块宽）。
    private static final int ZOOM_STEPS = 16;
    private static final int MAX_TILES = 8192; // 容纳一整张默认视野（~4096 区块瓦片）的余量
    // 每帧最多上传的瓦片数：与 12 核吞吐（约 5700 瓦片/秒 ÷ 60fps ≈ 95）匹配，
    // 避免拖动时一帧突发上传大量瓦片造成掉帧，让新瓦片于多帧内渐进出现。
    private static final int MAX_UPLOADS_PER_FRAME = 96;

    private long seed;
    private ChunkGenerator gen;
    private TileCache cache;
    private volatile boolean running = true;
    private GameVersion version = GameVersion.INF_20100625; // 当前地牢版本
    private boolean versionOpen = false;                     // 版本下拉菜单是否展开

    // 视图状态
    private double centerX = 0;   // 方块坐标
    private double centerZ = 0;
    private double bpp;   // 方块/像素（越小越放大）；最远 = WIDEST_BLOCKS/winW
    // 点击地图选中的地牢信息 [x,y,z,怪物id]，用于显示完整坐标 + 刷怪笼怪物
    private int[] selectedDungeon;
    // 本会话创建过的临时磁盘缓存文件（切种子时保留以便回退复用，退出时统一删除）
    private final Set<File> sessionDiskFiles = new HashSet<>();

    // UI 状态
    private final TextRenderer text = new TextRenderer(24);
    private String seedText = "";
    private String coordXText = "";
    private String coordZText = "";
    private boolean suppressPan = false;   // 刚点击过 UI，本帧抑制地图平移
    // 输入框焦点：0=无, 1=种子, 2=坐标X, 3=坐标Z
    private int focusField = 0;
    private int dragField = 0;            // 正在拖拽选字的输入框（0=无）
    private final int[] caretArr = new int[4];    // 各输入框光标位置（索引 1..3）
    private final int[] selArr = {0, -1, -1, -1}; // 各输入框选区锚点（-1=无）
    // 坐标显示缓冲：位置变化（拖动/缩放）时更新，静止时保持；切换焦点不重置
    private long lastXVal = Long.MIN_VALUE, lastZVal = Long.MIN_VALUE;
    // 种子输入框（左上角）与回调按钮
    private static final int FIELD_X = 10, FIELD_Y = 10, FIELD_W = 320, FIELD_H = 40;
    private static final int BTN_X = 340, BTN_Y = 10, BTN_W = 64, BTN_H = 40;
    // 版本下拉菜单（种子框右侧）
    private static final int VER_X = 414, VER_Y = 10, VER_W = 280, VER_H = 40;
    // 坐标输入区（左下角）：X: [输入框] Z: [输入框] 应用
    private static final int CFIELD_H = 40;
    private static final int CX_FIELD_X = 44, CX_FIELD_W = 134;   // X 输入框
    private static final int CZ_FIELD_X = 222, CZ_FIELD_W = 134;  // Z 输入框
    private static final int CB_X = 362, CB_W = 64, CB_H = 40;    // 应用按钮

    public static void main(String[] args) {
        long seed = new java.util.Random().nextLong();
        if (args.length > 0) {
            try {
                seed = Long.parseLong(args[0]);
            } catch (NumberFormatException ignored) {
                System.err.println("种子不是有效数字，使用随机种子: " + args[0]);
            }
        }
        new DungeonMapApp().run(seed);
    }

    private void run(long initialSeed) {
        setSeed(initialSeed);
        try {
            Display.setDisplayMode(new DisplayMode(WIN_W, WIN_H));
            Display.setTitle(title());
            Display.setResizable(true);   // 允许拖动边缘改变大小 / 最大化
            Display.create();
        } catch (LWJGLException e) {
            System.err.println("无法创建 LWJGL2 显示窗口。请确认已配置 lwjgl.jar 与 natives。");
            e.printStackTrace();
            return;
        }

        try {
            Mouse.create();
            Keyboard.create();
            Keyboard.enableRepeatEvents(true);   // 长按数字/退格时产生重复输入
        } catch (LWJGLException e) {
            System.err.println("无法初始化输入设备: " + e.getMessage());
            e.printStackTrace();
            Display.destroy();
            return;
        }

        // Display.create() 后先获取实际窗口尺寸，再初始化 GL 视口和投影
        winW = Display.getWidth();
        winH = Display.getHeight();
        initGL();

        // 默认最远视野：窗口宽覆盖 WIDEST_BLOCKS 方块
        bpp = maxBpp();

        int lastMx = Mouse.getX();
        int lastMy = Mouse.getY();

        while (!Display.isCloseRequested() && running) {
            // 缩放（滚轮）
            while (Mouse.next()) {
                int dw = Mouse.getEventDWheel();
                if (dw != 0) {
                    // 向上滚轮 = 放大（bpp 减小）；向下滚轮 = 缩小
                    zoomBy(dw > 0 ? 1 : -1);
                }
                // 鼠标左键点击：检测 UI（输入框 / 应用按钮）
                if (Mouse.getEventButton() == 0 && Mouse.getEventButtonState()) {
                    int mx = Mouse.getEventX();
                    int mySprite = winH - Mouse.getEventY();
                    handleUiClick(mx, mySprite);
                }
            }

            // 平移（左键拖拽）
            int mx = Mouse.getX();
            int my = Mouse.getY();
            if (Mouse.isButtonDown(0) && !suppressPan) {
                int ry = winH - my;
                int lastRy = winH - lastMy;
                int dpx = mx - lastMx;
                int dry = ry - lastRy;
                double bpp = blocksPerPixel();
                centerX -= dpx * bpp;
                centerZ -= dry * bpp;
            }
            // 输入框内拖拽选字：按住左键在框内移动时更新光标位置
            if (dragField != 0 && Mouse.isButtonDown(0)) {
                int mix = Mouse.getX();
                int miy = winH - Mouse.getY();
                if (pointInField(mix, miy, dragField)) {
                    caretArr[dragField] = charIndexAtX(inputText(dragField), mix - fieldTextX0(dragField));
                }
            }
            if (!Mouse.isButtonDown(0)) {
                dragField = 0;
                suppressPan = false;
            }
            lastMx = mx;
            lastMy = my;

            // 键盘
            handleKeyboard();

            // 窗口尺寸变化（拖动边缘 / 最大化）：更新视口与投影
            if (Display.wasResized()) {
                winW = Display.getWidth();
                winH = Display.getHeight();
                // 窗口变宽后最远视野对应更大的 bpp，若当前超出则收缩到最远
                double maxB = maxBpp();
                if (bpp > maxB) {
                    bpp = maxB;
                }
                GL11.glViewport(0, 0, winW, winH);
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glLoadIdentity();
                GL11.glOrtho(0, winW, winH, 0, -1, 1);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
            }

            render();
            Display.update();
            Display.sync(60);
        }

        cleanup();
    }

    private void initGL() {
        GL11.glViewport(0, 0, winW, winH);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, winW, winH, 0, -1, 1); // y 向下，匹配屏幕
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void handleKeyboard() {
        // 任一输入框聚焦时，优先处理输入，不响应地图控制
        if (focusField != 0) {
            int f = focusField;
            boolean isSeed = (f == 1);
            while (Keyboard.next()) {
                if (!Keyboard.getEventKeyState()) {
                    continue;
                }
                int key = Keyboard.getEventKey();
                char c = Keyboard.getEventCharacter();
                boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
                if (ctrl && Keyboard.isRepeatEvent()) {
                    // 长按重复事件：跳过剪贴板快捷键，避免连续粘贴/复制
                } else if (ctrl && key == Keyboard.KEY_C) {
                    copySelection(f);
                } else if (ctrl && key == Keyboard.KEY_X) {
                    cutSelection(f);
                } else if (ctrl && key == Keyboard.KEY_V) {
                    pasteToField(f);
                } else if (ctrl && key == Keyboard.KEY_A) {
                    selArr[f] = 0;
                    caretArr[f] = inputText(f).length();
                } else if (c >= '0' && c <= '9' || c == '-') {
                    int sel = selArr[f], cr = caretArr[f];
                    if (sel != -1 && sel != cr) {
                        deleteSelection(f);
                    }
                    String t = inputText(f);
                    if (t.length() < (isSeed ? 20 : 14)) {
                        int c2 = caretArr[f];
                        t = t.substring(0, c2) + c + t.substring(c2);
                        setInputText(f, t);
                        caretArr[f] = c2 + 1;
                    }
                    selArr[f] = -1;
                } else if (key == Keyboard.KEY_BACK) {
                    int sel = selArr[f], cr = caretArr[f];
                    if (sel != -1 && sel != cr) {
                        deleteSelection(f);
                    } else if (cr > 0) {
                        String t = inputText(f);
                        t = t.substring(0, cr - 1) + t.substring(cr);
                        setInputText(f, t);
                        caretArr[f] = cr - 1;
                    }
                    selArr[f] = -1;
                } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                    if (isSeed) applySeedInput(); else applyCoordInput();
                } else if (key == Keyboard.KEY_ESCAPE) {
                    focusField = 0;
                }
            }
            return;
        }

        while (Keyboard.next()) {
            if (!Keyboard.getEventKeyState()) {
                continue;
            }
            int key = Keyboard.getEventKey();
            switch (key) {
                case Keyboard.KEY_R: {
                    long r = new Random().nextLong();
                    setSeed(r);
                    break;
                }
                case Keyboard.KEY_ESCAPE: {
                    running = false;
                    break;
                }
                case Keyboard.KEY_EQUALS: case Keyboard.KEY_ADD: {
                    zoomBy(1);
                    break;
                }
                case Keyboard.KEY_MINUS: case Keyboard.KEY_SUBTRACT: {
                    zoomBy(-1);
                    break;
                }
                default: break;
            }
        }

        // 连续平移
        double bpp = blocksPerPixel();
        double pan = 24 * bpp;
        if (Keyboard.isKeyDown(Keyboard.KEY_LEFT) || Keyboard.isKeyDown(Keyboard.KEY_A)) centerX -= pan;
        if (Keyboard.isKeyDown(Keyboard.KEY_RIGHT) || Keyboard.isKeyDown(Keyboard.KEY_D)) centerX += pan;
        if (Keyboard.isKeyDown(Keyboard.KEY_UP) || Keyboard.isKeyDown(Keyboard.KEY_W)) centerZ -= pan;
        if (Keyboard.isKeyDown(Keyboard.KEY_DOWN) || Keyboard.isKeyDown(Keyboard.KEY_S)) centerZ += pan;
    }

    private void render() {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();

        // 每个瓦片 = 1 区块（TILE_SIZE 方块），1 方块/像素；方块/像素 = bpp
        double bpp = blocksPerPixel();          // 方块 / 像素
        int tileWorld = TILE_SIZE;              // 每瓦片覆盖的方块数 = 16
        float size = (float) (TILE_SIZE / bpp); // 瓦片在屏幕上的边长（像素）
        double left = centerX - (winW / 2.0) * bpp;
        double top = centerZ - (winH / 2.0) * bpp;
        double right = left + winW * bpp;
        double bottom = top + winH * bpp;

        int txMin = (int) Math.floor(left / tileWorld);
        int txMax = (int) Math.floor(right / tileWorld);
        int tzMin = (int) Math.floor(top / tileWorld);
        int tzMax = (int) Math.floor(bottom / tileWorld);

        // 第一步：请求后台生成所有可见瓦片（无 GL 副作用）。
        // 按离视野中心的距离排序请求，让中心区域（含光标附近）的瓦片优先拿到后台线程、最先出图。
        int midTx = (int) Math.floor(centerX / tileWorld);
        int midTz = (int) Math.floor(centerZ / tileWorld);
        java.util.List<int[]> tilesToRequest = new java.util.ArrayList<>();
        for (int tz = tzMin; tz <= tzMax; tz++) {
            for (int tx = txMin; tx <= txMax; tx++) {
                int d = (tx - midTx) * (tx - midTx) + (tz - midTz) * (tz - midTz);
                tilesToRequest.add(new int[]{tx, tz, d});
            }
        }
        tilesToRequest.sort((a, b) -> Integer.compare(a[2], b[2]));
        for (int[] t : tilesToRequest) {
            cache.request(new TileCache.Key(t[0], t[1]));
        }

        // 第二步：尚未上传的瓦片画深色占位块（不启纹理；注意此处不能做任何 GL 上传）。
        // 判据用“是否已上传”(textureId>=0) 而非 isReady：isReady 含“已栅格化未上传”，
        // 那些瓦片既不属于第三步绘制（未上传），也不应停留在占位之下，故凡未上传一律画占位。
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(0.13f, 0.13f, 0.15f, 1f);
        GL11.glBegin(GL11.GL_QUADS);
        for (int tz = tzMin; tz <= tzMax; tz++) {
            for (int tx = txMin; tx <= txMax; tx++) {
                if (cache.textureId(new TileCache.Key(tx, tz)) >= 0) {
                    continue;
                }
                float px0 = (float) ((tx * tileWorld - centerX) / bpp + winW / 2.0);
                float py0 = (float) ((tz * tileWorld - centerZ) / bpp + winH / 2.0);
                GL11.glVertex2f(px0, py0);
                GL11.glVertex2f(px0 + size, py0);
                GL11.glVertex2f(px0 + size, py0 + size);
                GL11.glVertex2f(px0, py0 + size);
            }
        }
        GL11.glEnd();

        // 第三步：绘制所有已上传瓦片，并渐进上传就绪瓦片。
        // 上传上限只限制“新上传”的数量；已上传的瓦片每帧都必须绘制，不占用上传配额，
        // 避免已上传瓦片把配额耗尽后，真正就绪待上传的瓦片永远轮不到而残留占位。
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        int uploadsThisFrame = 0;
        for (int tz = tzMin; tz <= tzMax; tz++) {
            for (int tx = txMin; tx <= txMax; tx++) {
                TileCache.Key key = new TileCache.Key(tx, tz);
                int tex = cache.textureId(key); // 已上传则直接绘制，不占配额
                if (tex < 0 && uploadsThisFrame < MAX_UPLOADS_PER_FRAME) {
                    tex = cache.uploadIfReady(key); // 仅当真正新上传时才计入配额
                    if (tex >= 0) {
                        uploadsThisFrame++;
                    }
                }
                if (tex < 0) {
                    continue;
                }
                float sx0 = (float) ((tx * tileWorld - centerX) / bpp + winW / 2.0);
                float sy0 = (float) ((tz * tileWorld - centerZ) / bpp + winH / 2.0);
                float sx1 = sx0 + size;
                float sy1 = sy0 + size;

                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
                GL11.glColor4f(1, 1, 1, 1);
                GL11.glBegin(GL11.GL_QUADS);
                // v=0 对应纹理第一行（z 最小），画在顶部，保证 z 向下连续、无边界跳变
                GL11.glTexCoord2f(0, 0); GL11.glVertex2f(sx0, sy0);
                GL11.glTexCoord2f(1, 0); GL11.glVertex2f(sx1, sy0);
                GL11.glTexCoord2f(1, 1); GL11.glVertex2f(sx1, sy1);
                GL11.glTexCoord2f(0, 1); GL11.glVertex2f(sx0, sy1);
                GL11.glEnd();
            }
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        drawCrosshair();
        drawUI();

        Display.setTitle(title());
    }

    /** 画面中心绘制 MC 样式反色十字准星（16x16，位于调试坐标中心）。 */
    private void drawCrosshair() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        // 与 MC 相同的反色混合：result = src*(1-dst) + dst*(1-src)，白色十字即目标色反色
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE_MINUS_SRC_COLOR);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        float cx = winW / 2f;
        float cy = winH / 2f;
        float half = 16f; // 32x32 的一半
        float t = 2f;    // 半厚度（总厚 4px）

        // 5 个互不重叠的矩形：四臂 + 中心实心块。
        // 反色混合对重叠区域会叠加抵消，因此必须保证每个像素只被画一次，
        // 否则中心块的滤镜会因多次反色而失效（变回原色）。
        GL11.glBegin(GL11.GL_QUADS);
        // 上臂
        GL11.glVertex2f(cx - t, cy - half); GL11.glVertex2f(cx + t, cy - half);
        GL11.glVertex2f(cx + t, cy - t); GL11.glVertex2f(cx - t, cy - t);
        // 下臂
        GL11.glVertex2f(cx - t, cy + t); GL11.glVertex2f(cx + t, cy + t);
        GL11.glVertex2f(cx + t, cy + half); GL11.glVertex2f(cx - t, cy + half);
        // 左臂
        GL11.glVertex2f(cx - half, cy - t); GL11.glVertex2f(cx - t, cy - t);
        GL11.glVertex2f(cx - t, cy + t); GL11.glVertex2f(cx - half, cy + t);
        // 右臂
        GL11.glVertex2f(cx + t, cy - t); GL11.glVertex2f(cx + half, cy - t);
        GL11.glVertex2f(cx + half, cy + t); GL11.glVertex2f(cx + t, cy + t);
        // 中心实心块
        GL11.glVertex2f(cx - t, cy - t); GL11.glVertex2f(cx + t, cy - t);
        GL11.glVertex2f(cx + t, cy + t); GL11.glVertex2f(cx - t, cy + t);
        GL11.glEnd();

        // 恢复正常混合
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** 处理鼠标左键点击：命中种子/坐标输入框或应用按钮。 */
    private void handleUiClick(int mx, int my) {
        int cfy = winH - CFIELD_H - 10;
        int f = fieldAt(mx, my);
        if (f != 0) {
            focusField = f;
            dragField = f;
            versionOpen = false; // 聚焦输入框时收起版本菜单
            // 点击时填入当前值（种子 = seed，X/Z = 当前坐标）
            String val;
            switch (f) {
                case 1: val = String.valueOf(seed); break;
                case 2: val = String.valueOf((long) Math.floor(centerX)); break;
                case 3: val = String.valueOf((long) Math.floor(centerZ)); break;
                default: val = "";
            }
            setInputText(f, val);
            caretArr[f] = val.length();
            selArr[f] = val.length();   // 塌缩选区，作为拖拽锚点
            suppressPan = true;
        } else if (mx >= BTN_X && mx <= BTN_X + BTN_W && my >= BTN_Y && my <= BTN_Y + BTN_H) {
            applySeedInput();
            dragField = 0;
            suppressPan = true;
        } else if (mx >= VER_X && mx <= VER_X + VER_W && my >= VER_Y && my <= VER_Y + VER_H) {
            versionOpen = !versionOpen; // 点击版本框：切换展开/收起
            focusField = 0;
            dragField = 0;
            suppressPan = true;
        } else if (versionOpen) {
            // 下拉菜单展开时，点击某个版本项
            GameVersion picked = versionAt(mx, my);
            if (picked != null) {
                setVersion(picked);
                versionOpen = false;
            } else {
                versionOpen = false; // 点击菜单外任意处收起
            }
            focusField = 0;
            dragField = 0;
            suppressPan = true;
        } else if (mx >= CB_X && mx <= CB_X + CB_W && my >= cfy && my <= cfy + CB_H) {
            applyCoordInput();
            dragField = 0;
            suppressPan = true;
        } else {
            focusField = 0;
            dragField = 0;
            // 地图点击：把屏幕像素换算成方块坐标，反查该处最近的刷怪笼地牢
            double bpp = blocksPerPixel();
            long bx = (long) Math.floor(centerX + (mx - winW / 2.0) * bpp);
            long bz = (long) Math.floor(centerZ + (my - winH / 2.0) * bpp);
            int radius = Math.max(8, (int) bpp + 4);
            selectedDungeon = gen.findNearestDungeon((int) bx, (int) bz, radius);
        }
    }

    /** 刷怪笼怪物 id -> 显示名（0=Skeleton,1/2=Zombie,3=Spider）。 */
    private static String mobName(int id) {
        switch (id) {
            case 0: return "Skeleton";
            case 1:
            case 2: return "Zombie";
            case 3: return "Spider";
            default: return "?";
        }
    }

    /** 根据输入框内相对文字起点的鼠标 x，返回最近的字符索引（用于点击定位 / 拖拽选字）。 */
    private int charIndexAtX(String s, int relX) {
        int best = 0, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i <= s.length(); i++) {
            int d = Math.abs(text.stringWidth(s.substring(0, i)) - relX);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** 删除指定输入框的选区（若有），并把光标移到选区起点。 */
    private void deleteSelection(int f) {
        int sel = selArr[f], cr = caretArr[f];
        if (sel == -1 || sel == cr) {
            return;
        }
        int lo = Math.min(cr, sel);
        int hi = Math.max(cr, sel);
        String t = inputText(f);
        t = t.substring(0, lo) + t.substring(hi);
        setInputText(f, t);
        caretArr[f] = lo;
        selArr[f] = -1;
    }

    /** 选区起点（无选区返回 -1）。 */
    private int selectionStart(int f) {
        int sel = selArr[f], cr = caretArr[f];
        return (sel == -1 || sel == cr) ? -1 : Math.min(cr, sel);
    }

    /** 选区终点（无选区返回 -1）。 */
    private int selectionEnd(int f) {
        int sel = selArr[f], cr = caretArr[f];
        return (sel == -1 || sel == cr) ? -1 : Math.max(cr, sel);
    }

    /** 复制选中文本到系统剪贴板。 */
    private void copySelection(int f) {
        int s = selectionStart(f), e = selectionEnd(f);
        if (s == -1) {
            return;
        }
        setClipboard(inputText(f).substring(s, e));
    }

    /** 剪切选中文本到系统剪贴板。 */
    private void cutSelection(int f) {
        int s = selectionStart(f), e = selectionEnd(f);
        if (s == -1) {
            return;
        }
        setClipboard(inputText(f).substring(s, e));
        deleteSelection(f);
    }

    /** 从系统剪贴板粘贴到输入框（过滤非法字符并截断到最大长度）。 */
    private void pasteToField(int f) {
        String clip = getClipboard();
        if (clip == null) {
            return;
        }
        boolean isSeed = (f == 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clip.length(); i++) {
            char ch = clip.charAt(i);
            if (ch >= '0' && ch <= '9' || ch == '-' || (!isSeed && (ch == '.' || ch == '+'))) {
                sb.append(ch);
            }
        }
        String s = sb.toString();
        if (s.isEmpty()) {
            return;
        }
        int st = selectionStart(f), en = selectionEnd(f);
        if (st == -1) {
            st = caretArr[f];
            en = st;
        }
        int maxLen = isSeed ? 20 : 14;
        String cur = inputText(f);
        String newText = cur.substring(0, st) + s + cur.substring(en);
        if (newText.length() > maxLen) {
            newText = newText.substring(0, maxLen);
        }
        setInputText(f, newText);
        caretArr[f] = Math.min(st + s.length(), newText.length());
        selArr[f] = -1;
    }

    private void setClipboard(String s) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        } catch (Exception ignored) {
            // 剪贴板不可用则忽略
        }
    }

    private String getClipboard() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            return (String) cb.getData(DataFlavor.stringFlavor);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 读取指定输入框的文本。 */
    private String inputText(int f) {
        switch (f) {
            case 1: return seedText;
            case 2: return coordXText;
            case 3: return coordZText;
            default: return "";
        }
    }

    /** 写入指定输入框的文本。 */
    private void setInputText(int f, String s) {
        switch (f) {
            case 1: seedText = s; break;
            case 2: coordXText = s; break;
            case 3: coordZText = s; break;
            default: break;
        }
    }

    /** 输入框文字绘制的屏幕 x 起点。 */
    private int fieldTextX0(int f) {
        switch (f) {
            case 1: return FIELD_X + 8;
            case 2: return CX_FIELD_X + 6;
            case 3: return CZ_FIELD_X + 6;
            default: return 0;
        }
    }

    /** 输入框文字垂直居中的 y。 */
    private int fieldTextY(int f) {
        switch (f) {
            case 1: return FIELD_Y + (FIELD_H - text.fontHeight()) / 2;
            default:
                int cfy = winH - CFIELD_H - 10;
                return cfy + (CFIELD_H - text.fontHeight()) / 2;
        }
    }

    /** 点 (mx,my) 命中的输入框：0=无, 1=种子, 2=坐标X, 3=坐标Z。 */
    private int fieldAt(int mx, int my) {
        if (pointInField(mx, my, 1)) return 1;
        if (pointInField(mx, my, 2)) return 2;
        if (pointInField(mx, my, 3)) return 3;
        return 0;
    }

    /** 点 (mx,my) 是否在指定输入框矩形内。 */
    private boolean pointInField(int mx, int my, int f) {
        int cfy = winH - CFIELD_H - 10;
        switch (f) {
            case 1: return mx >= FIELD_X && mx <= FIELD_X + FIELD_W && my >= FIELD_Y && my <= FIELD_Y + FIELD_H;
            case 2: return mx >= CX_FIELD_X && mx <= CX_FIELD_X + CX_FIELD_W && my >= cfy && my <= cfy + CFIELD_H;
            case 3: return mx >= CZ_FIELD_X && mx <= CZ_FIELD_X + CZ_FIELD_W && my >= cfy && my <= cfy + CFIELD_H;
            default: return false;
        }
    }

    /** 应用输入框中的种子。 */
    private void applySeedInput() {
        focusField = 0;
        dragField = 0;
        if (seedText.isEmpty()) {
            return;
        }
        try {
            setSeed(Long.parseLong(seedText));
        } catch (NumberFormatException ignored) {
            // 非法输入则忽略
        }
    }

    /** 应用坐标输入框中的坐标，把视野中心移动到 (x, z)。 */
    private void applyCoordInput() {
        focusField = 0;
        dragField = 0;
        if (coordXText.isEmpty() && coordZText.isEmpty()) {
            return;
        }
        try {
            double x = coordXText.isEmpty() ? centerX : Double.parseDouble(coordXText);
            double z = coordZText.isEmpty() ? centerZ : Double.parseDouble(coordZText);
            centerX = x;
            centerZ = z;
        } catch (NumberFormatException ignored) {
            // 非法输入则忽略
        }
    }

    /** 返回 (mx,my) 处点击到的版本下拉项；不在下拉范围内返回 null。 */
    private GameVersion versionAt(int mx, int my) {
        GameVersion[] vs = GameVersion.values();
        for (int i = 0; i < vs.length; i++) {
            int y0 = VER_Y + VER_H + i * VER_H;
            if (mx >= VER_X && mx <= VER_X + VER_W && my >= y0 && my <= y0 + VER_H) {
                return vs[i];
            }
        }
        return null;
    }

    /** 在 (cx,cy) 处画一个小的向下箭头（V 图标），用于版本下拉框。 */
    private void drawDropArrow(int cx, int cy) {
        int s = 5; // 箭头半宽
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(0.85f, 0.85f, 0.85f, 1f);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(cx - s, cy - s / 2);
        GL11.glVertex2f(cx + s, cy - s / 2);
        GL11.glVertex2f(cx, cy + s / 2);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** 左上角种子输入框 + 应用按钮，左下角坐标输入框 + 应用按钮。 */
    private void drawUI() {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        // ===== 种子输入框（左上角）=====
        // 背景（黑底 50%）+ 边框（黑色）
        GL11.glColor4f(0f, 0f, 0f, 0.5f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(FIELD_X, FIELD_Y);
        GL11.glVertex2f(FIELD_X + FIELD_W, FIELD_Y);
        GL11.glVertex2f(FIELD_X + FIELD_W, FIELD_Y + FIELD_H);
        GL11.glVertex2f(FIELD_X, FIELD_Y + FIELD_H);
        GL11.glEnd();
        GL11.glColor4f(0f, 0f, 0f, 1f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(FIELD_X, FIELD_Y);
        GL11.glVertex2f(FIELD_X + FIELD_W, FIELD_Y);
        GL11.glVertex2f(FIELD_X + FIELD_W, FIELD_Y + FIELD_H);
        GL11.glVertex2f(FIELD_X, FIELD_Y + FIELD_H);
        GL11.glEnd();
        // 种子文字：值仅在种子变化时更新（setSeed/编辑），但每帧都绘制以匹配 GL 清屏
        String shown = (focusField != 1 && seedText.isEmpty()) ? "输入种子..." : seedText;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        text.draw(shown, FIELD_X + 8, fieldTextY(1), Color.WHITE);
        if (focusField == 1) { // 聚焦时绘制选区高亮 + 闪烁光标
            drawSelectionAndCaret(1, shown);
        }

        // 种子应用按钮
        GL11.glColor4f(0.22f, 0.42f, 0.82f, 0.9f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(BTN_X, BTN_Y);
        GL11.glVertex2f(BTN_X + BTN_W, BTN_Y);
        GL11.glVertex2f(BTN_X + BTN_W, BTN_Y + BTN_H);
        GL11.glVertex2f(BTN_X, BTN_Y + BTN_H);
        GL11.glEnd();
        GL11.glColor4f(0.7f, 0.8f, 1f, 1f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(BTN_X, BTN_Y);
        GL11.glVertex2f(BTN_X + BTN_W, BTN_Y);
        GL11.glVertex2f(BTN_X + BTN_W, BTN_Y + BTN_H);
        GL11.glVertex2f(BTN_X, BTN_Y + BTN_H);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        text.draw("应用", BTN_X + (BTN_W - text.stringWidth("应用")) / 2,
                BTN_Y + (BTN_H - text.fontHeight()) / 2, Color.WHITE);

        // ===== 版本下拉菜单（种子框右侧）=====
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        // 版本框背景（黑底 50%）+ 边框
        GL11.glColor4f(0f, 0f, 0f, 0.5f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(VER_X, VER_Y);
        GL11.glVertex2f(VER_X + VER_W, VER_Y);
        GL11.glVertex2f(VER_X + VER_W, VER_Y + VER_H);
        GL11.glVertex2f(VER_X, VER_Y + VER_H);
        GL11.glEnd();
        GL11.glColor4f(0f, 0f, 0f, 1f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(VER_X, VER_Y);
        GL11.glVertex2f(VER_X + VER_W, VER_Y);
        GL11.glVertex2f(VER_X + VER_W, VER_Y + VER_H);
        GL11.glVertex2f(VER_X, VER_Y + VER_H);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        text.draw(version.label(), VER_X + 8, VER_Y + (VER_H - text.fontHeight()) / 2, Color.WHITE);
        // 右侧 V 图标（向下箭头）
        drawDropArrow(VER_X + VER_W - 18, VER_Y + VER_H / 2);
        // 下拉列表
        if (versionOpen) {
            GameVersion[] vs = GameVersion.values();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            for (int i = 0; i < vs.length; i++) {
                int y0 = VER_Y + VER_H + i * VER_H;
                boolean cur = vs[i] == version;
                GL11.glColor4f(cur ? 0.22f : 0.32f, cur ? 0.42f : 0.32f, cur ? 0.82f : 0.32f, 0.9f);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(VER_X, y0);
                GL11.glVertex2f(VER_X + VER_W, y0);
                GL11.glVertex2f(VER_X + VER_W, y0 + VER_H);
                GL11.glVertex2f(VER_X, y0 + VER_H);
                GL11.glEnd();
                GL11.glColor4f(0.7f, 0.8f, 1f, 1f);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(VER_X, y0);
                GL11.glVertex2f(VER_X + VER_W, y0);
                GL11.glVertex2f(VER_X + VER_W, y0 + VER_H);
                GL11.glVertex2f(VER_X, y0 + VER_H);
                GL11.glEnd();
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                text.draw(vs[i].label(), VER_X + 8, y0 + (VER_H - text.fontHeight()) / 2, Color.WHITE);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
            }
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }

        // ===== 坐标输入区（左下角）：X: [输入框] Z: [输入框] 应用 =====
        int cfy = winH - CFIELD_H - 10;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        // X / Z 输入框背景（黑底 50%）
        GL11.glColor4f(0f, 0f, 0f, 0.5f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(CX_FIELD_X, cfy);
        GL11.glVertex2f(CX_FIELD_X + CX_FIELD_W, cfy);
        GL11.glVertex2f(CX_FIELD_X + CX_FIELD_W, cfy + CFIELD_H);
        GL11.glVertex2f(CX_FIELD_X, cfy + CFIELD_H);
        GL11.glVertex2f(CZ_FIELD_X, cfy);
        GL11.glVertex2f(CZ_FIELD_X + CZ_FIELD_W, cfy);
        GL11.glVertex2f(CZ_FIELD_X + CZ_FIELD_W, cfy + CFIELD_H);
        GL11.glVertex2f(CZ_FIELD_X, cfy + CFIELD_H);
        GL11.glEnd();
        // X / Z 输入框边框（黑色）
        GL11.glColor4f(0f, 0f, 0f, 1f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(CX_FIELD_X, cfy);
        GL11.glVertex2f(CX_FIELD_X + CX_FIELD_W, cfy);
        GL11.glVertex2f(CX_FIELD_X + CX_FIELD_W, cfy + CFIELD_H);
        GL11.glVertex2f(CX_FIELD_X, cfy + CFIELD_H);
        GL11.glEnd();
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(CZ_FIELD_X, cfy);
        GL11.glVertex2f(CZ_FIELD_X + CZ_FIELD_W, cfy);
        GL11.glVertex2f(CZ_FIELD_X + CZ_FIELD_W, cfy + CFIELD_H);
        GL11.glVertex2f(CZ_FIELD_X, cfy + CFIELD_H);
        GL11.glEnd();
        // 坐标应用按钮背景 + 边框
        GL11.glColor4f(0.22f, 0.42f, 0.82f, 0.9f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(CB_X, cfy);
        GL11.glVertex2f(CB_X + CB_W, cfy);
        GL11.glVertex2f(CB_X + CB_W, cfy + CB_H);
        GL11.glVertex2f(CB_X, cfy + CB_H);
        GL11.glEnd();
        GL11.glColor4f(0.7f, 0.8f, 1f, 1f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(CB_X, cfy);
        GL11.glVertex2f(CB_X + CB_W, cfy);
        GL11.glVertex2f(CB_X + CB_W, cfy + CB_H);
        GL11.glVertex2f(CB_X, cfy + CB_H);
        GL11.glEnd();
        // 标签（X: / Z:）与按钮文字
        int cty = fieldTextY(2);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        text.draw("X:", CX_FIELD_X - 34, cty, Color.WHITE);
        text.draw("Z:", CZ_FIELD_X - 36, cty, Color.WHITE);
        text.draw("应用", CB_X + (CB_W - text.stringWidth("应用")) / 2,
                cfy + (CB_H - text.fontHeight()) / 2, Color.WHITE);
        // X / Z 值：位置变化（拖动/缩放）时更新到实时位置，静止时保持（切换焦点不重置）
        long fx = (long) Math.floor(centerX);
        if (fx != lastXVal) {
            lastXVal = fx;
            coordXText = String.valueOf(fx);
        }
        long fz = (long) Math.floor(centerZ);
        if (fz != lastZVal) {
            lastZVal = fz;
            coordZText = String.valueOf(fz);
        }
        String xshown = coordXText;
        String zshown = coordZText;
        text.draw(xshown, CX_FIELD_X + 6, cty, Color.WHITE);
        text.draw(zshown, CZ_FIELD_X + 6, cty, Color.WHITE);
        // 聚焦时绘制选区高亮 + 闪烁光标
        if (focusField == 2) {
            drawSelectionAndCaret(2, xshown);
        } else if (focusField == 3) {
            drawSelectionAndCaret(3, zshown);
        }

        // ===== 点击选中的地牢信息（跟随红点在屏幕上的位置）=====
        if (selectedDungeon != null) {
            double bpp = blocksPerPixel();
            double sx = (selectedDungeon[0] - centerX) / bpp + winW / 2.0;
            double sy = (selectedDungeon[2] - centerZ) / bpp + winH / 2.0;
            // 红点移出视距则关闭
            if (sx < -20 || sx > winW + 20 || sy < -20 || sy > winH + 20) {
                selectedDungeon = null;
            } else {
                String info = "地牢  X:" + selectedDungeon[0]
                        + "  Y:" + selectedDungeon[1]
                        + "  Z:" + selectedDungeon[2]
                        + "  刷怪笼:" + mobName(selectedDungeon[3]);
                int iw = text.stringWidth(info);
                int ih = text.fontHeight() + 8;
                int bx = (int) (sx - iw / 2.0) - 8;
                int by = (int) (sy - ih - 12); // 默认放在红点上方
                if (by < 0) by = (int) sy + 12; // 顶部放不下则放到红点下方
                int bx1 = bx + iw + 16;
                int by1 = by + ih;
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glColor4f(0f, 0f, 0f, 0.6f);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(bx, by);
                GL11.glVertex2f(bx1, by);
                GL11.glVertex2f(bx1, by1);
                GL11.glVertex2f(bx, by1);
                GL11.glEnd();
                GL11.glColor4f(1f, 0.13f, 0.13f, 1f);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(bx, by);
                GL11.glVertex2f(bx1, by);
                GL11.glVertex2f(bx1, by1);
                GL11.glVertex2f(bx, by1);
                GL11.glEnd();
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                text.draw(info, bx + 8, by + 4, Color.WHITE);
            }
        }
    }

    /** 为聚焦输入框绘制选区高亮背景 + 闪烁光标。 */
    private void drawSelectionAndCaret(int f, String shown) {
        int sel = selArr[f], cr = caretArr[f];
        int ty = fieldTextY(f);
        int sh = text.fontHeight();
        // 选区高亮
        if (sel != -1 && sel != cr) {
            int lo = Math.min(cr, sel), hi = Math.max(cr, sel);
            int sx0 = fieldTextX0(f) + text.stringWidth(shown.substring(0, lo));
            int sx1 = fieldTextX0(f) + text.stringWidth(shown.substring(0, hi));
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(0.3f, 0.6f, 1f, 0.55f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(sx0, ty);
            GL11.glVertex2f(sx1, ty);
            GL11.glVertex2f(sx1, ty + sh);
            GL11.glVertex2f(sx0, ty + sh);
            GL11.glEnd();
        }
        // 闪烁光标（2px 竖线）
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = fieldTextX0(f) + text.stringWidth(shown.substring(0, cr));
            int cy = ty + 1;
            int ch = sh - 6;
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(cx, cy);
            GL11.glVertex2f(cx + 2, cy);
            GL11.glVertex2f(cx + 2, cy + ch);
            GL11.glVertex2f(cx, cy + ch);
            GL11.glEnd();
        }
    }

    private void zoomBy(int dir) {
        selectedDungeon = null; // 缩放后红点位置变化，关闭信息框

        if (dir > 0) { // 放大：方块/像素减半
            bpp /= 2.0;
            if (bpp < minBpp()) bpp = minBpp();
        } else { // 缩小：方块/像素加倍
            bpp *= 2.0;
            if (bpp > maxBpp()) bpp = maxBpp();
        }
        // 以十字准星（画面中心）为缩放锚点：centerX/centerZ 保持不变，
        // 准星所指方块保持在画面中心。
    }

    /** 最远视野：窗口宽覆盖 WIDEST_BLOCKS 方块所对应的方块/像素。 */
    private double maxBpp() {
        return WIDEST_BLOCKS / winW;
    }

    /** 最近视野：最远再放大 ZOOM_STEPS 级。 */
    private double minBpp() {
        return maxBpp() / (1 << ZOOM_STEPS);
    }

    /** 每像素方块数（越小越放大）。 */
    private double blocksPerPixel() {
        return bpp;
    }

    private void setSeed(long newSeed) {
        if (gen != null) {
            gen.close(); // 停止后台落盘线程并收尾（文件保留，便于切回同一种子复用）
        }
        this.seed = newSeed;
        File disk = diskCacheFile(newSeed, version);
        sessionDiskFiles.add(disk);
        this.gen = new ChunkGenerator(seed, version, disk);
        if (cache != null) {
            cache.shutdown();
        }
        this.cache = new TileCache(gen, MAX_TILES);
        if (focusField != 1) {
            seedText = String.valueOf(seed);
        }
    }

    /** 切换地牢版本：重建生成器并清除缓存（含磁盘缓存，避免跨版本复用）。 */
    private void setVersion(GameVersion v) {
        if (v == version) return;
        this.version = v;
        setSeed(seed); // 用当前种子重建，自动换新版本缓存文件
        selectedDungeon = null;
    }

    /** 临时磁盘缓存文件路径（系统临时目录，按种子+版本命名）。 */
    private static File diskCacheFile(long seed, GameVersion v) {
        return new File(System.getProperty("java.io.tmpdir"),
                "dungeonfinder_" + seed + "_" + v.name() + ".dmc");
    }

    private String title() {
        return "Dungeon Finder";
    }

    private void cleanup() {
        if (cache != null) {
            cache.shutdown();
            cache.clear();
        }
        if (gen != null) {
            gen.close();
        }
        // 删除本会话创建的所有临时磁盘缓存文件（关闭程序时自动清理）
        for (File f : sessionDiskFiles) {
            try {
                if (f != null && f.exists()) f.delete();
            } catch (Exception ignored) {
            }
        }
        Mouse.destroy();
        Keyboard.destroy();
        Display.destroy();
    }
}