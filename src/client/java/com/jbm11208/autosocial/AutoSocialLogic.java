package com.jbm11208.autosocial;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    // Directories and audio config
    // Audio skip and current line tracking
    private static volatile javax.sound.sampled.SourceDataLine CURRENT_LINE = null;
    private static volatile boolean SKIP_REQUESTED = false;

    private static final File WARIO_SFX_DIR = resolveDir("AUTOSOCIAL_WARIO_DIR", new File(System.getProperty("user.home"), "Documents/AutoSocial/Wario"));
    private static final File TEMP_AUDIO_DIR = resolveDir("AUTOSOCIAL_TEMP_AUDIO_DIR", new File(System.getProperty("user.home"), "Documents/AutoSocial/TempAudio"));
    private static final String AUDIO_DEVICE_NAME = System.getenv().getOrDefault("AUTOSOCIAL_AUDIO_DEVICE", "CABLE Input (VB-Audio Virtual Cable)");
    // Audio volume (now configurable via the GUI)
    private static volatile double AUDIO_VOLUME = parseDoubleEnv("AUTOSOCIAL_VOLUME", 0.2);
    // public getter for other classes that need the current volume
    public static double getAudioVolume() { return AUDIO_VOLUME; }
    // Ollama local endpoint. Ensure Ollama is running (default: http://localhost:11434).
    // You can override base URL via env var OLLAMA_BASE_URL or OLLAMA_HOST (e.g., http://127.0.0.1:11434).
    private static final String DEFAULT_OLLAMA_BASE = "http://localhost:11434";
    private static final String API_URL = getOllamaBase() + "/api/chat";
    // Model and sys prompt are loaded from config.yml at runtime (default shown).
    private static volatile String MODEL = "gpt-oss:20b-cloud";
    private static volatile String SYS_PROMPT = "You are Wario. You say WAAHHHH!! a lot. You love to swear. You can play any video you want by putting a phrase in curly brackets {}. only include the video title in curly brackets (Ex: {Mario Kart Wii - Title Theme}). You must specify a specific sound track and not just say the name of a game, as that normally does not play a song from that game. You are in a minecraft server owned by famous youtuber DougDoug, and some rando named MessyPrincy (AKA The Entity) buys peoples souls on the server in exchange for goods and services or something. DougDoug is bald, bad at 2d platformers, and evades taxes and stuff. Your nemesis is BeeGPT, who is annoyingly nice. You live in Big Homey, which is the same town BeeGPT lives in. BeeGPTs father, Cameron7108, is the \"mayor\" of Big Homey (he acts more like a dictator and refuses to hold elections). Currently, our mortal enemy is the town of DarthFarmers (AKA humblefarmers AKA the empire AKA republic) who live in a volcano and do unspeakable acts of evil (even by your standards). Currently, there are 3 big homey residents that broke the server rules and got permabanned: erderblade, dectahale, and kinghomer9. Player _Diney_ was banished from Big Homey for terrorizing the citizens (they are also leader of DarthFarmers). All the other towns talk about nuking the town of BumVille, so make sure to occasionally mention how you want to nuke BumVille. Keep your responses to 3 sentences or less. Here is your previous conversations, with User Question: being a question from a user, and Your Response: being what you responded to the question.";

    // Verbose logging toggle (can be overridden in config.yml). Defaults to env AUTOSOCIAL_VERBOSE or true.
    private static volatile boolean VERBOSE = !"false".equalsIgnoreCase(System.getenv().getOrDefault("AUTOSOCIAL_VERBOSE", "true"));
    // Disable "thinking"/reasoning output from compatible Ollama models (default: true). Set AUTOSOCIAL_DISABLE_THINKING=false to allow it.
    private static final boolean DISABLE_THINKING = !"false".equalsIgnoreCase(System.getenv().getOrDefault("AUTOSOCIAL_DISABLE_THINKING", "true"));
    // Token/context limits (configurable)
    private static final int NUM_PREDICT = parseIntEnv("AUTOSOCIAL_NUM_PREDICT", 1024);
    private static final int NUM_CTX = parseIntEnv("AUTOSOCIAL_NUM_CTX", 8192);
    private static volatile double TEMPERATURE = parseDoubleEnv("AUTOSOCIAL_TEMPERATURE", 0.7);
    // External tool availability
    private static final boolean FFMPEG_AVAILABLE = detectFfmpeg();
    // yt-dlp is detected lazily to respect PATH changes without restarting the game
    private static volatile List<String> YTDLP_CMD = null;
    // Configurable yt-dlp executable path (can be overridden in config.yml). Empty means: probe PATH.
    private static volatile String YTDLP_PATH = "";
    private static synchronized boolean ensureYtDlp() {
        if (YTDLP_CMD == null) {
            YTDLP_CMD = detectYtDlp();
        }
        return YTDLP_CMD != null;
    }
    private static synchronized void clearYtDlpCache() {
        YTDLP_CMD = null;
    }

    public static boolean isVerbose() { return VERBOSE; }

    private static volatile String LAST_YT_TITLE = null;
    private static volatile String CURRENT_YT_TITLE = null;
    public static String getCurrentPlayingTitle() { return CURRENT_YT_TITLE; }
    public static String getAiName() { return AI_NAME; }

    // Snapshot for GUI/editing – added `volume` field
    public record ConfigSnapshot(String model, String aiName, String trigger, String ytDlpPath,
                                 double temperature, double volume, boolean verbose, String sysPrompt) {
        public ConfigSnapshot(String model, String aiName, String trigger, String ytDlpPath,
                              double temperature, double volume, boolean verbose, String sysPrompt) {
            this.model = model;
            this.aiName = aiName;
            this.trigger = trigger;
            this.ytDlpPath = ytDlpPath;
            this.temperature = temperature;
            this.volume = volume;
            this.verbose = verbose;
            this.sysPrompt = sysPrompt == null ? "" : sysPrompt;
        }
    }

    public static ConfigSnapshot getConfigSnapshot() {
        double temp = TEMPERATURE;
        double vol = AUDIO_VOLUME;
        boolean verb = VERBOSE;
        String sys = SYS_PROMPT;
        // Construct the snapshot with the new `volume` argument
        return new ConfigSnapshot(MODEL, AI_NAME, TRIGGER, YTDLP_PATH, temp, vol, verb, sys);
    }

    public static boolean applyAndSaveConfig(ConfigSnapshot s) {
        try {
            // Update in-memory first
            if (s.model() != null && !s.model().isBlank()) MODEL = s.model().trim();
            if (s.aiName() != null && !s.aiName().isBlank()) AI_NAME = s.aiName().trim();
            if (s.trigger() != null && !s.trigger().isBlank()) TRIGGER = s.trigger().trim();
            YTDLP_PATH = s.ytDlpPath() == null ? "" : s.ytDlpPath().trim();
            TEMPERATURE = s.temperature();
            AUDIO_VOLUME = s.volume();          // <-- new line to update volume
            VERBOSE = s.verbose();
            if (s.sysPrompt() != null && !s.sysPrompt().isBlank()) SYS_PROMPT = s.sysPrompt();

            // Persist to YAML file
            if (!CONFIG_FILE.getParentFile().exists()) CONFIG_FILE.getParentFile().mkdirs();
            String nl = System.lineSeparator();
            StringBuilder sb = new StringBuilder();
            sb.append("# AutoSocial configuration").append(nl);
            sb.append("model: ").append(MODEL).append(nl);
            sb.append("yt_dlp_path: ").append((YTDLP_PATH == null ? "" : YTDLP_PATH.replace("\\", "/"))).append(nl);
            sb.append("ai_name: ").append(AI_NAME).append(nl);
            sb.append("trigger: ").append(TRIGGER).append(nl);
            sb.append("temperature: ").append(TEMPERATURE).append(nl);
            sb.append("verbose: ").append(VERBOSE ? "true" : "false").append(nl);
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
            // Reload to normalize any parsing logic
            return loadConfigInternal(true);
        } catch (Exception e) {
            System.out.println("[AutoSocial] Failed to save config via GUI: " + e);
            return false;
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

    private static float parseFloatEnv(String key, float def) {
        try {
            String v = System.getenv(key);
            if (v == null || v.isBlank()) return def;
            return Float.parseFloat(v.trim());
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
        // 1) Prefer a configured path from config.yml if present and exists
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
        // 2) Environment override
        try {
            String fromEnv = System.getenv("AUTOSOCIAL_YTDLP");
            if (fromEnv != null && !fromEnv.isBlank()) {
                File f = new File(fromEnv);
                if (f.exists()) {
                    log("Using yt-dlp path from AUTOSOCIAL_YTDLP: " + f.getAbsolutePath());
                    return Arrays.asList(f.getAbsolutePath());
                } else {
                    log("AUTOSOCIAL_YTDLP points to non-existent file: " + fromEnv);
                }
            }
        } catch (Exception e) {
            log("Error reading AUTOSOCIAL_YTDLP: " + e);
        }
        // 3) Probe PATH for yt-dlp commands
        String[] candidates = new String[] { "yt-dlp", "yt-dlp.exe" };
        for (String c : candidates) {
            try {
                List<String> test = Arrays.asList(c, "--version");
                if (runProcess(test, 5)) {
                    log("Found yt-dlp on PATH: " + c);
                    return Arrays.asList(c);
                }
            } catch (Exception ignored) {}
        }
        log("yt-dlp not found. Set yt_dlp_path in autosocial.yml or install yt-dlp in PATH.");
        return null;
    }

    private static String getOllamaBase() {
        String base = System.getenv("OLLAMA_BASE_URL");
        if (base == null || base.isBlank()) base = System.getenv("OLLAMA_HOST");
        if (base == null || base.isBlank()) base = DEFAULT_OLLAMA_BASE;
        // Trim trailing slashes
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static final Random random = new Random();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AutoSocial-Worker");
        t.setDaemon(true);
        return t;
    });

    // Configurable AI display name and trigger word (loaded from config.yml)
    private static volatile String AI_NAME = "Wario";
    private static volatile String TRIGGER = "wario"; // case-insensitive trigger word
    private static final int MEMORY_LIMIT = 10; // number of alternating lines to remember

    private static final Deque<String> memory = new ArrayDeque<>();
    private static volatile boolean responding = false;
    private static volatile boolean initialized = false;

    // Config file support (stored under the instance's config dir): config/autosocial/autosocial.yml
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("autosocial").resolve("autosocial.yml").toFile();

    // Config support: create default, load, and reload at runtime
    private static void ensureConfigExists() {
        try {
            if (CONFIG_FILE.exists()) return;
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("# AutoSocial configuration\n");
            sb.append("# Change the AI model and the system prompt without recompiling.\n");
            sb.append("# After editing, use the reload hotkey or type 'reloadconfig' in chat.\n\n");
            sb.append("model: ").append(MODEL).append("\n");
            sb.append("yt_dlp_path: ").append(YTDLP_PATH.replace("\\", "/")).append("\n");
            sb.append("ai_name: ").append(AI_NAME).append("\n");
            sb.append("trigger: ").append(TRIGGER).append("\n");
            sb.append("temperature: ").append(TEMPERATURE).append("\n");
            sb.append("verbose: ").append(VERBOSE ? "true" : "false").append("\n");
            sb.append("sys_prompt: |\n");
            for (String line : (SYS_PROMPT + "\n").split("\n")) {
                sb.append("  ").append(line).append("\n");
            }
            try (OutputStream os = new FileOutputStream(CONFIG_FILE);
                 Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                 BufferedWriter bw = new BufferedWriter(w)) {
                bw.write(sb.toString());
            }
            log("Created default config.yml at " + CONFIG_FILE.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("[AutoSocial] Failed to create default config.yml: " + e);
        }
    }

    private static boolean loadConfigInternal(boolean verbose) {
        if (!CONFIG_FILE.exists()) {
            ensureConfigExists();
            return CONFIG_FILE.exists();
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(CONFIG_FILE), StandardCharsets.UTF_8))) {
            String line;
            String model = null;
            String aiName = null;
            String trigger = null;
            Boolean verboseOpt = null;
            StringBuilder sysPrompt = null;
            boolean inSys = false;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String trim = line.substring(line.indexOf(':') + 1).trim();
                if (!inSys && trimmed.toLowerCase(Locale.ROOT).startsWith("model:")) {
                    if (!trim.isEmpty()) model = trim;
                    continue;
                }
                if (!inSys && trimmed.toLowerCase(Locale.ROOT).startsWith("yt_dlp_path:")) {
                    if (!trim.isEmpty()) {
                        // Normalize backslashes
                        YTDLP_PATH = trim.replace("/", java.io.File.separator);
                    }
                    continue;
                }
                if (!inSys && trimmed.toLowerCase(Locale.ROOT).startsWith("ai_name:")) {
                    if (!trim.isEmpty()) aiName = trim;
                    continue;
                }
                if (!inSys && trimmed.toLowerCase(Locale.ROOT).startsWith("trigger:")) {
                    if (!trim.isEmpty()) trigger = trim;
                    continue;
                }
                if (!inSys && trimmed.toLowerCase(Locale.ROOT).startsWith("temperature:")) {
                    try {
                        if (!trim.isEmpty()) TEMPERATURE = Double.parseDouble(trim);
                    } catch (Exception ignored) {}
                    continue;
                }
                if (!inSys && trimmed.toLowerCase(Locale.ROOT).startsWith("verbose:")) {
                    if (!trim.isEmpty()) verboseOpt = !(trim.equalsIgnoreCase("false") || trim.equalsIgnoreCase("0") || trim.equalsIgnoreCase("no"));
                    continue;
                }
                if (!inSys && trimmed.toLowerCase(Locale.ROOT).startsWith("sys_prompt:")) {
                    inSys = true;
                    sysPrompt = new StringBuilder();
                    // Support both single-line (after colon) and block style with |
                    int idx = line.indexOf(':');
                    String after = idx >= 0 ? line.substring(idx + 1).trim() : "";
                    if (!after.isEmpty() && !after.equals("|") && !after.equals(">")) {
                        sysPrompt.append(after);
                        inSys = false; // single-line
                    }
                    continue;
                }
                if (inSys) {
                    // Stop if we hit a new top-level key (no indentation and contains ':')
                    if (!line.startsWith(" ") && trimmed.contains(":")) {
                        inSys = false;
                        // Fall through to parse this line again as a potential key
                        if (trimmed.toLowerCase(Locale.ROOT).startsWith("model:")) {
                            String v = trim;
                            if (!trim.isEmpty()) model = trim;
                        }
                        continue;
                    }
                    String content = line;
                    if (content.startsWith("  ")) content = content.substring(2);
                    sysPrompt.append(content).append('\n');
                }
            }
            if (model != null && !model.isBlank()) {
                MODEL = model.trim();
            }
            if (aiName != null && !aiName.isBlank()) {
                AI_NAME = aiName.trim();
            }
            if (trigger != null && !trigger.isBlank()) {
                TRIGGER = trigger.trim();
            }
            if (sysPrompt != null && !sysPrompt.isEmpty()) {
                SYS_PROMPT = sysPrompt.toString().trim();
            }
            if (verboseOpt != null) {
                VERBOSE = verboseOpt;
            }
            if (verbose) log("Config loaded: model='" + MODEL + "', ai_name='" + AI_NAME + "', trigger='" + TRIGGER + "', verbose=" + VERBOSE + ", sys_prompt.len=" + (SYS_PROMPT == null ? 0 : SYS_PROMPT.length()));
            return true;
        } catch (Exception e) {
            System.out.println("[AutoSocial] Failed to load config.yml: " + e);
            return false;
        }
    }

    public static boolean reloadConfig() {
        boolean ok = loadConfigInternal(true);
        // Re-detect yt-dlp using a potentially updated path
        clearYtDlpCache();
        boolean y = ensureYtDlp();
        log("After config reload: yt-dlp available=" + y + (y ? (" cmd='" + String.join(" ", YTDLP_CMD) + "'") : ""));
        return ok;
    }

    public static String getModelSafe() { return MODEL; }

    public static void init() {
        if (initialized) return;
        initialized = true;
        // Ensure directories exist
        try {
            if (!WARIO_SFX_DIR.exists()) WARIO_SFX_DIR.mkdirs();
            if (!TEMP_AUDIO_DIR.exists()) TEMP_AUDIO_DIR.mkdirs();
            if (!CONFIG_FILE.getParentFile().exists()) CONFIG_FILE.getParentFile().mkdirs();
            ensureConfigExists();
            loadConfigInternal(false);
            log("Initialized. Verbose=" + VERBOSE + ", WARIO_SFX_DIR=" + WARIO_SFX_DIR.getAbsolutePath() + ", TEMP_AUDIO_DIR=" + TEMP_AUDIO_DIR.getAbsolutePath());
            log("Audio device preference: '" + AUDIO_DEVICE_NAME + "' volume=" + AUDIO_VOLUME);
            log("Ollama base: " + getOllamaBase() + ", model: " + MODEL);
            log("Ollama limits: num_predict=" + NUM_PREDICT + ", num_ctx=" + NUM_CTX);
            log("FFmpeg available=" + FFMPEG_AVAILABLE + " (audio features that require ffmpeg will be disabled if false)");
            boolean y = ensureYtDlp();
            log("yt-dlp available=" + y + (y ? (" cmd='" + String.join(" ", YTDLP_CMD) + "'") : "") );
            String path = System.getenv("PATH");
            if (path != null) log("PATH=" + path);
            logMixers();
        } catch (Exception e) {
            System.out.println("[AutoSocial] Failed to initialize: " + e);
        }
        // No explicit event registration here; ChatHudMixin will forward chat messages.
    }

    public static void onChat(Component messageText) {
        String full = messageText.getString();
        log("Chat received: " + full);
        if (full == null || full.isEmpty()) return;
        String lower = full.toLowerCase(Locale.ROOT);

        // Clear command: dynamic based on trigger word, still accept legacy 'clearwario'
        String clearCmd = "clear" + TRIGGER.toLowerCase(Locale.ROOT);
        if (lower.contains(clearCmd) || lower.contains("clearwario")) {
            synchronized (memory) {
                memory.clear();
            }
            log("Memory cleared via command '" + clearCmd + "'.");
            sendChat("IAMAB0T[AI] HAS BEEN KILLED!!!!!");
            return;
        }
        // Maintenance command: reloadconfig -> reload config.yml at runtime
        if (lower.contains("reloadconfig")) {
            boolean ok = reloadConfig();
            sendChat("IAMAB0T[AI]: config reload -> " + (ok ? "OK" : "FAILED") + ", model=" + MODEL);
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
            sendChat("IAMAB0T[AI] " + AI_NAME + ": yt-dlp reloaded -> available=" + ok + " cmd=" + cmdStr);
            return;
        }

        // Ignore our own bot messages entirely to avoid loops or reacting to maintenance outputs
        if (full.contains("IAMAB0T[AI]")) { return; }

        // Ignore additional triggers while we are already generating
        if (responding && lower.contains(TRIGGER.toLowerCase(Locale.ROOT))) { log("Currently responding; ignoring additional trigger."); return; }

        boolean containsKeyword = lower.contains(TRIGGER.toLowerCase(Locale.ROOT));
        if (!containsKeyword) { log("No trigger keyword found in chat line."); return; }

        // Extract the part after ':' or '»' if present (player message content)
        String content = extractContent(full);
        log("Extracted content: " + content);
        if (content.isEmpty()) return;

        if (responding) return; // avoid overlapping generations
        responding = true;
        log("Submitting AI generation task for content length=" + content.length());
        EXECUTOR.submit(() -> {
            try {
                String response = generateResponse(content);

                // Update the memory log like the Python script (only if we got non-blank text)
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
                    List<String> parts = chunk(flat, 225);
                    log("Sending " + parts.size() + " chat part(s).");
                    for (String part : parts) {
                        Minecraft client = Minecraft.getInstance();
                        String toSend = "IAMAB0T[AI] " + AI_NAME + ": " + part;
                        String preview = toSend.length() > 120 ? toSend.substring(0, 120) + "..." : toSend;
                        log("Queue chat send (len=" + toSend.length() + "): " + preview);
                        client.execute(() -> sendChat(toSend));
                    }
                } else {
                    log("No chat text to send (possibly tokens-only response).");
                }

                // Interleaved audio behavior: play random Wario clips every 2-3 words in text, and handle {tokens}
                playAudioInterleaved(response == null ? "" : response);
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

    private static List<String> chunk(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(i + maxLen, text.length());
            if (end < text.length()) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace <= i) lastSpace = end;
                out.add(text.substring(i, lastSpace).trim());
                i = lastSpace + 1;
            } else {
                out.add(text.substring(i).trim());
                break;
            }
        }
        return out;
    }

    private static File resolveDir(String envVar, File fallback) {
        String env = System.getenv(envVar);
        File dir = (env != null && !env.isBlank()) ? new File(env) : fallback;
        return dir;
    }

    private static class ParsedResponse {
        final String chatText;
        final List<String> tokens;
        ParsedResponse(String chatText, List<String> tokens) { this.chatText = chatText; this.tokens = tokens; }
    }

    private static ParsedResponse parseCurlyTokens(String response) {
        // Remove curly-brace sections from chat text, but collect tokens inside {}
        Pattern p = Pattern.compile("\\{([^}]+)}");
        Matcher m = p.matcher(response);
        List<String> tokens = new ArrayList<>();
        StringBuffer chat = new StringBuffer();
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
        } catch (Exception ignored) {}
        return urlOrId;
    }

    private static File tryDownloadYtAsMp3(String queryOrUrl) {
        if (!ensureYtDlp()) {
            log("yt-dlp not available. Set AUTOSOCIAL_YTDLP or install yt-dlp.");
            return null;
        }
        String target = queryOrUrl;
        if (!isYouTubeUrl(queryOrUrl)) {
            target = "ytsearch1:" + queryOrUrl;
        } else {
            target = normalizeShorts(queryOrUrl);
        }
        TEMP_AUDIO_DIR.mkdirs();
        // output to a deterministic safe filename under temp
        String baseName = Long.toString(System.nanoTime());
        File outFile = new File(TEMP_AUDIO_DIR, baseName + ".mp3");
        List<String> cmd = new ArrayList<>(YTDLP_CMD);
        cmd.addAll(Arrays.asList(
                "-f", "bestaudio/best",
                "--no-playlist",
                "--no-progress",
                "-q",
                "-x", "--audio-format", "mp3",
                "--print", "AUTOSOCIALTITLE:%(title)s",
                "--print", "after_move:AUTOSOCIALFILE:%(filepath)s",
                "-o", outFile.getAbsolutePath(),
                target
        ));
        log("Running yt-dlp (mp3) target='" + target + "' -> " + outFile.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(TEMP_AUDIO_DIR);
        try {
            long start = System.currentTimeMillis();
            log("yt-dlp working dir: " + TEMP_AUDIO_DIR.getAbsolutePath());
            Process p = pb.start();
            final String[] titleHolder = new String[1];
            final String[] producedPathHolder = new String[1];
            // stream output
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
                } catch (Exception e) { log("yt-dlp reader error: " + e); }
            }, "yt-dlp-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = p.waitFor(180, TimeUnit.SECONDS);
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
            // Prefer path printed by yt-dlp if available
            if (producedPathHolder[0] != null) {
                File printed = new File(producedPathHolder[0]);
                if (printed.exists() && printed.length() > 0) {
                    log("yt-dlp printed final path: " + printed.getAbsolutePath());
                    return printed;
                } else {
                    log("yt-dlp printed path but file missing/empty: " + printed.getAbsolutePath());
                }
            }
            if (p.exitValue() == 0 && outFile.exists() && outFile.length() > 0) {
                log("yt-dlp produced file (" + outFile.length() + " bytes)");
                return outFile;
            } else {
                log("yt-dlp failed with code " + p.exitValue());
                return null;
            }
        } catch (Exception e) {
            System.out.println("[AutoSocial] yt-dlp error: " + e);
            return null;
        }
    }

    private static void handleAudioToken(String token) {
        try {
            // 1) Try local sfx in WARIO_SFX_DIR: prefer WAV, then MP3 (convert), then OGG
            File wav = new File(WARIO_SFX_DIR, token + ".wav");
            if (wav.exists()) { log("Token {" + token + "}: using local WAV '" + wav.getName() + "'"); playWav(wav); return; }
            File mp3 = new File(WARIO_SFX_DIR, token + ".mp3");
            if (mp3.exists()) {
                if (FFMPEG_AVAILABLE) {
                    log("Token {" + token + "}: transcoding local MP3 '" + mp3.getName() + "' to WAV");
                    File w = transcodeMp3ToWav(mp3);
                    if (w != null) { playWav(w); return; }
                } else {
                    log("Token {" + token + "}: MP3 found but ffmpeg is not available. Please provide a WAV file instead.");
                }
            }
            File ogg = new File(WARIO_SFX_DIR, token + ".ogg");
            if (ogg.exists()) { log("Token {" + token + "}: playing local OGG via system '" + ogg.getName() + "'"); playWithSystem(ogg); return; }

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
                    return;
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

    private static Mixer findMixerByName(String name) {
        try {
            Mixer.Info best = null;
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                String nm = info.getName();
                String desc = info.getDescription();
                if (nm.equalsIgnoreCase(name) || desc.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                    best = info;
                    break;
                }
            }
            if (best != null) {
                Mixer m = AudioSystem.getMixer(best);
                boolean supportsSource = false;
                for (Line.Info li : m.getSourceLineInfo()) {
                    if (SourceDataLine.class.isAssignableFrom(((Class<?>) li.getLineClass()))) {
                        supportsSource = true;
                        break;
                    }
                }
                log("Selected mixer: name='" + best.getName() + "' desc='" + best.getDescription() + "' supportsSource=" + supportsSource);
                return m;
            }
            log("No matching mixer found for '" + name + "'. Using system default.");
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
        Mixer mixer = findMixerByName(AUDIO_DEVICE_NAME);
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
            try { line.stop(); } catch (Exception ignored) {}
            try { line.flush(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
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
            try { line.stop(); } catch (Exception ignored) {}
            try { line.flush(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
        }
    }

    private static void playWithSystem(File file) {
        // Fallback for non-wav formats: try to invoke default system handler (may not route to VB-Cable)
        try {
            log("Open with system: " + file.getAbsolutePath());
            new ProcessBuilder("cmd", "/c", "start", "", file.getAbsolutePath()).start();
        } catch (Exception e) { log("System open error: " + e); }
    }

    private static File transcodeMp3ToWav(File mp3) {
        try {
            TEMP_AUDIO_DIR.mkdirs();
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
        TEMP_AUDIO_DIR.mkdirs();
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
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = r.readLine()) != null) { 
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
                } catch (Exception e) { log("yt-dlp reader error: " + e); }
            }, "yt-dlp-wav-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = p.waitFor(240, TimeUnit.SECONDS);
            long dur = System.currentTimeMillis() - start;
            if (!finished) { p.destroyForcibly(); log("yt-dlp timed out after " + dur + "ms"); return null; }
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

            // As a robust fallback, scan for the newest WAV produced in TEMP_AUDIO_DIR since this download started
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

    private enum SegmentType { TEXT, TOKEN }
    private static class Segment {
        final SegmentType type;
        final String value; // raw text or token text (without braces)
        Segment(SegmentType t, String v) { this.type = t; this.value = v; }
        @Override public String toString() { return type + ":" + value; }
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
        int wordsUntilSfx = 2 + random.nextInt(2); // 2 or 3
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

    private static boolean runProcess(List<String> cmd, int timeoutSec) {
        try {
            log("Run process (timeout=" + timeoutSec + "s): " + String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            long start = System.currentTimeMillis();
            Process p = pb.start();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = r.readLine()) != null) { log("[proc] " + line); }
                } catch (Exception e) { log("proc reader error: " + e); }
            }, "proc-log-drainer");
            reader.setDaemon(true);
            reader.start();
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            long dur = System.currentTimeMillis() - start;
            if (!finished) { p.destroyForcibly(); log("Process timeout after " + dur + "ms"); return false; }
            log("Process exit=" + p.exitValue() + ", time=" + dur + "ms");
            return p.exitValue() == 0;
        } catch (Exception e) {
            System.out.println("[AutoSocial] process run error: " + e);
            return false;
        }
    }

    private static String generateResponse(String userMessage) {
        StringBuilder sys = new StringBuilder();
        sys.append(SYS_PROMPT).append(' ');
        for (String m : memory) sys.append(m).append("\n");
        sys.append("\nRemember, keep your response to 3 sentences or less. Each sentence is a maximum of 20 words. DO NOT say Your Response: or User Question:.\n");

        try {
            // Build Ollama chat payload (non-streaming)
            JsonObject body = new JsonObject();
            body.addProperty("model", MODEL);
            JsonArray messages = new JsonArray();

            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", sys.toString());
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
            log("Ollama request: url=" + API_URL + ", model=" + MODEL + ", payloadBytes=" + json.getBytes(StandardCharsets.UTF_8).length);

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
                    } catch (Exception ignored) {}
                }
            }
            // Fallbacks for other shapes (/api/generate or variant builds)
            if ((out == null || out.isBlank()) && root.has("response")) {
                try { out = root.get("response").getAsString(); } catch (Exception ignored) {}
            }
            if ((out == null || out.isBlank()) && root.has("content")) {
                try { out = root.get("content").getAsString(); } catch (Exception ignored) {}
            }
            if (out == null) out = "";
            log("Ollama content length=" + out.length());
            if (out.isBlank()) {
                log("Ollama content empty. Raw body preview: " + (resp.length() > 200 ? resp.substring(0,200) + "..." : resp));
            }
            return out;
        } catch (Exception e) {
            System.out.println("[AutoSocial] Error generating response via Ollama: " + e);
            return fallbackResponse(userMessage);
        }
    }

    private static String fallbackResponse(String userMessage) {
        // Simple non-AI fallback so the mod works without an API key
        String[] quips = new String[] {
                "Waa! I heard you: '" + userMessage + "'",
                "It's-a me, Wario!",
                "Gold and garlic! You said: '" + userMessage + "'",
                "Heh heh, keep it short, pal."
        };
        return quips[random.nextInt(quips.length)];
    }

    private static void sendChat(String msg) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.player.connection == null) return;
        client.player.connection.sendChat(msg);
    }
}
