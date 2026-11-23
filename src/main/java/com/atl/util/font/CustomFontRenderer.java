package com.atl.util.font;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class CustomFontRenderer {

    private static final String COLOR_CODES = "0123456789abcdef";
    private static final int[] COLOR_TABLE = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private static final int SUPERSAMPLE = 2;

    private final Map<Character, Glyph> glyphCache = new HashMap<>();
    private final Font font;
    private final FontMetrics metrics;
    private final Font renderFont;
    private final FontMetrics renderMetrics;

    public final int FONT_HEIGHT;

    private static class Glyph {
        int textureId = -1;
        float width;
        float height;
        float topOffset;
    }

    public CustomFontRenderer(String resourcePath, int size) {
        this.font = loadFont(resourcePath, size);
        this.renderFont = font.deriveFont(Font.PLAIN, size * SUPERSAMPLE);
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        g.setFont(font);
        this.metrics = g.getFontMetrics();
        this.FONT_HEIGHT = metrics.getAscent() + metrics.getDescent();
        g.setFont(renderFont);
        this.renderMetrics = g.getFontMetrics();
        g.dispose();
    }

    private static Font loadFont(String resourcePath, int size) {
        try {
            InputStream in = Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation(resourcePath)).getInputStream();
            Font f = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(Font.PLAIN, size);
            in.close();
            return f;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load custom font: " + resourcePath, e);
        }
    }

    public int drawString(String text, float x, float y, int color) {
        return drawString(text, x, y, color, false);
    }

    public int drawStringWithShadow(String text, float x, float y, int color) {
        drawString(text, x + 1.0f, y + 1.0f, color, true);
        return drawString(text, x, y, color, false);
    }

    private int drawString(String text, float x, float y, int color, boolean shadow) {
        if (text == null || text.isEmpty()) return (int) x;

        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        float curX = x;
        int currentColor = shadow ? 0x66000000 : color;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(++i));
                int idx = COLOR_CODES.indexOf(code);
                if (idx != -1) {
                    if (!shadow) {
                        currentColor = 0xFF000000 | COLOR_TABLE[idx];
                    }
                } else if (code == 'r') {
                    currentColor = color;
                }
                continue;
            }
            Glyph g = getGlyph(c);
            if (g == null || g.width == 0) continue;
            if (g.textureId != -1) {
                drawGlyph(g, curX, y, currentColor);
            }
            curX += g.width;
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        return (int) Math.ceil(curX);
    }

    public int getStringWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        float width = 0.0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                i++;
                continue;
            }
            Glyph g = getGlyph(c);
            if (g != null) width += g.width;
        }
        return (int) Math.ceil(width);
    }

    private void drawGlyph(Glyph g, float x, float y, int color) {
        float a = ((color >> 24) & 255) / 255.0f;
        float r = ((color >> 16) & 255) / 255.0f;
        float gr = ((color >> 8) & 255) / 255.0f;
        float b = (color & 255) / 255.0f;

        GlStateManager.color(r, gr, b, a);
        GlStateManager.bindTexture(g.textureId);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x, y + g.topOffset, 0.0).tex(0.0, 0.0).endVertex();
        wr.pos(x, y + g.topOffset + g.height, 0.0).tex(0.0, 1.0).endVertex();
        wr.pos(x + g.width, y + g.topOffset + g.height, 0.0).tex(1.0, 1.0).endVertex();
        wr.pos(x + g.width, y + g.topOffset, 0.0).tex(1.0, 0.0).endVertex();
        tessellator.draw();
    }

    private Glyph getGlyph(char c) {
        Glyph cached = glyphCache.get(c);
        if (cached != null) return cached;
        Glyph glyph = createGlyph(c);
        glyphCache.put(c, glyph);
        return glyph;
    }

    private Glyph createGlyph(char c) {
        Glyph glyph = new Glyph();

        int cellWidth = Math.max(8 * SUPERSAMPLE, renderMetrics.charWidth(c) + 4);
        int cellHeight = FONT_HEIGHT * SUPERSAMPLE + 2;
        BufferedImage img = new BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setFont(renderFont);
        g2d.setColor(Color.WHITE);
        g2d.drawString(String.valueOf(c), 2, renderMetrics.getAscent() + 1);
        g2d.dispose();

        int minX = cellWidth, minY = cellHeight, maxX = -1, maxY = -1;
        for (int y = 0; y < cellHeight; y++) {
            for (int x = 0; x < cellWidth; x++) {
                if ((img.getRGB(x, y) >>> 24) > 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < 0) {
            glyph.width = Math.max(1, renderMetrics.charWidth(c) / SUPERSAMPLE);
            return glyph;
        }

        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        BufferedImage cropped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        cropped.getGraphics().drawImage(img, 0, 0, w, h, minX, minY, maxX + 1, maxY + 1, null);

        int textureId = GL11.glGenTextures();
        TextureUtil.uploadTextureImage(textureId, cropped);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        glyph.textureId = textureId;
        glyph.width = w / (float) SUPERSAMPLE;
        glyph.height = h / (float) SUPERSAMPLE;
        glyph.topOffset = minY / (float) SUPERSAMPLE;
        return glyph;
    }
}
