package com.itahm.command;

import java.io.IOException;

import com.itahm.Agent;
import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

public class Monitor extends Command {

	@Override
	public void execute(JSONObject request, Response response) throws IOException, JSONException {
		if (!Agent.node().setMonitor(request.getString("id"),
				request.isNull("protocol")? null: request.getString("protocol"))) {
			
			response.setStatus(Response.Status.CONFLICT);
			response.write(new JSONObject().put("error", "존재하지 않는 ID, 또는 IP").toString());
		}
	}

}
