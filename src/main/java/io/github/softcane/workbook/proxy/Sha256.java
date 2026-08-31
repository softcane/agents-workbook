package io.github.softcane.workbook.proxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hex SHA-256. Every identifier this proxy shows a dashboard reader is one of these rather than the value
 * it was derived from, so the one implementation lives here instead of once per caller.
 */
public final class Sha256 {
    private Sha256() { }

    public static String of(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
