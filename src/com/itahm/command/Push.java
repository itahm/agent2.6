package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import com.itahm.Agent;
import com.itahm.table.Table;
import com.itahm.http.Response;

public class Push extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		Table table = Agent.getTable(request.getString("database"));
		
		if (table == null) {
			throw new JSONException("Database not found.");
		}
		else {
			table.save(request.getJSONObject("data"));
		}
	}
	
}
