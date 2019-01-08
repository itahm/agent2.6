package com.itahm;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.snmp4j.mp.SnmpConstants;

import com.itahm.TopTable.Resource;
import com.itahm.database.Data;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.node.Node;
import com.itahm.node.ICMPNode;
import com.itahm.node.NodeListener;
import com.itahm.node.TCPNode;
import com.itahm.util.Util;

public class NodeManager implements NodeListener {

	private static final String PREFIX_NODE = "node.";
	private Long nodeNum = -1L;
	private final Path nodeRoot;
	private final TopTable topTable = new TopTable();
	private final Map<String, NodeData> map = new ConcurrentHashMap<>();
	private final JSONObject posTable;
	
	public NodeManager() throws NumberFormatException, JSONException, IOException {
		Path dataRoot = Paths.get(Agent.Setting.root().toURI());
		
		this.nodeRoot = dataRoot.resolve("node");
		
		this.posTable = Agent.db().get("position").json;
		
		Files.createDirectories(this.nodeRoot);
		
		load();
	}

	public String create(JSONObject json) {
		String id = String.format("%s%d", PREFIX_NODE, ++this.nodeNum);
		
		try {
			Path
				nodeDir = nodeRoot.resolve(id),
				nodePath;
			NodeData data;
			
			Files.createDirectory(nodeDir);
			
			nodePath = nodeDir.resolve(id);
			
			Util.putJSONtoFile(nodePath, json);
			
			data = new NodeData(this, nodePath, id);
			
			this.map.put(id, data);
			
			if (data.node != null) {
				data.node.ping(Agent.Setting.requestInterval());
			}
			
		} catch (IOException e) {
			e.printStackTrace();
			
			return null;
		}
		
		return id;
	}
	
	public void remove(String id) {
		close(id);
		
		JSONObject
			node = this.map.get(id).json,
			posTable = this.posTable.getJSONObject("position");
		
		if (posTable.has(id)) {
			JSONObject
				pos = posTable.getJSONObject(id),
				peer;
			
			for (Object key : pos.getJSONObject("ifEntry").keySet()) {
				peer = posTable.getJSONObject((String)key);
				
				if (peer != null) {
					peer.getJSONObject("ifEntry").remove(id);
				}
			}
			
			posTable.remove(id);
		}
		
		switch (node.getString("type")) {
		case "group":
			JSONObject child;
			
			for (Object key : posTable.keySet()) {
				child = posTable.getJSONObject((String)key);
				
				if (child.has("parent") && id.equals(child.getString("parent"))) {
					child.remove("parent");
				}
			}
			
			break;
		}
		
		this.map.remove(id);
	}
	
	public String getNodeName(String id) {
		NodeData data = this.map.get(id);
		
		if (data == null) {
			return "";
		}
		
		JSONObject base = data.json.getJSONObject("base");
		String name = base.has("name")? base.getString("name"): "";
		
		return name.length() > 0? name: id;
	}
	
	public JSONObject get(String id) {
		NodeData data = this.map.get(id);
		
		if (data == null) {
			return null;
		}
		
		return data.json;
	}
	
	public Node getNode(String id) {
		NodeData data = this.map.get(id);
		
		if (data == null) {
			return null;
		}
		
		return data.node;
	}
	
	public void setNode(String id, Node node) {
		NodeData data = this.map.get(id);
		
		if (data == null) {
			return;
		}
		
		data.node = node;
	}
	
	public void setBase(String id, JSONObject base) throws IOException {
		NodeData data = this.map.get(id);
		
		if (data != null) {
			data.json.put("base", base);
			
			data.save();
		}
	}
	
	public void setMonitor(String id, JSONObject monitor) throws IOException {
		NodeData data = this.map.get(id);
		
		if (data == null) {
			return;
		}
		
		if (data.node != null) {
			data.node.close();
			
			data.node = null;
		}
		
		if (monitor == null) {
			data.json.remove("monitor");
		}
		else {
			data.json.put("monitor", monitor);
			
			Node node = createNode(this, id, data);
			
			if (node != null) {
				node.ping(0);
			}
		}		
		
		data.save();
	}
	
	public void setUpDown(String id, JSONObject updown) {
		NodeData data = this.map.get(id);
		
		if (data != null && data.node instanceof ITAhMNode) {
			((ITAhMNode)data.node).setUpDown(updown);
		}
	}
	
	public void setSpeed(String id, JSONObject speed) {
		NodeData data = this.map.get(id);
		
		if (data != null && data.node instanceof ITAhMNode) {
			((ITAhMNode)data.node).setSpeed(speed);
		}
	}
	
	public void save (String id, JSONObject snmp) throws IOException {
		NodeData data = this.map.get(id);
		
		if (data == null) {
			return;
		}
		
		data.json.put("snmp", snmp);
		
		data.save();
	}
	
	public void close (String id) {
		if (!this.map.containsKey(id)) {
			return;
		}
		
		Node node = this.map.get(id).node;
		
		if (node != null) {
			node.close();
		}
	}
	
	public JSONObject backup() {
		JSONObject backup = new JSONObject();
		
		for (String table : this.map.keySet()) {
			backup.put(table, this.map.get(table).json);
		}
		
		return backup;
	}
	
	public void load() throws NumberFormatException, IOException {		
		NodeData data;
		String id;
	
		for (Path path : Files.newDirectoryStream(this.nodeRoot)) {
			if (!Files.isDirectory(path)) {
				continue;
			}
			
			id = path.getFileName().toString();
			
			data = new NodeData(this, path, id);
			
			this.map.put(id, data);
			
			if (data.node != null) {
				data.node.ping(Agent.Setting.requestInterval());
			}
			
			this.nodeNum = Math.max(this.nodeNum, Long.valueOf(id.replace(PREFIX_NODE, "")));			
		}
	}
	
	public void submitTop(String id, Resource resource, TopTable.Value value) {
		if (!this.map.containsKey(id)) {
			// 삭제된 노드는 toptable에서도 삭제
			this.topTable.remove(id);
			
			return;
		}
		
		this.topTable.submit(resource, id, value);
	}
	
	public void removeTop(String id) {
		this.topTable.remove(id);
	}
	
	public JSONObject getTop() {
		return this.topTable.getTop(Agent.Setting.top());
	}
	
	public final long calcLoad() {
		Node node;
		BigInteger bi = BigInteger.valueOf(0);
		long size = 0;
		
		for (String id : this.map.keySet()) {
			node = this.map.get(id).node;
			if (node instanceof ITAhMNode) {
				bi = bi.add(BigInteger.valueOf(((ITAhMNode)node).getLoad()));
			}
			
			size++;
		}
		
		return size > 0? bi.divide(BigInteger.valueOf(size)).longValue(): 0;
	}
	
	public void setHealth(int health) {
		int
			timeout = Byte.toUnsignedInt((byte)(health & 0x0f)) *1000,
			retry = Byte.toUnsignedInt((byte)((health >> 4)& 0x0f));
		Node node;
		
		for (String id : this.map.keySet()) {
			node = this.map.get(id).node;
			
			if (node != null) {
				node.setHealth(timeout, retry);
			}
		}
	}
	
	public long getResourceCount() {
		Node node;
		long count = 0;
		
		for (String id : this.map.keySet()) {
			node = this.map.get(id).node;
		
			if (node != null && node instanceof ITAhMNode) {
				count += ((ITAhMNode)node).getResourceCount();
			}
		}
		
		return count;
	}
	
	/**
	 * ICMPNode로 부터 호출
	 */
	@Override
	public void onSuccess(Node node, long time) {
		NodeData data = this.map.get(node.id);
		
		if (data == null) {
			return;
		}
		
		JSONObject monitor = data.json.getJSONObject("monitor");
		
		if (!monitor.getBoolean("status")) {
			monitor.put("status", true);
			
			try {
				data.save();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
			
			Agent.event().put(new JSONObject()
				.put("origin", "status")
				.put("id", node.id)
				.put("status", true)
				.put("message", String.format("%s 응답 정상.", getNodeName(node.id))), false);
		}
		
		node.ping(Agent.Setting.requestInterval());
	}

	/**
	 * ICMPNode로 부터 호출
	 */
	@Override
	public void onFailure(Node node) {
		NodeData data = this.map.get(node.id);
		
		if (data == null) {
			return;
		}
		
		JSONObject monitor = data.json.getJSONObject("monitor");
		
		if (monitor.getBoolean("status")) {
			monitor.put("status", false);
			
			try {	
				data.save();
				
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
			
			Agent.event().put(new JSONObject()
				.put("origin", "status")
				.put("id", node.id)
				.put("status", false)
				.put("message", String.format("%s 응답 없음.", getNodeName(node.id))), false);
		}
		
		data.node.ping(0);
	}

	
	public void stop() throws IOException {
		for (String id : this.map.keySet()) {
			close(id);
		}
	}

	private Node createNode(NodeListener listener, String id, Data data) throws IOException {
		Node node = null;
		
		try {
			JSONObject
				base = data.json.getJSONObject("base"),
				monitor = data.json.getJSONObject("monitor");
			
			switch(monitor.getString("protocol").toLowerCase()) {
			case "snmp":
				JSONObject
					profileTable = Agent.db().get("profile").json,
					profile = profileTable.getJSONObject(monitor.getString("profile"));
				
				if (profile.getInt("version") == SnmpConstants.version3) {
					node = new SNMPV3Node(listener, id, base.getString("ip"), profile.getInt("udp"), profile.getString("user"), profile.getInt("level"));
					
					((SNMPV3Node)node).setUSM(profile);
				}
				else {
					node = new SNMPDefaultNode(listener, id, base.getString("ip"), profile.getInt("udp"), profile.getString("community"));
				}
				
				if (node != null && data.json.has("snmp")) {
					JSONObject 
						snmp = data.json.getJSONObject("snmp"),
						speed = new JSONObject(),
						updown = new JSONObject(),
						critical = new JSONObject(),
						entry, indexData;
					String
						index, resource;
					
					for (Object key1 : snmp.keySet()) {
						resource = (String)key1;
						
						entry = snmp.getJSONObject(resource);
						
						for (Object key2 : entry.keySet()) {
							index = (String)key2;
							
							indexData = entry.getJSONObject(index);
							
							if (indexData.has("updown")) {
								updown.put(index, indexData.getBoolean("updown"));
							}
							
							if (indexData.has("speed")) {
								speed.put(index, indexData.getLong("speed"));
							}
							
							if (indexData.has("critical")) {
								if (!critical.has(resource)) {
									critical.put(resource, new JSONObject());
								}
							
								critical.getJSONObject(resource)
									.put(index, new JSONObject()
										.put("critical", indexData.getInt("critical"))
										.put("status", indexData.has("status")? indexData.getBoolean("status"): false));
							}
						}
					}
					
					if (updown.length() > 0) {
						((ITAhMNode)node).setUpDown(updown);
					}
					
					if (speed.length() > 0) {
						((ITAhMNode)node).setSpeed(speed);
					}
					
					if (critical.length() > 0) {
						((ITAhMNode)node).setCritical(critical);
					}
				}
				
				break;
				
			case "icmp":
				node = new ICMPNode(listener, id, base.getString("ip"));
				
				break;
				
			case "tcp":
				node = new TCPNode(listener, id, base.getString("ip"), monitor.getInt("udp"));
				
				break;
				
			default:
				node = null;
			}
		}
		catch (JSONException jsone) {
			throw new IOException(jsone);
		}
	
		return node;

	}
	
	private class NodeData extends Data {

		private Node node; 
		
		private NodeData(NodeListener listener, Path path, String id) throws IOException {
			super(path);
			
			if (super.json.has("monitor")) {
				this.node = createNode(listener, id, this);
			}
		}
	}
}
