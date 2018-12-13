package com.itahm.table;

import java.io.File;
import java.io.IOException;

import com.itahm.json.JSONObject;
import com.itahm.Agent;

public class Monitor extends Table {
	
	public Monitor(File dataRoot) throws IOException {
		super(dataRoot, Name.MONITOR);
	}
	
	public JSONObject put(String ip, JSONObject monitor) throws IOException {
		// 모니터를 icmp 에서 snmp로, snmp 에서 icmp로 변경되는 상황, 또는 모니터를 삭제하는 상황
		// 어떤상황이든 기존 모니터가 있다면 일단 지워주자.
		if (super.table.has(ip)) {
			// 삭제인 경우 monitor가 null 이므로
			switch((monitor == null? super.table.getJSONObject(ip): monitor).getString("protocol")) {
			case "snmp":
				if (Agent.removeSNMPNode(ip)) {
					Agent.getTable(Name.CRITICAL).put(ip, null);
				}
				
				break;
				
			case "icmp":
				Agent.removeICMPNode(ip);
				
				break;
			}
			
			super.put(ip, null);
		}
		
		if (monitor != null) {
			// 테스트 결과인 경우
			if (monitor.has("shutdown")) {
				super.put(ip,  monitor);
			}
			// 테스트 요청인 경우
			else {
				switch(monitor.getString("protocol")) {
				case "snmp":
					Agent.testSNMPNode(ip, monitor.getString("id"));
					
					break;
				case "icmp":
					Agent.testICMPNode(ip);
					
					break;
				}
			}
		}// else 위에서 처리 되었음.
		
		return super.table;
	}
}
