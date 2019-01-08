package com.itahm.command;

import java.io.IOException;

import com.itahm.Agent;
import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

public class Backup extends Command {

	@Override
	public void execute(JSONObject request, Response response) throws IOException, JSONException {
		JSONObject backup = new JSONObject();
		
		backup.put("database", Agent.db().backup());
		backup.put("node", Agent.node().backup());
		
		response.write(backup.toString());
	}

}
