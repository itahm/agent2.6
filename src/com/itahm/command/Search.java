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
import org.snmp4j.event.ResponseListener;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;

import com.itahm.Agent;
import com.itahm.ITAhMNode;
import com.itahm.http.Response;
import com.itahm.json.JSONObject;
import com.itahm.util.Network;

public class Search extends Command implements Runnable, ResponseListener {

	private Thread thread;
	private Network network;
	
	public void execute(Network network) throws IOException {
		this.network = network;
		this.thread = new Thread(this);
		
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
			table = Agent.db().get("profile").json(),
			profile;
		Target target;
		PDU request;
		String
			ip,
			name;
		UdpAddress udp;
		int version;
		
		for (@SuppressWarnings("unchecked")
		Iterator<Object> profiles = table.keys(); profiles.hasNext();) {
			name = (String)profiles.next();
			
			profile = table.getJSONObject(name);
			
			switch(profile.getString("version").toLowerCase()) {
			case "v3":
				target = new UserTarget();
				
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
			
			for (Iterator<String> it = network.iterator(); it.hasNext(); ) {
				ip = it.next();
				
				try {
					udp.setInetAddress(InetAddress.getByName(ip));
					
					target.setAddress(udp);
					
					request.setRequestID(new Integer32(0));
					
					Agent.node().send(request, target, new Args(ip, name), this);
				} catch (IOException e) {
					System.err.print(e);
				}
			}
		}
	}

	@Override
	public void onResponse(ResponseEvent event) {
		try {
			if (event == null) {
				throw new IOException("null event.");
			}
			else {				
				Object source = event.getSource();
				PDU response = event.getResponse();
				Args args = (Args)event.getUserObject();
				Address address = event.getPeerAddress();
				
				if (!(source instanceof Snmp.ReportHandler) &&
					address instanceof UdpAddress &&
					((UdpAddress)address).getInetAddress().getHostAddress().equals(args.ip) &&
					response != null &&
					response.getErrorStatus() == SnmpConstants.SNMP_ERROR_SUCCESS) {
					((Snmp)source).cancel(event.getRequest(), this);
					//Net Search의 결과 이더라도 base가 존재할 수 있고 심지어 Node가 존재 할 수도 있다.

					Agent.node().onSearch(args.ip, args.profile);
				}
			}
		} catch (IOException ioe) {
			System.err.print(ioe);
		}			
	}
	
	class Args {
		private final String profile;
		private final String ip;
		
		private Args(String ip, String profile) {
			this.ip = ip;
			this.profile = profile;
		}
		
	}
	
}
