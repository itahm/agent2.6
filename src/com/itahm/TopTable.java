package com.itahm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.itahm.json.JSONObject;
import com.itahm.table.Table;

public class TopTable implements Comparator<String> {
	
	public enum Resource {
		RESPONSETIME("responseTime"),
		FAILURERATE("failureRate", true),
		PROCESSOR("processor"),
		MEMORY("memory"),
		MEMORYRATE("memoryRate", true),
		STORAGE("storage"),
		STORAGERATE("storageRate", true),
		THROUGHPUT("throughput"),
		THROUGHPUTRATE("throughputRate", true),
		THROUGHPUTERR("throughputErr");
		
		private final String resource;
		private final boolean rate;
		
		private Resource(String resource) {
			this(resource, false);
		}
		
		private Resource(String resource, boolean rate) {
			this.resource = resource;
			this.rate = rate;
		}
		
		public String toString() {
			return this.resource;
		}
		
		public boolean isRate() {
			return rate;
		}
	};
	
	private final Map<Resource, HashMap<String, Value>> map = new ConcurrentHashMap<> ();
	private Map<String, Value> top;
	private boolean sortByRate;
	
	public TopTable() {
		for (Resource key : Resource.values()) {
			map.put(key, new HashMap<String, Value>());
		}
	}
	
	public void submit(Resource resource, String ip, Value value) {
		this.map.get(resource).put(ip, value);
	}
	
	public JSONObject getTop(int count) {
		Table monitorTable = Agent.getTable(Table.Name.MONITOR);
		JSONObject top = new JSONObject()
			,resourceTop
			,monitor;
		List<String> list;
		String ip;
		
		for (Resource resource : Resource.values()) {
			resourceTop = new JSONObject();
			list = new ArrayList<String>();
			
			this.top = this.map.get(resource);
			this.sortByRate = resource.isRate();
			
			list.addAll(this.top.keySet());
			Collections.sort(list, this);
		
			for (int i=0, _i= list.size(), n=0; i<_i && n<count; i++) {
				ip = list.get(i);
				
				monitor = monitorTable.getJSONObject(ip);
				
				if (monitor == null || monitor.getBoolean("shutdown")) {
					continue;
				}
				
				resourceTop.put(ip,  this.top.get(ip).toJSONObject());
				
				n++;
			}
			
			top.put(resource.toString(), resourceTop);
		}
		
		return top;
	}
	
	public void remove(String ip) {
		for (Resource resource: Resource.values()) {
			this.map.get(resource).remove(ip);
		}
	}
	
	@Override
	public int compare(String ip1, String ip2) {
		Value v1 = this.top.get(ip1),
			v2 = this.top.get(ip2);
		long l;
		
		if (this.sortByRate) {
			l = v2.getRate() - v1.getRate();
			
			if (l == 0) {
				l = v2.getValue() - v1.getValue();
			}
		}
		else {
			l = v2.getValue() - v1.getValue();
			
			if (l == 0) {
				l = v2.getRate() - v1.getRate();
			}
		}
		
        return l > 0? 1: l < 0? -1: 0;
	}
	
	public final static class Value {
		private final long value;
		private final long rate;
		private final long index;
		
		public Value(long value, long rate, String index) {
			this.value = value;
			this.rate = rate;
			this.index = Long.parseLong(index);
		}
		
		public long getValue() {
			return this.value;
		}
		
		public long getRate() {
			return this.rate;
		}
		
		public JSONObject toJSONObject() {
			return new JSONObject()
				.put("value", this.value)
				.put("rate", this.rate)
				.put("index", this.index);
		}
	}
}