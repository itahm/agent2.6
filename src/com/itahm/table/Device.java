package com.itahm.table;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.http.HTTPException;
import com.itahm.http.Response;

public class Device extends Table {
	
	public static final String NULL = "";
	private static final String PREFIX_DEVICE = "node.";
	private static final String PREFIX_GROUP = "group.";
	private static final String PREFIX_APP = "app.";
	
	private long groupOrder = -1;
	private long deviceOrder = -1;
	
	private final Map<String, JSONObject> idMap = new HashMap<>();
	
	public Device(File dataRoot) throws IOException {
		super(dataRoot, Name.DEVICE);
		
		JSONObject device;
		String id;
		
		for (Object key : super.table.keySet()) {
			id = (String)key;
			
			device = super.table.getJSONObject(id);
			
			if (device.has("ip")) {
				idMap.put(device.getString("ip"), device);
			}
			
			if (id.indexOf(PREFIX_DEVICE) == 0) {
				try {
					deviceOrder = Math.max(deviceOrder, Long.valueOf(id.replace(PREFIX_DEVICE, "")));
				}
				catch(NumberFormatException nfe) {}
			}
			else if (id.indexOf(PREFIX_GROUP) == 0) {
				try {
					groupOrder = Math.max(groupOrder, Long.valueOf(id.replace(PREFIX_GROUP, "")));
				}
				catch(NumberFormatException nfe) {}
			} 
		}
	}
	
	/* 공통: position (링크 포함) 정보 삭제
	device: monitor, critical 정보 삭제  */
	private void remove(String id) throws IOException {
		JSONObject device = super.getJSONObject(id);
		
		// 동기화 문제로 없는 device라면
		if (device == null) {
			throw new HTTPException(Response.Status.CONFLICT.getCode());
		}
		
		if (device.has("ip")) {
			this.idMap.remove(device.getString("ip"));
		}
		
		// device, group, application 공통
		final Table posTable = Agent.getTable(Name.POSITION);
		final JSONObject posData = posTable.getJSONObject(),
			pos = posTable.getJSONObject(id);
		
		if (pos != null) {
			JSONObject peer;
			for (Object key : pos.getJSONObject("ifEntry").keySet()) {
				peer = posTable.getJSONObject((String)key);
				if (peer != null) {
					peer.getJSONObject("ifEntry").remove(id);
				}
			}
		}
		
		posTable.put(id, null);
		
		// group인 경우 하위 장비가 있다면 parent 정보 초기화.
		if (device.has("group") && device.getBoolean("group")) {
			JSONObject child;
			
			for (Object key : posData.keySet()) {
				child = posData.getJSONObject((String)key);
				
				if (child.has("parent") && id.equals(child.getString("parent"))) {
					child.remove("parent");
				}
			}
		}
		// device인 경우 monitor 테이블과 critical 테이블 삭제
		else if (device.has("ip")){
			String ip = device.getString("ip");
			
			// monitor에서 critical을 함께 삭제함
			Agent.getTable(Name.MONITOR).put(ip, null);
			//Agent.getTable(Name.CRITICAL).put(ip,  null);
			
			// idMap에서 제거
			this.idMap.remove(ip);
		}
		// else application은 대상 아님
	}
	
	private String add(JSONObject device) {
		String id;
		
		if (device.has("application")) {
			id = String.format("%s%d", PREFIX_APP, device.getInt("tcp"));
			
			// TODO 바로 모니터 등록
		} else if (device.has("group") && device.getBoolean("group")) {
			id = String.format("%s%d", PREFIX_GROUP, ++this.groupOrder);
		} else {
			id = String.format("%s%d", PREFIX_DEVICE, ++this.deviceOrder);
		}			
				
		if (device.has("ip")) {
			this.idMap.put(device.getString("ip"), device);
		}
		
		return id;
	}
	
	private void modify(String id, JSONObject device) {
		if (device.has("ip")) {
			String ip = device.getString("ip");
			
			if (!this.idMap.containsKey(ip)) {
				this.idMap.put(ip, device);
			}
		}
		
		Agent.setInterface(device);
	}
	
	public JSONObject getDevicebyIP(String ip) {
		return this.idMap.get(ip);
	}
	/**
	 * 추가인 경우 position 기본 정보를 생성해 주어야 하며,
	  
	 * @throws IOException 
	 */
	
	public JSONObject put(String id, JSONObject device) throws IOException, HTTPException {
		if (device == null) { // 삭제
			remove(id);
		}
		else if (NULL.equals(id)){ // 추가
			id = add(device);
		}
		else { // 수정
			modify(id, device);
		}
		
		super.put(id, device);
		
		return new JSONObject().put(id, device);
	}
	
}
