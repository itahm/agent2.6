package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.http.Response;

public class Select extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		JSONObject body = Agent.getNodeData(request.getString("ip"), request.has("offline"));
		
		if (body != null) {
			response.write(body.toString());
		}
		// else node not found
	}
	
}
