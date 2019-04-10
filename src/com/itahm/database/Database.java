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
	private final Map<String, Table> map = new ConcurrentHashMap<>();
	
	public Database() throws IOException {
		
		File dataRoot;
		
		dataRoot = Agent.Config.root();
		
		this.dataRoot = Paths.get(dataRoot.toURI());
		
		Table table;
		
		for (String name : new String [] 
			{"account", "user", "profile", "icon", "config", "position", "address", "setting", "node", "line"}) {
			table = new Table(this.dataRoot.resolve(name));
			
			this.map.put(name, table);
			
			switch (name) {
			case "account":
				if (table.json().length() == 0) {
					table.json()
						.put("root", new JSONObject()
						.put("username", "root")
						.put("password", "63a9f0ea7bb98050796b649e85481845")
						.put("level", 0));
					
					table.save();
				}
				
				break;
			case "profile":
				if (table.json().length() == 0) {
					table.json()
						.put("default", new JSONObject()
						.put("udp", 161)
						.put("community", "public")
						.put("version", "v2c"));
					
					table.save();
				}
				
				break;
				
			case "config":
				if (table.json().length() == 0){
					table.json()
						.put("health", 5) // 5초, 재시도 0회
						.put("snmpInterval", 10000) // 초
						.put("saveInterval", 1) // 분
						.put("top", 5); // 개
					
					table.save();
				}
				
				break;
			}
		}
	}

	public Table get(String table) {
		return this.map.get(table);
	}
	
	public JSONObject get() {
		JSONObject database = new JSONObject();
		
		for (String table : this.map.keySet()) {
			database.put(table, this.map.get(table).json());
		}
		
		return database;
	}
	
	public void put(String name, String key, JSONObject value) throws IOException {
		Table table = this.map.get(name);
		
		table.json().put(key, value);
		
		table.save();
	}
	
	public void restore(JSONObject backup) throws IOException {
		String table;
		
		this.map.clear();
		
		for (Object key: backup.keySet()) {
			table = (String)key;
			
			Util.putJSONtoFile(this.dataRoot.resolve(table), backup.getJSONObject(table));
		}
	}
	
}
