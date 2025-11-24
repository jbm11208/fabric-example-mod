package com.jbm11208.autosocial.tts;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Small wrapper around TTS services.
 * Usage example:
 * <pre>
 *     byte[] wav = TTSClient.requestTTS("Hello world!", Voice.Brian, TTSProvider.CURRENT, null, null);
 *     // write the bytes to a file, feed to a SourceDataLine, etc.
 * </pre>
 */
public final class TTSClient {

    /** Shared HTTP client – thread‑safe and reusable. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private TTSClient() {
        // utility class – prevent instantiation
    }

    public enum TTSProvider {
        CURRENT("Current TTS"),
        ELEVENLABS("ElevenLabs");

        private final String displayName;

        TTSProvider(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Queries a TTS service and returns the raw audio bytes.
     *
     * @param text  The text to synthesize.
     * @param provider The TTS provider to use.
     * @param elevenlabsApiKey ElevenLabs API key (required if provider is ELEVENLABS).
     * @param elevenlabsVoiceId ElevenLabs voice ID (required if provider is ELEVENLABS).
     * @return Audio data as a byte array (typically a WAV file).
     * @throws IOException          on network/IO errors.
     * @throws InterruptedException if the request is interrupted.
     */
    public static byte[] requestTTS(String text, TTSProvider provider,
                                   String elevenlabsApiKey, String elevenlabsVoiceId)
            throws IOException, InterruptedException {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }

        return switch (provider) {
            case ELEVENLABS -> requestTTSElevenLabs(text, elevenlabsApiKey, elevenlabsVoiceId);
            case CURRENT -> requestTTSCurrent(text);
        };
    }

    /**
     * Queries the current free TTS service and returns the raw audio bytes.
     *
     * @param text  The text to synthesize.
     * @return Audio data as a byte array (typically a WAV file).
     * @throws IOException          on network/IO errors.
     * @throws InterruptedException if the request is interrupted.
     */
    private static byte[] requestTTSCurrent(String text) throws IOException, InterruptedException {

        String url = "https://tts.cyzon.us/tts?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();

        HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("TTS request failed with HTTP status " + status);
        }

        return response.body();
    }

    /**
     * Queries ElevenLabs TTS service and returns the raw audio bytes.
     *
     * @param text  The text to synthesize.
     * @param apiKey ElevenLabs API key.
     * @param voiceId ElevenLabs voice ID (overrides voice enum if provided).
     * @return Audio data as a byte array (MP3 format).
     * @throws IOException          on network/IO errors.
     * @throws InterruptedException if the request is interrupted.
     */
    private static byte[] requestTTSElevenLabs(String text, String apiKey, String voiceId)
            throws IOException, InterruptedException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("ElevenLabs API key is required");
        }

        // Map Voice enum to ElevenLabs voice IDs as fallback

        String url = "https://api.elevenlabs.io/v1/text-to-speech/" + voiceId;

        String requestBody = "{\"text\": \"" + text.replace("\"", "\\\"") + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .uri(URI.create(url))
                .header("Accept", "audio/mpeg")
                .header("Content-Type", "application/json")
                .header("xi-api-key", apiKey.trim())
                .build();

        HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String errorBody = new String(response.body(), StandardCharsets.UTF_8);
            throw new IOException("ElevenLabs TTS request failed with HTTP status " + status + ": " + errorBody);
        }

        return response.body();
    }
}
