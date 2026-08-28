package dev.noemt.loader;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixins;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public class NoemtLoader implements PreLaunchEntrypoint, ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("NoemtLoader");
    private static final String SERVER_BASE = "https://addons.noemt.dev";
    
    private static String cachedFlavor = null;
    private static File targetJarFile = null;
    private static boolean isPayloadReady = false;

    public NoemtLoader() {}

    public static String getFlavor() {
        if (cachedFlavor != null) return cachedFlavor;
        try (InputStream stream = NoemtLoader.class.getResourceAsStream("/loader-info.properties")) {
            if (stream != null) {
                Properties props = new Properties();
                props.load(stream);
                cachedFlavor = props.getProperty("flavor", "cheat").trim().toLowerCase();
            } else {
                cachedFlavor = "cheat";
            }
        } catch (Exception e) {
            cachedFlavor = "cheat";
        }
        return cachedFlavor;
    }

    @Override
    public void onPreLaunch() {
        String flavor = getFlavor();
        String flavorName = flavor.equals("legit") ? "Legit" : "Cheat";
        LOGGER.info("[NoemtLoader] PreLaunch: Bootstrapping NoemtAddons {}...", flavorName);

        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path cacheDir = gameDir.resolve("noemtaddons").resolve("cache");
        try {
            Files.createDirectories(cacheDir);
        } catch (Exception ignored) {}

        File jarFile = cacheDir.resolve("noemtaddons-" + flavor + ".jar").toFile();
        targetJarFile = jarFile;
        String downloadUrl = SERVER_BASE + "/loaders/noemtaddons-" + flavor + ".jar";

        // 1. Download or update payload jar before Mixins/Classes are initialized
        try {
            LOGGER.info("[NoemtLoader] Checking server for update: {}", downloadUrl);
            boolean updated = downloadIfNewer(downloadUrl, jarFile);
            if (updated || jarFile.exists()) {
                LOGGER.info("[NoemtLoader] Payload ready: {} ({} bytes)", jarFile.getName(), jarFile.length());
            }
        } catch (Exception e) {
            LOGGER.warn("[NoemtLoader] Update server unreachable: {}", e.getMessage());
            if (jarFile.exists()) {
                LOGGER.info("[NoemtLoader] Falling back to cached build: {}", jarFile.getAbsolutePath());
            }
        }

        // 2. Attach payload jar to KnotClassLoader and Register Mixins
        if (jarFile.exists() && jarFile.length() > 5000) {
            attachToKnotClassLoader(jarFile);
            registerMixins();
            isPayloadReady = true;
        } else {
            LOGGER.error("[NoemtLoader] Critical: No valid mod payload available!");
        }
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("[NoemtLoader] ClientInit: Launching NoemtAddons client...");
        if (targetJarFile != null && targetJarFile.exists() && isPayloadReady) {
            try {
                executeClientEntrypoint(targetJarFile);
            } catch (Throwable t) {
                LOGGER.error("[NoemtLoader] Error starting NoemtAddons client!", t);
            }
        } else {
            LOGGER.error("[NoemtLoader] Cannot initialize NoemtAddons: Payload is missing.");
        }
    }

    private boolean downloadIfNewer(String urlString, File destination) throws Exception {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "NoemtAddons-Loader/1.0.1 (" + getFlavor() + ")");

        if (destination.exists()) {
            conn.setIfModifiedSince(destination.lastModified());
        }

        conn.connect();
        int responseCode = conn.getResponseCode();

        if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            LOGGER.info("[NoemtLoader] Local mod files are already up to date.");
            return false;
        }

        if (responseCode == HttpURLConnection.HTTP_OK) {
            File tempFile = new File(destination.getParentFile(), destination.getName() + ".tmp");
            try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tempFile.toPath())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            if (tempFile.length() > 5000) {
                Files.move(tempFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[NoemtLoader] Downloaded latest {} payload ({} bytes).", getFlavor(), destination.length());
                return true;
            } else {
                tempFile.delete();
                throw new IllegalStateException("Downloaded file is incomplete.");
            }
        } else {
            throw new IllegalStateException("Server returned HTTP " + responseCode);
        }
    }

    private void attachToKnotClassLoader(File jarFile) {
        ClassLoader loader = NoemtLoader.class.getClassLoader();
        Path jarPath = jarFile.toPath();
        try {
            URL jarUrl = jarFile.toURI().toURL();

            // 1. Try KnotClassDelegate.addCodeSource(Path)
            try {
                Method getDelegate = loader.getClass().getDeclaredMethod("getDelegate");
                getDelegate.setAccessible(true);
                Object delegate = getDelegate.invoke(loader);
                Method addCodeSource = delegate.getClass().getMethod("addCodeSource", Path.class);
                addCodeSource.setAccessible(true);
                addCodeSource.invoke(delegate, jarPath);
                LOGGER.info("[NoemtLoader] Attached code source to KnotClassDelegate.");
            } catch (Throwable ignored) {}

            // 2. Try KnotClassLoader.addUrlFwd(URL)
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
