package com.itahm.database;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.itahm.Agent;
import com.itahm.json.JSONObject;
import com.itahm.util.Util;

public class Database {
	
	private final Path dataRoot;
	private final Map<String, Data> map = new ConcurrentHashMap<>();
	
	public Database() throws IOException {
		
		File dataRoot;
		
		dataRoot = Agent.Setting.root();
		
		this.dataRoot = Paths.get(dataRoot.toURI());
		
		load();
	}

	public Data get(String table) {
		return this.map.get(table);
	}
	
	public void put(String table, String key, JSONObject value) throws IOException {
		Data data = this.map.get(table);
		
		data.json.put(key, value);
		
		data.save();
	}
	
	public JSONObject backup() {
		JSONObject backup = new JSONObject();
		
		for (String table : this.map.keySet()) {
			backup.put(table, this.map.get(table).json);
		}
		
		return backup;
	}
	
	
	// TODO 동기화 필요할것.
	public void restore(JSONObject backup) throws IOException {
		String table;
		
		this.map.clear();
		
		for (Object key: backup.keySet()) {
			table = (String)key;
			
			Util.putJSONtoFile(this.dataRoot.resolve(table), backup.getJSONObject(table));
		}
		
		load();
	}
	
	private void load() throws IOException {
		Data data;
		
		for (String table : new String [] {"account", "profile", "icon", "config", "position", "email", "setting"}) {
			data = new Data(this.dataRoot.resolve(table));
			
			this.map.put(table, data);
			
			switch (table) {
			case "account":
				if (data.json.length() == 0) {
					data.json.put("root", new JSONObject()
						.put("username", "root")
						.put("password", "63a9f0ea7bb98050796b649e85481845")
						.put("level", 0));
					
					data.save();
				}
				
				break;
			case "profile":
				if (data.json.length() == 0) {
					data.json.put("default", new JSONObject()
						.put("udp", 161)
						.put("community", "public")
						.put("version", "v2c"));
					
					data.save();
				}
				
				break;
			case "position":
				if (data.json.length() == 0){
					data.json.put("position", new JSONObject());
					
					data.save();
				}
				
				break;
			}
		}
	}
	
}
