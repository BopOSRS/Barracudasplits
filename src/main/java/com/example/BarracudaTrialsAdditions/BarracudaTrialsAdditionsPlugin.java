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
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import java.awt.image.BufferedImage;
import java.io.*;
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

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ItemManager itemManager;

    private BarracudaTrialsAdditionsPanel panel;
    private NavigationButton navButton;
    boolean historyLoaded = false;
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

    private static final int MAX_RECENT_RUNS = 20;

    public static class RunRecord
    {
        final int kc;
        final int totalTicks;
        @Getter
        final List<String> splits;

        RunRecord(int kc, int totalTicks, List<String> splits)
        {
            this.kc = kc;
            this.totalTicks = totalTicks;
            this.splits = splits;
        }
    }


    private final List<RunRecord> recentRuns = new ArrayList<>();
    private int personalBestTicks = -1;

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

        panel = new BarracudaTrialsAdditionsPanel();

        BufferedImage icon = ImageUtil.loadImageResource(
                BarracudaTrialsAdditionsPlugin.class,
                "gwenith_flag.png"
        );

        navButton = NavigationButton.builder()
                .tooltip("Gwenith Glide Times")
                .icon(icon)
                .priority(10) // i dont want it at the top
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);

        panel.updateData(recentRuns, personalBestTicks);
        historyLoaded = false;
    }

    @Override
    protected void shutDown()
    {
        reset();
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
        panel = null;
        historyLoaded = false;
    }

    // State handling: start / finish / reset

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // Lazy load history once we actually know the player name
        if (!historyLoaded && client.getLocalPlayer() != null)
        {
            loadHistoryFromFile();
            historyLoaded = true;
        }
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
        int totalTicks = currentTime >= 0 ? currentTime + 1 : -1;

        print("Run finished, total time = " + totalTicks + " ticks (KC "
                + (kc >= 0 ? kc : "?") + ")", true);

        for (String s : splits)
        {
            print(s, true);
        }

        // Update in-memory history
        addRunToHistory(kc, totalTicks, splits);

        // Write to file
        exportTimes(kc, totalTicks, splits);

        reset();
    }

    private void addRunToHistory(int kc, int totalTicks, List<String> splits)
    {
        RunRecord rec = new RunRecord(kc, totalTicks, new ArrayList<>(splits));
        recentRuns.add(0, rec); // newest first

        if (recentRuns.size() > MAX_RECENT_RUNS)
        {
            recentRuns.remove(recentRuns.size() - 1);
        }

        if (totalTicks > 0 && (personalBestTicks == -1 || totalTicks < personalBestTicks))
        {
            personalBestTicks = totalTicks;
        }

        if (panel != null)
        {
            panel.updateData(recentRuns, personalBestTicks);
        }
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

    private void loadHistoryFromFile()
    {
        String playerName = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName()
                : null;

        if (playerName == null)
        {
            return;
        }

        File file = new File(TIMES_DIR, playerName + "_times.txt");
        if (!file.exists())
        {
            return;
        }

        List<RunRecord> allRuns = new ArrayList<>();

        RunRecord currentRun = null;
        List<String> currentSplits = null;

        try (BufferedReader reader = new BufferedReader(new FileReader(file)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                line = line.trim();
                if (line.isEmpty())
                {
                    continue;
                }

                // Start of a new run: "KC X - total: Y ticks"
                if (line.startsWith("KC "))
                {
                    // Finalize previous run (if any)
                    if (currentRun != null)
                    {
                        allRuns.add(currentRun);
                    }

                    try
                    {
                        int dashIdx = line.indexOf(" - total:");
                        if (dashIdx == -1)
                        {
                            currentRun = null;
                            currentSplits = null;
                            continue;
                        }

                        // between "KC " and " - total:"
                        String kcPart = line.substring(3, dashIdx).trim();
                        int kc = kcPart.equals("?") ? -1 : Integer.parseInt(kcPart);

                        int ticksIdx = line.indexOf("total:");
                        int ticksEndIdx = line.indexOf("ticks", ticksIdx);
                        if (ticksIdx == -1 || ticksEndIdx == -1)
                        {
                            currentRun = null;
                            currentSplits = null;
                            continue;
                        }

                        String ticksPart = line.substring(ticksIdx + "total:".length(), ticksEndIdx).trim();
                        int totalTicks = Integer.parseInt(ticksPart);

                        currentSplits = new ArrayList<>();
                        currentRun = new RunRecord(kc, totalTicks, currentSplits);
                    }
                    catch (Exception ignored)
                    {
                        currentRun = null;
                        currentSplits = null;
                    }

                    continue;
                }

                // Separator line: end of current run
                if (line.startsWith("---"))
                {
                    if (currentRun != null)
                    {
                        allRuns.add(currentRun);
                        currentRun = null;
                        currentSplits = null;
                    }
                    continue;
                }

                // Any other non-empty line inside a run = split
                if (currentRun != null && currentSplits != null)
                {
                    currentSplits.add(line);
                }
            }

            // File might not end with separator; finalize last run
            if (currentRun != null)
            {
                allRuns.add(currentRun);
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        recentRuns.clear();

        List<BarracudaTrialsAdditionsPlugin.RunRecord> target =
                allRuns.size() > MAX_RECENT_RUNS
                        ? allRuns.subList(allRuns.size() - MAX_RECENT_RUNS, allRuns.size())
                        : allRuns;

        // reverse order so newest is index 0
        for (int i = target.size() - 1; i >= 0; i--)
        {
            recentRuns.add(target.get(i));
        }

        // Recompute PB from all runs
        personalBestTicks = -1;
        for (RunRecord r : allRuns)
        {
            if (r.totalTicks > 0 && (personalBestTicks == -1 || r.totalTicks < personalBestTicks))
            {
                personalBestTicks = r.totalTicks;
            }
        }

        // Push to panel
        if (panel != null)
        {
            panel.updateData(recentRuns, personalBestTicks);
        }

        if (config.debug())
        {
            print("Loaded " + allRuns.size() + " historical runs from disk. PB: " + personalBestTicks + " ticks", true);
        }
    }
}
