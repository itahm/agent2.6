package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import com.itahm.http.Response;

abstract public class Command {
	abstract public void execute(JSONObject request, Response response) throws IOException, JSONException;
	
	public static Command valueOf(String command) {
		switch(command.toUpperCase()) {
		case "PULL":
			return new Pull();
			
		case "PUSH":
			return new Push();
			
		case "PUT":
			return new Put();
			
		case "QUERY":
			return new Query();
			
		case "SELECT":
			return new Select();
			
		case "CONFIG":
			return new Config();
			
		case "EXTRA":
			return new Extra();
		}
		
		return null;
	}
	
}
