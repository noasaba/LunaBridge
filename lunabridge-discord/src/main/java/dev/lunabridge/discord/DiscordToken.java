package dev.lunabridge.discord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves a Discord credential without copying file-backed secrets into generated configuration. */
public final class DiscordToken {
    private DiscordToken() { }

    public static String resolve(String inlineToken, String tokenFile, Path dataDirectory) throws IOException {
        String configuredFile = tokenFile == null ? "" : tokenFile.trim();
        if (configuredFile.isEmpty()) return inlineToken == null ? "" : inlineToken.trim();
        Path path = Path.of(configuredFile);
        if (!path.isAbsolute()) path = dataDirectory.resolve(path).normalize();
        if (!Files.isRegularFile(path)) throw new IOException("Discord token-file is not a regular file: " + path);
        String token = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (token.isEmpty() || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0) {
            throw new IOException("Discord token-file must contain exactly one non-empty token");
        }
        return token;
    }
}
