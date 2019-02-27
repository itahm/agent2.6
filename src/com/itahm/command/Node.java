package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.database.Table;
import com.itahm.http.Response;

public class Node extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		Table nodeTable = Agent.db().get("node");
		String id = request.getString("id");
		
		if (!nodeTable.json().has(id)) {
			throw new JSONException("Node not found.");
		}
		
		JSONObject base = nodeTable.json().getJSONObject(id);
		JSONObject body = new JSONObject();
		String key;
		
		for (Object o : base.keySet()) {
			key = (String)o;
			
			body.put(key, base.get(key));
		}
		
		if (request.has("snmp") && request.getBoolean("snmp")) {
			JSONObject snmp = Agent.node().getSNMP(id);
			
			if (snmp != null) {
				body.put("snmp", snmp);	
			}
		}
		
		response.write(body.toString());
	}
	
}
