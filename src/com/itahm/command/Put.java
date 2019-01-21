package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import com.itahm.Agent;
import com.itahm.NodeManager;
import com.itahm.database.Data;
import com.itahm.http.Response;

public class Put extends Command {
	private static final String NULL = "";
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		String
			database = request.getString("database"),
			key = request.getString("key");
		JSONObject value = request.isNull("value")? null: request.getJSONObject("value");
		
		if (database.equals("node")) {
			NodeManager node = Agent.node();
			
			if (value == null) {
				node.remove(key);
			}
			else {
				if (!NULL.equals(key)) {
					node.save(key, "base", value);
				}
				else if (node.create(value) == null) {
					response.setStatus(Response.Status.CONFLICT);
					response.write(new JSONObject().put("error", "사용중인 IP.").toString());
				}
			}
		}
		else {
			Data db = Agent.db().get(database);
			
			if (db == null) {
				throw new JSONException("존재하지 않는 Database.");
			}
			
			if (value == null) {
				db.json.remove(key);
			}
			else {
				db.json.put(key, value);
			}
			
			db.save();
		}
	}
	
}
