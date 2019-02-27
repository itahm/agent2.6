package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import com.itahm.Agent;
import com.itahm.database.Table;
import com.itahm.http.Response;

public class Put extends Command {
	private static final String NULL = "";
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		String
			database = request.getString("database"),
			key = request.getString("key");
		
		if (database.equals("node")) {
			JSONObject value = request.isNull("value")? null: request.getJSONObject("value");
			
			if (value == null) {
				Agent.node().removeBase(key);
			}
			else if (!NULL.equals(key)) {
				Agent.node().modifyBase(key, value);				
			}
			else if (!Agent.node().createBase(value)) {
				response.setStatus(Response.Status.CONFLICT);
				response.write(new JSONObject().put("error", "사용중인 IP.").toString());
			}
		}
		else {
			Object value = request.isNull("value")? null: request.get("value");
			
			Table db = Agent.db().get(database);
			
			if (db == null) {
				throw new JSONException("존재하지 않는 Database.");
			}
			
			if (value == null) {
				if (database.equals("profile")) {
					if (Agent.node().isInUseProfile(key)) {
						response.setStatus(Response.Status.CONFLICT);
						response.write(new JSONObject().put("error", "사용중인 프로파일.").toString());
					
						return;
					}
					else {
						JSONObject profile = db.json().getJSONObject(key);
						
						if (profile.getString("version").equals("v3")) {
							Agent.node().removeUSMUser(profile.getString("user"));
						}
					}
				}
				else if (database.equals("account")) {
					JSONObject account = Agent.db().get("account").json();
					boolean lastRoot = true;
					
					for (Object username : account.keySet()) {
						if (username.equals(key)) {
							continue;
						}
						
						if (account.getJSONObject((String)username).getInt("level") == 0) {
							lastRoot = false;
							
							break;
						}
					}
					
					if (lastRoot) {
						response.setStatus(Response.Status.CONFLICT);
						response.write(new JSONObject().put("error", "유일한 Root 계정.").toString());
					
						return;
					}
				}
				
				db.json().remove(key);
			}
			else {
				if (database.equals("profile")) {
					JSONObject profile = (JSONObject)value;
					
					if (profile.getString("version").equals("v3")) {
						Agent.node().addUSMUser(profile);
					}
				}
				
				db.json().put(key, value);
			}
			
			db.save();
		}
	}
	
}
