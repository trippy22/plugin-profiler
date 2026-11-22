package io.github.pluginprofiler;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ProfilerConfig.GROUP)
public interface ProfilerConfig extends Config
{
    String GROUP = "pluginprofiler";
    String HIDE_ZERO = "hideZeroUsage";
    String REFRESH_SECONDS = "panelRefreshSeconds";

    @ConfigItem(keyName = HIDE_ZERO, name = "Hide 0% Usage",
        description = "Hide rows that would display 0.0 or have no value for the selected ranking", hidden = true)
    default boolean hideZeroUsage() { return false; }

    @ConfigItem(keyName = REFRESH_SECONDS, name = "Panel refresh interval",
        description = "Seconds between updates to the visible ranking (1–60)", hidden = true)
    default int panelRefreshSeconds() { return 5; }
}
