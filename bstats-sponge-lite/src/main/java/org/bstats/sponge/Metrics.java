package org.bstats.sponge;

import com.google.gson.JsonObject;
import org.spongepowered.api.plugin.PluginContainer;

import java.util.List;
import java.util.TimerTask;

public interface Metrics {
    /**
     * Gets all metrics instances known to this instance.
     * For taking over if replacing an older version.
     *
     * @return
     */
    List<Metrics> getKnownMetricsInstances();

    /**
     * Gets the plugin specific data
     *
     * @return the plugin specific data or null if failure to acquire
     */
    JsonObject getPluginData();

    /**
     * Gets the plugin container for this instance.
     *
     * @return
     */
    PluginContainer getPluginContainer();

    /**
     * Gets the timer task, if one is scheduled.
     * Designed for cancelling to replace outdated versions.
     *
     * @return timer task
     */
    TimerTask getTimerTask();

    /**
     * Gets the revision of this bStats instance.
     *
     * @return revision
     */
    int getRevision();

    /**
     * Links another metrics instance to this one, which should be the master instance.
     *
     * @param metrics metrics instance
     */
    void linkMetrics(Metrics metrics);
}
