package xyz.srnyx.annoyingapi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;


/**
 * Used for downloading {@link AnnoyingDependency}s
 */
public class AnnoyingDownload {
    @NotNull private final AnnoyingPlugin plugin;
    @NotNull private final String userAgent;
    @NotNull private final List<AnnoyingDependency> dependencies;
    private int remaining = 0;

    /**
     * Constructor for {@link AnnoyingDownload} with multiple dependencies
     *
     * @param   plugin          the plugin that is downloading the dependencies
     * @param   dependencies    the {@link AnnoyingDependency}s to download
     */
    @Contract(pure = true)
    public AnnoyingDownload(@NotNull AnnoyingPlugin plugin, @NotNull List<AnnoyingDependency> dependencies) {
        this.plugin = plugin;
        this.userAgent = plugin.getName() + "/" + plugin.getDescription().getVersion() + " via AnnoyingAPI";
        this.dependencies = dependencies;
    }

    /**
     * Constructor for {@link AnnoyingDownload} with a single dependency
     *
     * @param   plugin      the plugin that is downloading the {@link AnnoyingDependency}
     * @param   dependency  the {@link AnnoyingDependency} to download
     */
    public AnnoyingDownload(@NotNull AnnoyingPlugin plugin, @NotNull AnnoyingDependency dependency) {
        this(plugin, Collections.singletonList(dependency));
    }

    /**
     * Downloads the plugins
     */
    public void downloadPlugins() {
        remaining = dependencies.size();
        dependencies.forEach(dependency -> new Thread(() -> attemptDownload(dependency)).start());
    }

    /**
     * Retry downloading the dependency with a new {@link Platform}
     */
    private void attemptDownload(@NotNull AnnoyingDependency dependency) {
        final String name = dependency.name;
        final Map<Platform, String> platforms = dependency.platforms;
        
        // Modrinth
        if (platforms.containsKey(Platform.MODRINTH)) {
            modrinth(dependency);
            return;
        }

        // Spigot
        if (platforms.containsKey(Platform.SPIGOT)) {
            spigot(dependency);
            return;
        }

        // Bukkit
        if (platforms.containsKey(Platform.BUKKIT)) {
            downloadFile(dependency, Platform.BUKKIT, "https://dev.bukkit.org/projects/" + platforms.get(Platform.BUKKIT) + "/files/latest");
            return;
        }

        // External
        if (platforms.containsKey(Platform.EXTERNAL)) {
            downloadFile(dependency, Platform.EXTERNAL, platforms.get(Platform.EXTERNAL));
            return;
        }

        // Manual
        if (platforms.containsKey(Platform.MANUAL)) {
            plugin.log(Level.WARNING, "&6" + name + " &8|&e Please install this plugin manually at &6" + platforms.get(Platform.MANUAL));
            finish(dependency, false);
            return;
        }

        // Ran out of platforms
        plugin.log(Level.SEVERE, "&4" + name + " &8|&c Ran out of platforms!");
        finish(dependency, false);
    }

    /**
     * Downloads the plugin from Modrinth using their Labrinth API
     * <p>This will download the appropriate Minecraft version of the plugin
     */
    private void modrinth(@NotNull AnnoyingDependency dependency) {
        final String[] version = Bukkit.getBukkitVersion().split("\\.");
        final String url = "https://api.modrinth.com/v2/project/" + dependency.platforms.get(Platform.MODRINTH) + "/version" +
                "?loaders=%5B%22spigot%22,%22paper%22,%22purpur%22%5D" +
                "&game_versions=%5B%22" + version[0] + "." + version[1] + "." + version[2].split("-")[0] + "%22%5D";
        final JsonElement json = getJson(url);

        // Request failed
        if (json == null) {
            dependency.platforms.remove(Platform.MODRINTH);
            attemptDownload(dependency);
            return;
        }

        // Download file
        downloadFile(dependency, Platform.MODRINTH, json.getAsJsonArray().get(0).getAsJsonObject()
                .getAsJsonArray("files").get(0).getAsJsonObject()
                .get("url").getAsString());
    }

    /**
     * Downloads the plugin from Spigot using Spiget API
     * <p>This will check if the plugin is premium and/or external before attempting to download
     */
    private void spigot(@NotNull AnnoyingDependency dependency) {
        final Map<Platform, String> platforms = dependency.platforms;
        final String url = "https://api.spiget.org/v2/resources/" + platforms.get(Platform.SPIGOT);
        final JsonElement json = getJson(url);

        // Request failed
        if (json == null) {
            platforms.remove(Platform.SPIGOT);
            attemptDownload(dependency);
            return;
        }
        final JsonObject object = json.getAsJsonObject();

        // Resource is premium
        if (object.get("premium").getAsBoolean()) {
            platforms.remove(Platform.SPIGOT);
            attemptDownload(dependency);
            return;
        }

        // Resource is external
        if (object.get("external").getAsBoolean()) {
            platforms.remove(Platform.SPIGOT);

            // Get external URL and set new platform
            final String externalUrl = object
                    .get("file").getAsJsonObject()
                    .get("externalUrl").getAsString();
            if (externalUrl.endsWith(".jar")) {
                platforms.putIfAbsent(Platform.EXTERNAL, externalUrl);
            } else {
                platforms.putIfAbsent(Platform.MANUAL, externalUrl);
            }

            // Retry download
            attemptDownload(dependency);
            return;
        }

        // Download file
        downloadFile(dependency, Platform.SPIGOT,  url + "/download");
    }

    /**
     * Retrieves the {@link JsonElement} from the specified URL
     *
     * @param   urlString   the URL to retrieve the {@link JsonElement} from
     *
     * @return              the {@link JsonElement} retrieved from the specified URL
     */
    @Nullable
    private JsonElement getJson(@NotNull String urlString) {
        final HttpURLConnection connection;
        final JsonElement json;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", userAgent);
            if (connection.getResponseCode() == 404) return null;
            json = new JsonParser().parse(new InputStreamReader(connection.getInputStream()));
        } catch (final IOException e) {
            e.printStackTrace();
            return null;
        }
        connection.disconnect();
        return json;
    }

    /**
     * Downloads a file from a URL
     *
     * @param   platform    the platform of the URL
     * @param   urlString   the URL of the file
     */
    private void downloadFile(@NotNull AnnoyingDependency dependency, @NotNull Platform platform, @NotNull String urlString) {
        // Get URL connection
        final HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", userAgent);
        } catch (final IOException e) {
            e.printStackTrace();
            dependency.platforms.remove(platform);
            attemptDownload(dependency);
            return;
        }

        // Download file
        try (final BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
             final FileOutputStream out = new FileOutputStream(dependency.getFile())) {
            final byte[] buffer = new byte[1024];
            int numRead;
            while ((numRead = in.read(buffer)) != -1) out.write(buffer, 0, numRead);
        } catch (final IOException ignored) {
            //ignored
        }

        // Send success message
        plugin.log(Level.INFO, "&2" + dependency.name + " &8|&a Successfully downloaded from &2" + platform.name());
        finish(dependency, true);
    }

    /**
     * Finishes the download and checks if all plugins have been processed
     */
    private void finish(@NotNull AnnoyingDependency dependency, boolean enable) {
        // Load/enable plugin and register its commands
        final PluginManager manager = Bukkit.getPluginManager();
        if (enable && dependency.enableAfterDownload && manager.getPlugin(dependency.name) == null) {
            // Load and enable plugin
            Plugin dependencyPlugin = null;
            try {
                dependencyPlugin = manager.loadPlugin(dependency.getFile());
                dependencyPlugin.onLoad();
                manager.enablePlugin(dependencyPlugin);
            } catch (final InvalidPluginException | InvalidDescriptionException e) {
                plugin.log(Level.SEVERE, "&4" + dependency.name + " &8|&c Failed to load plugin!");
            }

            // Register commands
            if (dependencyPlugin != null) try {
                registerCommands(dependencyPlugin);
            } catch (final ClassNotFoundException e) {
                plugin.log(Level.WARNING, "&6" + dependency.name + " &8|&e Failed to register commands: &6" + e.getMessage());
            }
        }

        // Check if all plugins have been processed
        remaining--;
        if (remaining == 0) {
            plugin.log(Level.INFO, "\n&a&lAll &2&l" + dependencies.size() + "&a&l plugins have been processed!\n&aPlease resolve any errors and then restart the server.");
        }
    }

    /**
     * Registers the commands of the specified plugin using PlugManX
     *
     * @param   dependency  the plugin to register the commands of
     */
    private void registerCommands(@NotNull Plugin dependency) throws ClassNotFoundException {
        final AnnoyingCommandRegister register = AnnoyingPlugin.commandRegister;
        if (register == null) throw new ClassNotFoundException("AnnoyingCommandRegister is null");
        final String dependencyName = dependency.getName();
        for (final Map.Entry<String, Map<String, Object>> entry : dependency.getDescription().getCommands().entrySet()) {
            // Get command
            final PluginCommand command = Bukkit.getPluginCommand(entry.getKey());
            if (command == null) continue;

            // Set command settings
            command.setDescription((String) entry.getValue().get("description"));
            command.setUsage((String) entry.getValue().get("usage"));
            command.setPermission((String) entry.getValue().get("permission"));
            command.setPermissionMessage((String) entry.getValue().get("permission-message"));
            final Object aliases = entry.getValue().get("aliases");
            if (aliases instanceof List) {
                command.setAliases(((List<?>) aliases).stream()
                        .map(String.class::cast)
                        .collect(Collectors.toList()));
            } else if (aliases instanceof String) {
                command.setAliases(Collections.singletonList((String) aliases));
            }

            // Register command
            register.register(command, dependencyName);
        }
        register.sync();
    }

    /**
     * Platforms that plugins can be downloaded from
     */
    public enum Platform {
        /**
         * <a href="https://modrinth.com">{@code https://modrinth.com}</a>
         * <p>The project's ID <b>or</b> slug
         * <p><b>Example:</b> {@code lzjYdd5h} <i>or</i> {@code personal-phantoms}
         */
        MODRINTH,
        /**
         * <a href="https://spigotmc.org">{@code https://spigotmc.org}</a>
         * <p>The project's ID
         * <p><b>Example:</b> {@code 106381}
         */
        SPIGOT,
        /**
         * <a href="https://dev.bukkit.org">{@code https://dev.bukkit.org/projects}</a>
         * <p>The project's ID <b>or</b> slug
         */
        BUKKIT,
        /**
         * An external direct-download URL
         */
        EXTERNAL,
        /**
         * A URL that the user can manually download the plugin from
         */
        MANUAL
    }
}
