package com.itahm;

import java.util.HashMap;
import java.util.Map;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

abstract public class Analyzer {

	private final Map<String, Map<String, Critical>> critical = new HashMap<>();
	
	public void analyze(String resource, String index, long max, long value) {
		Map<String, Critical> entry;
		Critical critical;
		
		if (resource.equals("hrProcessorEntry")) {
			if (this.critical.containsKey("hrProcessorEntry")) {
				entry = this.critical.get(resource);
				
				critical = entry.get(index);
				
				if (critical == null) {
					critical = entry.get("0");
					
					if (critical != null) {
						critical = critical.clone();
						
						entry.put(index, critical);
						
						long rate = value *100 / max;
						
						if (critical.test(rate)) {
							for (String key : entry.keySet()) {
								if ("0".equals(key) || index.equals(key)) {
									continue;
								}
								
								if (entry.get(key).isCritical()) {
									return;
								}
							}
							
							onCritical(critical.isCritical(), "hrProcessorEntry", "0", rate);
						}
					}
				}
			}
		}
		else if (this.critical.containsKey(resource)) {
			entry = this.critical.get(resource);
			
			critical = entry.get(index);
		
			if (critical != null) {
				long rate = value *100 / max;
				
				if (critical.test(rate)) {
					onCritical(critical.isCritical(), resource.toString(), index, rate);
				}
			}
		}
	}
	
	public void set(JSONObject critical) {
		this.critical.clear();
		
		Map<String, Critical> entry;
		JSONObject jsono;
		String
			resource, index;
		
		for (Object key1 : critical.keySet()) {
			resource = (String)key1;
			
			if (this.critical.containsKey(resource)) {
				entry = this.critical.get(resource);
			}
			else {
				entry = new HashMap<>();
				
				this.critical.put(resource, entry);
			}
				 
			jsono = critical.getJSONObject(resource);
			
			for (Object key2 : jsono.keySet()) {
				index = (String)key2;
					
				try {
					entry.put(index, new Critical(jsono.getJSONObject(index).getInt("critical")));
				}
				catch(JSONException jsone) {
					System.err.print(jsone);
				}
			}
		}
	}
	
	class Critical {
		
		private final int limit;
		private boolean critical = false;
		
		private Critical(int limit) {
			this.limit = limit;
		}
		
		public boolean isCritical() {
			return this.critical;
		}
		
		private boolean test(long rate) {
			boolean critical = this.limit <= rate;
			
			if (this.critical == critical) { // 상태가 같으면 none
				return false;
			}
			
			this.critical = critical; // 바뀐 상태 입력
			
			return true;
		}
		
		@Override
		public Critical clone() {
			return new Critical(this.limit);
		}
		
	}
	
	abstract public void onCritical(boolean isCritical, String resource, String index, long rate);
}
