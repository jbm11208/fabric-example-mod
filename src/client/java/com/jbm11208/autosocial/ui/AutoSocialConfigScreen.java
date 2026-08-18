package com.jbm11208.autosocial.ui;

import com.jbm11208.autosocial.AutoSocialLogic;
import com.jbm11208.autosocial.tts.TTSClient.TTSProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AutoSocialConfigScreen extends Screen {
    private final Screen parent;
    private EditBox modelField;
    private EditBox aiNameField;
    private EditBox triggerField;
    private EditBox ytDlpPathField;
    private EditBox temperatureField;
    private EditBox sysPromptField;
    private Checkbox verboseCheck;
    private Checkbox botPrefixCheck;
    private VolumeSlider volumeSlider;
    private Checkbox TTSon;
    private CycleButton<AutoSocialLogic.AIProvider> providerButton;
    private EditBox openaiApiKeyField;
    private CycleButton<TTSProvider> ttsProviderButton;
    private EditBox elevenlabsApiKeyField;
    private EditBox elevenlabsVoiceIdField;

    private static class VolumeSlider extends AbstractSliderButton {
        public VolumeSlider(int x, int y, int width, int height, Component message, double initialValue) {
            super(x, y, width, height, message, initialValue);
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("Volume: " + String.format("%.0f%%", this.value * 100)));
        }

        @Override
        protected void applyValue() {
            // No extra action needed; the value is read on Save.
        }

        public void initMessage() {
            this.updateMessage();
        }

        public double getValue() {
            return this.value;
        }
    }

    public AutoSocialConfigScreen(Screen parent) {
        super(Component.literal("AutoSocial Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int y = 40;
        int xLeft = this.width / 2 - 150;
        int fieldW = 300;
        int fieldH = 20;
        AutoSocialLogic.ConfigSnapshot snap = AutoSocialLogic.getConfigSnapshot();

        this.modelField = new LabeledEditBox(this.font, xLeft, y, fieldW, fieldH, Component.literal("Model"), Component.literal("Model"));
        this.modelField.setValue(snap.model());
        this.modelField.setMaxLength(1000);
        this.addRenderableWidget(this.modelField);
        y += 24;

        this.aiNameField = new LabeledEditBox(this.font, xLeft, y, fieldW, fieldH, Component.literal("AI Name"), Component.literal("AI Name"));
        this.aiNameField.setValue(snap.aiName());
        this.aiNameField.setMaxLength(1000);
        this.addRenderableWidget(this.aiNameField);
        y += 24;

        this.triggerField = new LabeledEditBox(this.font, xLeft, y, fieldW, fieldH, Component.literal("Trigger"), Component.literal("Trigger"));
        this.triggerField.setValue(snap.trigger());
        this.triggerField.setMaxLength(1000);
        this.addRenderableWidget(this.triggerField);
        y += 24;

        this.ytDlpPathField = new LabeledEditBox(this.font, xLeft, y, fieldW, fieldH, Component.literal("yt-dlp path"), Component.literal("yt-dlp path"));
        this.ytDlpPathField.setMaxLength(1000);
        this.ytDlpPathField.setValue(snap.ytDlpPath() == null ? "" : snap.ytDlpPath());
        this.addRenderableWidget(this.ytDlpPathField);
        y += 24;

        this.temperatureField = new LabeledEditBox(this.font, xLeft, y, fieldW, fieldH,
                Component.literal("Temperature (0-2)"), Component.literal("Temperature (0-2)"));
        this.temperatureField.setValue(Double.toString(snap.temperature()));
        this.addRenderableWidget(this.temperatureField);
        y += 24;

        this.volumeSlider = new VolumeSlider(xLeft, y, fieldW, fieldH,
                Component.literal("Volume: "), snap.volume());
        this.volumeSlider.initMessage();
        this.addRenderableWidget(this.volumeSlider);
        y += 28;

        this.sysPromptField = new LabeledEditBox(this.font, xLeft, y, fieldW, fieldH,
                Component.literal("Sys Prompt (single line)"), Component.literal("Sys Prompt (single line)"));
        this.sysPromptField.setMaxLength(1000000);
        this.sysPromptField.setValue(snap.sysPrompt().replace('\n', ' '));
        this.addRenderableWidget(this.sysPromptField);
        y += 24;

        this.verboseCheck = Checkbox.builder(Component.literal("Verbose logs"), this.font)
                .pos(xLeft, y)
                .selected(snap.verbose())
                .build();
        this.addRenderableWidget(this.verboseCheck);
        y += 28;

        this.botPrefixCheck = Checkbox.builder(Component.literal("Add 'IAMAB0T' to messages"), this.font)
                .pos(xLeft, y)
                .selected(snap.botPrefix())
                .build();
        this.addRenderableWidget(this.botPrefixCheck);
        y += 28;

        this.TTSon = Checkbox.builder(Component.literal("TTS"), this.font)
                .pos(xLeft, y)
                .selected(snap.tts())
                .build();
        this.addRenderableWidget(this.TTSon);
        y += 28;

        this.ttsProviderButton = CycleButton.builder(provider -> Component.literal("TTS Provider: " + provider.getDisplayName()), snap.ttsProvider())
                .withValues(TTSProvider.values())
                .create(xLeft, y, fieldW, fieldH, Component.literal("TTS Provider"));
        this.addRenderableWidget(this.ttsProviderButton);
        y += 28;

        this.elevenlabsApiKeyField = new LabeledEditBox(this.font, xLeft, y, fieldW, fieldH,
                Component.literal("ElevenLabs API Key"), Component.literal("ElevenLabs API Key"));
        this.elevenlabsApiKeyField.setMaxLength(1000);
        this.elevenlabsApiKeyField.setValue(snap.elevenlabsApiKey() == null ? "" : snap.elevenlabsApiKey());
        this.addRenderableWidget(this.elevenlabsApiKeyField);
        y += 24;

        this.elevenlabsVoiceIdField = new LabeledEditBox(this.font, xLeft, y, fieldW, fieldH,
                Component.literal("ElevenLabs Voice ID"), Component.literal("ElevenLabs Voice ID"));
        this.elevenlabsVoiceIdField.setMaxLength(200);
        this.elevenlabsVoiceIdField.setValue(snap.elevenlabsVoiceId() == null ? "" : snap.elevenlabsVoiceId());
        this.addRenderableWidget(this.elevenlabsVoiceIdField);
        y += 24;

        this.providerButton = CycleButton.<AutoSocialLogic.AIProvider>builder(provider -> Component.literal("AI Provider: " + provider.getDisplayName()), snap.aiProvider())
                .withValues(AutoSocialLogic.AIProvider.values())
                .create(xLeft, y, fieldW, fieldH, Component.literal("AI Provider"));
        this.addRenderableWidget(this.providerButton);
        y += 28;

        this.openaiApiKeyField = new LabeledEditBox(this.font, xLeft, y, fieldW, fieldH,
                Component.literal("OpenAI API Key"), Component.literal("OpenAI API Key"));
        this.openaiApiKeyField.setMaxLength(1000);
        this.openaiApiKeyField.setValue(snap.openaiApiKey() == null ? "" : snap.openaiApiKey());
        this.addRenderableWidget(this.openaiApiKeyField);
        y += 24;

        int btnW = 98;
        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> onSave())
                .pos(xLeft, y)
                .size(btnW, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Reload"), b -> onReload())
                .pos(xLeft + btnW + 4, y)
                .size(btnW, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onCancel())
                .pos(xLeft + (btnW + 4) * 2, y)
                .size(btnW, 20)
                .build());
    }

    private void onSave() {
        String model = this.modelField.getValue().trim();
        String aiName = this.aiNameField.getValue().trim();
        String trigger = this.triggerField.getValue().trim();
        String yt = this.ytDlpPathField.getValue().trim();
        boolean verbose = this.verboseCheck.selected();
        boolean botPrefix = this.botPrefixCheck.selected();
        String sys = this.sysPromptField.getValue();
        double temp = 0.7;
        boolean tts = this.TTSon.selected();
        AutoSocialLogic.AIProvider provider = this.providerButton.getValue();
        String apiKey = this.openaiApiKeyField.getValue().trim();
        TTSProvider ttsProvider = this.ttsProviderButton.getValue();
        String elevenlabsApiKey = this.elevenlabsApiKeyField.getValue().trim();
        String elevenlabsVoiceId = this.elevenlabsVoiceIdField.getValue().trim();
        try {
            temp = Double.parseDouble(this.temperatureField.getValue().trim());
        } catch (Exception ignored) {
        }
        double vol = this.volumeSlider.getValue();
        AutoSocialLogic.ConfigSnapshot s = new AutoSocialLogic.ConfigSnapshot(
                model, aiName, trigger, yt, temp, vol, verbose, tts, sys, provider, apiKey,
                ttsProvider, elevenlabsApiKey, elevenlabsVoiceId, botPrefix);
        AutoSocialLogic.applyAndSaveConfig(s);
        this.onClose();
    }

    private void onReload() {
        boolean ok = AutoSocialLogic.reloadConfig();
        if (Minecraft.getInstance().player != null) {
            if (AutoSocialLogic.isBotPrefix()) {
                Minecraft.getInstance().player.connection.sendChat("IAMAB0T[AI]: reload -> " + (ok ? "OK" : "FAILED") + ", model=" + AutoSocialLogic.getModelSafe());
            } else {
                Minecraft.getInstance().player.connection.sendChat("[AI]: reload -> " + (ok ? "OK" : "FAILED") + ", model=" + AutoSocialLogic.getModelSafe());
            }
        }
        this.onClose();
    }

    private void onCancel() {
        this.onClose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }
}
