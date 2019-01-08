package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import com.itahm.Agent;
import com.itahm.database.Data;
import com.itahm.http.Response;

public class Put extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		Data db = Agent.db().get(request.getString("database"));
		
		if (db == null) {
			throw new JSONException("Database not found.");
		}
		
		String key = request.getString("key");
		
		if (request.isNull("value")) {
			db.json.remove(key);
		}
		else {
			db.json.put(key, request.getJSONObject("value"));
		}
		
		db.save();
	}
	
}
