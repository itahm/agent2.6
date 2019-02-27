package com.itahm.command;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;

import com.itahm.Agent;
import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.util.Util;

public class Information extends Command {

	@Override
	public void execute(JSONObject request, Response response) throws IOException, JSONException {
		Calendar c = Calendar.getInstance();
		File root = Agent.Config.root();
		JSONObject body = new JSONObject();
		
		c.set(Calendar.DATE, c.get(Calendar.DATE) -1);
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		
		body
			.put("space", root == null? 0: root.getUsableSpace())
			.put("version", Agent.Config.version)
			.put("load", Agent.node().calcLoad())
			.put("resource", Agent.node().getResourceCount())
			.put("usage", Util.getDirectorySize(root.toPath().resolve("node"), Long.toString(c.getTimeInMillis())))
			.put("java", System.getProperty("java.version"))
			.put("path", root.getAbsoluteFile().toString())
			.put("expire", Agent.Config.expire());
			
		response.write(body.toString());
	}

}
