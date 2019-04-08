package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.ITAhMNode;
import com.itahm.http.Response;

public class Query extends Command {
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		ITAhMNode node = Agent.node().getITAhMNode(request.getString("id"));
		
		if (node == null) {
			throw new JSONException("Node not found.");
		}
		
		String resource = request.getString("resource");
		
		JSONObject body;
		
		if (resource.equals("hrProcessorLoad")) {
			body = node.getData(ITAhMNode.Resource.HRPROCESSORLOAD,
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false);
		}
		else if (resource.equals("throughput")) {
			body = new JSONObject();
			
			body.put("in", node.getData(ITAhMNode.Resource.IFINBPS,
				String.valueOf(request.getInt("index")),
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false));
			
			body.put("out", node.getData(ITAhMNode.Resource.IFOUTBPS,
				String.valueOf(request.getInt("index")),
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false));
		}
		else if (resource.equals("error")) {
			body = new JSONObject();
			
			body.put("in", node.getData(ITAhMNode.Resource.IFINERRORS,
				String.valueOf(request.getInt("index")),
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false));
			
			body.put("out", node.getData(ITAhMNode.Resource.IFOUTERRORS,
				String.valueOf(request.getInt("index")),
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false));
		}
		else {
			body = node.getData(ITAhMNode.Resource.valueof(resource),
				String.valueOf(request.getInt("index")),
				request.getLong("start"),
				request.getLong("end"),
				request.has("summary")? request.getBoolean("summary"): false);
		}
		
		response.write(body.toString());
	}

}
