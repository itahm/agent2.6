package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import com.itahm.http.Response;

abstract public class Command {
	abstract public void execute(JSONObject request, Response response) throws IOException, JSONException;
	
	public static Command valueOf(String command) {
		switch(command.toUpperCase()) {
		case "BACKUP": return new Backup();
		
		case "CLEAN": return new Clean();
		
		case "CONFIG": return new Config();
		
		case "CRITICAL": return new Critical();
		
		case "HEALTH": return new Health();

		case "INFORMATION": return new Information();
		
		case "LOG": return new Log();
		
		case "MONITOR": return new Monitor();
		
		case "NODE": return new Node();
		
		case "PULL": return new Pull();
		
		case "PUT": return new Put();
		
		case "QUERY": return new Query();
		
		case "RESTORE": return new Restore();
		
		case "SEARCH": return new Search();
		
		case "SPEED": return new Speed();
		
		case "TOP": return new Top();
		
		case "UPDOWN": return new UpDown();
		}
		
		return null;
	}
	
}
