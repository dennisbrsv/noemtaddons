import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/**
 * Standalone Offline Ed25519 Payload Signing Utility
 * ===================================================
 * Used to generate offline asymmetric keys and sign compiled mod JAR payloads.
 * 
 * Usage:
 *   java scripts/Signer.java generate-keys
 *   java scripts/Signer.java sign <path/to/payload.jar> [path/to/private.key]
 *   java scripts/Signer.java verify <path/to/payload.jar> <path/to/payload.sig> [publicKeyBase64]
 */
public class Signer {

    private static final String DEFAULT_PRIV_KEY = "keys/ed25519_private.key";
    private static final String DEFAULT_PUB_KEY = "keys/ed25519_public.key";

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String command = args[0].toLowerCase();
        try {
            switch (command) {
                case "generate-keys":
                case "genkeys":
                    generateKeys(Paths.get(DEFAULT_PRIV_KEY), Paths.get(DEFAULT_PUB_KEY));
                    break;

                case "sign":
                    if (args.length < 2) {
                        System.err.println("Error: Missing JAR path. Usage: sign <path/to/payload.jar> [privateKeyPath]");
                        System.exit(1);
                    }
                    Path jarPath = Paths.get(args[1]);
                    Path privKeyPath = args.length >= 3 ? Paths.get(args[2]) : Paths.get(DEFAULT_PRIV_KEY);
                    Path sigPath = Paths.get(args[1] + ".sig");
                    signPayload(jarPath, privKeyPath, sigPath);
                    break;

                case "verify":
                    if (args.length < 3) {
                        System.err.println("Error: Usage: verify <payload.jar> <payload.sig> [publicKeyBase64]");
                        System.exit(1);
                    }
                    Path vJarPath = Paths.get(args[1]);
                    Path vSigPath = Paths.get(args[2]);
                    String pubKeyStr = args.length >= 4 ? args[3] : null;
                    if (pubKeyStr == null && Files.exists(Paths.get(DEFAULT_PUB_KEY))) {
                        byte[] pubBytes = Files.readAllBytes(Paths.get(DEFAULT_PUB_KEY));
                        pubKeyStr = Base64.getEncoder().encodeToString(pubBytes);
                    }
                    if (pubKeyStr == null) {
                        System.err.println("Error: No public key provided or found at " + DEFAULT_PUB_KEY);
                        System.exit(1);
                    }
                    boolean ok = verifyPayload(vJarPath, vSigPath, pubKeyStr);
                    System.out.println("Verification Result: " + (ok ? "VALID ✅" : "INVALID ❌"));
                    if (!ok) System.exit(1);
                    break;

                default:
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Execution failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void generateKeys(Path privPath, Path pubPath) throws Exception {
        System.out.println("🔑 Generating Ed25519 KeyPair...");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();

        if (privPath.getParent() != null) {
            Files.createDirectories(privPath.getParent());
        }

        byte[] privBytes = kp.getPrivate().getEncoded();
        byte[] pubBytes = kp.getPublic().getEncoded();

        Files.write(privPath, privBytes);
        Files.write(pubPath, pubBytes);

        String pubBase64 = Base64.getEncoder().encodeToString(pubBytes);

        System.out.println("\n" + "=".repeat(70));
        System.out.println("✅ Keys generated successfully!");
        System.out.println("   Private Key (SECRET, KEEP OFFLINE): " + privPath.toAbsolutePath());
        System.out.println("   Public Key File:                    " + pubPath.toAbsolutePath());
        System.out.println("   Base64 Public Key (Embed in Loader):");
        System.out.println("   " + pubBase64);
        System.out.println("=".repeat(70) + "\n");
    }

    public static void signPayload(Path jarPath, Path privKeyPath, Path sigOutputPath) throws Exception {
        if (!Files.exists(jarPath)) {
            throw new IllegalArgumentException("Payload file does not exist: " + jarPath);
        }
        if (!Files.exists(privKeyPath)) {
            throw new IllegalArgumentException("Private key file does not exist: " + privKeyPath + "\nRun 'generate-keys' first.");
        }

        byte[] jarBytes = Files.readAllBytes(jarPath);
        byte[] privKeyBytes = Files.readAllBytes(privKeyPath);

        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(privKeyBytes);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = kf.generatePrivate(spec);

        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(jarBytes);
        byte[] signatureBytes = sig.sign();

        Files.write(sigOutputPath, signatureBytes);

        System.out.println("✍️ Signed payload: " + jarPath.getFileName() + " (" + jarBytes.length + " bytes)");
        System.out.println("   Output signature: " + sigOutputPath.toAbsolutePath() + " (" + signatureBytes.length + " bytes)");
        System.out.println("   Base64 Signature: " + Base64.getEncoder().encodeToString(signatureBytes));
    }

    public static boolean verifyPayload(Path jarPath, Path sigPath, String publicKeyBase64) throws Exception {
        if (!Files.exists(jarPath) || !Files.exists(sigPath)) {
            System.err.println("File or signature missing.");
            return false;
        }

        byte[] jarBytes = Files.readAllBytes(jarPath);
        byte[] sigBytes = Files.readAllBytes(sigPath);
        byte[] pubKeyBytes = Base64.getDecoder().decode(publicKeyBase64.trim());

        X509EncodedKeySpec spec = new X509EncodedKeySpec(pubKeyBytes);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        PublicKey publicKey = kf.generatePublic(spec);

        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(publicKey);
        sig.update(jarBytes);
        return sig.verify(sigBytes);
    }

    private static void printUsage() {
        System.out.println("NoemtAddons Ed25519 Payload Signer");
        System.out.println("Commands:");
        System.out.println("  java scripts/Signer.java generate-keys");
        System.out.println("  java scripts/Signer.java sign <path/to/payload.jar> [privateKeyPath]");
        System.out.println("  java scripts/Signer.java verify <path/to/payload.jar> <path/to/payload.sig> [publicKeyBase64]");
    }
}
