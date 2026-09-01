package com.jbm11208.autosocial;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import com.jbm11208.autosocial.tts.TTSClient;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

public class AutoSocialLogic {


    // AI Provider enum
    public enum AIProvider {
        OLLAMA("Ollama"),
        OPENAI("OpenAI"),
        GEMINI("Gemini");

        private final String displayName;

        AIProvider(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static volatile javax.sound.sampled.SourceDataLine CURRENT_LINE = null;
    private static volatile boolean SKIP_REQUESTED = false;
    // Track recently sent messages to prevent responding to our own messages
    private static final Set<String> RECENT_SENT_MESSAGES = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final File WARIO_SFX_DIR = resolveDir("AUTOSOCIAL_WARIO_DIR", new File(System.getProperty("user.home"), "Documents/AutoSocial/Wario"));
    private static final File TEMP_AUDIO_DIR = resolveDir("AUTOSOCIAL_TEMP_AUDIO_DIR", new File(System.getProperty("user.home"), "Documents/AutoSocial/TempAudio"));
    private static final String AUDIO_DEVICE_NAME = System.getenv().getOrDefault("AUTOSOCIAL_AUDIO_DEVICE", "CABLE Input (VB-Audio Virtual Cable)");
    private static volatile double AUDIO_VOLUME = parseDoubleEnv("AUTOSOCIAL_VOLUME", 0.2);

    // TTS-related configuration
    private static volatile TTSClient.TTSProvider TTS_PROVIDER = TTSClient.TTSProvider.CURRENT;
    private static volatile String ELEVENLABS_API_KEY = "";
    private static volatile String ELEVENLABS_VOICE_ID = "";
    private static volatile boolean BOT_PREFIX = !"false".equalsIgnoreCase(System.getenv().getOrDefault("AUTOSOCIAL_BOT_PREFIX", "true"));
    private static volatile boolean TTS = !"false".equalsIgnoreCase(System.getenv().getOrDefault("AUTOSOCIAL_TTS", "true"));

    // Verbose logging toggle (can be overridden in config.yml). Defaults to env AUTOSOCIAL_VERBOSE or true.
    private static volatile boolean VERBOSE = !"false".equalsIgnoreCase(System.getenv().getOrDefault("AUTOSOCIAL_VERBOSE", "true"));

    public static volatile List<String> PlayerList = null;

    public static Semaphore screenshotLatch = new Semaphore(0); // Start with 0 permits
    private static boolean waitingForScreenshotPrompt = false;
    private static String pendingScreenshotPrompt = null;
    // AI-related configuration
    private static volatile AIProvider AI_PROVIDER = AIProvider.OLLAMA;
    private static volatile String OPENAI_API_KEY = "";
    private static volatile String GEMINI_API_KEY = "";
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final boolean DISABLE_THINKING = !"false".equalsIgnoreCase(System.getenv().getOrDefault("AUTOSOCIAL_DISABLE_THINKING", "true"));
    // Ollama local endpoint. Ensure Ollama is running (default: http://localhost:11434).
    // You can override base URL via env var OLLAMA_BASE_URL or OLLAMA_HOST (e.g., http://127.0.0.1:11434).
    private static final String DEFAULT_OLLAMA_BASE = "http://localhost:11434";
    private static final String API_URL = getOllamaBase() + "/api/chat";
    private static volatile String MODEL = "gpt-oss:20b-cloud";
    private static volatile String SYS_PROMPT = "You are Wario. You say WAAHHHH!! a lot. You can play any video you want by putting a phrase in curly brackets {}. only include the video title in curly brackets (Ex: {Mario Kart Wii - Title Theme}). You must specify a specific sound track and not just say the name of a game, as that normally does not play a song from that game. You are in a minecraft server owned by famous youtuber DougDoug, and some rando named MessyPrincy (AKA The Entity) buys peoples souls on the server in exchange for goods and services or something. DougDoug is bald, bad at 2d platformers, and evades taxes and stuff. Your nemesis is BeeGPT, who is annoyingly nice. You live in Big Homey, which is the same town BeeGPT lives in. BeeGPTs father, Cameron7108, is the \"mayor\" of Big Homey (he acts more like a dictator and refuses to hold elections). Currently, our mortal enemy is the town of DarthFarmers (AKA humblefarmers AKA the empire AKA republic) who live in a volcano and do unspeakable acts of evil (even by your standards). Currently, there are 3 big homey residents that broke the server rules and got permabanned: erderblade, dectahale, and kinghomer9. Player _Diney_ was banished from Big Homey for terrorizing the citizens (they are also leader of DarthFarmers). All the other towns talk about nuking the town of BumVille, so make sure to occasionally mention how you want to nuke BumVille. Keep your responses to 3 sentences or less. Here is your previous conversations, with User Question: being a question from a user, and Your Response: being what you responded to the question.";
    // Configurable AI display name and trigger word (loaded from config.yml)
    private static volatile String AI_NAME = "Wario";
    private static volatile String TRIGGER = "wario"; // case-insensitive trigger word
    private static final int MEMORY_LIMIT = 15; // number of alternating lines to remember

    private static final Deque<String> memory = new ArrayDeque<>();
    private static volatile boolean responding = false;
    private static volatile boolean initialized = false;

    // Config file support (stored under the instance's config dir): config/autosocial/autosocial.yml
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("autosocial").resolve("autosocial.yml").toFile();

    // Token/context limits
    private static final int NUM_PREDICT = parseIntEnv("AUTOSOCIAL_NUM_PREDICT", 1024);
    private static final int NUM_CTX = parseIntEnv("AUTOSOCIAL_NUM_CTX", 8192);
    private static volatile double TEMPERATURE = parseDoubleEnv("AUTOSOCIAL_TEMPERATURE", 0.7);

    // External tool availability
    private static final boolean FFMPEG_AVAILABLE = detectFfmpeg();
    // yt-dlp is detected lazily to respect PATH changes without restarting the game
    private static volatile List<String> YTDLP_CMD = null;
    // Configurable yt-dlp executable path (can be overridden in config.yml). Empty means: probe PATH.
    private static volatile String YTDLP_PATH = "";
    private static volatile String PLAYERBLACKLIST = "";

    private static synchronized boolean ensureYtDlp() {
        if (YTDLP_CMD == null) {
            YTDLP_CMD = detectYtDlp();
        }
        return YTDLP_CMD != null;
    }

    private static synchronized void clearYtDlpCache() {
        YTDLP_CMD = null;
    }

    public static boolean isVerbose() {
        return VERBOSE;
    }

    public static boolean isBotPrefix() {
        return BOT_PREFIX;
    }

    public static boolean isTTS() {
        return TTS;
    }

    private static volatile String LAST_YT_TITLE = null;
    private static volatile String CURRENT_YT_TITLE = null;

    public static String getCurrentPlayingTitle() {
        return CURRENT_YT_TITLE;
    }

    // Snapshot for GUI/editing
    public record ConfigSnapshot(String model, String aiName, String trigger, String ytDlpPath, String playerBlacklist,
                                 double temperature, double volume, boolean verbose, boolean tts, String sysPrompt,
                                 AIProvider aiProvider, String openaiApiKey, TTSClient.TTSProvider ttsProvider,
                                 String elevenlabsApiKey, String elevenlabsVoiceId, boolean botPrefix) {
        public ConfigSnapshot(String model, String aiName, String trigger, String ytDlpPath, String playerBlacklist,
                              double temperature, double volume, boolean verbose, boolean tts, String sysPrompt,
                              AIProvider aiProvider, String openaiApiKey, TTSClient.TTSProvider ttsProvider,
                              String elevenlabsApiKey, String elevenlabsVoiceId, boolean botPrefix) {
            this.model = model;
            this.aiName = aiName;
            this.trigger = trigger;
            this.ytDlpPath = ytDlpPath;
            this.playerBlacklist = playerBlacklist;
            this.temperature = temperature;
            this.volume = volume;
            this.verbose = verbose;
            this.tts = tts;
            this.sysPrompt = sysPrompt == null ? "" : sysPrompt;
            this.aiProvider = aiProvider == null ? AIProvider.OLLAMA : aiProvider;
            this.openaiApiKey = openaiApiKey == null ? "" : openaiApiKey;
            this.ttsProvider = ttsProvider;
            this.elevenlabsApiKey = elevenlabsApiKey;
            this.elevenlabsVoiceId = elevenlabsVoiceId;
            this.botPrefix = botPrefix;
        }
    }

    public static ConfigSnapshot getConfigSnapshot() {
        return new ConfigSnapshot(MODEL, AI_NAME, TRIGGER, YTDLP_PATH, PLAYERBLACKLIST, TEMPERATURE, AUDIO_VOLUME, VERBOSE, TTS, SYS_PROMPT,
                AI_PROVIDER, OPENAI_API_KEY, TTS_PROVIDER, ELEVENLABS_API_KEY, ELEVENLABS_VOICE_ID, BOT_PREFIX);
    }

    public static void applyAndSaveConfig(ConfigSnapshot s) {
        try {
            // Update in‑memory first
            if (s.model() != null && !s.model().isBlank()) MODEL = s.model().trim();
            if (s.aiName() != null && !s.aiName().isBlank()) AI_NAME = s.aiName().trim();
            if (s.trigger() != null && !s.trigger().isBlank()) TRIGGER = s.trigger().trim();
            YTDLP_PATH = s.ytDlpPath() == null ? "" : s.ytDlpPath().trim();
            if (s.playerBlacklist() != null) PLAYERBLACKLIST = s.playerBlacklist().trim();
            TEMPERATURE = s.temperature();
            AUDIO_VOLUME = s.volume();
            VERBOSE = s.verbose();
            TTS = s.tts();
            if (s.aiProvider() != null) AI_PROVIDER = s.aiProvider();
            OPENAI_API_KEY = s.openaiApiKey() == null ? "" : s.openaiApiKey().trim();
            if (s.sysPrompt() != null && !s.sysPrompt().isBlank()) SYS_PROMPT = s.sysPrompt();
            TTS_PROVIDER = s.ttsProvider();
            ELEVENLABS_API_KEY = s.elevenlabsApiKey();
            ELEVENLABS_VOICE_ID = s.elevenlabsVoiceId();
            BOT_PREFIX = s.botPrefix();
            PlayerList = Arrays.asList(PLAYERBLACKLIST.split("\\s*,\\s*"));

            // Persist to YAML file
            if (!CONFIG_FILE.getParentFile().exists()) Files.createDirectories(CONFIG_FILE.getParentFile().toPath());
            String nl = System.lineSeparator();
            StringBuilder sb = new StringBuilder();
            sb.append("# AutoSocial configuration").append(nl);
            sb.append("model: ").append(MODEL).append(nl);
            sb.append("yt_dlp_path: ").append((YTDLP_PATH == null ? "" : YTDLP_PATH.replace("\\", "/"))).append(nl);
            sb.append("player_blacklist: ").append(PLAYERBLACKLIST).append(nl);
            sb.append("ai_name: ").append(AI_NAME).append(nl);
            sb.append("trigger: ").append(TRIGGER).append(nl);
            sb.append("temperature: ").append(TEMPERATURE).append(nl);
            sb.append("volume: ").append(AUDIO_VOLUME).append(nl);
            sb.append("verbose: ").append(VERBOSE ? "true" : "false").append(nl);
            sb.append("tts: ").append(TTS ? "true" : "false").append(nl);   // <-- write TTS option
            sb.append("ai_provider: ").append(AI_PROVIDER.name()).append(nl);   // <-- write AI provider
            sb.append("openai_api_key: ").append(OPENAI_API_KEY).append(nl);   // <-- write OpenAI API key
            sb.append("tts_provider: ").append(TTS_PROVIDER).append(nl);
            sb.append("elevenlabs_api_key: ").append(ELEVENLABS_API_KEY).append(nl);
            sb.append("elevenlabs_voice_id: ").append(ELEVENLABS_VOICE_ID).append(nl);
            sb.append("bot_prefix: ").append(BOT_PREFIX ? "true" : "false").append(nl);
            sb.append("sys_prompt: |").append(nl);
            for (String line : (SYS_PROMPT + "\n").split("\n")) {
                sb.append("  ").append(line).append(nl);
            }
            try (OutputStream os = new FileOutputStream(CONFIG_FILE);
                 Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                 BufferedWriter bw = new BufferedWriter(w)) {
                bw.write(sb.toString());
            }
            clearYtDlpCache();
            boolean y = ensureYtDlp();
            log("Config saved via GUI. yt-dlp available=" + y + (y ? (" cmd='" + String.join(" ", YTDLP_CMD) + "'") : ""));
            loadConfigInternal();
        } catch (Exception e) {
            System.out.println("[AutoSocial] Failed to save config via GUI: " + e);
        }
    }

    private static void log(String msg) {
        if (VERBOSE) System.out.println("[AutoSocial] " + msg);
    }

    private static int parseIntEnv(String key, int def) {
        try {
            String v = System.getenv(key);
            if (v == null || v.isBlank()) return def;
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double parseDoubleEnv(String key, double def) {
        try {
            String v = System.getenv(key);
            if (v == null || v.isBlank()) return def;
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean detectFfmpeg() {
        try {
            List<String> cmd = Arrays.asList("ffmpeg", "-version");
            return runProcess(cmd, 5);
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> detectYtDlp() {
        // Prefer a configured path from config.yml if present and exists
        try {
            String configured = YTDLP_PATH;
            if (configured != null && !configured.isBlank()) {
                File f = new File(configured);
                if (f.exists()) {
                    log("Using yt-dlp path from config.yml: " + f.getAbsolutePath());
                    return List.of(f.getAbsolutePath());
                } else {
                    log("Configured yt-dlp path does not exist: " + configured);
                }
            }
        } catch (Exception e) {
            log("Error reading configured yt-dlp path: " + e);
        }
        // Environment override
        try {
            String fromEnv = System.getenv("AUTOSOCIAL_YTDLP");
            if (fromEnv != null && !fromEnv.isBlank()) {
                File f = new File(fromEnv);
                if (f.exists()) {
                    log("Using yt-dlp path from AUTOSOCIAL_YTDLP: " + f.getAbsolutePath());
                    return List.of(f.getAbsolutePath());
                } else {
                    log("AUTOSOCIAL_YTDLP points to non-existent file: " + fromEnv);
                }
            }
        } catch (Exception e) {
            log("Error reading AUTOSOCIAL_YTDLP: " + e);
        }
        // Probe PATH for yt-dlp commands
        String[] candidates = new String[]{"yt-dlp", "yt-dlp.exe"};
        for (String c : candidates) {
            try {
                List<String> test = Arrays.asList(c, "--version");
                if (runProcess(test, 5)) {
                    log("Found yt-dlp on PATH: " + c);
                    return List.of(c);
                }
            } catch (Exception ignored) {
            }
        }
        log("yt-dlp not found. Set yt_dlp_path in autosocial.yml or install yt-dlp in PATH.");
        return null;
    }

    private static String getOllamaBase() {
        String base = System.getenv("OLLAMA_BASE_URL");
        if (base == null || base.isBlank()) base = System.getenv("OLLAMA_HOST");
        if (base == null || base.isBlank()) base = DEFAULT_OLLAMA_BASE;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static final Random random = new Random();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AutoSocial-Worker");
        t.setDaemon(true);
        return t;
    });

    private static boolean loadConfigInternal() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(CONFIG_FILE), StandardCharsets.UTF_8))) {
            String line;
            String model = null;
            String aiName = null;
            String trigger = null;
            String ytDlpPath = null;
            String playerBlacklist = null;
            Double temperature = null;
            Double volume = null;
            Boolean verboseOpt = null;
            Boolean ttsOpt = null;
            AIProvider providerOpt = null;
            String apiKeyOpt = null;
            StringBuilder sysPrompt = null;
            String elevenlabsVoiceId = null;
            String elevenlabsApiKey = null;
            String ttsProvider = null;
            boolean inSys = false;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                // Check if we're entering the sys_prompt block
                String trim1 = line.substring(line.indexOf(':') + 1).trim();
                if (!inSys && trimmed.toLowerCase(Locale.ROOT).startsWith("sys_prompt:")) {
                    inSys = true;
                    sysPrompt = new StringBuilder();
                    if (!trim1.isEmpty() && !trim1.equals("|")) {
                        sysPrompt.append(trim1);
                    }
                    continue;
                }

                // If we're in the sys_prompt block, collect indented lines
                if (inSys) {
                    if (line.startsWith("  ") || line.startsWith("\t")) {
                        String content = line.replaceFirst("^\\s{2}", "");
                        if (!sysPrompt.isEmpty()) sysPrompt.append("\n");
                        sysPrompt.append(content);
                    } else {
                        // A non-indented line means we've exited the sys_prompt block
                        inSys = false;
                    }
                }

                if (inSys) continue;

                // Parse other fields
                if (!line.contains(":")) continue;

                if (trimmed.toLowerCase(Locale.ROOT).startsWith("model:")) {
                    if (!trim1.isEmpty()) model = trim1;
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("ai_name:")) {
                    if (!trim1.isEmpty()) aiName = trim1;
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("trigger:")) {
                    if (!trim1.isEmpty()) trigger = trim1;
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("elevenlabs_api_key:")) {
                    if (!trim1.isEmpty()) elevenlabsApiKey = trim1;
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("elevenlabs_voice_id:")) {
                    if (!trim1.isEmpty()) elevenlabsVoiceId = trim1;
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("tts_provider:")) {
                    if (!trim1.isEmpty()) ttsProvider = trim1;
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("yt_dlp_path:")) {
                    ytDlpPath = trim1;
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("player_blacklist:")) {
                    playerBlacklist = trim1;
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("temperature:")) {
                    if (!trim1.isEmpty()) {
                        try {
                            temperature = Double.parseDouble(trim1);
                        } catch (NumberFormatException e) {
                            log("Invalid temperature in config: " + trim1);
                        }
                    }
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("volume:")) {
                    if (!trim1.isEmpty()) {
                        try {
                            volume = Double.parseDouble(trim1);
                        } catch (NumberFormatException e) {
                            log("Invalid volume in config: " + trim1);
                        }
                    }
                    continue;
                }

                boolean boolVal = !(trim1.equalsIgnoreCase("false") || trim1.equalsIgnoreCase("0") || trim1.equalsIgnoreCase("no"));
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("verbose:")) {
                    if (!trim1.isEmpty()) verboseOpt = boolVal;
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("tts:")) {
                    if (!trim1.isEmpty()) ttsOpt = boolVal;
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("ai_provider:")) {
                    if (!trim1.isEmpty()) {
                        try {
                            providerOpt = AIProvider.valueOf(trim1);
                        } catch (IllegalArgumentException e) {
                            log("Invalid AI provider in config: " + trim1 + ", using default OLLAMA");
                        }
                    }
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("openai_api_key:")) {
                    apiKeyOpt = trim1;
                }
            }

            // Apply loaded values
            if (model != null && !model.isBlank()) MODEL = model;
            if (aiName != null && !aiName.isBlank()) AI_NAME = aiName;
            if (trigger != null && !trigger.isBlank()) TRIGGER = trigger;
            if (ytDlpPath != null) YTDLP_PATH = ytDlpPath;
            if (playerBlacklist != null) PLAYERBLACKLIST = playerBlacklist;
            if (temperature != null) TEMPERATURE = temperature;
            if (volume != null) AUDIO_VOLUME = volume;
            if (verboseOpt != null) VERBOSE = verboseOpt;
            if (ttsOpt != null) TTS = ttsOpt;
            if (providerOpt != null) AI_PROVIDER = providerOpt;
            if (apiKeyOpt != null) OPENAI_API_KEY = apiKeyOpt;
            if (sysPrompt != null && !sysPrompt.isEmpty()) SYS_PROMPT = sysPrompt.toString();
            if (elevenlabsApiKey != null && !elevenlabsApiKey.isBlank()) ELEVENLABS_API_KEY = elevenlabsApiKey;
            if (elevenlabsVoiceId != null && !elevenlabsVoiceId.isBlank()) ELEVENLABS_VOICE_ID = elevenlabsVoiceId;
            if (ttsProvider != null && !ttsProvider.isBlank())
                TTS_PROVIDER = TTSClient.TTSProvider.valueOf(ttsProvider);
            PlayerList = Arrays.asList(PLAYERBLACKLIST.split("\\s*,\\s*"));
            return true;
        } catch (Exception e) {
            System.out.println("[AutoSocial] Failed to load config.yml: " + e);
            return false;
        }
    }

    public static boolean reloadConfig() {
        boolean ok = loadConfigInternal();
        // Re-detect yt-dlp using a potentially updated path
        clearYtDlpCache();
        boolean y = ensureYtDlp();
        log("After config reload: yt-dlp available=" + y + (y ? (" cmd='" + String.join(" ", YTDLP_CMD) + "'") : ""));
        return ok;
    }

    public static String getModelSafe() {
        return MODEL;
    }

    public static void init() {
        ClientReceiveMessageEvents.CHAT.register((message, signed_message, sender, params, timestamp) -> {
            try {
                onChat(message);
            } catch (BrokenBarrierException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            try {
                onChat(message);
            } catch (BrokenBarrierException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        if (initialized) return;
        initialized = true;
        // Ensure directories exist
        try {
            if (!WARIO_SFX_DIR.exists()) Files.createDirectories(WARIO_SFX_DIR.toPath());
            if (!TEMP_AUDIO_DIR.exists()) Files.createDirectories(TEMP_AUDIO_DIR.toPath());
            if (!CONFIG_FILE.getParentFile().exists()) Files.createDirectories(CONFIG_FILE.getParentFile().toPath());
            loadConfigInternal();
            log("Initialized. Verbose=" + VERBOSE + ", WARIO_SFX_DIR=" + WARIO_SFX_DIR.getAbsolutePath() + ", TEMP_AUDIO_DIR=" + TEMP_AUDIO_DIR.getAbsolutePath());
            log("Audio device preference: '" + AUDIO_DEVICE_NAME + "' volume=" + AUDIO_VOLUME);
            log("Ollama base: " + getOllamaBase() + ", model: " + MODEL);
            log("Ollama limits: num_predict=" + NUM_PREDICT + ", num_ctx=" + NUM_CTX);
            log("FFmpeg available=" + FFMPEG_AVAILABLE + " (audio features that require ffmpeg will be disabled if false)");
            boolean y = ensureYtDlp();
            log("yt-dlp available=" + y + (y ? (" cmd='" + String.join(" ", YTDLP_CMD) + "'") : ""));
            String path = System.getenv("PATH");
            if (path != null) log("PATH=" + path);
            logMixers();
        } catch (Exception e) {
            System.out.println("[AutoSocial] Failed to initialize: " + e);
        }
        // No explicit event registration here; ChatHudMixin will forward chat messages.
    }

    public static void onChat(Component messageText) throws BrokenBarrierException, InterruptedException {
        String full = messageText.getString();
        log("Chat received: " + full);
        if (full.isEmpty()) return;

        // Check if this is a message we recently sent
        if (RECENT_SENT_MESSAGES.contains(full)) {
            log("Ignoring our own recently sent message.");
            RECENT_SENT_MESSAGES.remove(full);
            return;
        }

        // Ignore messages from ourselves that contain [AI] (our bot messages)
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            String playerName = client.player.getName().getString();
            if (full.contains(playerName) && full.contains("[AI]")) {
                log("Ignoring our own bot message (from " + playerName + " containing [AI]).");
                return;
            }
        }

        // Ignore message if player username is blacklisted
        if (PlayerList != null && !PlayerList.isEmpty()) {
            for (String blockedPlayer : PlayerList) {
                if (full.contains(blockedPlayer) && blockedPlayer.length() >= 2) {
                    full = "Hello " + AI_NAME + ". A player by the name of " + blockedPlayer + " tried to send you a message, but they have been BANNED from doing so for crimes of unspeakable evil. Please shame them for their crimes (feel free to make up some sort of silly evil crime they committed to shame them about)";
                    break;
                }
            }
        }

        String lower = full.toLowerCase(Locale.ROOT);

        // Extract the part after ':' or '»' if present (player message content)
        if (waitingForScreenshotPrompt && client.player != null) {
            String playerName = client.player.getName().getString();

            // Only capture messages from the player (not other players)
            if (full.contains(playerName)) {
                String content = extractContent(full);
                if (!content.isBlank()) {
                    screenshotLatch.release(); // Release a permit to unblock waiting thread
                    pendingScreenshotPrompt = content;
                    waitingForScreenshotPrompt = false; // Stop waiting
                    log("Captured screenshot prompt: " + content);
                    return; // Don't process this as a regular chat message
                }
            }
        }

        // Clear command: dynamic based on trigger word
        String clearCmd = "clear" + TRIGGER.toLowerCase(Locale.ROOT);
        if (lower.contains(clearCmd)) {
            synchronized (memory) {
                memory.clear();
            }
            log("Memory cleared via command '" + clearCmd + "'.");
            if (BOT_PREFIX) {
                sendChat("IAMAB0T[AI] HAS BEEN KILLED!!!!!");
            } else {
                sendChat("[AI] HAS BEEN KILLED!!!!!");
            }
            return;
        }
        // Maintenance command: reloadconfig -> reload config.yml at runtime
        if (lower.contains("reloadconfig")) {
            boolean ok = reloadConfig();
            if (BOT_PREFIX) {
                sendChat("IAMAB0T[AI]: config reload -> " + (ok ? "OK" : "FAILED") + ", model=" + MODEL);
            } else {
                sendChat("[AI]: config reload -> " + (ok ? "OK" : "FAILED") + ", model=" + MODEL);
            }
            return;
        }
        // Maintenance command: reloadytdlp -> re-probe yt-dlp on PATH or via AUTOSOCIAL_YTDLP
        if (lower.contains("reloadytdlp")) {
            clearYtDlpCache();
            boolean ok = ensureYtDlp();
            if (ok) {
                log("reloadytdlp: found yt-dlp cmd='" + String.join(" ", YTDLP_CMD) + "'");
            } else {
                log("reloadytdlp: yt-dlp still not found. PATH may require game restart or set AUTOSOCIAL_YTDLP.");
            }
            String cmdStr = ok ? String.join(" ", YTDLP_CMD) : "<not found>";
            if (BOT_PREFIX) {
                sendChat("IAMAB0T[AI] " + AI_NAME + ": yt-dlp reloaded -> available=" + ok + " cmd=" + cmdStr);
            } else {
                sendChat("[AI] " + AI_NAME + ": yt-dlp reloaded -> available=" + ok + " cmd=" + cmdStr);
            }
            return;
        }

        // Ignore additional triggers while we are already generating
        if (responding && lower.contains(TRIGGER.toLowerCase(Locale.ROOT))) {
            log("Currently responding; ignoring additional trigger.");
            return;
        }

        boolean containsKeyword = lower.contains(TRIGGER.toLowerCase(Locale.ROOT));
        if (!containsKeyword) {
            log("No trigger keyword found in chat line.");
            return;
        }

        String content = extractContent(full);
        log("Extracted content: " + content);
        if (content.isEmpty()) return;

        // Extract the part after ':' or '»' if present (player message content)
        if (waitingForScreenshotPrompt && client.player != null) {
            String playerName = client.player.getName().getString();

            // Only capture messages from the player (not other players)
            if (full.contains(playerName)) {
                if (!content.isBlank()) {
                    screenshotLatch.release(); // Release a permit to unblock waiting thread
                    pendingScreenshotPrompt = content;
                    waitingForScreenshotPrompt = false; // Stop waiting
                    log("Captured screenshot prompt: " + content);
                    return; // Don't process this as a regular chat message
                }
            }
        }
        log("Extracted content: " + content);

        if (responding) return; // avoid overlapping generations
        responding = true;
        log("Submitting AI generation task for content length=" + content.length());
        EXECUTOR.submit(() -> {
            try {
                String response = generateResponse(content);
                if (response != null && !response.isBlank()) {
                    pushMemory("User Question: " + content);
                    pushMemory("Your Response: " + response);
                }

                // Parse response for audio tokens {.} and build segments for interleaved audio
                ParsedResponse pr = parseCurlyTokens(response == null ? "" : response);
                log("Parsed response: chatTextLen=" + pr.chatText.length() + ", tokens=" + pr.tokens.size());
                if (!pr.tokens.isEmpty()) log("Tokens: " + pr.tokens);


                // Replace newlines then chunk into <=225 chars and send (only if there's text)
                if (!pr.chatText.isBlank()) {
                    String flat = pr.chatText.replace('\n', ' ');
                    List<String> parts = chunk(flat);
                    log("Sending " + parts.size() + " chat part(s).");
                    for (String part : parts) {
                        Minecraft mc = Minecraft.getInstance();
                        String toSend = BOT_PREFIX ? "IAMAB0T[AI] " + AI_NAME + ": " + part : "[AI] " + AI_NAME + ": " + part;
                        String preview = toSend.length() > 120 ? toSend.substring(0, 120) + "..." : toSend;
                        log("Queue chat send (len=" + toSend.length() + "): " + preview);
                        mc.execute(() -> sendChat(toSend));
                    }
                } else {
                    log("No chat text to send (possibly tokens-only response).");
                }

                // Interleaved audio behavior: play random sound clips every 2-3 words in text (or use TTS if enabled), and handle {token}
                boolean ttsEnabled = isTTS();
                log("TTS enabled: " + ttsEnabled);
                if (ttsEnabled) {
                    log("Calling playTTSInterleaved with response length: " + (response == null ? 0 : response.length()));
                    playTTSInterleaved(response == null ? "" : response);
                } else {
                    log("Calling playAudioInterleaved (TTS disabled)");
                    playAudioInterleaved(response == null ? "" : response);
                }
            } finally {
                responding = false;
                log("AI generation task complete.");
            }
        });
    }

    private static String extractContent(String full) {
        String work = full;
        int idxR = work.indexOf("]");
        if (idxR >= 0 && idxR + 1 < work.length()) {
            work = work.substring(idxR + 1).trim();
        }
        int arrow = work.indexOf('»');
        if (arrow >= 0 && arrow + 1 < work.length()) {
            return work.substring(arrow + 1).trim();
        }
        int colon = work.indexOf(':');
        if (colon >= 0 && colon + 1 < work.length()) {
            return work.substring(colon + 1).trim();
        }
        return work;
    }

    private static void pushMemory(String line) {
        synchronized (memory) {
            memory.addLast(line);
            while (memory.size() > MEMORY_LIMIT) memory.removeFirst();
            log("Memory push (size=" + memory.size() + "): " + (line.length() > 140 ? line.substring(0, 140) + "..." : line));
        }
    }

    private static List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        // Ensure we don't exceed packet limits
        String safeText = sanitizeMessage(text);
        if (safeText.isEmpty()) return out;

        int i = 0;
        while (i < safeText.length()) {
            // Use a conservative limit to avoid packet issues
            int maxChunkSize = 200;  // Reduced from 225 for safety
            int end = Math.min(i + maxChunkSize, safeText.length());

            if (end < safeText.length()) {
                int lastSpace = safeText.lastIndexOf(' ', end);
                if (lastSpace <= i) lastSpace = end;
                String chunk = safeText.substring(i, lastSpace).trim();
                if (!chunk.isEmpty()) {
                    out.add(chunk);
                }
                i = lastSpace + 1;
            } else {
                String finalChunk = safeText.substring(i).trim();
                if (!finalChunk.isEmpty()) {
                    out.add(finalChunk);
                }
                break;
            }
        }
        return out;
    }

    private static File resolveDir(String envVar, File fallback) {
        String env = System.getenv(envVar);
        return (env != null && !env.isBlank()) ? new File(env) : fallback;
    }

    private record ParsedResponse(String chatText, List<String> tokens) {
    }

    private static ParsedResponse parseCurlyTokens(String response) {
        // Remove curly-brace sections from chat text, but collect tokens inside {}
        Pattern p = Pattern.compile("\\{([^}]+)}");
        Matcher m = p.matcher(response);
        List<String> tokens = new ArrayList<>();
        StringBuilder chat = new StringBuilder();
        while (m.find()) {
            tokens.add(m.group(1));
            m.appendReplacement(chat, "");
        }
        m.appendTail(chat);
        return new ParsedResponse(chat.toString().trim(), tokens);
    }

    private static boolean isYouTubeUrl(String s) {
        String yt = "(?i)^(https?://)?(www\\.)?(youtube\\.com|youtu\\.be)/.*$";
        return s != null && s.matches(yt);
    }

    private static String normalizeShorts(String urlOrId) {
        try {
            Pattern shorts = Pattern.compile("https?://(www\\.)?youtube\\.com/shorts/([\\w\\-]+)", Pattern.CASE_INSENSITIVE);
            Matcher m = shorts.matcher(urlOrId);
            if (m.matches()) {
                return "https://www.youtube.com/watch?v=" + m.group(2);
            }
        } catch (Exception ignored) {
        }
        return urlOrId;
    }

    private static @NotNull Thread getThread(Process p, String[] titleHolder, String[] producedPathHolder) {
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log("[yt-dlp] " + line);
                    String t = line.trim();
                    if (t.startsWith("AUTOSOCIALTITLE:")) {
                        titleHolder[0] = t.substring("AUTOSOCIALTITLE:".length()).trim();
                    } else if (t.startsWith("AUTOSOCIALFILE:")) {
                        String path = t.substring("AUTOSOCIALFILE:".length()).trim();
                        File cand = new File(path);
                        if (!cand.isAbsolute()) cand = new File(TEMP_AUDIO_DIR, path);
                        if (cand.exists()) producedPathHolder[0] = cand.getAbsolutePath();
                    }
                }
            } catch (Exception e) {
                log("yt-dlp reader error: " + e);
            }
        }, "yt-dlp-wav-reader");
        reader.setDaemon(true);
        return reader;
    }

    private static void handleAudioToken(String token) {
        try {
            // 1) Try local sfx in WARIO_SFX_DIR: prefer WAV, then MP3 (convert), then OGG
            File wav = new File(WARIO_SFX_DIR, token + ".wav");
            if (wav.exists()) {
                log("Token {" + token + "}: using local WAV '" + wav.getName() + "'");
                playWav(wav);
                return;
            }
            File mp3 = new File(WARIO_SFX_DIR, token + ".mp3");
            if (mp3.exists()) {
                if (FFMPEG_AVAILABLE) {
                    log("Token {" + token + "}: transcoding local MP3 '" + mp3.getName() + "' to WAV");
                    File w = transcodeMp3ToWav(mp3);
                    if (w != null) {
                        playWav(w);
                        return;
                    }
                } else {
                    log("Token {" + token + "}: MP3 found but ffmpeg is not available. Please provide a WAV file instead.");
                }
            }
            File ogg = new File(WARIO_SFX_DIR, token + ".ogg");
            if (ogg.exists()) {
                log("Token {" + token + "}: playing local OGG via system '" + ogg.getName() + "'");
                playWithSystem(ogg);
                return;
            }

            // 2) If the token looks like a YouTube URL or search phrase, use yt-dlp to download
            String query = token.trim();
            if (!query.isEmpty()) {
                log("Token {" + token + "}: attempting yt-dlp download as WAV");
                File dl = downloadToWav(query);
                if (dl != null) {
                    // Set current playing title for HUD while this track plays
                    String prev = CURRENT_YT_TITLE;
                    CURRENT_YT_TITLE = LAST_YT_TITLE;
                    try {
                        playWav(dl);
                    } finally {
                        CURRENT_YT_TITLE = prev; // restore previous (usually null) after playback completes
                        // Delete downloaded temp file when done playing
                        try {
                            if (dl.exists()) {
                                // Only delete files within TEMP_AUDIO_DIR for safety
                                File parent = dl.getParentFile();
                                if (parent != null && parent.getAbsolutePath().equals(TEMP_AUDIO_DIR.getAbsolutePath())) {
                                    boolean del = dl.delete();
                                    log("Deleted temp audio file '" + dl.getName() + "' -> " + del);
                                }
                            }
                        } catch (Exception e) {
                            log("Failed to delete temp audio file: " + e);
                        }
                    }
                } else {
                    log("Token {" + token + "}: yt-dlp download failed.");
                }
            }
        } catch (Exception e) {
            System.out.println("[AutoSocial] handleAudioToken error for {" + token + "}: " + e);
        }
    }

    private static void logMixers() {
        try {
            Mixer.Info[] infos = AudioSystem.getMixerInfo();
            log("System mixers (" + infos.length + "):");
            for (int i = 0; i < infos.length; i++) {
                Mixer.Info info = infos[i];
                Mixer m = AudioSystem.getMixer(info);
                Line.Info[] src = m.getSourceLineInfo();
                Line.Info[] tgt = m.getTargetLineInfo();
                log("  [" + i + "] name='" + info.getName() + "' desc='" + info.getDescription() + "' vendor='" + info.getVendor() + "' ver='" + info.getVersion() + "' srcLines=" + src.length + " tgtLines=" + tgt.length);
            }
        } catch (Exception e) {
            log("Mixer list error: " + e);
        }
    }

    private static Mixer findMixerByName() {
        try {
            Mixer.Info best = null;
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                String nm = info.getName();
                String desc = info.getDescription();
                if (nm.equalsIgnoreCase(AutoSocialLogic.AUDIO_DEVICE_NAME) || desc.toLowerCase(Locale.ROOT).contains(AutoSocialLogic.AUDIO_DEVICE_NAME.toLowerCase(Locale.ROOT))) {
                    best = info;
                    break;
                }
            }
            if (best != null) {
                Mixer m = AudioSystem.getMixer(best);
                boolean supportsSource = false;
                for (Line.Info li : m.getSourceLineInfo()) {
                    if (SourceDataLine.class.isAssignableFrom(li.getLineClass())) {
                        supportsSource = true;
                        break;
                    }
                }
                log("Selected mixer: name='" + best.getName() + "' desc='" + best.getDescription() + "' supportsSource=" + supportsSource);
                return m;
            }
            log("No matching mixer found for '" + AutoSocialLogic.AUDIO_DEVICE_NAME + "'. Using system default.");
        } catch (Exception e) {
            log("Mixer enumeration error: " + e);
        }
        return null;
    }

    private static void playWav(File wav) {
        if (wav == null || !wav.exists()) return;
        // Reset skip flag at the start of each playback to avoid carrying over from previous clip
        SKIP_REQUESTED = false;
        log("Play WAV: " + wav.getAbsolutePath());
        Mixer mixer = findMixerByName();
        try (AudioInputStream aisOrig = AudioSystem.getAudioInputStream(wav)) {
            AudioFormat base = aisOrig.getFormat();
            AudioFormat decoded = base;
            AudioInputStream ais = aisOrig;
            // Ensure PCM_SIGNED 16-bit for SourceDataLine
            if (base.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                decoded = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                        (int) base.getSampleRate(),
                        16,
                        base.getChannels(),
                        base.getChannels() * 2,
                        (int) base.getSampleRate(),
                        false);
                ais = AudioSystem.getAudioInputStream(decoded, aisOrig);
                log("Converted audio to PCM_SIGNED 16-bit @" + (int) base.getSampleRate() + "Hz channels=" + base.getChannels());
            }

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, decoded);
            SourceDataLine line = (SourceDataLine) ((mixer != null) ? mixer.getLine(info) : AudioSystem.getLine(info));
            line.open(decoded);
            CURRENT_LINE = line;
            // Volume control if supported
            try {
                FloatControl vol = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                double volLinear = Math.max(0.0001, Math.min(1.0, AUDIO_VOLUME));
                float min = vol.getMinimum();
                float max = vol.getMaximum();
                // Convert linear 0..1 to dB range (approx)
                float db = (float) (Math.log10(volLinear) * 20.0);
                db = Math.max(min, Math.min(max, db));
                vol.setValue(db);
                log("Applied volume (dB): " + db + " [min=" + min + ", max=" + max + "]");
            } catch (Exception ignored) {
                // Some mixers don't expose MASTER_GAIN; ignore
            }
            line.start();

            byte[] buffer = new byte[4096];
            int n;
            long total = 0;
            boolean skipped = false;
            while ((n = ais.read(buffer, 0, buffer.length)) != -1) {
                // Check for skip
                if (SKIP_REQUESTED) {
                    log("Skip signal received during playback; stopping current audio.");
                    skipped = true;
                    SKIP_REQUESTED = false;
                    break;
                }
                int written = line.write(buffer, 0, n);
                total += written;
            }
            if (!skipped) {
                line.drain();
            }
            try {
                line.stop();
            } catch (Exception ignored) {
            }
            try {
                line.flush();
            } catch (Exception ignored) {
            }
            try {
                line.close();
            } catch (Exception ignored) {
            }
            CURRENT_LINE = null;
            ais.close();
            log((skipped ? "Skipped WAV: " : "Finished WAV: ") + wav.getName() + (skipped ? "" : (" bytesWritten=" + total)));
        } catch (UnsupportedAudioFileException e) {
            System.out.println("[AutoSocial] Unsupported WAV: " + e);
        } catch (Exception e) {
            System.out.println("[AutoSocial] WAV playback error: " + e);
        } finally {
            CURRENT_LINE = null;
        }
    }

    public static void skipCurrentAudio() {
        SKIP_REQUESTED = true;
        SourceDataLine line = CURRENT_LINE;
        log("Skip requested by user. Current line=" + (line != null));
        if (line != null) {
            try {
                line.stop();
            } catch (Exception ignored) {
            }
            try {
                line.flush();
            } catch (Exception ignored) {
            }
            try {
                line.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void playWithSystem(File file) {
        // Fallback for non-wav formats: try to invoke default system handler (may not route to VB-Cable)
        try {
            log("Open with system: " + file.getAbsolutePath());
            new ProcessBuilder("cmd", "/c", "start", "", file.getAbsolutePath()).start();
        } catch (Exception e) {
            log("System open error: " + e);
        }
    }

    private static File transcodeMp3ToWav(File mp3) {
        try {
            if (!TEMP_AUDIO_DIR.exists()) Files.createDirectories(TEMP_AUDIO_DIR.toPath());
            File out = new File(TEMP_AUDIO_DIR, mp3.getName().replaceAll("(?i)\\.mp3$", "") + "_tmp.wav");
            List<String> cmd = Arrays.asList("ffmpeg", "-y", "-i", mp3.getAbsolutePath(), out.getAbsolutePath());
            if (runProcess(cmd, 120)) return out;
        } catch (Exception e) {
            System.out.println("[AutoSocial] ffmpeg transcode error: " + e);
        }
        return null;
    }

    private static File downloadToWav(String queryOrUrl) {
        if (!ensureYtDlp()) {
            log("yt-dlp not available. Set AUTOSOCIAL_YTDLP or install yt-dlp.");
            return null;
        }
        String target = isYouTubeUrl(queryOrUrl) ? normalizeShorts(queryOrUrl) : ("ytsearch1:" + queryOrUrl);
        try {
            if (!TEMP_AUDIO_DIR.exists()) Files.createDirectories(TEMP_AUDIO_DIR.toPath());
        } catch (Exception e) {
            log("Failed to create temp audio directory: " + e);
        }
        // Use a base prefix and let yt-dlp decide extension; then locate the produced file.
        String base = "yt_" + System.nanoTime();
        File basePath = new File(TEMP_AUDIO_DIR, base);
        String outputTemplate = basePath.getAbsolutePath() + ".%(ext)s";
        List<String> cmd = new ArrayList<>(YTDLP_CMD);
        cmd.addAll(Arrays.asList(
                "-f", "bestaudio/best",
                "--no-playlist",
                "--no-progress",
                "-q",
                "-x",
                "--audio-format", "wav",
                "--print", "AUTOSOCIALTITLE:%(title)s",
                "--print", "after_move:AUTOSOCIALFILE:%(filepath)s",
                "-o", outputTemplate,
                target
        ));
        log("Running yt-dlp (wav) target='" + target + "' -> template " + outputTemplate);
        // Capture title by reading stdout; also writes the file(s)
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(TEMP_AUDIO_DIR);
        try {
            long start = System.currentTimeMillis();
            log("yt-dlp working dir: " + TEMP_AUDIO_DIR.getAbsolutePath());
            Process p = pb.start();
            final String[] titleHolder = new String[1];
            final String[] producedPathHolder = new String[1];
            Thread reader = getThread(p, titleHolder, producedPathHolder);
            reader.start();
            boolean finished = p.waitFor(240, TimeUnit.SECONDS);
            long dur = System.currentTimeMillis() - start;
            if (!finished) {
                p.destroyForcibly();
                log("yt-dlp timed out after " + dur + "ms");
                return null;
            }
            if (titleHolder[0] != null && !titleHolder[0].isBlank()) {
                LAST_YT_TITLE = titleHolder[0];
                log("Captured YouTube title: " + LAST_YT_TITLE);
            }
            log("yt-dlp exit=" + p.exitValue() + ", time=" + dur + "ms");

            // Try exact expected file first
            File expectedWav = new File(TEMP_AUDIO_DIR, base + ".wav");
            if (expectedWav.exists() && expectedWav.length() > 0) {
                log("Found expected WAV: " + expectedWav.getAbsolutePath() + " (" + expectedWav.length() + " bytes)");
                return expectedWav;
            }

            // Discover the actual output file(s) created by yt-dlp for this base prefix
            File[] produced = TEMP_AUDIO_DIR.listFiles(f -> f.isFile() && f.getName().startsWith(base + "."));
            if (produced != null && produced.length > 0) {
                // Pick the latest modified
                File best = produced[0];
                for (File f : produced) if (f.lastModified() > best.lastModified()) best = f;
                String nameLower = best.getName().toLowerCase(Locale.ROOT);
                log("yt-dlp produced file: " + best.getAbsolutePath() + " (" + best.length() + " bytes)");
                if (nameLower.endsWith(".wav")) {
                    return best;
                } else if (FFMPEG_AVAILABLE) {
                    // Transcode to wav if ffmpeg is available
                    try {
                        File outWav = new File(TEMP_AUDIO_DIR, base + "_conv.wav");
                        List<String> ff = Arrays.asList("ffmpeg", "-y", "-i", best.getAbsolutePath(), outWav.getAbsolutePath());
                        if (runProcess(ff, 120) && outWav.exists() && outWav.length() > 0) {
                            log("Transcoded to WAV via ffmpeg: " + outWav.getAbsolutePath());
                            return outWav;
                        } else {
                            log("ffmpeg transcode failed for: " + best.getName());
                        }
                    } catch (Exception e) {
                        System.out.println("[AutoSocial] ffmpeg transcode exception: " + e);
                    }
                } else {
                    log("Non-WAV file produced (" + best.getName() + ") and ffmpeg is not available. Provide ffmpeg or configure yt-dlp to output WAV.");
                }
            }

            // As a fallback, scan for the newest WAV produced in TEMP_AUDIO_DIR since this download started
            File[] wavs = TEMP_AUDIO_DIR.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".wav") && f.lastModified() >= (start - 2000));
            if (wavs != null && wavs.length > 0) {
                File newest = wavs[0];
                for (File f : wavs) if (f.lastModified() > newest.lastModified()) newest = f;
                log("Fallback picked newest WAV since start: " + newest.getAbsolutePath() + " (" + newest.length() + " bytes)");
                if (newest.exists() && newest.length() > 0) return newest;
            }

            // If mp3 was created instead (some setups ignore --audio-format), try to transcode if ffmpeg is present
            File possibleMp3 = new File(TEMP_AUDIO_DIR, base + ".mp3");
            if (possibleMp3.exists() && possibleMp3.length() > 0) {
                if (FFMPEG_AVAILABLE) {
                    File outWav = new File(TEMP_AUDIO_DIR, base + "_conv.wav");
                    List<String> ff = Arrays.asList("ffmpeg", "-y", "-i", possibleMp3.getAbsolutePath(), outWav.getAbsolutePath());
                    if (runProcess(ff, 120) && outWav.exists() && outWav.length() > 0) {
                        log("Transcoded fallback MP3 to WAV: " + outWav.getAbsolutePath());
                        return outWav;
                    }
                } else {
                    log("MP3 produced but ffmpeg not available; cannot transcode to WAV.");
                }
            }
        } catch (Exception e) {
            System.out.println("[AutoSocial] yt-dlp wav error: " + e);
        }
        log("yt-dlp WAV download failed or file missing.");
        return null;
    }

    // Cache for transcoded mp3->wav SFX
    private static final Map<File, File> MP3_WAV_CACHE = new HashMap<>();

    private enum SegmentType {TEXT, TOKEN}

    /**
     * @param value raw text or token text (without braces)
     */
    private record Segment(SegmentType type, String value) {
        @Override
        public @NotNull String toString() {
            return type + ":" + value;
        }
    }

    private static List<Segment> parseSegments(String response) {
        List<Segment> segs = new ArrayList<>();
        if (response == null) return segs;
        Matcher m = Pattern.compile("\\{([^}]+)}").matcher(response);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                String txt = response.substring(last, m.start());
                if (!txt.isBlank()) segs.add(new Segment(SegmentType.TEXT, txt));
            }
            String tok = m.group(1).trim();
            if (!tok.isEmpty()) segs.add(new Segment(SegmentType.TOKEN, tok));
            last = m.end();
        }
        if (last < response.length()) {
            String txt = response.substring(last);
            if (!txt.isBlank()) segs.add(new Segment(SegmentType.TEXT, txt));
        }
        log("parseSegments -> " + segs.size() + " segment(s)");
        return segs;
    }

    private static File getRandomWarioWav() {
        File[] wavs = WARIO_SFX_DIR.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".wav"));
        List<File> candidates = new ArrayList<>();
        if (wavs != null) candidates.addAll(Arrays.asList(wavs));
        if (candidates.isEmpty()) {
            File[] mp3s = WARIO_SFX_DIR.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".mp3"));
            if (mp3s != null && mp3s.length > 0) {
                // pick a random mp3 and transcode only if ffmpeg is available
                File pick = mp3s[random.nextInt(mp3s.length)];
                File cached = MP3_WAV_CACHE.get(pick);
                if (FFMPEG_AVAILABLE) {
                    if (cached == null || !cached.exists()) {
                        log("Transcoding MP3 SFX to WAV for random clip: " + pick.getName());
                        cached = transcodeMp3ToWav(pick);
                        if (cached != null) MP3_WAV_CACHE.put(pick, cached);
                    }
                    if (cached != null && cached.exists()) return cached;
                } else {
                    log("Random SFX mp3 '" + pick.getName() + "' cannot be played without ffmpeg. Provide WAV files for random SFX.");
                }
            }
        } else {
            return candidates.get(random.nextInt(candidates.size()));
        }
        return null;
    }

    private static void playAudioInterleaved(String response) {
        List<Segment> segs = parseSegments(response);
        int wordsUntilSfx = 2 + random.nextInt(2);
        for (Segment s : segs) {
            if (s.type == SegmentType.TEXT) {
                String[] words = s.value.trim().split("\\s+");
                for (String w : words) {
                    if (w.isEmpty()) continue;
                    wordsUntilSfx--;
                    if (wordsUntilSfx <= 0) {
                        File clip = getRandomWarioWav();
                        if (clip != null) {
                            log("Random Wario SFX after word '" + w + "': " + clip.getName());
                            playWav(clip);
                        } else {
                            log("No Wario SFX clips found in " + WARIO_SFX_DIR.getAbsolutePath());
                        }
                        wordsUntilSfx = 2 + random.nextInt(2);
                    }
                }
            } else if (s.type == SegmentType.TOKEN) {
                log("Interleaved token: {" + s.value + "}");
                handleAudioToken(s.value);
            }
        }
    }

    // helper to quickly verify WAV can be decoded by AudioSystem
    private static boolean isWavPlayable(File wavFile) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wavFile)) {
            AudioFormat fmt = ais.getFormat();
            // Accept PCM_SIGNED or PCM_UNSIGNED; any other encoding is likely unsupported
            return fmt.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                    || fmt.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED;
        } catch (Exception e) {
            // IOException / UnsupportedAudioFileException -> not playable as WAV
            return false;
        }
    }

    private static void playTTSInterleaved(String response) {
        log("[playTTSInterleaved] Starting with response: " + (response == null ? "null" : response.substring(0, Math.min(100, response.length()))));

        if (response == null || response.isEmpty()) {
            log("[playTTSInterleaved] Response is null or empty, nothing to play");
            return;
        }

        // Parse response into segments (TEXT and TOKEN) for interleaved playback
        List<Segment> segments = parseSegments(response);
        log("[playTTSInterleaved] Parsed " + segments.size() + " segments");

        if (segments.isEmpty()) {
            log("[playTTSInterleaved] No segments found, nothing to play");
            return;
        }

        // Process each segment in order: TTS for text, audio tokens for {tokens}
        int segmentIndex = 0;
        for (Segment seg : segments) {
            log("[playTTSInterleaved] Processing segment " + segmentIndex + " type=" + seg.type + " value=" +
                    (seg.value.length() > 50 ? seg.value.substring(0, 50) + "..." : seg.value));
            if (seg.type == SegmentType.TEXT) {
                // Generate and play TTS for this text segment
                String textToSpeak = seg.value.trim();
                log("[playTTSInterleaved] Text segment length after trim: " + textToSpeak.length());
                if (!textToSpeak.isEmpty()) {
                    try {
                        if (!TEMP_AUDIO_DIR.exists()) Files.createDirectories(TEMP_AUDIO_DIR.toPath());
                        log("[AutoSocial] Requesting TTS for segment " + segmentIndex + ": " +
                                (textToSpeak.length() > 50 ? textToSpeak.substring(0, 50) + "..." : textToSpeak));
                        byte[] audioData = TTSClient.requestTTS(textToSpeak, TTS_PROVIDER, ELEVENLABS_API_KEY, ELEVENLABS_VOICE_ID);
                        log("[AutoSocial] TTS response received: " + (audioData == null ? "null" : audioData.length + " bytes"));
                        if (audioData != null && audioData.length > 0) {
                            File wavFile = new File(TEMP_AUDIO_DIR, "tts_segment_" + segmentIndex + ".wav");
                            if (TTS_PROVIDER != TTSClient.TTSProvider.ELEVENLABS) {
                                try (FileOutputStream fos = new FileOutputStream(wavFile)) {
                                    fos.write(audioData);
                                }
                            } else if (TTS_PROVIDER == TTSClient.TTSProvider.ELEVENLABS) {
                                // ElevenLabs returns MP3, convert to WAV
                                try (FileOutputStream fos = new FileOutputStream(wavFile)) {
                                    fos.write(audioData);
                                }
                            }
                            if (isWavPlayable(wavFile)) {
                                log("[AutoSocial] TTS segment " + segmentIndex + " decoded as PCM WAV, playing directly.");
                                playWav(wavFile);
                            } else {
                                // Not a playable WAV → assume MP3, try ffmpeg conversion.
                                File mp3File = new File(TEMP_AUDIO_DIR, "tts_segment_" + segmentIndex + ".mp3");
                                try (FileOutputStream fos = new FileOutputStream(mp3File)) {
                                    fos.write(audioData);
                                }

                                if (FFMPEG_AVAILABLE) {
                                    File convWav = new File(TEMP_AUDIO_DIR, "tts_segment_" + segmentIndex + "_converted.wav");
                                    List<String> cmd = Arrays.asList(
                                            "ffmpeg", "-y",
                                            "-i", mp3File.getAbsolutePath(),
                                            convWav.getAbsolutePath()
                                    );
                                    log("[AutoSocial] Converting MP3 → WAV via ffmpeg: " + String.join(" ", cmd));
                                    if (runProcess(cmd, 120) && convWav.exists() && convWav.length() > 0) {
                                        playWav(convWav);
                                    } else {
                                        playWithSystem(mp3File);
                                    }
                                } else {
                                    playWithSystem(mp3File);
                                }
                            }
                        }

                    } catch (Exception e) {
                        log("[AutoSocial] TTS segment " + segmentIndex + " error: " + e.getClass().getName() + ": " + e.getMessage());
                    }
                }
            } else if (seg.type == SegmentType.TOKEN) {
                // Play audio token (yt-dlp or local file)
                String token = seg.value.trim();
                if (!token.isEmpty()) {
                    log("[AutoSocial] Playing audio token at segment " + segmentIndex + ": {" + token + "}");
                    if (ensureYtDlp()) {
                        try {
                            handleAudioToken(token);
                        } catch (Exception e) {
                            log("[AutoSocial] Error handling audio token {" + token + "}: " + e);
                        }
                    } else {
                        log("[AutoSocial] yt-dlp not found – cannot play token: {" + token + "}");
                    }
                }
            }
            segmentIndex++;
        }
    }

    private static boolean runProcess(List<String> cmd, int timeoutSec) {
        try {
            log("Run process (timeout=" + timeoutSec + "s): " + String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            long start = System.currentTimeMillis();
            Process p = pb.start();
            Thread reader = getThread(p);
            reader.start();
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            long dur = System.currentTimeMillis() - start;
            if (!finished) {
                p.destroyForcibly();
                log("Process timeout after " + dur + "ms");
                return false;
            }
            log("Process exit=" + p.exitValue() + ", time=" + dur + "ms");
            return p.exitValue() == 0;
        } catch (Exception e) {
            System.out.println("[AutoSocial] process run error: " + e);
            return false;
        }
    }

    private static @NotNull Thread getThread(Process p) {
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log("[proc] " + line);
                }
            } catch (Exception e) {
                log("proc reader error: " + e);
            }
        }, "proc-log-drainer");
        reader.setDaemon(true);
        return reader;
    }

    private static String generateResponse(String userMessage) {
        StringBuilder sys = new StringBuilder();
        sys.append(SYS_PROMPT).append(' ');
        for (String m : memory) sys.append(m).append("\n");
        sys.append("\nRemember, keep your response to 3 sentences or less. Each sentence is a maximum of 20 words. DO NOT say Your Response: or User Question:.\n");

        // Route to appropriate AI provider
        if (AI_PROVIDER == AIProvider.OPENAI) {
            return generateResponseOpenAI(userMessage, sys.toString());
        } else if (AI_PROVIDER == AIProvider.OLLAMA) {
            return generateResponseOllama(userMessage, sys.toString());
        } else {
            return generateResponseGemini(userMessage, sys.toString());
        }
    }

    private static String generateResponseOllama(String userMessage, String systemPrompt) {
        try {
            // Build Ollama chat payload (non-streaming)
            JsonObject body = new JsonObject();
            body.addProperty("model", MODEL);
            JsonArray messages = new JsonArray();

            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            messages.add(sysMsg);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userMessage);
            messages.add(userMsg);

            body.add("messages", messages);

            JsonObject options = new JsonObject();
            options.addProperty("temperature", TEMPERATURE);
            options.addProperty("num_predict", NUM_PREDICT);
            options.addProperty("num_ctx", NUM_CTX);
            // Attempt to disable chain-of-thought/thinking in compatible models
            options.addProperty("thinking", false);
            options.addProperty("include_thinking", false);
            options.addProperty("include_reasoning", false);
            body.add("options", options);
            if (DISABLE_THINKING) {
                log("Thinking disabled via options for model " + MODEL);
            }

            body.addProperty("stream", false);

            String json = body.toString();
            log("Ollama request: url=" + API_URL + ", model: " + MODEL + ", payloadBytes=" + json.getBytes(StandardCharsets.UTF_8).length);

            long start = System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            // No Authorization header for local Ollama
            conn.setDoOutput(true);

            try (OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                os.write(json);
            }

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String resp = sb.toString();
            long dur = System.currentTimeMillis() - start;
            log("Ollama response: code=" + code + ", timeMs=" + dur + ", bytes=" + resp.getBytes(StandardCharsets.UTF_8).length);

            if (code < 200 || code >= 300) {
                System.out.println("[AutoSocial] Ollama API error (" + code + "): " + resp);
                return fallbackResponse(userMessage);
            }

            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            // Try standard /api/chat shape first
            String out = null;
            JsonObject msg = root.getAsJsonObject("message");
            if (msg != null) {
                JsonElement content = msg.get("content");
                if (content != null && !content.isJsonNull()) out = content.getAsString();
                // Some Ollama models (reasoning models) return text in `thinking` with empty `content` when stream=false.
                // Only use `thinking` as a fallback if the feature is NOT disabled.
                if (!DISABLE_THINKING && (out == null || out.isBlank()) && msg.has("thinking")) {
                    try {
                        out = msg.get("thinking").getAsString();
                        log("Using Ollama message.thinking as content fallback (len=" + (out == null ? 0 : out.length()) + ")");
                    } catch (Exception ignored) {
                    }
                }
            }
            // Fallbacks for other shapes (/api/generate or variant builds)
            if ((out == null || out.isBlank()) && root.has("response")) {
                try {
                    out = root.get("response").getAsString();
                } catch (Exception ignored) {
                }
            }
            if ((out == null || out.isBlank()) && root.has("content")) {
                try {
                    out = root.get("content").getAsString();
                } catch (Exception ignored) {
                }
            }
            if (out == null) out = "";
            log("Ollama content length " + out.length());
            if (out.isBlank()) {
                log("Ollama content empty. Raw body preview: " + (resp.length() > 200 ? resp.substring(0, 200) + "..." : resp));
            }
            return out;
        } catch (Exception e) {
            System.out.println("[AutoSocial] Error generating response via Ollama: " + e);
            return fallbackResponse(userMessage);
        }
    }

    private static String generateResponseOpenAI(String userMessage, String systemPrompt) {
        try {
            if (OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank()) {
                log("OpenAI API key not configured. Please set it in the config.");
                return fallbackResponse(userMessage);
            }

            // Build OpenAI chat payload
            String json = getString(userMessage, systemPrompt);
            log("OpenAI request: url=" + OPENAI_API_URL + ", model: " + MODEL + ", payloadBytes=" + json.getBytes(StandardCharsets.UTF_8).length);

            long start = System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection) URI.create(OPENAI_API_URL).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + OPENAI_API_KEY);
            conn.setDoOutput(true);

            try (OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                os.write(json);
            }

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String resp = sb.toString();
            long dur = System.currentTimeMillis() - start;
            log("OpenAI response: code=" + code + ", timeMs=" + dur + ", bytes=" + resp.getBytes(StandardCharsets.UTF_8).length);

            if (code < 200 || code >= 300) {
                System.out.println("[AutoSocial] OpenAI API error (" + code + "): " + resp);
                return fallbackResponse(userMessage);
            }

            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            String out = null;

            // Parse OpenAI response format
            if (root.has("choices")) {
                JsonArray choices = root.getAsJsonArray("choices");
                if (!choices.isEmpty()) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    if (choice.has("message")) {
                        JsonObject msg = choice.getAsJsonObject("message");
                        if (msg.has("content")) {
                            out = msg.get("content").getAsString();
                        }
                    }
                }
            }

            if (out == null) out = "";
            log("OpenAI content length " + out.length());
            if (out.isBlank()) {
                log("OpenAI content empty. Raw body preview: " + (resp.length() > 200 ? resp.substring(0, 200) + "..." : resp));
            }
            return out;
        } catch (Exception e) {
            System.out.println("[AutoSocial] Error generating response via OpenAI: " + e);
            return fallbackResponse(userMessage);
        }
    }

    private static String generateResponseGemini(String userMessage, String systemPrompt) {
        GEMINI_API_KEY = OPENAI_API_KEY;
        try {
            if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
                log("Gemini API key not configured. Please set it in the config.");
                return fallbackResponse(userMessage);
            }

            // Build Gemini chat payload
            String json = getStringGemini(userMessage, systemPrompt);
            log("Gemini request: url=" + GEMINI_API_URL + ", model: " + MODEL + ", payloadBytes=" + json.getBytes(StandardCharsets.UTF_8).length);

            long start = System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection) URI.create(GEMINI_API_URL).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-goog-api-key", OPENAI_API_KEY);
            conn.setDoOutput(true);

            try (OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                os.write(json);
            }

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String resp = sb.toString();
            long dur = System.currentTimeMillis() - start;
            log("Gemini response: code=" + code + ", timeMs=" + dur + ", bytes=" + resp.getBytes(StandardCharsets.UTF_8).length);

            if (code < 200 || code >= 300) {
                System.out.println("[AutoSocial] Gemini API error (" + code + "): " + resp);
                return fallbackResponse(userMessage);
            }

            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            String out = null;

            if (root.has("steps")) {
                JsonArray steps = root.getAsJsonArray("steps");

                StringBuilder text = new StringBuilder();

                for (JsonElement stepElement : steps) {
                    JsonObject step = stepElement.getAsJsonObject();

                    if (!"model_output".equals(
                            step.has("type") ? step.get("type").getAsString() : "")) {
                        continue;
                    }

                    if (!step.has("content")) {
                        continue;
                    }

                    JsonArray content = step.getAsJsonArray("content");

                    for (JsonElement contentElement : content) {
                        JsonObject item = contentElement.getAsJsonObject();

                        if ("text".equals(
                                item.has("type") ? item.get("type").getAsString() : "")
                                && item.has("text")) {
                            text.append(item.get("text").getAsString());
                        }
                    }
                }

                out = text.toString();
            }

            if (out == null) {
                out = "";
            }
            log("Gemini content length " + out.length());
            if (out.isBlank()) {
                log("Gemini content empty. Raw body preview: " + (resp.length() > 200 ? resp.substring(0, 200) + "..." : resp));
            }
            return out;
        } catch (Exception e) {
            System.out.println("[AutoSocial] Error generating response via Gemini: " + e);
            return fallbackResponse(userMessage);
        }
    }

    private static String getStringWithImage(String userMessage, String systemPrompt, String base64Image) {
        // Check if model supports vision
        if (!MODEL.toLowerCase().contains("vision") && !MODEL.toLowerCase().contains("gpt-4o")) {
            log("Model " + MODEL + " may not support vision. Consider using a vision-capable model like 'gpt-4o' or 'gpt-4-vision-preview'");
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonArray content = new JsonArray();
        // Add text content
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", userMessage);
        content.add(textContent);
        // Add image content if base64Image is provided
        if (base64Image != null && !base64Image.isBlank()) {
            String cleanedBase64 = base64Image.replaceAll("\\s+", "").trim();
            // Check if the data already has a data URL prefix
            String imageUrl;
            if (cleanedBase64.startsWith("data:image/")) {
                // Already has data URL prefix, use as-is
                imageUrl = cleanedBase64;
                log("Base64 data already has data URL prefix");
            } else {
                // Add the data URL prefix
                imageUrl = "data:image/png;base64," + cleanedBase64;
                log("Added data URL prefix to base64 data");
            }

            // Basic length check - if it's reasonably sized, assume it's valid
            if (imageUrl.length() > 100) { // Reasonable minimum for any image
                JsonObject imageContent = new JsonObject();
                imageContent.addProperty("type", "image_url");

                JsonObject imageUrlObj = new JsonObject();
                imageUrlObj.addProperty("url", imageUrl);
                imageContent.add("image_url", imageUrlObj);

                content.add(imageContent);
                log("Image URL prepared: " + imageUrl.length() + " characters");
            } else {
                log("Image URL too short to be valid. Length: " + imageUrl.length());
            }
        } else {
            log("No base64 image data provided to getStringWithImage");
        }

        message.add("content", content);

        // Create the full request structure
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", MODEL);

        JsonArray messages = new JsonArray();

        // Add system prompt if provided
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", systemPrompt);
            messages.add(systemMessage);
        }

        // Add the user message with image
        messages.add(message);
        requestBody.add("messages", messages);
        requestBody.addProperty("max_tokens", NUM_PREDICT);

        String jsonString = new Gson().toJson(requestBody);
        log("Generated JSON structure size: " + jsonString.getBytes(StandardCharsets.UTF_8).length + " bytes");
        return jsonString;
    }

    public static void takeScreenshot(boolean customPrompt) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            try {
                log("Capturing screenshot on main thread...");
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), png -> {
                    try (png) {
                        log("Screenshot captured, saving to file on main thread...");
                        // Save to temporary file first (this must be on main thread)
                        File temp = new File(TEMP_AUDIO_DIR, "screenshot.png");
                        png.writeToFile(temp);
                        log("Screenshot saved to: " + temp.getAbsolutePath() + " (" + temp.length() + " bytes)");
                        EXECUTOR.submit(() -> {
                            try {
                                // Read the file bytes on worker thread
                                byte[] imageBytes = Files.readAllBytes(temp.toPath());
                                log("Screenshot file read: " + imageBytes.length + " bytes");

                                String base64 = Base64.getEncoder().encodeToString(imageBytes);
                                log("Base64 encoded, length: " + base64.length());
                                String userMessage;

                                if (customPrompt) {
                                    // Set flag to wait for player message
                                    waitingForScreenshotPrompt = true;
                                    pendingScreenshotPrompt = null;
                                    screenshotLatch.acquire(); // Wait for a permit (blocks until available)
                                    userMessage = (pendingScreenshotPrompt != null) ? pendingScreenshotPrompt : "Please describe the image.";
                                    log("Captured prompt: " + userMessage);
                                } else {
                                    userMessage = "Please describe the image.";
                                }

                                String response = sendToOpenAI(base64, userMessage);
                                log("OpenAI response received: " + (response != null ? response.length() : 0) + " characters");

                                // Process response back on main thread for chat only
                                client.execute(() -> {
                                    try {
                                        if (response != null && !response.isBlank()) {
                                            pushMemory("User Question: " + "Please describe the image.");
                                            pushMemory("Your Response: " + response);
                                        }

                                        // Parse response for audio tokens {.} and build segments for interleaved audio
                                        ParsedResponse pr = parseCurlyTokens(response == null ? "" : response);
                                        log("Parsed response: chatTextLen=" + pr.chatText.length() + ", tokens=" + pr.tokens.size());
                                        if (!pr.tokens.isEmpty()) log("Tokens: " + pr.tokens);

                                        // Replace newlines then chunk into <=225 chars and send (only if there's text)
                                        if (!pr.chatText.isBlank()) {
                                            String flat = pr.chatText.replace('\n', ' ');
                                            List<String> parts = chunk(flat);
                                            log("Sending " + parts.size() + " chat part(s).");
                                            for (String part : parts) {
                                                String toSend = BOT_PREFIX ? "IAMAB0T[AI] " + AI_NAME + ": " + part : "[AI] " + AI_NAME + ": " + part;
                                                String preview = toSend.length() > 120 ? toSend.substring(0, 120) + "..." : toSend;
                                                log("Queue chat send (len=" + toSend.length() + "): " + preview);
                                                sendChat(toSend);
                                            }
                                        } else {
                                            log("No chat text to send (possibly tokens-only response).");
                                        }

                                        log("Chat message sending complete.");
                                    } catch (Exception e) {
                                        log("Error sending chat message: " + e.getMessage());
                                    }
                                });

                                // Process audio TTS on a separate worker thread to avoid blocking
                                EXECUTOR.submit(() -> {
                                    try {
                                        // Interleaved audio behavior: play random sound clips every 2-3 words in text (or use TTS if enabled), and handle {token}
                                        boolean ttsEnabled = isTTS();
                                        log("TTS enabled: " + ttsEnabled);
                                        if (ttsEnabled) {
                                            log("Calling playTTSInterleaved with response length: " + (response == null ? 0 : response.length()));
                                            playTTSInterleaved(response == null ? "" : response);
                                        } else {
                                            log("Calling playAudioInterleaved (TTS disabled)");
                                            playAudioInterleaved(response == null ? "" : response);
                                        }
                                        log("Audio processing complete.");
                                    } catch (Exception e) {
                                        log("Error during audio processing: " + e.getMessage());
                                    }
                                });

                            } catch (IOException e) {
                                log("Screenshot file read error: " + e.getMessage());
                            } catch (Exception e) {
                                log("Screenshot processing error: " + e.getMessage());
                            } finally {
                                // Clean up temp file on worker thread
                                try {
                                    if (temp.exists()) {
                                        boolean deleted = temp.delete();
                                        log("Temp file cleanup: " + deleted);
                                    }
                                } catch (Exception e) {
                                    log("Failed to delete temp file: " + e.getMessage());
                                }
                            }
                        });

                    } catch (IOException e) {
                        log("Screenshot save error: " + e.getMessage());
                    }
                });

            } catch (Exception e) {
                log("Screenshot capture error: " + e.getMessage());
            }
        });
    }

    private static String sendToOpenAI(String png, String userMessage) {
        StringBuilder sys = new StringBuilder();
        sys.append(SYS_PROMPT).append(' ');
        for (String m : memory) sys.append(m).append("\n");
        sys.append("\nRemember, keep your response to 3 sentences or less. Each sentence is a maximum of 20 words. DO NOT say Your Response: or User Question:.\n");
        String systemPrompt = sys.toString();
        try {
            if (OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank()) {
                log("OpenAI API key not configured. Please set it in the config.");
                return fallbackResponse(userMessage);
            }

            // Build OpenAI chat payload with image support
            String json;
            if (png != null && !png.isBlank()) {
                json = getStringWithImage(userMessage, systemPrompt, png);
            } else {
                json = getString(userMessage, systemPrompt);
            }
            log("OpenAI request: url=" + OPENAI_API_URL + ", model: " + MODEL + ", payloadBytes=" + json.getBytes(StandardCharsets.UTF_8).length);
            long start = System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection) URI.create(OPENAI_API_URL).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + OPENAI_API_KEY);
            conn.setDoOutput(true);

            try (OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                os.write(json);
            }

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String resp = sb.toString();
            long dur = System.currentTimeMillis() - start;
            log("OpenAI response: code=" + code + ", timeMs=" + dur + ", bytes=" + resp.getBytes(StandardCharsets.UTF_8).length);

            if (code < 200 || code >= 300) {
                System.out.println("[AutoSocial] OpenAI API error (" + code + "): " + resp);
                return fallbackResponse(userMessage);
            }

            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            String out = null;

            // Parse OpenAI response format
            if (root.has("choices")) {
                JsonArray choices = root.getAsJsonArray("choices");
                if (!choices.isEmpty()) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    if (choice.has("message")) {
                        JsonObject msg = choice.getAsJsonObject("message");
                        if (msg.has("content")) {
                            out = msg.get("content").getAsString();
                        }
                    }
                }
            }

            if (out == null) out = "";
            log("OpenAI content length " + out.length());
            if (out.isBlank()) {
                log("OpenAI content empty. Raw body preview: " + (resp.length() > 200 ? resp.substring(0, 200) + "..." : resp));
            }
            return out;
        } catch (Exception e) {
            System.out.println("[AutoSocial] Error generating response via OpenAI: " + e);
            return fallbackResponse(userMessage);
        }
    }

    private static String sendToGemini(String png, String userMessage) {
        StringBuilder sys = new StringBuilder();
        sys.append(SYS_PROMPT).append(' ');
        for (String m : memory) sys.append(m).append("\n");
        sys.append("\nRemember, keep your response to 3 sentences or less. Each sentence is a maximum of 20 words. DO NOT say Your Response: or User Question:.\n");
        String systemPrompt = sys.toString();
        try {
            if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
                log("Gemini API key not configured. Please set it in the config.");
                return fallbackResponse(userMessage);
            }

            // Build OpenAI chat payload with image support
            String json;
            if (png != null && !png.isBlank()) {
                json = getStringWithImage(userMessage, systemPrompt, png);
            } else {
                json = getString(userMessage, systemPrompt);
            }
            log("OpenAI request: url=" + GEMINI_API_URL + ", model: " + MODEL + ", payloadBytes=" + json.getBytes(StandardCharsets.UTF_8).length);
            long start = System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection) URI.create(OPENAI_API_URL).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-goog-api-key", GEMINI_API_KEY);
            conn.setDoOutput(true);

            try (OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                os.write(json);
            }

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String resp = sb.toString();
            long dur = System.currentTimeMillis() - start;
            log("Gemini response: code=" + code + ", timeMs=" + dur + ", bytes=" + resp.getBytes(StandardCharsets.UTF_8).length);

            if (code < 200 || code >= 300) {
                System.out.println("[AutoSocial] Gemini API error (" + code + "): " + resp);
                return fallbackResponse(userMessage);
            }

            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            String out = null;

            // Parse Gemini response format
            if (root.has("choices")) {
                JsonArray choices = root.getAsJsonArray("choices");
                if (!choices.isEmpty()) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    if (choice.has("message")) {
                        JsonObject msg = choice.getAsJsonObject("message");
                        if (msg.has("content")) {
                            out = msg.get("content").getAsString();
                        }
                    }
                }
            }

            if (out == null) out = "";
            log("Gemini content length " + out.length());
            if (out.isBlank()) {
                log("Gemini content empty. Raw body preview: " + (resp.length() > 200 ? resp.substring(0, 200) + "..." : resp));
            }
            return out;
        } catch (Exception e) {
            System.out.println("[AutoSocial] Error generating response via Gemini: " + e);
            return fallbackResponse(userMessage);
        }
    }

    private static String getString(String userMessage, String systemPrompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        body.add("messages", messages);
        body.addProperty("temperature", TEMPERATURE);
        body.addProperty("max_tokens", NUM_PREDICT);

        return body.toString();
    }

    private static String getStringGemini(String userMessage, String systemPrompt) {
        JsonObject body = new JsonObject();

        body.addProperty("model", MODEL);

        // Interactions API accepts input as a string.
        // Combine the system instruction and user message explicitly.
        StringBuilder input = new StringBuilder();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            input.append(systemPrompt);
            input.append("\n\n");
        }

        input.append(userMessage);

        body.addProperty("input", input.toString());

        return body.toString();
    }

    private static String fallbackResponse(String userMessage) {
        // Simple non-AI fallback so the mod works without an API key
        String[] quips = new String[]{
                "WAAAAHHHHHH! I heard you: '" + userMessage + "'",
                "It's-a me, Wario!",
                "Gold and garlic! You said: '" + userMessage + "'",
                "Heh heh, keep it short, LOSER.",
                "WAAAAAAHHHH!!!!! CAMERON7108 SUCKS!!!!!!"
        };
        return quips[random.nextInt(quips.length)];
    }

    private static void sendChat(String msg) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        try {
            // Ensure message is properly encoded and within limits
            String safeMsg = sanitizeMessage(msg);
            if (safeMsg.length() > 256) {  // Minecraft chat packet limit
                safeMsg = safeMsg.substring(0, 253) + "...";
            }

            final String finalSafeMsg = safeMsg;  // Make effectively final

            // Add to recent sent messages to prevent responding to ourselves
            RECENT_SENT_MESSAGES.add(finalSafeMsg);
            // Clean up old messages after 5 seconds to prevent memory leak
            EXECUTOR.schedule(() -> RECENT_SENT_MESSAGES.remove(finalSafeMsg), 5, java.util.concurrent.TimeUnit.SECONDS);

            client.player.connection.sendChat(finalSafeMsg);
        } catch (Exception e) {
            log("Failed to send chat message: " + e.getMessage());
        }
    }

    private static String sanitizeMessage(String msg) {
        if (msg == null) return "";

        try {
            // Remove or replace problematic characters
            StringBuilder clean = new StringBuilder();
            for (int i = 0; i < msg.length(); i++) {
                char c = msg.charAt(i);
                // Allow most characters, but replace control characters
                if (c >= 32 && c <= 126 || c == '\n' || c == '\t') {
                    clean.append(c);
                } else if (Character.isLetterOrDigit(c) || Character.isWhitespace(c) || Character.isDefined(c)) {
                    clean.append(c);
                } else {
                    clean.append('?'); // Replace problematic characters
                }
            }

            // Ensure UTF-8 encoding is valid
            byte[] bytes = clean.toString().getBytes(StandardCharsets.UTF_8);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log("Message sanitization failed, using fallback: " + e.getMessage());
            return "Message could not be encoded properly";
        }
    }
}
