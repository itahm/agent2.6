package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import com.itahm.table.Table;
import com.itahm.table.Config;
import com.itahm.Agent;
import com.itahm.http.Response;

public class Pull extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		Table table = Agent.getTable(request.getString("database"));
		
		if (table == null) {
			throw new JSONException("Database not found.");
		}
		else {
			JSONObject body = table.getJSONObject();
			
			if (table instanceof Config) {
				Agent.getInformation(body);
			}
			
			response.write(body.toString());
		}
	}
	
}
