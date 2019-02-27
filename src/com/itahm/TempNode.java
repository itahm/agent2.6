package com.itahm;

import java.io.IOException;
import java.net.InetAddress;

import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;

import com.itahm.json.JSONObject;

public class TempNode implements Runnable {
	private final Thread thread = new Thread(this);
	private final String id;
	private final String ip;
	
	public TempNode(String id, String ip) {
		this.id = id;
		this.ip = ip;
		
		this.thread.setName("ITAhM TempNode");
		this.thread.setDaemon(true);
		this.thread.start();
	}
	
	@Override
	public void run() {
		JSONObject
			table = Agent.db().get("profile").json(),
			event = new JSONObject()
				.put("origin", "test")
				.put("id", this.id)
				.put("name", "System")
				.put("protocol", "snmp"),
			profile;
		Target target;
		PDU request;
		String name;
		UdpAddress udp;
		int version;
		
		for (Object key : table.keySet()) {
			name = (String)key;
			
			profile = table.getJSONObject(name);
			
			switch(profile.getString("version").toLowerCase()) {
			case "v3":
				target = new UserTarget();
				System.out.println(profile.getString("user"));
				target.setSecurityName(new OctetString(profile.getString("user")));
				target.setSecurityLevel(profile.getInt("level"));
				
				request = new ScopedPDU();
				
				version = SnmpConstants.version3;
				
				break;
			case "v2c":
				target = new CommunityTarget();
					
				((CommunityTarget)target).setCommunity(new OctetString(profile.getString("community")));
				
				request = new PDU();
				
				version = SnmpConstants.version2c;
				
				break;
				
			default:
				target = new CommunityTarget();
				
				((CommunityTarget)target).setCommunity(new OctetString(profile.getString("community")));
				
				request = new PDU();
				
				version = SnmpConstants.version1;	
			}
			
			target.setVersion(version);
			target.setRetries(0);
			target.setTimeout(Agent.Config.timeout());
			
			request.setType(PDU.GETNEXT);
			request.add(new VariableBinding(ITAhMNode.OID_mib2));
			
			udp = new UdpAddress(profile.getInt("udp"));
				
			try {
				udp.setInetAddress(InetAddress.getByName(this.ip));
				
				target.setAddress(udp);
				
				request.setRequestID(new Integer32(0));
				
				if (onResponse(Agent.node().send(request, target))) {
		
					Agent.node().onDetect(this.ip, name);
					
					Agent.event().put(event.put("status", true)
						.put("message",
							String.format("%s SNMP 등록 성공.", Agent.node().getNodeName(this.id))), false);
					
					return;
				}
			} catch (IOException e) {
				System.err.print(e);
			}
		}

		Agent.event().put(event.put("status", false)
			.put("message", String.format("%s SNMP 등록 실패.", Agent.node().getNodeName(this.id))), false);
	}
	
	public boolean onResponse(ResponseEvent event) {
		Object source = event.getSource();
		PDU response = event.getResponse();
		Address address = event.getPeerAddress();
		
		return (event != null &&
			!(source instanceof Snmp.ReportHandler) &&
			(address instanceof UdpAddress) &&
			((UdpAddress)address).getInetAddress().getHostAddress().equals(this.ip) &&
			response != null &&
			response.getErrorStatus() == SnmpConstants.SNMP_ERROR_SUCCESS);
	}
}
