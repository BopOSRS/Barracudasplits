package com.example.BarracudaTrialsAdditions;

import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.Getter;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@PluginDescriptor(
        name = "Gwenith Glide additions",
        description = "Some QoL for Gwenith Glide",
        tags = {"barracuda", "trials"}
)
public class BarracudaTrialsAdditionsPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private BarracudaTrialsAdditionsConfig config;

    private boolean started;
    private int portalIndex = 1;
    private int currentTime = -1;      // total ticks since start (from script 8605)
    private int lastSplitTime = 0;     // total ticks at previous portal
    private final List<String> splits = new ArrayList<>();

    private int lastStartVarp = 0;     // for detecting 0 -> non-zero (new run)

    private static final int GOTR_ADJUST_PORTAL_SCRIPT_ID = 5986;
    private static final int CREATE_PORTAL_OVERLAY_SCRIPT_ID = 5984;
    private static final int GT_UI_SCRIPT_ID = 8605;

    // directory for times
    private static final File TIMES_DIR =
            new File(RuneLite.RUNELITE_DIR.getPath() + File.separator + "gwenith-glide");

    @Provides
    BarracudaTrialsAdditionsConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(BarracudaTrialsAdditionsConfig.class);
    }

    @Override
    protected void startUp()
    {
        if (!TIMES_DIR.exists())
        {
            TIMES_DIR.mkdirs();
        }
        reset();
    }

    @Override
    protected void shutDown()
    {
        reset();
    }

    // State handling: start / finish / reset

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
            // this seems to be more than one tick if the last thing we do is enter a portal,
            // picking up a box makes it just +1 i think?
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

    // Completion detection via chat -> export to {NAME}_times.txt

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event.getType() != ChatMessageType.GAMEMESSAGE)
        {
            return;
        }

        String msg = Text.removeTags(event.getMessage());
        if (msg.startsWith("Your Gwenith Glide completion count is:"))
        {
            if (!started)
            {
                return;
            }
            int kc = parseKc(msg); // may be -1 if parsing fails
            handleCourseCompleted(kc);
        }
    }

    private int parseKc(String msg)
    {
        try
        {
            // "Your Gwenith Glide completion count is: X"
            int colonIdx = msg.indexOf(':');
            if (colonIdx == -1)
            {
                return -1;
            }

            String afterColon = msg.substring(colonIdx + 1).trim();
            // afterColon should now be just the number
            return Integer.parseInt(afterColon);
        }
        catch (Exception e)
        {
            return -1;
        }
    }

    private void handleCourseCompleted(int kc)
    {
        // currentTime is the last value from script 8605.
        int totalTicks = currentTime >= 0 ? currentTime + 1 : -1;

        print("Run finished, total time = " + totalTicks + " ticks (KC "
                + (kc >= 0 ? kc : "?") + ")", true);

        for (String s : splits)
        {
            print(s, false);
        }

        // Append to {NAME}_times.txt
        exportTimes(kc, totalTicks, splits);

        reset();
    }

    private void exportTimes(int kc, int totalTicks, List<String> splitsToWrite)
    {
        String playerName = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName()
                : "unknown";

        File file = new File(TIMES_DIR, playerName + "_times.txt");

        try (FileWriter writer = new FileWriter(file, true))
        {
            // Header line per KC
            writer.write("KC " + (kc >= 0 ? kc : "?") + " - total: " + totalTicks + " ticks\n");

            // Each split on its own line
            for (String line : splitsToWrite)
            {
                writer.write(line);
                writer.write('\n');
            }

            // Separator between runs
            writer.write("----------------------------------------\n");
        }
        catch (IOException ignored) { // cba
        }
    }

    // Script hooks

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

        // 5986: remove portal overlay
        if (config.hideTransition() && scriptId == GOTR_ADJUST_PORTAL_SCRIPT_ID)
        {
            removePortalOverlay(event);
        }
    }

    // Helpers

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

        // last arg is "now"; ticks since server restart
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
        portalIndex = 1;
        currentTime = -1;
        lastSplitTime = 0;
        splits.clear();
    }

    private void print(String message, boolean debug)
    {
        if (debug && !config.debug())
        {
            return;
        }
        if (!config.showSplits())
        {
            return;
        }
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, "");
    }
}
