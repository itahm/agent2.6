package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.http.Response;

public class Node extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		if (request.has("id")) {
			JSONObject node = Agent.node().get(request.getString("id"));
			
			if (node != null) {
				JSONObject body = new JSONObject();
				
				body.put("base", node.getJSONObject("base"));
				
				if (node.has("monitor")) {
					body.put("monitor", node.getJSONObject("monitor"));
				}
				
				if (request.has("snmp") && request.getBoolean("snmp")) {
					body.put("snmp", node.getJSONObject("snmp"));
				}
				
				response.write(body.toString());
			}
			else {
				throw new JSONException("Node not found.");
			}
		}
		else {
			response.write(Agent.node().get().toString());
		}
	}
	
}
