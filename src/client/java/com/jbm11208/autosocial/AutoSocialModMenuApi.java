package com.jbm11208.autosocial;

import com.jbm11208.autosocial.ui.AutoSocialConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.Minecraft;

public class AutoSocialModMenuApi implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            return new AutoSocialConfigScreen(Minecraft.getInstance().screen);
        };
    }
}