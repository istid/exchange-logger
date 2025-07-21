/*
 * Copyright (c) 2021, Anton <https://github.com/istid>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.exchangelogger;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import java.io.File;

@Slf4j
@PluginDescriptor(
		name = "Exchange Logger",
		description = "Stores all GE transactions in log file(s)"
)
public class ExchangeLoggerPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ExchangeLoggerConfig config;

	private final String dirName = File.separator + "exchange-logger";

	public static final String CONFIG_GROUP = "exchangelogger";
	private ExchangeLoggerFormat format;
	private boolean rewrite;
	public String logPath;

	private ExchangeLoggerWriter writer;

	@Override
	protected void startUp() throws Exception
	{
		format = config.logFormat();
		rewrite = config.rewriteLog();

		String dir = RuneLite.RUNELITE_DIR.getPath() + dirName;
		new File(dir).mkdirs();
		logPath = dir + File.separator + getBaseLogFileName(format);

		writer = new ExchangeLoggerWriter(logPath, format, rewrite);
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("ExchangeLogger stopped!");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(CONFIG_GROUP))
		{
			if (event.getKey().equals("logFormat"))
			{
				format = config.logFormat();
				writer.setFormat(format);
			}
			else if (event.getKey().equals("rewriteLog"))
			{
				rewrite = config.rewriteLog();
				writer.setRewrite(rewrite);
			}
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged offerEvent)
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			writer.grandExchangeEvent(offerEvent);
		}
	}

	@Provides
	ExchangeLoggerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ExchangeLoggerConfig.class);
	}

	private String getBaseLogFileName(ExchangeLoggerFormat format)
	{
		switch (format)
		{
			case JSON: return "exchange.json";
			case TABULAR: return "exchange.tsv";
			case TEXT:
			default: return "exchange.txt";
		}
	}
}
