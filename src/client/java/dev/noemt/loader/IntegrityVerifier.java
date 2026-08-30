package dev.noemt.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Cryptographic Asymmetric Integrity Verifier for NoemtAddons Mod Payloads.
 * =========================================================================
 * Enforces Ed25519 signature verification against the embedded developer public key.
 * Guarantees that any untrusted, modified, or hijacked remote payload is strictly
 * rejected prior to class loading or JVM execution.
 */
public final class IntegrityVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger("NoemtLoader-Integrity");

    /**
     * Base64-encoded X.509 DER Public Key (Ed25519).
     * The private key is held strictly offline by the developers.
     */
    public static final String PUBLIC_KEY_BASE64 =
            "MCowBQYDK2VwAyEAmA3Ke19LpvFX+bbVsLwIQVA2Mv2vnzdIWuyDI6r4F00=";

    private static PublicKey cachedPublicKey = null;

    private IntegrityVerifier() {}

    /**
     * Initializes and caches the Ed25519 Public Key instance.
     */
    public static synchronized PublicKey getPublicKey() {
        if (cachedPublicKey != null) {
            return cachedPublicKey;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(PUBLIC_KEY_BASE64.trim());
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            cachedPublicKey = kf.generatePublic(spec);
            return cachedPublicKey;
        } catch (Exception e) {
            LOGGER.error("[IntegrityVerifier] Failed to decode embedded public key: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Verifies the cryptographic Ed25519 signature of the payload bytes.
     *
     * @param payloadBytes   The raw bytes of the downloaded or cached payload .jar
     * @param signatureBytes The 64-byte Ed25519 signature (binary or decoded from Base64)
     * @return true if and only if the signature is mathematically authentic; false otherwise.
     */
    public static boolean verify(byte[] payloadBytes, byte[] signatureBytes) {
        if (payloadBytes == null || payloadBytes.length < 1000) {
            LOGGER.error("[IntegrityVerifier] Rejection: Payload is empty or unreasonably small ({} bytes).",
                    payloadBytes == null ? 0 : payloadBytes.length);
            return false;
        }

        if (signatureBytes == null || signatureBytes.length == 0) {
            LOGGER.error("[IntegrityVerifier] Rejection: Missing cryptographic signature.");
            return false;
        }

        // If signature was provided as ASCII Base64 string bytes, attempt decode
        byte[] rawSig = signatureBytes;
        if (rawSig.length > 64 && rawSig.length < 120) {
            try {
                String sigStr = new String(rawSig).trim();
                rawSig = Base64.getDecoder().decode(sigStr);
            } catch (Exception ignored) {}
        }

        if (rawSig.length != 64) {
            LOGGER.error("[IntegrityVerifier] Rejection: Invalid Ed25519 signature length ({} bytes, expected 64).",
                    rawSig.length);
            return false;
        }

        try {
            PublicKey pubKey = getPublicKey();
            if (pubKey == null) {
                LOGGER.error("[IntegrityVerifier] Rejection: Embedded public key is unavailable.");
                return false;
            }

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pubKey);
            verifier.update(payloadBytes);
            boolean isValid = verifier.verify(rawSig);

            if (isValid) {
                LOGGER.info("[IntegrityVerifier] Payload signature verified successfully! (Ed25519 Authentic)");
            } else {
                LOGGER.error("==========================================================================");
                LOGGER.error("[IntegrityVerifier] CRITICAL SECURITY ALERT: Signature mismatch!");
                LOGGER.error("[IntegrityVerifier] The payload .jar does NOT match the developer Ed25519 key.");
                LOGGER.error("[IntegrityVerifier] Possible tampering, network interception, or corruption.");
                LOGGER.error("==========================================================================");
            }
            return isValid;
        } catch (Exception e) {
            LOGGER.error("[IntegrityVerifier] Verification exception encountered: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Helper to verify payload file against a signature file on disk.
     */
    public static boolean verify(File payloadFile, File signatureFile) {
        if (payloadFile == null || !payloadFile.exists() || signatureFile == null || !signatureFile.exists()) {
            return false;
        }
        try {
            byte[] payloadBytes = Files.readAllBytes(payloadFile.toPath());
            byte[] signatureBytes = Files.readAllBytes(signatureFile.toPath());
            return verify(payloadBytes, signatureBytes);
        } catch (Exception e) {
            LOGGER.error("[IntegrityVerifier] Failed reading files for verification: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Helper to verify payload file on disk against raw in-memory signature bytes.
     */
    public static boolean verify(File payloadFile, byte[] signatureBytes) {
        if (payloadFile == null || !payloadFile.exists()) {
            return false;
        }
        try {
            byte[] payloadBytes = Files.readAllBytes(payloadFile.toPath());
            return verify(payloadBytes, signatureBytes);
        } catch (Exception e) {
            LOGGER.error("[IntegrityVerifier] Failed reading payload file for verification: {}", e.getMessage());
            return false;
        }
    }
}
