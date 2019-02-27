package com.itahm.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.itahm.json.JSONObject;
import com.itahm.util.Util;

public class Table {

	private JSONObject json;
	private Path
		path, backup;
	
	public Table(Path path) throws IOException {
		String id = path.getFileName().toString();
		
		this.path = path;
		this.backup = path.getParent().resolve(id +".backup");
		
		if(Files.isRegularFile(path)) {
			JSONObject json = Util.getJSONFromFile(path);
			
			if (json == null) {				
				if (Files.isRegularFile(this.backup)) {
					json = Util.getJSONFromFile(this.backup);
					
					Files.move(this.backup, this.path, StandardCopyOption.REPLACE_EXISTING);
					
					System.err.print(new IOException(String.format("%s restored from backup", id)));
				}
				
				if (json == null) {
					throw new IOException(String.format("%s not initialized", id));
				}
			}
			
			this.json = json;
		}
		else {
			Util.putJSONtoFile(path, this.json = new JSONObject());
		}
	}
	
	public synchronized void save() throws IOException {
		Files.move(this.path, this.backup, StandardCopyOption.REPLACE_EXISTING);
		
		Util.putJSONtoFile(this.path, this.json);
	}
	
	public JSONObject json() {
		return this.json;
	}
	
	public void save(JSONObject json) throws IOException {
		this.json = json;
		
		save();
	}

}
