package com.itahm.table;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

import com.itahm.Agent;
import com.itahm.json.JSONObject;
import com.itahm.util.Util;

public class Table implements Closeable{
	public enum Name {
		ACCOUNT("account"),
		CRITICAL("critical"),
		DEVICE("device"),
		MONITOR("monitor"),
		ICON("icon"),
		POSITION("position"),
		PROFILE("profile"),
		CONFIG("config"),
		SMTP("smtp"),
		SMS("sms");
		
		private String name;
		
		private Name(String name) {
			this.name = name;
		}
		
		public String toString() {
			return this.name;
		}
		
		public static Name getName(String name) {
			for (Name value : values()) {
				if (value.toString().equalsIgnoreCase(name)) {
					return value;
				}
			}
			
			return null;
		}
	}
	
	protected JSONObject table;
	private final File tableFile;
	private final File backupFile;
	
	/**
	 * 
	 * @param dataRoot
	 * @param name 
	 * @throws IOException
	 */
	public Table(File dataRoot, Name name) throws IOException {
		tableFile = new File(dataRoot, name.toString());
		backupFile = new File(dataRoot, name.toString() +".backup");
		
		if (tableFile.isFile()) {
			table = Util.getJSONFromFile(tableFile);
			
			if (table == null) {
				if (backupFile.isFile()) {
					tableFile.delete();
					
					backupFile.renameTo(tableFile);
					
					table = Util.getJSONFromFile(tableFile);
					
					Agent.log(new JSONObject().
						put("origin", "system").
						put("message", String.format("Table.%s 파일이 백업으로부터 복구되었습니다.", name.toString())), false);
				}
				
				if (table == null) {
					throw new IOException("Table."+ name.toString() +" loading failure");						
				}
			}
		}
		else {
			table = Util.putJSONtoFile(tableFile, new JSONObject());
		}
	}
	
	protected boolean isEmpty() {
		return this.table.length() == 0;
	}
	
	public JSONObject getJSONObject() {
		return this.table;
	}
	
	public JSONObject getJSONObject(String key) {
		if (this.table.has(key)) {
			return this.table.getJSONObject(key);
		}
		
		return null;
	}
	
	public JSONObject put(String key, JSONObject value) throws IOException {
		if (value == null) {
			this.table.remove(key);
		}
		else {
			this.table.put(key, value);
		}
		
		return save();
	}
	
	public synchronized JSONObject save() throws IOException {
		this.tableFile.renameTo(this.backupFile);
		
		return Util.putJSONtoFile(this.tableFile, this.table);
	}

	public JSONObject save(JSONObject table) throws IOException{
		this.table = table;
		
		return save();
	}

	@Override
	public synchronized void close() throws IOException {}
	
}
