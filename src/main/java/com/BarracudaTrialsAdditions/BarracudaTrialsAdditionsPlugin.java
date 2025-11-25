package com.example.trials;

import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.Getter;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
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
public class BarracudaTrialsAdditionsPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private KeyManager keyManager;

    @Inject
    private BarracudaTrialsAdditionsConfig config;

    @Getter
    boolean started;

    int portal = 0;
    int currentTime = -1;
    int lastStartTime = 0;
    int lastSplitTime = 0;
    List<String> splits = new ArrayList<>();

    private static final int GOTR_ADJUST_PORTAL_SCRIPT_ID = 5986;
    private static final int CREATE_PORTAL_OVERLAY_SCRIPT_ID = 5984;
    private static final int GT_UI_SCRIPT_ID = 8605;


    @Provides
    BarracudaTrialsAdditionsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BarracudaTrialsAdditionsConfig.class);
    }

    @Override
    protected void startUp() {
    }

    @Override
    protected void shutDown() {
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        //print("test test");
        int startTime = client.getVarpValue(VarPlayerID.SAILING_BT_TIME_START);
        if (startTime == 0 && started)
        {
            started = false;
            print("Run finished, total time = " + currentTime + 1);
            for (String s : splits) {
                print(s);
            }
            reset();
        }
    }

    @Subscribe
    public void onClientTick(ClientTick tick) {
    }

    @Subscribe
    private void onScriptPreFired(ScriptPreFired event)
    {
        // 8605: time update
        if (event.getScriptId() == GT_UI_SCRIPT_ID)
        {
            int startTime = client.getVarpValue(VarPlayerID.SAILING_BT_TIME_START);
            // Detect run start
            if (startTime != 0 && !started)
            {
                reset();
                started = true;
                print("BarracudaTrials: run started");
            }
            // run reset (if we finish 8605 doesn't run)
            if (startTime == 0 && started)
            {
                started = false;
                print("BarracudaTrials: run reset, portals counted = " + portal);
                for (String s : splits) {
                    print(s);
                }
                reset();
            }

            lastStartTime = startTime;

            if (!started || startTime == 0)
            {
                return;
            }

            Object[] args = event.getScriptEvent().getArguments();
            if (args.length == 0)
            {
                return;
            }

            // last arg is the current tick / time, as you already discovered
            int now = (int) args[args.length - 1];
            currentTime = now - startTime;
        }

        // 5984: we went through a portal, log split
        if (event.getScriptId() == CREATE_PORTAL_OVERLAY_SCRIPT_ID)
        {
            if (started && currentTime >= 0)
            {
                int delta = currentTime - lastSplitTime;
                splits.add("Portal " + portal + " time: " + currentTime + " ticks (" + delta + ")");
                print("Portal " + portal + " time: " + currentTime + " ticks (" + delta + ")");

                lastSplitTime = currentTime;
                portal++;
            }
        }

        // 5986: remove portal overlay
        if (config.hideTransition() && event.getScriptId() == GOTR_ADJUST_PORTAL_SCRIPT_ID)
        {
            int gameCycle = client.getGameCycle();
            Object[] args = event.getScriptEvent().getArguments();
            args[1] = gameCycle - 100; // force int0 so clientclock - int0 >= 20
            args[2] = 0;               // boolean false
        }
    }

    private void reset()
    {
        portal = 0;
        currentTime = -1;
        lastSplitTime = 0;
        splits.clear();
    }

    private void print(String message)
    {
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, "");
    }
}
