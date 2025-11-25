package com.example;

import net.runelite.client.RuneLite;
import com.example.BarracudaTrialAdditions.BarracudaTrialsAdditionsPlugin;

import net.runelite.client.externalplugins.ExternalPluginManager;

public class ExamplePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BarracudaTrialsAdditionsPlugin.class);
		RuneLite.main(args);
	}
}