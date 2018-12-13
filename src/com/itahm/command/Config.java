package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.http.Response;

public class Config extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		try {
			final String key = request.getString("key");
			
			switch(key) {
			case "clean":
				Agent.setClean(request.getInt("value"));
				
				break;
			
			case "dashboard":
				Agent.config(key, request.getJSONObject("value"));
				
				break;
			
			case "sms":
			case "menu":
				Agent.config(key, request.getBoolean("value"));
				
				break;
			
			case "interval":
				Agent.setRollingInterval(request.getInt("value"));
				
				break;
				
			
			case "top":
				Agent.config(key, request.getInt("value"));
			
				break;
			
			case "iftype":
				Agent.setValidIFType(request.getString("value"));
				
				break;
			
			case "requestTimer":
				Agent.setInterval(request.getLong("value"));
				
				break;
			
			case "health":
				Agent.setHealth(request.getInt("value"));
				
				break;
			
			case "smtp":
				if (!Agent.setSMTP(request.getJSONObject("value"))) {
					response.setStatus(Response.Status.NOTIMPLEMENTED);
				};
				
				break;
				
			default:
				Agent.config(key, request.getString("value"));
			}
		}
		catch (JSONException jsone) {
			response.setStatus(Response.Status.BADREQUEST);
		}
	}
	
}
