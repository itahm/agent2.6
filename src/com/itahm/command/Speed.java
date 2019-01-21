package com.itahm.command;

import java.io.IOException;

import com.itahm.Agent;
import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

public class Speed extends Command {

	@Override
	public void execute(JSONObject request, Response response) throws IOException, JSONException {
		Agent.node().setSpeed(request.getString("id"), request.getJSONObject("speed"));
	}

}