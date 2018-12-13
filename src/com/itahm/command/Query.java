package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.http.Response;

public class Query extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		JSONObject body = Agent.getNodeData(request);
		
		if (body == null) {
			throw new JSONException("Node or Data not found.");
		}
		
		response.write(body.toString());
	}

}
