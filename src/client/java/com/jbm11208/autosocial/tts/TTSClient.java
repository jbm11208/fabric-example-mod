package com.jbm11208.autosocial.tts;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Small wrapper around StreamElements TTS endpoint.
 *
 * Usage example:
 * <pre>
 *     byte[] wav = TTSClient.requestTTS("Hello world!", Voice.Brian);
 *     // write the bytes to a file, feed to a SourceDataLine, etc.
 * </pre>
 */
public final class TTSClient {

    /** Base URL used by the original Python script. */
    private static final String BASE_URL = "https://api.streamelements.com/kappa/v2/speech";

    /** Shared HTTP client – thread‑safe and reusable. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private TTSClient() {
        // utility class – prevent instantiation
    }

    /**
     * Queries the StreamElements TTS API and returns the raw audio bytes.
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

        // Build query string – proper escaping of both parameters.
        StringBuilder qs = new StringBuilder();
        qs.append("voice=").append(URLEncoder.encode(
                voice != null ? voice.getApiName() : Voice.Brian.getApiName(),
                StandardCharsets.UTF_8));
        qs.append('&');
        qs.append("text=").append(URLEncoder.encode(text, StandardCharsets.UTF_8));

        URI uri = URI.create(BASE_URL + "?" + qs);

        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(uri)
                .header("User-Agent", "AutoSocialMod/1.0")
                .build();

        HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("TTS request failed with HTTP status " + status);
        }

        return response.body();
    }
}
