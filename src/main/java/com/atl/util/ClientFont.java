package com.atl.util;

import com.atl.module.Claude;
import com.atl.module.management.Module;
import com.atl.module.modules.ClickGUI;
import com.atl.util.font.CustomFontRenderer;
import net.minecraft.client.Minecraft;

public class ClientFont {

    public static final String FONT_PATH = "textures/client/fonts/font.ttf";
    public static final int FONT_SIZE = 8;

    private static CustomFontRenderer custom;
    private static boolean attempted = false;

    private static CustomFontRenderer getCustom() {
        if (!attempted) {
            attempted = true;
            try {
                custom = new CustomFontRenderer(FONT_PATH, FONT_SIZE);
            } catch (Exception e) {
                custom = null;
            }
        }
        return custom;
    }

    public static boolean isCustom() {
        if (Claude.moduleManager == null) return false;
        Module m = Claude.moduleManager.get("ClickGUI");
        return m instanceof ClickGUI && ((ClickGUI) m).customFont.isEnabled();
    }

    private static boolean useCustom() {
        return isCustom() && getCustom() != null;
    }

    public static void drawString(String text, float x, float y, int color) {
        if (useCustom()) {
            getCustom().drawString(text, x, y, color);
        } else {
            Minecraft.getMinecraft().fontRendererObj.drawString(text, (int) x, (int) y, color);
        }
    }

    public static void drawStringWithShadow(String text, float x, float y, int color) {
        if (useCustom()) {
            getCustom().drawStringWithShadow(text, x, y, color);
        } else {
            Minecraft.getMinecraft().fontRendererObj.drawStringWithShadow(text, x, y, color);
        }
    }

    public static int getStringWidth(String text) {
        if (useCustom()) {
            return getCustom().getStringWidth(text);
        }
        return Minecraft.getMinecraft().fontRendererObj.getStringWidth(text);
    }

    public static int getHeight() {
        if (useCustom()) {
            return getCustom().FONT_HEIGHT;
        }
        return Minecraft.getMinecraft().fontRendererObj.FONT_HEIGHT;
    }
}
