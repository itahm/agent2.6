package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import com.itahm.http.Response;

abstract public class Command {
	abstract public void execute(JSONObject request, Response response) throws IOException, JSONException;
	
	public static Command valueOf(String command) {
		switch(command.toUpperCase()) {
		case "CONFIG": return new Config();

		case "INFORMATION": return new Information();
		
		case "LOG": return new Log();
		
		case "NODE": return new Node();
		
		case "ORDER": return new Order();
		
		case "PULL": return new Pull();
		
		case "PUSH": return new Push();
		
		case "PUT": return new Put();
		
		case "QUERY": return new Query();
		
		case "SEARCH": return new Search();
		}
		
		return null;
	}
	
}
