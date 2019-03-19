package com.itahm.command;

import java.io.IOException;

import com.itahm.Agent;
import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

public class Order extends Command {

	@Override
	public void execute(JSONObject request, Response response) throws IOException, JSONException {
		switch(request.getString("order").toLowerCase()) {
		case "monitor":
			if (!Agent.node().setMonitor(request.getString("id"),
					request.has("protocol")? request.getString("protocol"): null)) {
				
				response.setStatus(Response.Status.CONFLICT);
				response.write(new JSONObject().put("error", "존재하지 않는 ID, 또는 IP").toString());
			}
			
			break;
		case "backup":
			response.write(Agent.db().get().toString());
			
			break;
		case "critical":
			Agent.node().setCritical(request.getString("id"), request.getJSONObject("critical"));
			
			break;
		case "restore":
			try{
				Agent.stop();
				
				Agent.db().restore(request.getJSONObject("database"));
				
				Agent.start();
				
			} catch(Exception e) {
				System.err.print(e);
			}
			
			break;
		case "speed":
			Agent.node().setSpeed(request.getString("id"), request.getJSONObject("speed"));
			
			break;
		case "traffic":
			response.write(Agent.node().getTraffic(request.getJSONObject("line")).toString());
			
			break;
		case "updown":
			Agent.node().setUpDown(request.getString("id"), request.getJSONObject("updown"));
			
			break;
		case "top":
			response.write(Agent.node()
				.getTop(request.has("top")? request.getJSONArray("top"): null)
				.toString());
			
			break;
		}
	}

}
