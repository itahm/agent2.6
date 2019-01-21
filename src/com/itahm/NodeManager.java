package com.itahm;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.snmp4j.mp.SnmpConstants;

import com.itahm.TopTable.Resource;
import com.itahm.command.Search;
import com.itahm.database.Data;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.node.Node;
import com.itahm.node.ICMPNode;
import com.itahm.node.NodeListener;
import com.itahm.node.TCPNode;
import com.itahm.util.Network;
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
	}

	public synchronized String detect(String ip, String profile) throws IOException {
		String id = isInUseIP(ip);
		
		// 생성
		if (id == null) {
			id = createNode(new JSONObject().put("ip", ip));
		}
		// else 수정
		
		NodeData data = this.map.get(id);
		
		data.json.put("monitor", new JSONObject()
			.put("protocol", "snmp")
			.put("status", true)
			.put("profile", profile));
		
		createMonitor(id, data);
		
		return id;
	}
	
	// User로 부터, 무조건 생성 | TCPNode 인 경우 별도 처리해야함
	public synchronized String create(JSONObject base) throws IOException {
		String id;
		
		if (base.has("ip")) {
			id = this.isInUseIP(base.getString("ip"));
			
			if (id != null) {
				return null;
			}
		}
		
		id = createNode(base);;
		
		if (base.has("type") && base.getString("type").equals("application")) {
			setMonitor(id, "tcp");
		}
		
		return id;
	}
	
	public String createNode(JSONObject base) throws IOException {
		String id = String.format("%s%d", PREFIX_NODE, ++this.nodeNum);
			
		base.put("id", id);
			
		Path
			nodeDir = nodeRoot.resolve(id),
			nodePath;
		NodeData data;
	
		Files.createDirectory(nodeDir);
		
		nodePath = nodeDir.resolve("node");
		
		Util.putJSONtoFile(nodePath, new JSONObject().put("base", base));
		
		data = new NodeData(nodePath, id);
			
		this.map.put(id, data);
		
		return id;
	}
	
	public void remove(String id) throws IOException {
		close(id);
		
		NodeData data = this.map.get(id);
		JSONObject
			node = data.json,
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
		
		switch (node.getJSONObject("base").getString("type")) {
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
		
		data.remove();
		
		this.map.remove(id);
	}
	
	public String isInUseIP(String ip) {
		JSONObject base;
		
		for (String id : this.map.keySet()) {
			base = this.map.get(id).json.getJSONObject("base");
			
			if (base.has("ip") && base.getString("ip").equals(ip)) {
				return id;
			}
		}
		
		return null;
	}
	
	public String getNodeName(String id) {
		NodeData data = this.map.get(id);
		
		if (data == null) {
			return id;
		}
		
		JSONObject base = data.json.getJSONObject("base");
		String name;
		
		for (String key : new String [] {"name", "ip"}) {
			if (base.has(key)) {
				name = base.getString(key);
				
				if (name.length() > 0) {
					return name;
				}
			}
		}
		
		return id;
	}
	
	public JSONObject get() {
		JSONObject
			dump = new JSONObject(),
			node;
		NodeData data;
		
		for (String id : this.map.keySet()) {
			data = this.map.get(id);
			
			node = new JSONObject();
			
			dump.put(id, node);
			
			node.put("base", data.json.getJSONObject("base"));
			
			if (data.json.has("monitor")) {
				node.put("monitor", data.json.getJSONObject("monitor"));
			}
		}
		
		return dump;
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
	
	public boolean setMonitor(String id, String protocol) throws IOException {
		NodeData data = this.map.get(id);
		
		if (data == null) {
			return false;
		}
		
		if (data.node != null) {
			data.node.close();
			
			data.node = null;
		}
		
		JSONObject base = data.json.getJSONObject("base");
		
		if (!base.has("ip")) {
			return false;
		}
		
		// 일단 실패했다고 가정하고 지워 놓는다.
		data.json.remove("monitor");
		data.save();
		
		if (protocol != null) {
			switch(protocol.toLowerCase()) {
			case "snmp":
				new Search().execute(new Network(base.getString("ip"), 32), id);
				
				break;
			case "icmp":
				new ICMPNode(this, id, base.getString("ip")).ping(0);
				
				break;
			case "tcp":
				new TCPNode(this, id, base.getString("ip")).ping(0);
				
				break;
			}
		}
		
		return true;
	}
	
	public void setCritical(String id, JSONObject critical) {
		NodeData data = this.map.get(id);
		
		if (data != null && data.node instanceof ITAhMNode) {
			((ITAhMNode)data.node).setCritical(critical);
		}
	}
	
	public void setUpDown(String id, JSONObject updown) throws IOException {
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
	
	public void save (String id, String key, JSONObject jsono) throws IOException {
		NodeData data = this.map.get(id);
		
		if (data == null) {
			return;
		}
		
		data.json.put(key, jsono);
		
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
	
	public void start() throws IOException {		
		NodeData data;
		String id;
	
		for (Path path : Files.newDirectoryStream(this.nodeRoot)) {
			if (!Files.isDirectory(path)) {
				continue;
			}
			
			id = path.getFileName().toString();
			
			data = new NodeData(path.resolve("node"), id);
			
			this.map.put(id, data);
			
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
	
	public void onCritical(Node node, boolean status) {
		NodeData data = this.map.get(node.id);
		
		if (data == null) {
			return;
		}
		
		JSONObject monitor = data.json.getJSONObject("monitor");
		
		monitor.put("critical", status);
	}
	
	/**
	 * Node로 부터 호출
	 */
	@Override
	public void onSuccess(Node node, long time) {
		NodeData data = this.map.get(node.id);
		
		if (data == null) {
			return;
		}
		
		String
			protocol =
				node instanceof ICMPNode? "ICMP":
				node instanceof TCPNode? "TCP": "",
			name = getNodeName(node.id);
		
		if (data.node == null) {
			data.json.put("monitor", new JSONObject()
				.put("protocol", protocol.toLowerCase())
				.put("status", true));
			
			try {
				data.save();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
			
			data.node = node;
			
			Agent.event().put(new JSONObject()
				.put("origin", "test")
				.put("id", node.id)
				.put("protocol", protocol.toLowerCase())
				.put("name", name)
				.put("status", true)
				.put("message", String.format("%s %s 등록 성공", name, protocol)), false);
			
			node.ping(0);
		}
		else {
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
					.put("protocol", protocol.toLowerCase())
					.put("name", name)
					.put("status", true)
					.put("message", String.format("%s %s 응답 정상.", name, protocol)), false);
			}
			
			node.ping(Agent.Setting.requestInterval());
		}
	}

	/**
	 * Node로 부터 호출
	 */
	@Override
	public void onFailure(Node node) {
		NodeData data = this.map.get(node.id);
		
		if (data == null) {
			return;
		}
		
		String
			protocol =
				node instanceof ICMPNode? "ICMP":
				node instanceof TCPNode? "TCP": "";
		
		if (data.node == null) {
			String name = getNodeName(node.id);
			
			Agent.event().put(new JSONObject()
				.put("origin", "test")
				.put("id", node.id)
				.put("protocol", protocol.toLowerCase())
				.put("name", name)
				.put("status", false)
				.put("message", String.format("%s %s 등록 실패", name, protocol)), false);
		}
		else {
			JSONObject monitor = data.json.getJSONObject("monitor");
			
			if (monitor.getBoolean("status")) {
				String name = getNodeName(node.id);
				
				monitor.put("status", false);
				
				try {
					data.save();
					
				} catch (IOException ioe) {
					System.err.print(ioe);
				}
				
				Agent.event().put(new JSONObject()
					.put("origin", "status")
					.put("id", node.id)
					.put("protocol", protocol.toLowerCase())
					.put("name", name)
					.put("status", false)
					.put("message", String.format("%s %s 응답 없음.", name, protocol)), false);
			}
			
			data.node.ping(0);
		}
	}

	
	public void stop() throws IOException {
		for (String id : this.map.keySet()) {
			close(id);
		}
	}

	private Node createMonitor(String id, NodeData data) throws IOException {
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
				
				switch(profile.getString("version")) {
				case "v3":
					node = new SNMPV3Node(this, id,
						base.getString("ip"),
						profile.getInt("udp"),
						profile.getString("user"),
						profile.getInt("level"));
					
					((SNMPV3Node)node).setUSM(profile);
					
					break;
				case "v2c":
					node = new SNMPDefaultNode(this, id,
						base.getString("ip"),
						profile.getInt("udp"),
						profile.getString("community"));
					
					break;
				default:
					node = new SNMPDefaultNode(this, id,
						base.getString("ip"),
						profile.getInt("udp"),
						profile.getString("community"),
						SnmpConstants.version1);
				}
				
				if (data.json.has("snmp")) {
					JSONObject 
						snmp = data.json.getJSONObject("snmp"),
						speed = new JSONObject(),
						updown = new JSONObject(),
						critical = new JSONObject(),
						entry, indexData;
					String
						index, resource;
					
					for (Object key1 : snmp.keySet()) {
						switch(resource = (String)key1) {
						case "hrProcessorEntry":
						case "hrStorageEntry":
						case "ifEntry":
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
				node = new ICMPNode(this, id, base.getString("ip"));
				
				break;
				
			case "tcp":
				node = new TCPNode(this, id, base.getString("ip"));
				
				break;
				
			default:
				node = null;
			}
		}
		catch (JSONException jsone) {
			throw new IOException(jsone);
		}
	
		if (node != null) {
			node.ping(0);
		}
		
		data.node = node;
		
		return node;
	}
	
	private class NodeData extends Data {

		private Node node;
		
		private NodeData(Path path, String id) throws IOException {
			super(path);
			
			if (super.json.has("monitor")) {
				this.node = createMonitor(id, this);
			}
		}
		
		private void remove() {
			final File dir = super.path.getParent().toFile();
			
			if (node != null) {
				node.close();
			}
			
			Thread thread = new Thread(new Runnable () {

				@Override
				public void run() {
					Util.deleteDirectory(dir);
				}
			});
			
			thread.setDaemon(true);
			thread.start();
		}
	}
	
}
