package betterbundle.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;

/**
 * Retrieves the currently open Screen across MC 26.1 (Minecraft.screen)
 * and MC 26.2 (moved onto Minecraft.gui), without linking directly to the
 * version-specific field so the same source compiles for both versions.
 */
public final class MinecraftScreenHelper {
    private static Field mcScreenField;   // 26.1: field declared on Minecraft
    private static Field guiField;         // 26.2: Minecraft.gui
    private static Field guiScreenField;   // 26.2: Gui.screen
    private static boolean resolved = false;

    private MinecraftScreenHelper() {}

    public static Screen getCurrentScreen(Minecraft client) {
        resolve(client);
        try {
            if (mcScreenField != null) {
                return (Screen) mcScreenField.get(client);
            }
            Object gui = guiField.get(client);
            if (gui != null && guiScreenField != null) {
                return (Screen) guiScreenField.get(gui);
            }
        } catch (IllegalAccessException e) {
            return null;
        }
        return null;
    }

    private static void resolve(Minecraft client) {
        if (resolved) return;
        resolved = true;
        try {
            Field gui = findField(Minecraft.class, "gui");
            if (gui != null) {
                guiField = gui;
                Object guiInstance = gui.get(client);
                if (guiInstance != null) {
                    guiScreenField = findScreenField(guiInstance.getClass());
                }
            }
            if (guiScreenField == null) {
                mcScreenField = findScreenField(Minecraft.class);
            }
        } catch (IllegalAccessException e) {
            // resolved stays true; fall back gracefully
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Field findScreenField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (Screen.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }
}