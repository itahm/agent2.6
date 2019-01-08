package com.itahm.command;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Iterator;

import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import com.itahm.Agent;
import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.util.Network;

public class Search extends Command implements Runnable {

	private final Thread thread = new Thread(this);
	private Network network;
	private Snmp snmp; 
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException, JSONException {
		this.snmp = new Snmp(new DefaultUdpTransportMapping());
		
		this.network = new Network(InetAddress.getByName(request.getString("network")).getAddress(), request.getInt("mask"));
		
		this.thread.setName("ITAhM Search");
		this.thread.setDaemon(true);
		this.thread.start();
	}

	@Override
	public void run() {	
		JSONObject
			table = Agent.db().get("profile").json,
			profile;
		Target target;
		PDU 
			request,
			response;
		String
			id,
			name;
		UdpAddress udp;
		ResponseEvent event;
		int version;
		
		for (Object key : table.keySet()) {
			name = (String)key;
			
			profile = table.getJSONObject(name);
			
			switch(version = profile.getInt("version")) {
			case SnmpConstants.version3:
				OctetString user = new OctetString(profile.getString("user"));
				USM usm = this.snmp.getUSM();
				
				if (usm.getUserTable().getUser(user) == null) {
					usm.addUser(new UsmUser(user,
						profile.has("md5")? AuthMD5.ID: profile.has("sha")? AuthSHA.ID: null,
						profile.has("md5")? new OctetString(profile.getString("md5")): profile.has("sha")? new OctetString(profile.getString("sha")): null,
						profile.has("des")?	PrivDES.ID: null,
						profile.has("des")?	new OctetString(profile.getString("des")): null));
				}
				
				target = new UserTarget();
				
				target.setSecurityName(user);
				target.setSecurityLevel(profile.getInt("level"));
				
				request = new ScopedPDU();
				
				break;
			default:
				target = new CommunityTarget();
					
				((CommunityTarget)target).setCommunity(new OctetString(profile.getString("community")));
				
				request = new PDU();
			}
			
			target.setVersion(version);
			target.setRetries(0);
			target.setTimeout(Agent.Setting.timeout());
			
			request.setType(PDU.GETNEXT);
			request.add(new VariableBinding(new OID(new int [] {1,3,6,1,2,1})));
			
			udp = new UdpAddress(profile.getInt("udp"));
		
			for (Iterator<String> it = network.iterator(); it.hasNext(); ) {
				try {
					udp.setInetAddress(InetAddress.getByName(it.next()));
					
					request.setRequestID(new Integer32(0));
					
					event = this.snmp.send(request, target);
					
					if (event != null) {
						response = event.getResponse();
						
						if (response != null && response.getErrorStatus() == SnmpConstants.SNMP_ERROR_SUCCESS) {
							id = Agent.node().create(new JSONObject()
								.put("base", new JSONObject()
									.put("ip", ""))
								.put("monitor", new JSONObject()
									.put("profile", name)
									.put("protocol", "snmp")));
							
							Agent.event().put(new JSONObject()
								.put("origin", "test")
								.put("id", id)
								.put("test", true)
								.put("protocol", "snmp")
								.put("message", String.format("%s SNMP 등록 성공", id)), false);
						}
						
					}
				} catch (IOException e) {}
				
				target.setAddress(udp);
			}
			
		}
	}
	
}
