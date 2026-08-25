package dev.lunabridge.discord;

/** Discord safety and length rules shared by both platform adapters. */
public final class DiscordText {
    public static final int MAX_LENGTH = 2_000;

    private DiscordText() { }

    public static String fit(String text) {
        if (text.length() <= MAX_LENGTH) return text;
        int end = MAX_LENGTH - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + '…';
    }

    public static String suppressMentions(String text) {
        return text.replace("@", "@\u200B");
    }

    public static String suppressMentionsExceptLeadingRole(String text) {
        int end = text.indexOf('>');
        return end < 0
                ? suppressMentions(text)
                : text.substring(0, end + 1) + suppressMentions(text.substring(end + 1));
    }

    /** Removes Bukkit's legacy presentation codes without changing the API message itself. */
    public static String stripMinecraftLegacyFormatting(String text) {
        int marker = text.indexOf('\u00a7');
        if (marker < 0) return text;
        StringBuilder plain = new StringBuilder(text.length());
        plain.append(text, 0, marker);
        for (int index = marker; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\u00a7' && index + 1 < text.length()
                    && isLegacyFormattingCode(text.charAt(index + 1))) {
                index++;
                continue;
            }
            plain.append(current);
        }
        return plain.toString();
    }

    private static boolean isLegacyFormattingCode(char value) {
        char code = Character.toLowerCase(value);
        return code >= '0' && code <= '9'
                || code >= 'a' && code <= 'f'
                || code >= 'k' && code <= 'o'
                || code == 'r'
                || code == 'x';
    }
}
