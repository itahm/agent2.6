package com.itahm.command;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.itahm.Agent;
import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.util.Util;

public class Restore extends Command {

	@Override
	public void execute(JSONObject request, Response response) throws IOException, JSONException {
		Agent.node().stop();
		
		Agent.db().restore(request.getJSONObject("database"));
			
		String id;
		Path nodeRoot = Paths.get(Agent.Setting.root().toURI()).resolve("node");
		JSONObject backup = request.getJSONObject("node");
			
		for (Object key: backup.keySet()) {
			id = (String)key;
			
			Util.putJSONtoFile(nodeRoot.resolve(id), backup.getJSONObject(id));
		}
		
		Agent.node().load();
	}

}
