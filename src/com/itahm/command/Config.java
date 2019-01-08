package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.database.Data;
import com.itahm.http.Response;

public class Config extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		final String key = request.getString("key");
		Data data = Agent.db().get("config");
		
		switch(key) {
		case "rollingInterval":
			int rollingInterval = request.getInt("value");
			
			Agent.Setting.rollingInterval(rollingInterval);
		
			data.json.put("rollingInterval", rollingInterval);
			
			break;
		
		case "top":
			int top = request.getInt("value");
			
			Agent.Setting.top(top);
		
			data.json.put("top", top);
			
			break;
		
		case "requestInterval":
			long requestInterval = request.getLong("value");
			
			Agent.Setting.requestInterval(requestInterval);
			
			data.json.put("requestInterval", requestInterval);
			
			break;
			
		case "smtp":
			JSONObject smtp = request.getJSONObject("smtp");
			
			Agent.event().setSMTP(smtp);
			
			data.json.put("smtp", smtp);
			
			break;
			
		case "config":
			data.json.put("config", request.getJSONObject("value"));
			
			break;
		}
		
		data.save();
	}
	
}
