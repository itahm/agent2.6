package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.database.Table;
import com.itahm.http.Response;

public class Config extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		final String key = request.getString("key");
		Table data = Agent.db().get("config");
		
		switch(key) {
		case "health":
			Agent.Config.health(request.getInt("value"), true);
			
			break;
		case "snmpInterval":
			Agent.Config.snmpInterval(request.getLong("value"));
			
			break;
		case "clean":
			Agent.Config.clean(request.getInt("value"), true);
			
			break;
		case "saveInterval":
			Agent.Config.saveInterval(request.getInt("value"));
			
			break;
		
		case "top":
			Agent.Config.top(request.getInt("value"));
		
			break;		
			
		case "smtp":
			Agent.Config.smtp(request.getJSONObject("value"), true);
			
			break;
			
		
		default:
			response.setStatus(Response.Status.BADREQUEST);
			response.write(new JSONObject().put("error", "Config not found.").toString());
		}
		
		data.json().put(key, request.get("value"));
		
		data.save();
	}
	
}
