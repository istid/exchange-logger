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
import net.runelite.api.GrandExchangeOfferState;
import static net.runelite.api.GrandExchangeOfferState.*;

public class ExchangeLoggerFormatting
{
	// Always use the client's injected Gson rather than constructing our own -
	// plugin-hub's packager rejects plugins that create fresh Gson instances.
	private final Gson gson;

	ExchangeLoggerFormatting(Gson gson)
	{
		this.gson = gson;
	}

	public String plainText(ExchangeLoggerSlotStatus status)
	{
		String time = status.date + " " + status.time;
		String line;

		//First offer for item
		if (status.qty == 0 && anyEqualState(status.state, BUYING, SELLING))
		{
			//Differentiate the first offer state from subsequent ones
			String firstState = ((status.state == BUYING) ? "BUY" : "SELL");

			line = (time + " state: " + firstState + " slot: " + status.slot + " item: " + status.item
					+ " (" + status.itemName + ")" + " max: " + status.max + " offer: " + status.offer);
		}
		else if (anyEqualState(status.state, CANCELLED_BUY, CANCELLED_SELL))
		{
			line = (time + " state: " + status.state + " slot: " + status.slot + " item: " + status.item
					+ " (" + status.itemName + ")" + " qty: " + status.qty + " worth: " + status.worth
					+ " tax: " + status.tax + " max: " + status.max);
		}
		else if (status.state == EMPTY)
		{
			line = (time + " state: " + status.state + " slot: " + status.slot);
		}
		else
		{
			line = (time + " state: " + status.state + " slot: " + status.slot + " item: " + status.item
					+ " (" + status.itemName + ")" + " qty: " + status.qty + " worth: " + status.worth
					+ " tax: " + status.tax);
		}
		return line;
	}

	// itemName and tax are appended at the end, after the original 9 columns, rather
	// than interleaved - existing users' spreadsheets/scripts reading by column
	// position keep working unchanged; only new trailing columns show up.
	public String tabular(ExchangeLoggerSlotStatus status)
	{
		return (status.date + "," + status.time + "," + status.state
				+ "," + status.slot + "," + status.item + "," + status.qty
				+ "," + status.worth + "," + status.max + "," + status.offer
				+ "," + csvField(status.itemName) + "," + status.tax);
	}

	// Item names are the only free-text field in tabular output, so they're the only
	// thing that needs CSV quoting/escaping (OSRS item names don't use commas, but a
	// stray quote or a future name that does shouldn't be able to break the format).
	private static String csvField(String value)
	{
		return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
	}

	public String json(ExchangeLoggerSlotStatus status)
	{
		return gson.toJson(status);
	}

	public ExchangeLoggerSlotStatus parseJson(String line)
	{
		return gson.fromJson(line, ExchangeLoggerSlotStatus.class);
	}

	public boolean anyEqualState(GrandExchangeOfferState expected, GrandExchangeOfferState ...array)
	{
		for (GrandExchangeOfferState state : array)
		{
			if (state.equals(expected))
			{
				return true;
			}
		}
		return false;
	}
}
