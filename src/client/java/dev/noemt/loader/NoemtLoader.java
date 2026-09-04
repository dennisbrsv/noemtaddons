package dev.noemt.loader;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixins;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Lightweight Bootstrap Mod Loader for NoemtAddons.
 * =================================================
 * Features:
 * - Remote Safety / Anticheat Kill-Switch Manifest Check
 * - Asymmetric Cryptographic Verification (Ed25519) before ClassLoader injection
 * - Zero-Execution on Tampered / Compromised Payloads
 * - KnotClassLoader Dynamic Bytecode & Mixin Injection
 */
public class NoemtLoader implements PreLaunchEntrypoint, ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("NoemtLoader");
    private static final String SERVER_BASE = "https://addons.noemt.dev";

    private static File targetJarFile = null;
    private static boolean isPayloadReady = false;

    public NoemtLoader() {}

    @Override
    public void onPreLaunch() {
        LOGGER.info("[NoemtLoader] PreLaunch: Bootstrapping NoemtAddons Secure Loader...");

        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path cacheDir = gameDir.resolve("noemtaddons").resolve("cache");
        try {
            Files.createDirectories(cacheDir);
        } catch (Exception ignored) {}

        File jarFile = cacheDir.resolve("noemtaddons.jar").toFile();
        File sigFile = cacheDir.resolve("noemtaddons.sig").toFile();
        targetJarFile = jarFile;

        // 1. Check Remote Kill-Switch / Safety Manifest
        if (!checkRemoteSafetySwitch()) {
            LOGGER.warn("[NoemtLoader] Mod loading aborted by remote safety manifest.");
            return;
        }

        String jarUrl = SERVER_BASE + "/loaders/noemtaddons.jar";
        String sigUrl = SERVER_BASE + "/loaders/noemtaddons.jar.sig";

        // 2. Fetch and Cryptographically Verify Remote Payload
        boolean downloadSuccess = false;
        try {
            LOGGER.info("[NoemtLoader] Checking update server: {}", jarUrl);
            downloadSuccess = fetchAndVerifyPayload(jarUrl, sigUrl, jarFile, sigFile);
        } catch (SecurityException se) {
            LOGGER.error("==========================================================================");
            LOGGER.error("[NoemtLoader] CRITICAL SECURITY ALERT: Remote payload rejected!");
            LOGGER.error("[NoemtLoader] Reason: {}", se.getMessage());
            LOGGER.error("[NoemtLoader] The remote server returned a payload with an invalid Ed25519 signature.");
            LOGGER.error("[NoemtLoader] Mod initialization safely halted. Classes will NOT be loaded.");
            LOGGER.error("==========================================================================");
            return;
        } catch (Exception e) {
            LOGGER.warn("[NoemtLoader] Update server unreachable or offline: {}", e.getMessage());
        }

        // 3. Fallback / Validation of Local Cached Payload
        if (!downloadSuccess && jarFile.exists()) {
            LOGGER.info("[NoemtLoader] Verifying cached local payload: {}", jarFile.getAbsolutePath());
            if (sigFile.exists() && IntegrityVerifier.verify(jarFile, sigFile)) {
                LOGGER.info("[NoemtLoader] Cached payload is authentic and verified.");
            } else {
                LOGGER.error("[NoemtLoader] Cached payload failed cryptographic signature check! Rejecting.");
                return;
            }
        }

        // 4. Inject Verified Payload into KnotClassLoader
        if (jarFile.exists() && jarFile.length() > 5000 && sigFile.exists() && IntegrityVerifier.verify(jarFile, sigFile)) {
            attachToKnotClassLoader(jarFile);
            registerMixins();
            isPayloadReady = true;
            LOGGER.info("[NoemtLoader] Verified payload successfully attached to JVM classpath.");
        } else {
            LOGGER.error("[NoemtLoader] No valid, cryptographically verified mod payload available.");
        }
    }

    @Override
    public void onInitializeClient() {
        if (isPayloadReady && targetJarFile != null && targetJarFile.exists()) {
            LOGGER.info("[NoemtLoader] ClientInit: Launching NoemtAddons client...");
            try {
                executeClientEntrypoint(targetJarFile);
            } catch (Throwable t) {
                LOGGER.error("[NoemtLoader] Error initializing NoemtAddons client entrypoint: {}", t.getMessage(), t);
            }
        } else {
            LOGGER.warn("[NoemtLoader] NoemtAddons initialization skipped (Payload missing, unverified, or disabled).");
        }
    }

    /**
     * Checks remote kill-switch / safety manifest.
     * Returns false if the server explicitly states "enabled": false.
     */
    private boolean checkRemoteSafetySwitch() {
        try {
            String manifestUrl = SERVER_BASE + "/api/manifest";
            byte[] data = downloadBytes(manifestUrl, 3500);
            if (data != null && data.length > 0) {
                String json = new String(data, StandardCharsets.UTF_8);
                if (json.contains("\"enabled\": false") || json.contains("\"enabled\":false")) {
                    LOGGER.warn("[NoemtLoader] Remote killswitch active. Manifest response: {}", json);
                    return false;
                }
            }
        } catch (Exception ignored) {
            // If manifest is unreachable, proceed to signature check
        }
        return true;
    }

    /**
     * Downloads payload JAR and signature, then performs Ed25519 cryptographic verification.
     */
    private boolean fetchAndVerifyPayload(String jarUrl, String sigUrl, File destJar, File destSig) throws Exception {
        // Download signature first
        byte[] sigBytes = downloadBytes(sigUrl, 6000);
        if (sigBytes == null || sigBytes.length == 0) {
            throw new SecurityException("Server did not supply an Ed25519 signature file (.sig).");
        }

        // Check 304 Not Modified if jar exists and signature matches cached sig
        if (destJar.exists() && destSig.exists()) {
            byte[] cachedSig = Files.readAllBytes(destSig.toPath());
            if (java.util.Arrays.equals(sigBytes, cachedSig) && IntegrityVerifier.verify(destJar, sigBytes)) {
                // Signature is identical and valid
                if (isServerFileUnmodified(jarUrl, destJar.lastModified())) {
                    LOGGER.info("[NoemtLoader] Local mod files are already up to date & verified.");
                    return true;
                }
            }
        }

        // Download full payload bytes
        byte[] jarBytes = downloadBytes(jarUrl, 15000);
        if (jarBytes == null || jarBytes.length < 5000) {
            throw new IllegalStateException("Downloaded payload jar is empty or corrupted.");
        }

        // Cryptographic Verification BEFORE writing to disk or loading
        boolean isAuthentic = IntegrityVerifier.verify(jarBytes, sigBytes);
        if (!isAuthentic) {
            throw new SecurityException("Cryptographic Ed25519 signature mismatch! Payload rejected.");
        }

        // Atomically write verified files to cache
        File tempJar = new File(destJar.getParentFile(), destJar.getName() + ".tmp");
        File tempSig = new File(destSig.getParentFile(), destSig.getName() + ".tmp");

        Files.write(tempJar.toPath(), jarBytes);
        Files.write(tempSig.toPath(), sigBytes);

        Files.move(tempJar.toPath(), destJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.move(tempSig.toPath(), destSig.toPath(), StandardCopyOption.REPLACE_EXISTING);

        LOGGER.info("[NoemtLoader] Successfully downloaded and verified new payload ({} bytes).", destJar.length());
        return true;
    }

    private boolean isServerFileUnmodified(String urlString, long lastModified) {
        try {
            URL url = URI.create(urlString).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "NoemtAddons-Loader/1.0.3");
            if (lastModified > 0) {
                conn.setIfModifiedSince(lastModified);
            }
            conn.connect();
            int code = conn.getResponseCode();
            return code == HttpURLConnection.HTTP_NOT_MODIFIED;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] downloadBytes(String urlString, int timeoutMs) throws Exception {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "NoemtAddons-Loader/1.0.3");
        conn.connect();

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("HTTP " + code + " from " + urlString);
        }

        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    private void attachToKnotClassLoader(File jarFile) {
        ClassLoader loader = NoemtLoader.class.getClassLoader();
        Path jarPath = jarFile.toPath();
        try {
            URL jarUrl = jarFile.toURI().toURL();

            // 1. KnotClassDelegate.addCodeSource(Path)
            try {
                Method getDelegate = loader.getClass().getDeclaredMethod("getDelegate");
                getDelegate.setAccessible(true);
                Object delegate = getDelegate.invoke(loader);
                Method addCodeSource = delegate.getClass().getMethod("addCodeSource", Path.class);
                addCodeSource.setAccessible(true);
                addCodeSource.invoke(delegate, jarPath);
                LOGGER.info("[NoemtLoader] Attached code source to KnotClassDelegate.");
            } catch (Throwable ignored) {}

            // 2. KnotClassLoader.addUrlFwd(URL) / addURL(URL)
            try {
                Method addUrlFwd = loader.getClass().getMethod("addUrlFwd", URL.class);
                addUrlFwd.setAccessible(true);
                addUrlFwd.invoke(loader, jarUrl);
                LOGGER.info("[NoemtLoader] Injected jar URL into KnotClassLoader.");
            } catch (Throwable ignored) {
                try {
                    Method addUrl = loader.getClass().getMethod("addURL", URL.class);
                    addUrl.setAccessible(true);
                    addUrl.invoke(loader, jarUrl);
                } catch (Throwable ignored2) {}
            }
        } catch (Exception e) {
            LOGGER.error("[NoemtLoader] Failed attaching jar to ClassLoader: {}", e.getMessage());
        }
    }

    private void registerMixins() {
        try {
            Mixins.addConfiguration("noemtaddons.client.mixins.json");
            LOGGER.info("[NoemtLoader] Registered Mixin config: noemtaddons.client.mixins.json");
        } catch (Throwable t) {
            LOGGER.warn("[NoemtLoader] Could not dynamically register Mixin config: {}", t.getMessage());
        }
    }

    private void executeClientEntrypoint(File jarFile) throws Exception {
        ClassLoader loader = NoemtLoader.class.getClassLoader();
        ClassLoader effectiveLoader;
        try {
            loader.loadClass("dev.noemt.client.NoemtaddonsClient");
            effectiveLoader = loader;
        } catch (ClassNotFoundException e) {
            effectiveLoader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, loader);
        }

        Class<?> clientClass = effectiveLoader.loadClass("dev.noemt.client.NoemtaddonsClient");
        Object instance;
        try {
            instance = clientClass.getField("INSTANCE").get(null);
        } catch (Throwable t) {
            instance = clientClass.getDeclaredConstructor().newInstance();
        }

        Method initMethod = clientClass.getMethod("onInitializeClient");
        initMethod.invoke(instance);
        LOGGER.info("[NoemtLoader] NoemtAddons client successfully initialized!");
    }
}
