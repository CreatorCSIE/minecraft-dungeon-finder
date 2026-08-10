package dungeon.render;

import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 简易文本渲染器：用 AWT 把字符串画到 BufferedImage，再上传为 OpenGL 纹理绘制。
 * 适用于 LWJGL2 纯 OpenGL 环境下的调试信息 / UI 文字。
 */
public final class TextRenderer {
    private final Font font;
    private final FontMetrics metrics;

    public TextRenderer(int fontSize) {
        // 优先用支持中文的粗体黑体（Windows 自带微软雅黑）；不可用时由 Java 回退
        this.font = new Font("Microsoft YaHei", Font.BOLD, fontSize);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = probe.createGraphics();
        g.setFont(font);
        this.metrics = g.getFontMetrics();
        g.dispose();
    }

    public int stringWidth(String s) {
        return metrics.stringWidth(s);
    }

    public int fontHeight() {
        return metrics.getHeight();
    }

    /**
     * 在屏幕坐标 (x,y)（左上角，y 向下）绘制文字。
     * 颜色已烘焙进纹理，调用方不改变 GL 颜色。
     */
    public void draw(String text, int x, int y, Color color) {
        int w = Math.max(1, metrics.stringWidth(text));
        int h = metrics.getHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, 0, metrics.getAscent());
        g.dispose();

        // 转 RGBA 字节（ARGB -> RGBA）
        byte[] rgba = new byte[w * h * 4];
        int idx = 0;
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int argb = img.getRGB(xx, yy);
                rgba[idx++] = (byte) ((argb >> 16) & 0xFF); // R
                rgba[idx++] = (byte) ((argb >> 8) & 0xFF);  // G
                rgba[idx++] = (byte) (argb & 0xFF);         // B
                rgba[idx++] = (byte) ((argb >> 24) & 0xFF); // A
            }
        }

        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F); // GL_CLAMP_TO_EDGE
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F); // GL_CLAMP_TO_EDGE
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, asByteBuffer(rgba));

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(x + w, y);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(x, y + h);
        GL11.glEnd();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glDeleteTextures(tex);
    }

    private static java.nio.ByteBuffer asByteBuffer(byte[] data) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(data.length);
        buf.put(data);
        buf.flip();
        return buf;
    }
}