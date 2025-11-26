package com.example.BarracudaTrialsAdditions;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup("BarracudaTrialsAdditions")
public interface BarracudaTrialsAdditionsConfig extends Config {
    @ConfigItem(
        keyName = "hideTransition",
        name = "Hide portal transition",
        description = "",
        position = 0
    )
    default boolean hideTransition() {
        return true;
    }
    @ConfigItem(
        keyName = "showSplits",
        name = "Show splits",
        description = "",
        position = 1
    )
    default boolean showSplits() {
        return true;
    }

    @ConfigItem(
        keyName = "debug",
        name = "Debug Mode",
        description = "Enable debug output to chat",
        position = 99
    )
    default boolean debug() {
        return false;
    }
}
