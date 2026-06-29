package dev.d4nilpzz.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ChecksumUtils {

    public static void writeChecksums(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        writeChecksum(file, data, "MD5", ".md5");
        writeChecksum(file, data, "SHA-1", ".sha1");
        writeChecksum(file, data, "SHA-256", ".sha256");
    }

    private static void writeChecksum(Path file, byte[] data, String algorithm, String ext) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(data);
            String hex = bytesToHex(hash);
            Files.writeString(
                    Path.of(file + ext),
                    hex,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Checksum algorithm not available: " + algorithm, e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
