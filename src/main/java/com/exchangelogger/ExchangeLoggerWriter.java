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

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
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
import java.util.concurrent.ScheduledExecutorService;
import static net.runelite.api.GrandExchangeOfferState.BUYING;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL;
import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;

@Slf4j
public class ExchangeLoggerWriter
{
	// GE sale tax: 2% of the per-item price, rounded down, waived under 50gp/item,
	// capped at 5,000,000gp per item. offer.getSpent() reports the gross pre-tax
	// total on sells, not what the seller actually receives, so this is applied
	// after the fact using the average per-unit price (worth / qty).
	private static final int GE_TAX_MIN_PRICE = 50;
	private static final int GE_TAX_CAP_PER_ITEM = 5_000_000;

	private File logFile;
	private volatile boolean fileExist;

	private final int[] prevQuantity;
	private final int[] prevItemId;
	private final GrandExchangeOfferState[] prevState;

	private final ExchangeLoggerFormatting formatting;
	private final ScheduledExecutorService executor;
	private volatile ExchangeLoggerFormat format;
	private volatile boolean rewrite;
	private volatile boolean splitByAccount;
	private final String logPath;
	private final String logDir;
	private String activePath;
	private String logDate;

	ExchangeLoggerWriter(String path, ExchangeLoggerFormat form, boolean re, boolean split, ScheduledExecutorService executor, Gson gson)
	{
		fileExist = true;
		logDate = currentDateTime("yyyy-MM-dd");

		logPath = path;
		logDir = new File(path).getParent();
		format = form;
		rewrite = re;
		splitByAccount = split;
		this.executor = executor;

		prevQuantity = new int[8];
		prevItemId = new int[8];
		prevState = new GrandExchangeOfferState[8];
		Arrays.fill(prevQuantity, -1);          //Default to -1, because 0 is a valid state
		Arrays.fill(prevItemId, -1);

		formatting = new ExchangeLoggerFormatting(gson);
		openLogFile(computeLogPath(null));
	}

	// Called on the client thread. Snapshots the offer into plain data before handing
	// off to a background thread - GrandExchangeOffer must not be touched off-thread.
	public void grandExchangeEvent(GrandExchangeOfferChanged event, String accountName, String itemName)
	{
		if (!fileExist)
		{
			return;
		}

		GrandExchangeOffer offer = event.getOffer();
		String[] split = currentDateTime("yyyy-MM-dd HH:mm:ss").split(" ", 2);

		ExchangeLoggerSlotStatus status = new ExchangeLoggerSlotStatus();
		status.date = split[0];
		status.time = split[1];
		status.state = offer.getState();
		status.slot = event.getSlot();
		status.item = offer.getItemId();
		status.itemName = itemName;
		status.qty = offer.getQuantitySold();
		status.worth = offer.getSpent();
		status.max = offer.getTotalQuantity();
		status.offer = offer.getPrice();

		if (formatting.anyEqualState(status.state, SELLING, SOLD, CANCELLED_SELL))
		{
			applySellTax(status);
		}

		executor.execute(() -> processEvent(status, accountName));
	}

	// Mutates worth (down to the net amount actually received) and sets tax (the amount
	// withheld) - tax stays 0 for buys and for sales under the 50gp/item exemption.
	// Math is done in long to avoid overflowing int on the unitPrice*2 step for
	// extremely high-value items; the final tax can never exceed worth (an int), so
	// the cast back to int at the end is always safe.
	private static void applySellTax(ExchangeLoggerSlotStatus status)
	{
		if (status.qty <= 0)
		{
			return;
		}

		long unitPrice = status.worth / status.qty;
		if (unitPrice < GE_TAX_MIN_PRICE)
		{
			return;
		}

		long unitTax = Math.min((unitPrice * 2) / 100, GE_TAX_CAP_PER_ITEM);
		status.tax = (int) (unitTax * status.qty);
		status.worth -= status.tax;
	}

	// Runs on a background thread. All disk I/O and mutable writer state lives here.
	private synchronized void processEvent(ExchangeLoggerSlotStatus status, String accountName)
	{
		if (!fileExist)
		{
			return;
		}

		String targetPath = computeLogPath(accountName);
		if (!targetPath.equals(activePath))    //Switch (or create) the file for this account
		{
			openLogFile(targetPath);
		}
		else if (!rewrite && !logDate.equals(status.date))  //New log if date changed during run-time
		{
			preserveCurrentFile(logDate);
		}

		if (duplicateHandler(status))         //Filter out duplicated events
		{
			return;
		}
		writeFile(status);
	}

	// The shared log, unless splitting by account is on and we know the account - then each
	// account gets its own file in the same directory. The extension always matches the
	// currently selected format, so a format change mid-session is picked up the same way
	// an account switch is - processEvent() just sees the target path changed.
	private String computeLogPath(String accountName)
	{
		String base = logPath;

		if (splitByAccount && accountName != null && !accountName.isEmpty())
		{
			String sanitized = accountName.trim().replaceAll("[^a-zA-Z0-9]+", "_");
			if (!sanitized.isEmpty())
			{
				base = logDir + File.separator + "exchange_" + sanitized;
			}
		}

		return base + extensionFor(format);
	}

	private static String extensionFor(ExchangeLoggerFormat format)
	{
		switch (format)
		{
			case TABULAR:
				return ".csv";
			case JSON:
				return ".json";
			case TEXT:
			default:
				return ".log";
		}
	}

	// Opens (or creates) the log file at path, applying the same rewrite/date-rollover
	// rules a plugin restart would apply - used both at startup and on an account switch.
	private void openLogFile(String path)
	{
		activePath = path;
		logFile = new File(path);

		if (logFile.isFile())
		{
			if (rewrite)
			{
				removeCurrentFile();			//If user only want one log file
				logFile = createLog(path);
			}
			else
			{
				fileDateCheck();				//Check if current log is for today's date
			}
		}
		else
		{
			logFile = createLog(path);       //First time running plugin (or first time for this account)
		}
	}

	private void writeFile(ExchangeLoggerSlotStatus status)
	{
		String writeLine;
		switch (format)
		{
			case TABULAR:
				writeLine = formatting.tabular(status);
				break;
			case JSON:
				writeLine = formatting.json(status);
				break;
			case TEXT:
			default:
				writeLine = formatting.plainText(status);
				break;
		}

		try (FileWriter writer = new FileWriter(logFile, true))
		{
			writer.write(writeLine + "\n");
		}
		catch (IOException e)
		{
			log.warn("An error occurred while writing to log file: " + e.toString());
		}
	}

	//GE OfferChanged events sometimes send duplicates of buying,selling and cancelled..
	//This method will compare current event with the previous.
	// 2 buying/selling events in sequence in the same slot can't have the same QuantitySold
	// 2 cancelled_buy/sell events in sequence in the same slot shouldn't be possible
	// Also requires the item id to match - otherwise a desync (e.g. a new offer placed with a
	// coincidentally equal quantity/state) would be wrongly swallowed as a duplicate.
	private boolean duplicateHandler(ExchangeLoggerSlotStatus status)
	{
		int slot = status.slot;
		boolean duplicate = false;
		boolean sameItem = prevItemId[slot] == status.item;

		if (sameItem
				&& ((prevQuantity[slot] == status.qty && formatting.anyEqualState(status.state, BUYING, SELLING))
					|| (prevState[slot] == status.state && formatting.anyEqualState(status.state, CANCELLED_BUY, CANCELLED_SELL))))
		{
			duplicate = true;
		}
		else    //EMPTY is always qty = 0, which makes next buy/sell assume it's a duplicate. Set it to -1
		{
			prevQuantity[slot] = ((status.state == EMPTY) ? -1 : status.qty);
			prevItemId[slot] = ((status.state == EMPTY) ? -1 : status.item);
			prevState[slot] = status.state;
		}
		return duplicate;
	}

	private String currentDateTime(String form)
	{
		Date date = new Date();
		SimpleDateFormat formatter = new SimpleDateFormat(form);   //"yyyy-MM-dd HH:mm:ss"
		return formatter.format(date);
	}

	//Adding _[fileDate] at the end of the current file name and creates a new log
	private void preserveCurrentFile(String fileDate)
	{
		int extIndex = activePath.lastIndexOf('.');
		String base = extIndex >= 0 ? activePath.substring(0, extIndex) : activePath;
		String fileType = extIndex >= 0 ? activePath.substring(extIndex) : "";
		String rename = base + "_" + fileDate + fileType;

		if (!logFile.renameTo(new File(rename)))
		{
			log.debug("Failed to rename previous file to: " + rename);
		}
		logFile = createLog(activePath);
	}

	//on start: If the current log file does not have the current date, store it and create a new one
	private void fileDateCheck()
	{
		String fileDate = "";

		try (Scanner reader = new Scanner(logFile))	//Read current log´s date
		{
			if (reader.hasNextLine())
			{
				fileDate = extractDate(reader.nextLine());
			}
		}
		catch (IOException e)
		{
			log.warn("Couldn't read file: " + logFile.toString() + " " + e.toString());
		}

		if (!fileDate.equals(logDate) && !fileDate.equals(""))
		{
			preserveCurrentFile(fileDate);
		}
	}

	// TEXT and TABULAR both start each line with the date (yyyy-MM-dd). JSON needs
	// actual parsing rather than assuming "date" is serialized as the first field -
	// that was only ever true by accident of field declaration order, not guaranteed.
	// activePath's extension always matches the currently active format (see
	// computeLogPath), so this file was written under that same format.
	private String extractDate(String firstLine)
	{
		if (format == ExchangeLoggerFormat.JSON)
		{
			try
			{
				ExchangeLoggerSlotStatus status = formatting.parseJson(firstLine);
				return status != null && status.date != null ? status.date : "";
			}
			catch (JsonSyntaxException e)
			{
				return "";
			}
		}

		return firstLine.length() >= logDate.length() ? firstLine.substring(0, logDate.length()) : "";
	}

	private File createLog(String path)
	{
		logDate = currentDateTime("yyyy-MM-dd");

		try
		{
			File log = new File(path);
			if (log.createNewFile())
			{
				fileExist = true;
				return log;
			}
		}
		catch (IOException e)
		{
			log.warn("An error occurred while creating a new log file" + e.toString());
		}

		fileExist = false;
		return null;
	}

	//Removes current logFile and creates a new one, used on startup if user only wants one log file
	public void removeCurrentFile()
	{
		try
		{
			if (!logFile.delete())
			{
				log.debug("Failed to delete old log file: " + logFile.toString());
			}
		}
		catch (Exception e)
		{
			log.warn("Error deleting old log file: " + e.toString());
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

	public void setSplitByAccount(boolean split)
	{
		splitByAccount = split;
	}

	// Lets callers skip event-preparation work (e.g. resolving the item name) up front
	// when nothing would be written anyway - e.g. the log file failed to create.
	public boolean isActive()
	{
		return fileExist;
	}
}
