package dungeon.app;

import dungeon.core.ChunkGenerator;
import dungeon.render.TileRenderer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 瓦片缓存 + 异步加载（对应网页的 Web Worker 池）。
 *
 * 每个瓦片 = 一个区块（16x16 方块）。区块瓦片内容与缩放无关（地牢标记不变），
 * 因此缩放/平移时瓦片可复用，仅需按视野请求新瓦片。后台线程用满所有核心并行
 * 栅格化区块瓦片，主线程只做 GL 纹理上传与绘制。
 *
 * 职责划分：
 *  - request(key)：请求后台生成某区块瓦片（去重，避免重复入队）。
 *  - uploadIfReady(key)：已就绪则上传为 GL 纹理并返回 id；否则返回 -1。
 */
final class TileCache {
    private static final int TILE_SIZE = TileRenderer.TILE_SIZE; // 16
    // 后台区块瓦片栅格化线程数：用满所有核心。区块瓦片数量多、相互独立，可近线性扩展。
    private static final int NUM_WORKERS = Runtime.getRuntime().availableProcessors();

    /** 瓦片键：区块坐标。 */
    static final class Key {
        final int cx, cz;
        Key(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return cx == k.cx && cz == k.cz;
        }
        @Override public int hashCode() {
            return cx * 73856093 ^ cz * 19349663;
        }
    }

    private final ChunkGenerator generator;
    private final int maxTiles;

    // 主线程访问：已上传的 GL 纹理（LRU，超限淘汰）
    private final Map<Key, Integer> textures;
    // 后台填充 / 主线程读取：刚栅格化、尚未上传的瓦片
    private final Map<Key, BufferedImage> readyImages;
    // 主线程去重：已排队 / 生成中 / 待上传的瓦片
    private final Map<Key, Boolean> requested;
    private final LinkedBlockingQueue<Key> queue;

    private final Thread[] workers;

    TileCache(ChunkGenerator generator, int maxTiles) {
        this.generator = generator;
        this.maxTiles = maxTiles;
        this.textures = new LinkedHashMap<Key, Integer>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Key, Integer> eldest) {
                if (size() > maxTiles) {
                    GL11.glDeleteTextures(eldest.getValue());
                    requested.remove(eldest.getKey()); // 允许将来重新生成
                    return true;
                }
                return false;
            }
        };
        this.readyImages = new ConcurrentHashMap<>();
        this.requested = new ConcurrentHashMap<>();
        this.queue = new LinkedBlockingQueue<>();

        this.workers = new Thread[NUM_WORKERS];
        for (int i = 0; i < NUM_WORKERS; i++) {
            workers[i] = new Thread(this::workerLoop, "tile-loader-" + i);
            workers[i].setDaemon(true);
            // 优先级略低于主渲染线程：主线程被 vsync 阻塞时 worker 用满核心，
            // 一旦主线程需要渲染/上传，OS 优先调度主线程，避免拖动时掉帧。
            workers[i].setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            workers[i].start();
        }
    }

    /** 后台线程主体：从队列取任务并栅格化瓦片。 */
    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            Key key;
            try {
                key = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (key == null) {
                continue;
            }
            try {
                BufferedImage img = TileRenderer.renderTile(generator, key.cx, key.cz);
                readyImages.put(key, img);
            } catch (Throwable t) {
                // 单个瓦片失败不能拖垮 worker 线程：打印后继续处理下一个任务
                t.printStackTrace();
            }
        }
    }

    /** 主线程调用：请求后台生成某瓦片（若尚未生成/上传）。 */
    void request(Key key) {
        if (textures.containsKey(key) || requested.containsKey(key)) {
            return;
        }
        requested.put(key, Boolean.TRUE);
        queue.offer(key);
    }

    /** 主线程调用：判断瓦片是否已上传或已栅格化就绪（无 GL 副作用，可在 glBegin 前调用）。 */
    boolean isReady(Key key) {
        return textures.containsKey(key) || readyImages.containsKey(key);
    }

    /**
     * 主线程调用：若瓦片纹理已就绪则返回 GL 纹理 id；
     * 若栅格化完成但尚未上传，则上传并返回 id；否则返回 -1（未就绪，本帧跳过）。
     */
    int uploadIfReady(Key key) {
        Integer tex = textures.get(key);
        if (tex != null) {
            return tex;
        }
        BufferedImage img = readyImages.get(key);
        if (img == null) {
            return -1;
        }
        int id = upload(img);
        textures.put(key, id);
        readyImages.remove(key);
        requested.remove(key);
        return id;
    }

    /** 只返回已上传纹理 id；未上传（含已栅格化但未上传）返回 -1，不触发上传。 */
    int textureId(Key key) {
        Integer tex = textures.get(key);
        return tex == null ? -1 : tex;
    }

    /** 释放所有 GL 纹理并清空状态。 */
    void clear() {
        for (Integer id : textures.values()) {
            GL11.glDeleteTextures(id);
        }
        textures.clear();
        readyImages.clear();
        requested.clear();
        queue.clear();
    }

    /** 停止后台线程。 */
    void shutdown() {
        for (Thread w : workers) {
            w.interrupt();
        }
    }

    /** 把 BufferedImage 上传为 GL_RGBA 纹理。 */
    private static int upload(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                buf.put((byte) ((argb >> 16) & 0xFF)); // R
                buf.put((byte) ((argb >> 8) & 0xFF));  // G
                buf.put((byte) (argb & 0xFF));         // B
                buf.put((byte) ((argb >> 24) & 0xFF)); // A
            }
        }
        buf.flip();

        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        // 放大过滤用 LINEAR：mag>1 额外拉近时平滑过渡，而非马赛克
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // 关键：CLAMP_TO_EDGE (0x812F) 避免瓦片边界因 GL_REPEAT 把 v=1.0 回绕到 texel 0 而产生接缝线
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return id;
    }
}