package com.jbm11208.autosocial;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import com.jbm11208.autosocial.ui.AutoSocialConfigScreen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

public class ClientKeybinds implements ClientModInitializer {

    private static KeyMapping skipKey;
    private static KeyMapping reloadConfigKey;
    private static KeyMapping toggleVideoHudKey;
    private static KeyMapping openConfigKey;
    private static KeyMapping screenshotKey;
    private static KeyMapping customScreenshotKey;
    private static boolean SHOW_VIDEO_HUD = false;

    @Override
    public void onInitializeClient() {
        if (AutoSocialLogic.isVerbose()) System.out.println("[AutoSocial] ClientKeybinds initializing...");
        KeyMapping.Category CATEGORY = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("autosocial", "category")
        );
        // Register keybinding for skipping current audio; appears in Controls -> Key Binds
        skipKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autosocial.skip", InputConstants.Type.KEYSYM, InputConstants.KEY_PERIOD, CATEGORY));
        // Register keybinding for reloading config
        reloadConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autosocial.reload_config", InputConstants.Type.KEYSYM, InputConstants.KEY_NUMPAD6, CATEGORY));
        // Register keybinding for toggling the Now Playing HUD
        toggleVideoHudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autosocial.toggle_video_hud", InputConstants.Type.KEYSYM, InputConstants.KEY_NUMPAD8, CATEGORY));
        // Register keybinding for opening the AutoSocial config GUI
        openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autosocial.open_config", InputConstants.Type.KEYSYM, InputConstants.KEY_NUMPAD5, CATEGORY));
        // Register keybinding for taking a screenshot
        screenshotKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autosocial.screenshot", InputConstants.Type.KEYSYM, InputConstants.KEY_NUMPAD9, CATEGORY));
        // Register keybinding for taking a screenshot and adding a custom user prompt
        customScreenshotKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autosocial.custom_screenshot", InputConstants.Type.KEYSYM, InputConstants.KEY_NUMPAD7, CATEGORY));

        // Create HUD element for showing current playing YouTube title
        Identifier hudId = Identifier.fromNamespaceAndPath("assets.autosocial", "now_playing_hud");

        HudElement element = (GuiGraphicsExtractor drawContext, DeltaTracker tickDelta) -> {
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

            int pad = 6;
            drawContext.fill(x - pad, y - pad, x + tw + pad, y + th + pad, 0xAA000000);
            drawContext.text(font, text, x, y, 0xFFFFFFFF, false);
        };

        // Register the HUD element
        HudElementRegistry.addLast(hudId, element);

        // Listen for key presses each client tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (skipKey != null) {
                while (skipKey.consumeClick()) {
                    if (AutoSocialLogic.isVerbose())
                        System.out.println("[AutoSocial] Skip key pressed. Requesting audio skip...");
                    AutoSocialLogic.skipCurrentAudio();
                }
            }
            if (reloadConfigKey != null) {
                while (reloadConfigKey.consumeClick()) {
                    if (AutoSocialLogic.isVerbose())
                        System.out.println("[AutoSocial] Reload Config key pressed. Reloading config.yml...");
                    boolean ok = AutoSocialLogic.reloadConfig();
                    if (client.player != null) {
                        client.player.connection.sendChat("IAMAB0T[AI]: config reload -> " + (ok ? "OK" : "FAILED") + ", model=" + AutoSocialLogic.getModelSafe());
                    }
                }
            }
            if (toggleVideoHudKey != null) {
                while (toggleVideoHudKey.consumeClick()) {
                    SHOW_VIDEO_HUD = !SHOW_VIDEO_HUD;
                    if (SHOW_VIDEO_HUD) {
                        client.gui.hud.setOverlayMessage(Component.literal("§a\"Now Playing\" HUD Enabled"), false);
                    } else {
                        client.gui.hud.setOverlayMessage(Component.literal("§c\"Now Playing\" HUD Disabled"), false);
                    }
                    if (AutoSocialLogic.isVerbose())
                        System.out.println("[AutoSocial] Toggle Now Playing HUD -> " + (SHOW_VIDEO_HUD ? "ON" : "OFF"));
                }
            }
            if (openConfigKey != null) {
                while (openConfigKey.consumeClick()) {
                    if (AutoSocialLogic.isVerbose()) System.out.println("[AutoSocial] Open Config GUI key pressed.");
                    Minecraft.getInstance().setScreenAndShow(new AutoSocialConfigScreen(Minecraft.getInstance().gui.screen()));
                }
            }
            if (screenshotKey != null) {
                while (screenshotKey.consumeClick()) {
                    if (AutoSocialLogic.isVerbose()) {
                        System.out.println("[AutoSocial] Screenshot key pressed.");
                    }
                    AutoSocialLogic.takeScreenshot(false);
                }
            }
            if (customScreenshotKey != null) {
                while (customScreenshotKey.consumeClick()) {
                    if (AutoSocialLogic.isVerbose()) {
                        System.out.println("[AutoSocial] Custom Screenshot key pressed.");
                    }
                    AutoSocialLogic.takeScreenshot(true);
                }
            }
        });
    }
}