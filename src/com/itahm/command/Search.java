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
import com.itahm.json.JSONObject;
import com.itahm.util.Network;

public class Search extends Command implements Runnable {

	private Thread thread;
	private Network network;
	private Snmp snmp;
	private String id;
	
	public void execute(Network network) throws IOException {
		execute(network, null);
	}
	
	public void execute(Network network, String id) throws IOException {
		this.snmp = new Snmp(new DefaultUdpTransportMapping());
		this.network = network;
		this.id = id;
		this.thread = new Thread(this);
		
		this.snmp.listen();
		
		this.thread.setName("ITAhM Search");
		this.thread.setDaemon(true);
		this.thread.start();
	}
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		execute(new Network(InetAddress.getByName(request.getString("network")).getAddress(),
				request.getInt("mask")));
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
			ip,
			name;
		UdpAddress udp;
		ResponseEvent event;
		int version;
		
		for (@SuppressWarnings("unchecked")
		Iterator<Object> profiles = table.keys(); profiles.hasNext();) {
			name = (String)profiles.next();
			
			profile = table.getJSONObject(name);
			
			switch(profile.getString("version").toLowerCase()) {
			case "v3": //SnmpConstants.version3
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
			target.setTimeout(Agent.Setting.timeout());
			
			request.setType(PDU.GETNEXT);
			request.add(new VariableBinding(new OID(new int [] {1,3,6,1,2,1})));
			
			udp = new UdpAddress(profile.getInt("udp"));
			
			for (Iterator<String> it = network.iterator(); it.hasNext(); ) {
				ip = it.next();

				try {
					udp.setInetAddress(InetAddress.getByName(ip));
					
					target.setAddress(udp);
					
					request.setRequestID(new Integer32(0));
					
					event = this.snmp.send(request, target);
					
					// 발생하면 안되는 상황
					if (event == null) {
						if (this.id != null) {
							Agent.event().put(new JSONObject()
								.put("origin", "system")
								.put(name, "System")
								.put("status", false)
								.put("message", String.format("SNMP 서비스에 오류가 있습니다.")), false);
						}
					}
					else {
						response = event.getResponse();
						
						// Search 성공
						if (response != null && response.getErrorStatus() == SnmpConstants.SNMP_ERROR_SUCCESS) {
							//Net Search의 결과 이더라도 base가 존재할 수 있고 심지어 Node가 존재 할 수도 있다. 
							
							this.id = Agent.node().detect(ip, name);
							
							name = Agent.node().getNodeName(this.id);
							
							// Net Search, Set Monitor 여부와 무관하게 통보
							Agent.event().put(new JSONObject()
								.put("origin", "test")
								.put("id", this.id)
								.put("name", name)
								.put("status", true)
								.put("protocol", "snmp")
								.put("message", String.format("%s SNMP 등록 성공", name)), false);
						}
						// Search 실패, Set Monitor의 결과이고 더이상 profile이 존재하지 않으면 통보
						else if (this.id != null && !profiles.hasNext()) {
							name = Agent.node().getNodeName(this.id);
							
							Agent.event().put(new JSONObject()
								.put("origin", "test")
								.put("id", this.id)
								.put("name", name)
								.put("status", false)
								.put("protocol", "snmp")
								.put("message", String.format("%s SNMP 등록 실패", name)), false);
						}
						
					}
				} catch (IOException e) {
					System.err.print(e);
				}
			}
		}
	}
	
}
