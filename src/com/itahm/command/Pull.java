package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONArray;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import com.itahm.Agent;
import com.itahm.database.Table;
import com.itahm.http.Response;

public class Pull extends Command {
	
	private JSONObject pull(String database) {
		Table db = Agent.db().get((String)database);
		
		if (db == null) {
			throw new JSONException("Database not found.");
		}
		
		return db.json();
	}
	
	private JSONObject pull(JSONArray databases) {
		JSONObject jsono = new JSONObject();
		String database;
		
		for (int i=0, _i = databases.length(); i<_i; i++) {
			database = databases.getString(i);
			
			jsono.put(database, pull(database));
		}
		
		return jsono;
	}
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		Object database = request.get("database");
		
		if (database instanceof String) {
			response.write(pull((String)database).toString());
		}
		else if (database instanceof JSONArray) {
			response.write(pull((JSONArray)database).toString());
		}
		else {
			throw new JSONException("Database 는 반드시 string 이거나 array 이어야 합니다.");
		}
	}
	
}
