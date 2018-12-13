package com.itahm;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import com.itahm.icmp.ICMPListener;
import com.itahm.icmp.ICMPNode;
import com.itahm.table.Table;

public class ICMPAgent implements ICMPListener, Closeable {
	
	private final Map<String, ICMPNode> nodeList = new ConcurrentHashMap<>();
	private final Table monitorTable = Agent.getTable(Table.Name.MONITOR);
	private long interval = 10000;
	private int timeout = 10000,
		retry = 1;
	
	public ICMPAgent() throws IOException {		
		System.out.println("ICMP manager start.");
	}
	
	public void start() {
		JSONObject snmpData = monitorTable.getJSONObject();
		
		for (Object ip : snmpData.keySet()) {
			try {
				if ("icmp".equals(snmpData.getJSONObject((String)ip).getString("protocol"))) {
					addNode((String)ip);
				}
			} catch (JSONException jsone) {
				System.err.print(jsone);
			}
		}
	}
	
	private void addNode(String ip) {
		try {
			ICMPNode node = new ICMPNode(this, ip);
			
			node.setHealth(this.timeout, this.retry);
			
			this.nodeList.put(ip, node);
			
			node.ping(0);
		} catch (UnknownHostException uhe) {
			System.err.print(uhe);
		}		
	}
	
	public boolean removeNode(String ip) {
		ICMPNode node;
		
		synchronized (this.nodeList) {
			node = this.nodeList.remove(ip);
		}
		
		if (node == null) {
			return false;
		}
		
		try {
			node.close();
		} catch (IOException ioe) {
			System.err.print(ioe);
		}
		
		return true;
	}
	
	public ICMPNode getNode(String ip) {
		synchronized(this.nodeList) {
			return this.nodeList.get(ip);
		}
	}
	
	public void testNode(final String ip) {
		new Thread(new Runnable() {

			@Override
			public void run() {
				boolean isReachable = false;
				
				try {
					InetAddress target = InetAddress.getByName(ip);
					
					for (int i=0; i<retry; i++) {
						if (Thread.interrupted()) {
							break;
						}
						
						try {
							if (target.isReachable(timeout)) {
								isReachable = true;
								
								break;
							}
						} catch (IOException ioe) {
							System.err.print(ioe);
							
							break;
						}
					}
				} catch (UnknownHostException uhe) {}
				
				if (!isReachable) {
					Agent.log(new JSONObject()
						.put("origin", "test")
						.put("ip", ip)
						.put("test", false)
						.put("protocol", "icmp")
						.put("message", String.format("%s ICMP 등록 실패", ip)), false);
				}
				else {
					monitorTable.getJSONObject().put(ip, new JSONObject()
						.put("protocol", "icmp")
						.put("ip", ip)
						.put("shutdown", false));
					
					try {
						monitorTable.save();
					} catch (IOException ioe) {
						System.err.print(ioe);
					}
					
					addNode(ip);
					
					Agent.log(new JSONObject()
						.put("origin", "test")
						.put("ip", ip)
						.put("test", true)
						.put("protocol", "icmp")
						.put("message", String.format("%s ICMP 등록 성공", ip)), false);
				}
			}
			
		}).start();
	}
	
	public void setHealth(int timeout, int retry) {
		this.timeout = timeout;
		this.retry = retry;
		
		for (String ip : this.nodeList.keySet()) {
			this.nodeList.get(ip).setHealth(timeout, retry);
		}
	}
	
	public void setInterval(long interval) {
		this.interval = interval;
	}
	
	public void onSuccess(ICMPNode node, long time) {
		JSONObject monitor = this.monitorTable.getJSONObject(node.ip);
		
		if (monitor == null) {
			return;
		}
		
		if (monitor.getBoolean("shutdown")) {
			monitor.put("shutdown", false);
			
			try {
				this.monitorTable.save();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
			
			Agent.log(new JSONObject()
				.put("origin", "shutdown")
				.put("ip", node.ip)
				.put("shutdown", false)
				.put("protocol", "icmp")
				.put("message", String.format("%s ICMP 응답 정상", node.ip)), true);
		}
		
		node.ping(this.interval);
	}
	
	public void onFailure(ICMPNode node) {
		JSONObject monitor = this.monitorTable.getJSONObject(node.ip);
		
		if (monitor == null) {
			return;
		}
		
		if (!monitor.getBoolean("shutdown")) {
			monitor.put("shutdown", true);
			
			try {
				this.monitorTable.save();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
			
			Agent.log(new JSONObject()
				.put("origin", "shutdown")
				.put("ip", node.ip)
				.put("shutdown", true)
				.put("protocol", "icmp")
				.put("message", String.format("%s ICMP 응답 없음", node.ip)), true);
		}
		
		node.ping(0);
	}
	
	/**
	 * ovverride
	 */
	@Override
	public void close() {
		synchronized (this.nodeList) {
			for (ICMPNode node : this.nodeList.values()) {
				try {
					node.close();
				} catch (IOException ioe) {
					System.err.print(ioe);
				}
			}
		}
		
		this.nodeList.clear();
		
		System.out.format("ICMP manager stop.\n");
		
	}
	
}
