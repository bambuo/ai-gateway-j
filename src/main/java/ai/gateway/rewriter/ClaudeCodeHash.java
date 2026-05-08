package ai.gateway.rewriter;

import org.jspecify.annotations.NonNull;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.random.RandomGenerator;

public final class ClaudeCodeHash {

    private static final String SALT = "59cf53e54c78";
    private static final int[] POSITIONS = {4, 7, 20};
    private static final RandomGenerator RNG = RandomGenerator.getDefault();

    public static @NonNull String compute(String firstUserMessageText, String version) {
        var sb = new StringBuilder(3);
        for (var pos : POSITIONS) {
            sb.append(pos < firstUserMessageText.length() ? firstUserMessageText.charAt(pos) : '0');
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((SALT + sb + version).getBytes());
            return HexFormat.of().formatHex(digest.digest()).substring(0, 3);
        }
        catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static @NonNull String fallbackHash() {
        var bytes = new byte[2];
        RNG.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes).substring(0, 3);
    }
}
