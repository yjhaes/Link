package com.example.shortlink.observability;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Small JRE-only container probe, separately compiled so it needs no Boot startup or curl. */
public final class CoreReadinessProbe {
    private CoreReadinessProbe() {}
    public static void main(String[] arguments) {
        int result = 1;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:8080/readyz").toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() == 200) {
                try (var body = connection.getInputStream()) {
                    if ("{\"status\":\"UP\"}".equals(new String(body.readNBytes(128), StandardCharsets.UTF_8))) result = 0;
                }
            }
        } catch (Exception unavailable) {
            // Probe output must not contain endpoints, secrets or native exceptions.
        } finally {
            if (connection != null) connection.disconnect();
        }
        System.exit(result);
    }
}
