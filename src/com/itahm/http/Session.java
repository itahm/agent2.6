package com.itahm.http;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Session {

	private static final Map<String, Session> sessions = new ConcurrentHashMap<>();
	private static final Timer timer = new Timer(true);
	
	private final Map<String, Object> attribute = new HashMap<>();
	private long timeout = 60*60*1000;
	private final String id;
	private TimerTask task;
	
	public Session() {
		id = UUID.randomUUID().toString();
		
		sessions.put(id, this);
		
		update();
	}
	
	public static int count() {
		return sessions.size();
	}
	
	public static Session find(String id) {
		return sessions.get(id);
	}
	
	public void setMaxInactiveInterval(long timeout) {
		this.timeout = timeout * 1000;
		
		update();
	}
	
	public String getId() {
		return this.id;
	}
	
	public Session update() {
		if (this.task != null) {
			this.task.cancel();
		}
		
		this.task = new TimerTask() {

			@Override
			public void run() {
				sessions.remove(id);
			}
		};
		
		try {
			timer.schedule(this.task, timeout);
		}
		catch (IllegalStateException ise) {
			return null;
		}
		
		return this;
	}
	
	public void setAttribute(String name, Object value) {
		this.attribute.put(name, value);
	}
	
	public Object getAttribute(String name) {
		return this.attribute.get(name);
	}
	
	public void invalidate() {
		this.task.cancel();
		
		sessions.remove(id);
	}
	
}
