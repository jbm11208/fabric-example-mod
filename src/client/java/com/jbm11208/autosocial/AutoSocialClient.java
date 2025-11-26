package com.jbm11208.autosocial;

import net.fabricmc.api.ClientModInitializer;

public class AutoSocialClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        System.out.println("[AutoSocial] Client mod initializing...");
        // Ensure AutoSocial initializes immediately so config/autosocial/autosocial.yml is created on startup.
        // We use reflection here to avoid compile-time dependency from main source set to client-only classes.
        try {
            Class<?> logic = Class.forName("com.jbm11208.autosocial.AutoSocialLogic");
            logic.getMethod("init").invoke(null);
        } catch (Throwable t) {
            System.out.println("[AutoSocial] Failed to initialize AutoSocialLogic via reflection: " + t);
        }
        System.out.println("[AutoSocial] Client mod initialized.");
    }
}
