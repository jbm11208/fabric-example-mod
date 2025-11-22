package com.jbm11208.autosocial.tts;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Small wrapper around a free TTS endpoint.
 *
 * Usage example:
 * <pre>
 *     byte[] wav = TTSClient.requestTTS("Hello world!", Voice.Brian);
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

    /**
     * Queries a free TTS service and returns the raw audio bytes.
     *
     * @param text  The text to synthesize.
     * @param voice Desired voice; if {@code null} the API defaults to “Brian”.
     * @return Audio data as a byte array (typically a WAV file).
     * @throws IOException          on network/IO errors.
     * @throws InterruptedException if the request is interrupted.
     */
    public static byte[] requestTTS(String text, Voice voice) throws IOException, InterruptedException {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }

        // Use a simple free TTS service - this one supports multiple voices including Geraint
        String voiceParam = switch (voice) {
            case Geraint, Brian, Amy, Emma, Justin -> voice.getApiName();
            default ->
                // Fallback to a standard voice if the specific one isn't supported
                    "Geraint";
        }; // Default fallback
        // Map to voices supported by this service

        String url = "https://tts.cyzon.us/tts?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8) + 
                     "&voice=" + URLEncoder.encode(voiceParam, StandardCharsets.UTF_8);

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
}
