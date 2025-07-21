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

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;

import java.io.FileWriter;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Scanner;

import static net.runelite.api.GrandExchangeOfferState.*;

@Slf4j
public class ExchangeLoggerWriter
{
	private File logFile;
	private boolean fileExist;

	private final int[] prevQuantity;
	private final GrandExchangeOfferState[] prevState;

	private final ExchangeLoggerFormatting formatting;
	private ExchangeLoggerFormat format;
	private boolean rewrite;
	private final String logPath;
	private String logDate;

	ExchangeLoggerWriter(String path, ExchangeLoggerFormat form, boolean re)
	{
		fileExist = true;
		logDate = currentDateTime("yyyy-MM-dd");

		logPath = path;
		format = form;
		rewrite = re;

		prevQuantity = new int[8];
		prevState = new GrandExchangeOfferState[8];
		Arrays.fill(prevQuantity, -1);  // 0 is valid, -1 means uninitialized

		formatting = new ExchangeLoggerFormatting();

		String filename = rewrite ? logPath : buildDatedFilename();
		logFile = new File(filename);

		if (logFile.isFile())
		{
			if (rewrite)
			{
				removeCurrentFile();
				logFile = createLog(filename);
			}
			else
			{
				fileDateCheck();
			}
		}
		else
		{
			logFile = createLog(filename);
		}
	}

	public void grandExchangeEvent(GrandExchangeOfferChanged event)
	{
		String time = currentDateTime("yyyy-MM-dd HH:mm:ss");

		if (!fileExist)
		{
			return;
		}
		else if (!rewrite && !logDate.equals(time.substring(0, logDate.length())))
		{
			preserveCurrentFile(logDate);
		}

		GrandExchangeOffer offer = event.getOffer();
		int slot = event.getSlot();

		if (duplicateHandler(offer, slot))
		{
			return;
		}
		writeFile(offer, slot, time);
	}

	private void writeFile(GrandExchangeOffer offer, int slot, String time)
	{
		String writeLine = "";
		try (FileWriter writer = new FileWriter(logFile, true))
		{
			switch (format)
			{
				case TEXT:
					writeLine = formatting.plainText(offer, slot, time);
					break;
				case TABULAR:
					writeLine = formatting.tabular(offer, slot, time);
					break;
				case JSON:
					writeLine = formatting.json(offer, slot, time);
					break;
			}

			writer.write(writeLine + "\n");
			writer.flush();

		}
		catch (IOException e)
		{
			log.warn("An error occurred while writing to log file: " + e);
		}
	}

	private boolean duplicateHandler(GrandExchangeOffer offer, int slot)
	{
		boolean duplicate = false;

		if ((prevQuantity[slot] == offer.getQuantitySold() && formatting.anyEqualState(offer.getState(), BUYING, SELLING))
				|| (prevState[slot] == offer.getState() && formatting.anyEqualState(offer.getState(), CANCELLED_BUY, CANCELLED_SELL)))
		{
			duplicate = true;
		}
		else
		{
			prevQuantity[slot] = (offer.getState() == EMPTY) ? -1 : offer.getQuantitySold();
			prevState[slot] = offer.getState();
		}
		return duplicate;
	}

	private String currentDateTime(String form)
	{
		Date date = new Date();
		SimpleDateFormat formatter = new SimpleDateFormat(form);
		return formatter.format(date);
	}

	private void preserveCurrentFile(String fileDate)
	{
		String extension = getExtension();
		String renamed = logPath.replace(extension, "_" + fileDate + extension);

		if (!logFile.renameTo(new File(renamed)))
		{
			log.debug("Failed to rename previous file to: " + renamed);
		}
		logFile = createLog(buildDatedFilename());
	}

	private void fileDateCheck()
	{
		String fileDate = "";

		try (Scanner reader = new Scanner(logFile))
		{
			if (reader.hasNextLine())
			{
				fileDate = reader.nextLine();
				if (fileDate.contains("{"))
				{
					String remove = "{\"date\":\"";
					fileDate = fileDate.substring(remove.length(), logDate.length() + remove.length());
				}
				else
				{
					fileDate = fileDate.substring(0, logDate.length());
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Couldn't read file: " + logFile + " " + e);
		}

		if (!fileDate.equals(logDate) && !fileDate.equals(""))
		{
			preserveCurrentFile(fileDate);
		}
	}

	private File createLog(String fullPath)
	{
		logDate = currentDateTime("yyyy-MM-dd");

		try
		{
			File log = new File(fullPath);
			if (log.createNewFile())
			{
				fileExist = true;
				return log;
			}
		}
		catch (IOException e)
		{
			log.warn("Error creating new log file: " + e);
		}

		fileExist = false;
		return null;
	}

	private String buildDatedFilename()
	{
		String base = logPath.replaceAll("\\.(log|txt|json|tsv)?$", "");
		String extension = getExtension();
		return base + "-" + logDate + extension;
	}

	private String getExtension()
	{
		switch (format)
		{
			case JSON: return ".json";
			case TABULAR: return ".tsv";
			case TEXT:
			default: return ".txt";
		}
	}

	public void removeCurrentFile()
	{
		try
		{
			if (!logFile.delete())
			{
				log.debug("Failed to delete old log file: " + logFile);
			}
		}
		catch (Exception e)
		{
			log.warn("Error deleting old log file: " + e);
		}
	}

	public void setRewrite(boolean re)
	{
		rewrite = re;
	}

	public void setFormat(ExchangeLoggerFormat form)
	{
		format = form;
	}
}
