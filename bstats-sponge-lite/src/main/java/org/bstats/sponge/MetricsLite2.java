package org.bstats.sponge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.spongepowered.api.Platform;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.scheduler.Scheduler;
import org.spongepowered.api.scheduler.Task;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

/**
 * bStats collects some data for plugin authors.
 *
 * Check out https://bStats.org/ to learn more about bStats!
 *
 * DO NOT modify any of this class. Access it from your own plugin ONLY.
 */
public class MetricsLite2 implements Metrics {
    /**
     * Internal class for storing information about old bStats instances.
     */
    private static class OutdatedInstance implements Metrics {
        private Object instance;
        private Method method;
        private PluginContainer plugin;

        private OutdatedInstance(Object instance, Method method, PluginContainer plugin) {
            this.instance = instance;
            this.method = method;
            this.plugin = plugin;
        }

        @Override
        public List<Metrics> getKnownMetricsInstances() {
            return new ArrayList<>();
        }

        @Override
        public JsonObject getPluginData() {
            try {
                return (JsonObject) method.invoke(instance);
            } catch (ClassCastException | IllegalAccessException | InvocationTargetException e) {
            }
            return null;
        }

        @Override
        public PluginContainer getPluginContainer() {
            return plugin;
        }

        @Override
        public TimerTask getTimerTask() {
            return null;
        }

        @Override
        public int getRevision() {
            return 0;
        }

        @Override
        public void linkMetrics(Metrics metrics) {
            // Do nothing
        }
    }

    static {
        // Do not touch. Needs to always be in this class.
        final String defaultName = "org:bstats:sponge:Metrics".replace(":", ".");
        if (!Metrics.class.getName().equals(defaultName)) {
            throw new IllegalStateException("bStats Metrics interface has been relocated or renamed and will not be run!");
        }
        if (!MetricsLite2.class.getName().equals(defaultName + "Lite2")) {
            throw new IllegalStateException("bStats MetricsLite2 class has been relocated or renamed and will not be run!");
        }
    }

    // The version of bStats info being sent
    public static final int B_STATS_VERSION = 1;

    // The version of this bStats class
    public static final int B_STATS_CLASS_REVISION = 2;

    // The url to which the data is sent
    private static final String URL = "https://bStats.org/submitData/sponge";

    // The logger
    private Logger logger;

    // The plugin
    private final PluginContainer plugin;

    // The uuid of the server
    private String serverUUID;

    // Should failed requests be logged?
    private boolean logFailedRequests = false;

    // A list with all known metrics class objects including this one
    private final List<Metrics> knownMetricsInstances = new CopyOnWriteArrayList<>();

    // The config path
    private Path configDir;

    // The list of instances from the bStats 1 instance's that started first
    private List<Object> oldInstances;

    // The timer task
    private TimerTask timerTask;

    // The constructor is not meant to be called by the user.
    // The instance is created using Dependency Injection (https://docs.spongepowered.org/master/en/plugin/injection.html)
    @Inject
    private MetricsLite2(PluginContainer plugin, Logger logger, @ConfigDir(sharedRoot = true) Path configDir) {
        this.plugin = plugin;
        this.logger = logger;
        this.configDir = configDir;

        Sponge.getEventManager().registerListeners(plugin, this);
    }

    @Listener
    public void startup(GamePreInitializationEvent event) {
        try {
            loadConfig();
        } catch (IOException e) {
            // Failed to load configuration
            logger.warn("Failed to load bStats config!", e);
            return;
        }

        Metrics provider;
        if (Sponge.getServiceManager().isRegistered(Metrics.class)) {
            provider = Sponge.getServiceManager().provideUnchecked(Metrics.class);
        } else {
            provider = this;
            Sponge.getServiceManager().setProvider(plugin.getInstance().get(), Metrics.class, this);
            startSubmitting();
        }
        provider.linkMetrics(this);
    }

    @Override
    public List<Metrics> getKnownMetricsInstances() {
        return knownMetricsInstances;
    }

    @Override
    public PluginContainer getPluginContainer() {
        return plugin;
    }

    @Override
    public TimerTask getTimerTask() {
        return timerTask;
    }

    @Override
    public int getRevision() {
        return B_STATS_CLASS_REVISION;
    }

    /**
     * Links a bStats 1 metrics class with this instance.
     *
     * @param metrics An object of the metrics class to link.
     */
    private void linkOldMetrics(Object metrics) {
        try {
            Field field = metrics.getClass().getDeclaredField("plugin");
            field.setAccessible(true);
            PluginContainer plugin = (PluginContainer) field.get(metrics);
            Method method = metrics.getClass().getMethod("getPluginData");
            linkMetrics(new OutdatedInstance(metrics, method, plugin));
        } catch (NoSuchFieldException | IllegalAccessException |NoSuchMethodException e) {
            // Move on, this bStats is broken
        }
    }

    /**
     * Links an other metrics class with this class.
     * This method is called using Reflection.
     *
     * @param metrics An object of the metrics class to link.
     */
    @Override
    public void linkMetrics(Metrics metrics) {
        knownMetricsInstances.add(metrics);
    }

    @Override
    public JsonObject getPluginData() {
        JsonObject data = new JsonObject();

        String pluginName = plugin.getName();
        String pluginVersion = plugin.getVersion().orElse("unknown");
        int revision = getRevision();

        data.addProperty("pluginName", pluginName);
        data.addProperty("pluginVersion", pluginVersion);
        data.addProperty("metricsRevision", revision);

        JsonArray customCharts = new JsonArray();
        data.add("customCharts", customCharts);

        return data;
    }

    private void startSubmitting() {
        // bStats 1 cleanup. Runs once.
        try {
            Path configPath = configDir.resolve("bStats");
            configPath.toFile().mkdirs();
            String className = readFile(new File(configPath.toFile(), "temp.txt"));
            if (className != null) {
                try {
                    // Let's check if a class with the given name exists.
                    Class<?> clazz = Class.forName(className);

                    // Time to eat it up!
                    Field instancesField = clazz.getDeclaredField("knownMetricsInstances");
                    instancesField.setAccessible(true);
                    oldInstances = (List<Object>) instancesField.get(null);
                    for (Object instance : oldInstances) {
                        linkOldMetrics(instance); // Om nom nom
                    }
                    oldInstances.clear(); // Look at me. I'm the bStats now.

                    // Cancel its timer task
                    // bStats for Sponge version 1 did not expose its timer task - gotta go find it!
                    Map<Thread, StackTraceElement[]> threadSet = Thread.getAllStackTraces();
                    for (Map.Entry<Thread, StackTraceElement[]> entry : threadSet.entrySet()) {
                        try {
                            if (entry.getKey().getName().startsWith("Timer")) {
                                Field timerThreadField = entry.getKey().getClass().getDeclaredField("queue");
                                timerThreadField.setAccessible(true);
                                Object taskQueue = timerThreadField.get(entry.getKey());

                                Field taskQueueField = taskQueue.getClass().getDeclaredField("queue");
                                taskQueueField.setAccessible(true);
                                Object[] tasks = (Object[]) taskQueueField.get(taskQueue);
                                for (Object task : tasks) {
                                    if (task == null) {
                                        continue;
                                    }
                                    if (task.getClass().getName().startsWith(clazz.getName())) {
                                        ((TimerTask) task).cancel();
                                    }
                                }
                            }
                        } catch (Exception e) {
                        }
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        } catch (IOException e) {
        }

        // We use a timer cause want to be independent from the server tps
        final Timer timer = new Timer(true);
        timerTask = new TimerTask() {
            @Override
            public void run() {
                // Catch any stragglers from inexplicable post-server-load plugin loading of outdated bStats
                for (Object instance : oldInstances) {
                    linkOldMetrics(instance); // Om nom nom
                }
                oldInstances.clear(); // Look at me. I'm the bStats now.
                // The data collection (e.g. for custom graphs) is done sync
                // Don't be afraid! The connection to the bStats server is still async, only the stats collection is sync ;)
                Scheduler scheduler = Sponge.getScheduler();
                Task.Builder taskBuilder = scheduler.createTaskBuilder();
                taskBuilder.execute(() -> submitData()).submit(plugin);
            }
        };
        timer.scheduleAtFixedRate(timerTask, 1000 * 60 * 5, 1000 * 60 * 30);
        // Submit the data every 30 minutes, first time after 5 minutes to give other plugins enough time to start
        // WARNING: Changing the frequency has no effect but your plugin WILL be blocked/deleted!
        // WARNING: Just don't do it!
    }

    /**
     * Gets the server specific data.
     *
     * @return The server specific data.
     */
    private JsonObject getServerData() {
        // Minecraft specific data
        int playerAmount = Sponge.getServer().getOnlinePlayers().size();
        playerAmount = playerAmount > 200 ? 200 : playerAmount;
        int onlineMode = Sponge.getServer().getOnlineMode() ? 1 : 0;
        String minecraftVersion = Sponge.getGame().getPlatform().getMinecraftVersion().getName();
        String spongeImplementation = Sponge.getPlatform().getContainer(Platform.Component.IMPLEMENTATION).getName();

        // OS/Java specific data
        String javaVersion = System.getProperty("java.version");
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        String osVersion = System.getProperty("os.version");
        int coreCount = Runtime.getRuntime().availableProcessors();

        JsonObject data = new JsonObject();

        data.addProperty("serverUUID", serverUUID);

        data.addProperty("playerAmount", playerAmount);
        data.addProperty("onlineMode", onlineMode);
        data.addProperty("minecraftVersion", minecraftVersion);
        data.addProperty("spongeImplementation", spongeImplementation);

        data.addProperty("javaVersion", javaVersion);
        data.addProperty("osName", osName);
        data.addProperty("osArch", osArch);
        data.addProperty("osVersion", osVersion);
        data.addProperty("coreCount", coreCount);

        return data;
    }

    /**
     * Collects the data and sends it afterwards.
     */
    private void submitData() {
        final JsonObject data = getServerData();

        JsonArray pluginData = new JsonArray();
        // Search for all other bStats Metrics classes to get their plugin data
        for (Metrics metrics : knownMetricsInstances) {
            if (!Sponge.getMetricsConfigManager().areMetricsEnabled(metrics.getPluginContainer())) {
                continue;
            }
            JsonObject plugin = metrics.getPluginData();
            if (plugin != null) {
                pluginData.add(plugin);
            }
        }

        if (pluginData.size() == 0) {
            return; // All plugins disabled, so we don't send anything
        }

        data.add("plugins", pluginData);

        // Create a new thread for the connection to the bStats server
        new Thread(() ->  {
            try {
                // Send the data
                sendData(data);
            } catch (Exception e) {
                // Something went wrong! :(
                if (logFailedRequests) {
                    logger.warn("Could not submit plugin stats!", e);
                }
            }
        }).start();
    }

    /**
     * Loads the bStats configuration.
     *
     * @throws IOException If something did not work :(
     */
    private void loadConfig() throws IOException {
        Path configPath = configDir.resolve("bStats");
        configPath.toFile().mkdirs();
        File configFile = new File(configPath.toFile(), "config.conf");
        HoconConfigurationLoader configurationLoader = HoconConfigurationLoader.builder().setFile(configFile).build();
        CommentedConfigurationNode node;
        if (!configFile.exists()) {
            configFile.createNewFile();
            node = configurationLoader.load();

            // Add default values
            node.getNode("enabled").setValue(false);
            // Every server gets it's unique random id.
            node.getNode("serverUuid").setValue(UUID.randomUUID().toString());
            // Should failed request be logged?
            node.getNode("logFailedRequests").setValue(false);

            node.getNode("enabled").setComment(
                    "Enabling bStats in this file is deprecated. At least one of your plugins now uses the\n" +
                            "Sponge config to control bStats. Leave this value as you want it to be for outdated plugins,\n" +
                            "but look there for further control");
            // Add information about bStats
            node.getNode("serverUuid").setComment(
                    "bStats collects some data for plugin authors like how many servers are using their plugins.\n" +
                            "To control whether this is enabled or disabled, see the Sponge configuration file.\n" +
                            "Check out https://bStats.org/ to learn more :)"
            );
            node.getNode("configVersion").setValue(2);

            configurationLoader.save(node);
        } else {
            node = configurationLoader.load();

            if (!node.getNode("configVersion").isVirtual()) {

                node.getNode("configVersion").setValue(2);

                node.getNode("enabled").setComment(
                        "Enabling bStats in this file is deprecated. At least one of your plugins now uses the\n" +
                                "Sponge config to control bStats. Leave this value as you want it to be for outdated plugins,\n" +
                                "but look there for further control");

                node.getNode("serverUuid").setComment(
                        "bStats collects some data for plugin authors like how many servers are using their plugins.\n" +
                                "To control whether this is enabled or disabled, see the Sponge configuration file.\n" +
                                "Check out https://bStats.org/ to learn more :)"
                );

                configurationLoader.save(node);
            }
        }

        // Load configuration
        serverUUID = node.getNode("serverUuid").getString();
        logFailedRequests = node.getNode("logFailedRequests").getBoolean(false);
    }

    /**
     * Reads the first line of the file.
     *
     * @param file The file to read. Cannot be null.
     * @return The first line of the file or <code>null</code> if the file does not exist or is empty.
     * @throws IOException If something did not work :(
     */
    private String readFile(File file) throws IOException {
        if (!file.exists()) {
            return null;
        }
        try (
                FileReader fileReader = new FileReader(file);
                BufferedReader bufferedReader =  new BufferedReader(fileReader);
        ) {
            return bufferedReader.readLine();
        }
    }

    /**
     * Sends the data to the bStats server.
     *
     * @param data The data to send.
     * @throws Exception If the request failed.
     */
    private static void sendData(JsonObject data) throws Exception {
        Validate.notNull(data, "Data cannot be null");
        HttpsURLConnection connection = (HttpsURLConnection) new URL(URL).openConnection();

        // Compress the data to save bandwidth
        byte[] compressedData = compress(data.toString());

        // Add headers
        connection.setRequestMethod("POST");
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("Connection", "close");
        connection.addRequestProperty("Content-Encoding", "gzip"); // We gzip our request
        connection.addRequestProperty("Content-Length", String.valueOf(compressedData.length));
        connection.setRequestProperty("Content-Type", "application/json"); // We send our data in JSON format
        connection.setRequestProperty("User-Agent", "MC-Server/" + B_STATS_VERSION);

        // Send data
        connection.setDoOutput(true);
        DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
        outputStream.write(compressedData);
        outputStream.flush();
        outputStream.close();

        connection.getInputStream().close(); // We don't care about the response - Just send our data :)
    }

    /**
     * Gzips the given String.
     *
     * @param str The string to gzip.
     * @return The gzipped String.
     * @throws IOException If the compression failed.
     */
    private static byte[] compress(final String str) throws IOException {
        if (str == null) {
            return null;
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(outputStream);
        gzip.write(str.getBytes("UTF-8"));
        gzip.close();
        return outputStream.toByteArray();
    }

}
