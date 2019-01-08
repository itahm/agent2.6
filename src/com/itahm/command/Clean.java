package com.itahm.command;

import java.io.IOException;

import com.itahm.Agent;
import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

public class Clean extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException, JSONException {
		int store = request.getInt("store");
		
		Agent.Setting.clean(store);
		
		if (request.has("clean") && request.getBoolean("clean")) {
			Agent.cleaner.clean(store);
		}
	}

}
