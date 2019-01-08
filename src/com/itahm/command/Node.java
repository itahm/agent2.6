package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.http.Response;

public class Node extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		JSONObject node = Agent.node().get(request.getString("id"));
		
		// snmp 정보만 전달할 것인지 모두 전달할 것인지
		if (node != null) {
			response.write(node.toString());
		}
		else {
			throw new JSONException("Node not found.");
		}
	}
	
}
