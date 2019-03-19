package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import com.itahm.Agent;
import com.itahm.database.Table;
import com.itahm.http.Response;

public class Push extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		Table db = Agent.db().get(request.getString("database"));
		
		if (db == null) {
			throw new JSONException("존재하지 않는 Database.");
		}
		
		db.save(request.getJSONObject("data"));
	}
	
}
