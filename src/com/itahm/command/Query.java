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
		
		response.write(((ITAhMNode)node).getData(request.getString("resource"),
			String.valueOf(request.getInt("index")),
			request.getLong("start"),
			request.getLong("end"),
			request.has("summary")? request.getBoolean("summary"): false).toString());
	}

}
