package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.node.Node;
import com.itahm.Agent;
import com.itahm.ITAhMNode;
import com.itahm.http.Response;

public class Query extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		Node node = Agent.node().getNode(request.getString("id"));
		
		if (node == null || !(node instanceof ITAhMNode)) {
			throw new JSONException("Node not found.");
		}
		
		String resource = request.getString("resource");
		
		JSONObject body;
		
		if (resource.equals("hrProcessorLoad")) {
			body = ((ITAhMNode)node).getData(resource,
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false);
		}
		else if (resource.equals("throughput")) {
			body = new JSONObject();
			
			body.put("in", ((ITAhMNode)node).getData("ifInOctets",
				String.valueOf(request.getInt("index")),
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false));
			
			body.put("out", ((ITAhMNode)node).getData("ifOutOctets",
				String.valueOf(request.getInt("index")),
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false));
		}
		else if (resource.equals("error")) {
			body = new JSONObject();
			
			body.put("in", ((ITAhMNode)node).getData("ifInErrors",
				String.valueOf(request.getInt("index")),
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false));
			
			body.put("out", ((ITAhMNode)node).getData("ifOutErrors",
				String.valueOf(request.getInt("index")),
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false));
		}
		else {
			body = ((ITAhMNode)node).getData(resource,
				String.valueOf(request.getInt("index")),
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false);
		}
		
		response.write(body.toString());
	}

}
