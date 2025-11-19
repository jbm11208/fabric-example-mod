package com.jbm11208.autosocial;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import org.lwjgl.glfw.GLFW;
import com.jbm11208.autosocial.ui.AutoSocialConfigScreen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

public class ClientKeybinds implements ClientModInitializer {

    private static KeyMapping skipKey;
    private static KeyMapping reloadConfigKey;
    private static KeyMapping toggleVideoHudKey;
    private static KeyMapping openConfigKey;
    private static boolean SHOW_VIDEO_HUD = false;

    @Override
    public void onInitializeClient() {
        if (AutoSocialLogic.isVerbose()) System.out.println("[AutoSocial] ClientKeybinds initializing...");
        // Register keybinding for skipping current audio; appears in Controls -> Key Binds
        skipKey = KeyBindingHelper.registerKeyBinding(createKeyMapping("key.autosocial.skip", GLFW.GLFW_KEY_UNKNOWN, "key.categories.misc"));
        // Register keybinding for reloading config
        reloadConfigKey = KeyBindingHelper.registerKeyBinding(createKeyMapping("key.autosocial.reload_config", GLFW.GLFW_KEY_UNKNOWN, "key.categories.misc"));
        // Register keybinding for toggling the Now Playing HUD
        toggleVideoHudKey = KeyBindingHelper.registerKeyBinding(createKeyMapping("key.autosocial.toggle_video_hud", GLFW.GLFW_KEY_UNKNOWN, "key.categories.misc"));
        // Register keybinding for opening the AutoSocial config GUI
        openConfigKey = KeyBindingHelper.registerKeyBinding(createKeyMapping("key.autosocial.open_config", GLFW.GLFW_KEY_UNKNOWN, "key.categories.misc"));

        // Listen for key presses each client tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (skipKey != null) {
                while (skipKey.consumeClick()) {
                    if (AutoSocialLogic.isVerbose()) System.out.println("[AutoSocial] Skip key pressed. Requesting audio skip...");
                    AutoSocialLogic.skipCurrentAudio();
                }
            }
            if (reloadConfigKey != null) {
                while (reloadConfigKey.consumeClick()) {
                    if (AutoSocialLogic.isVerbose()) System.out.println("[AutoSocial] Reload Config key pressed. Reloading config.yml...");
                    boolean ok = AutoSocialLogic.reloadConfig();
                    if (client.player != null) {
                        String name = AutoSocialLogic.getAiName();
                        client.player.connection.sendChat("IAMAB0T[AI]: config reload -> " + (ok ? "OK" : "FAILED") + ", model=" + AutoSocialLogic.getModelSafe());
                    }
                }
            }
            if (toggleVideoHudKey != null) {
                while (toggleVideoHudKey.consumeClick()) {
                    SHOW_VIDEO_HUD = !SHOW_VIDEO_HUD;
                    if (AutoSocialLogic.isVerbose()) System.out.println("[AutoSocial] Toggle Now Playing HUD -> " + (SHOW_VIDEO_HUD ? "ON" : "OFF"));
                }
            }
            if (openConfigKey != null) {
                while (openConfigKey.consumeClick()) {
                    if (AutoSocialLogic.isVerbose()) System.out.println("[AutoSocial] Open Config GUI key pressed.");
                    Minecraft.getInstance().setScreen(new AutoSocialConfigScreen(Minecraft.getInstance().screen));
                }
            }
        });

        // HUD overlay to show current playing YouTube title via yt-dlp when toggled on
        HudRenderCallback.EVENT.register((drawContext, deltaTracker) -> {
            if (!SHOW_VIDEO_HUD) return;
            String title = AutoSocialLogic.getCurrentPlayingTitle();
            if (title == null || title.isBlank()) return;
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            String text = "Now Playing: " + title;
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();
            int tw = font.width(text);
            int th = font.lineHeight;
            int x = Math.max(0, (sw - tw) / 2);
            int y = Math.max(0, (sh - th) / 2);
            // Background box
            int pad = 6;
            drawContext.fill(x - pad, y - pad, x + tw + pad, y + th + pad, 0xAA000000);
            // Text
            drawContext.drawString(font, text, x, y, 0xFFFFFFFF, false);
        });
    }

    private static KeyMapping createKeyMapping(String translationKey, int defaultKey, String categoryKey) {
        try {
            // Prefer Mojang-style constructor: (String, int, String)
            Constructor<KeyMapping> mojangCtor = KeyMapping.class.getConstructor(String.class, int.class, String.class);
            return mojangCtor.newInstance(translationKey, defaultKey, categoryKey);
        } catch (NoSuchMethodException mojangMissing) {
            try {
                // Fallback to enum Category signature: (String, int, KeyMapping.Category)
                Class<?> categoryClass = Class.forName("net.minecraft.client.KeyMapping$Category");
                Field miscField = categoryClass.getField("MISC");
                Object misc = miscField.get(null);
                Constructor<KeyMapping> yarnCtor = KeyMapping.class.getConstructor(String.class, int.class, categoryClass);
                return yarnCtor.newInstance(translationKey, defaultKey, misc);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to construct KeyMapping with either signature", t);
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to construct KeyMapping", t);
        }
    }
}
