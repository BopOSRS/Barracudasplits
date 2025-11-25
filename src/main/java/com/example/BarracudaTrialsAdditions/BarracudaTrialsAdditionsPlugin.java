package com.example.BarracudaTrialsAdditions;

import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.Getter;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import java.util.ArrayList;
import java.util.List;

@PluginDescriptor(
        name = "Barracuda Trials additions",
        description = "Description for BarracudaTrials",
        tags = {""}
)
public class BarracudaTrialsAdditionsPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private KeyManager keyManager;

    @Inject
    private BarracudaTrialsAdditionsConfig config;

    @Getter
    private boolean started;

    private int portalIndex = 0;
    private int currentTime = -1;      // total ticks since start (from script 8605)
    private int lastSplitTime = 0;     // total ticks at previous portal
    private final List<String> splits = new ArrayList<>();

    private static final int GOTR_ADJUST_PORTAL_SCRIPT_ID = 5986;
    private static final int CREATE_PORTAL_OVERLAY_SCRIPT_ID = 5984;
    private static final int GT_UI_SCRIPT_ID = 8605;

    @Provides
    BarracudaTrialsAdditionsConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(BarracudaTrialsAdditionsConfig.class);
    }

    @Override
    protected void startUp()
    {
        reset();
    }

    @Override
    protected void shutDown()
    {
        reset();
    }

    // -------------------------------------------------------------------------
    // State handling: start / finish / reset
    // -------------------------------------------------------------------------

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // Single source of truth for start/end of a run
        int startTime = client.getVarpValue(VarPlayerID.SAILING_BT_TIME_START);

        // Run starts
        if (!started && startTime != 0)
        {
            reset();
            started = true;
            print("BarracudaTrials: run started", true);
            return;
        }

        // Run ends or is reset (teleport back, overlay removed, etc.)
        if (started && startTime == 0)
        {
            // currentTime is the last value we got from script 8605 while running.
            // Often you want +1 here because the last tick isn't included in the delta.
            int total = currentTime >= 0 ? currentTime + 1 : -1;

            if (portalIndex > 0)
            {
                print("Run finished, total time = " + total + " ticks", true);
            }
            else
            {
                print("Run reset before completing any portals.", true);
            }

            for (String s : splits)
            {
                print(s, true);
            }
            reset();
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event.getType() != ChatMessageType.GAMEMESSAGE)
        {
            return;
        }

        String msg = event.getMessage();

        if (msg.startsWith("Your Gwenith Glide completion count is:"))
        {
            if (!started) return;
            // course completed, print splits
            handleCourseCompleted();
        }
    }

    private void handleCourseCompleted()
    {
        // currentTime is the last value from script 8605.
        int total = currentTime >= 0 ? currentTime + 1 : -1;

        print("Run finished (completed), total time = " + total + " ticks", true);
        for (String s : splits)
        {
            print(s, false);
        }

        reset();
    }


    // -------------------------------------------------------------------------
    // Script hooks
    // -------------------------------------------------------------------------

    @Subscribe
    private void onScriptPreFired(ScriptPreFired event)
    {
        int scriptId = event.getScriptId();

        // 8605: UI script that updates time once per gametick;
        // we only use it to refresh currentTime
        if (scriptId == GT_UI_SCRIPT_ID)
        {
            updateCurrentTimeFromScript(event);
            return;
        }

        // 5984: portal overlay created -> log split
        if (scriptId == CREATE_PORTAL_OVERLAY_SCRIPT_ID)
        {
            handlePortalSplit();
            return;
        }

        // 5986: remove portal overlay (also used in gotr)
        if (config.hideTransition() && scriptId == GOTR_ADJUST_PORTAL_SCRIPT_ID)
        {
            removePortalOverlay(event);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void updateCurrentTimeFromScript(ScriptPreFired event)
    {
        if (!started)
        {
            return;
        }

        int startTime = client.getVarpValue(VarPlayerID.SAILING_BT_TIME_START);
        if (startTime == 0)
        {
            // run is over, don't update
            return;
        }

        Object[] args = event.getScriptEvent().getArguments();
        if (args == null || args.length == 0)
        {
            return;
        }

        // last arg is "now"; time since some epoch in ticks
        int now = (int) args[args.length - 1];
        currentTime = now - startTime;
    }

    private void handlePortalSplit()
    {
        if (!started || currentTime < 0)
        {
            return;
        }

        int delta = currentTime - lastSplitTime;
        String msg = "Portal " + portalIndex + " time: " + currentTime + " ticks (" + delta + ")";

        splits.add(msg);
        print(msg, false);

        lastSplitTime = currentTime;
        portalIndex++;
    }

    private void removePortalOverlay(ScriptPreFired event)
    {
        Object[] args = event.getScriptEvent().getArguments();
        if (args == null || args.length < 3)
        {
            return;
        }

        int gameCycle = client.getGameCycle();
        // Force int0 so (clientclock - int0) >= 20
        args[1] = gameCycle - 100;
        // Force boolean1 = false so cc_deleteall(...) is used
        args[2] = 0;
    }

    private void reset()
    {
        started = false;
        portalIndex = 0;
        currentTime = -1;
        lastSplitTime = 0;
        splits.clear();
    }

    private void print(String message, boolean debug)
    {
        if (debug && !config.debug()) return;
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, "");
    }
}
