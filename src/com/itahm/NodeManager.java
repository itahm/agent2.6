package com.itahm;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OctetString;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import com.itahm.TopTable.Resource;
import com.itahm.database.Table;
import com.itahm.json.JSONArray;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.node.Node;
import com.itahm.node.ICMPNode;
import com.itahm.node.NodeListener;
import com.itahm.node.TCPNode;

public class NodeManager extends Snmp implements NodeListener {

	private static final String PREFIX_NODE = "node.";
	private Long nodeNum = -1L;
	private final Path snmp;
	private final Map<String, Node> map = new ConcurrentHashMap<>();
	private final Map<String, String> index = new HashMap<>();
	private final Table nodeTable;
	private final Table prfTable;
	private final Table lineTable;
	private final TopTable topTable = new TopTable();
	
	public NodeManager() throws NumberFormatException, JSONException, IOException {
		super(new DefaultUdpTransportMapping());
		
		Path dataRoot = Paths.get(Agent.Config.root().toURI());
		
		this.snmp = dataRoot.resolve("snmp");
		
		this.nodeTable = Agent.db().get("node");
		this.prfTable = Agent.db().get("profile");
		this.lineTable = Agent.db().get("line");
		
		Files.createDirectories(this.snmp);
		
		SecurityModels.getInstance()
			.addSecurityModel(new USM(SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0));
	}
	
	private void addIndex(JSONObject base) throws IOException {
		if (base != null && base.has("ip")) {
			String ip = base.getString("id");
			try {
				InetAddress.getByName(ip);
				
				this.index.put(base.getString("ip"), ip);
			}
			catch (UnknownHostException uhe) {} 
		}
	}
	
	private void removeIndex(JSONObject base) {
		if (base != null && base.has("ip")) {
			this.index.remove(base.getString("ip"));
		}
	}
	
	public void start() throws IOException {
		JSONObject node;
		String id;
	
		for (Object key : this.nodeTable.json().keySet()) {
			id = (String)key;
		
			node = this.nodeTable.json().getJSONObject(id);
		
			Files.createDirectories(this.snmp.resolve(id));
			
			addIndex(node);
			
			createMonitor(node); 
			
			try {
				this.nodeNum = Math.max(this.nodeNum, Long.valueOf(id.replace(PREFIX_NODE, "")));
			}
			catch(NumberFormatException nfe) {
			}
			
			System.out.print("!");
		}
		
		System.out.println();
		
		JSONObject profile;
		
		for (Object key : this.prfTable.json().keySet()) {
			profile = this.prfTable.json().getJSONObject((String)key);
			
			if (profile.getString("version").equals("v3")) {
				addUSMUser(profile);
			}
		}
		
		super.listen();
	}
	
	public void stop() throws IOException {
		System.out.println("Stop Node manager.");
		
		for (Iterator<String> it = this.map.keySet().iterator(); it.hasNext(); ) {
			this.map.get(it.next()).close();
			
			it.remove();
			
			System.out.print("-");
		}
		
		System.out.println();
		
		super.getUSM().removeAllUsers();
		
		super.close();
	}
	
	/**
	 * Search 성공 한것
	 * @param ip
	 * @param profile
	 * @return
	 * @throws IOException
	 */
	public void onSearch(String ip, String profile) throws IOException {
		synchronized(this.index) {
			String id;
			JSONObject base;
			
			if (this.index.containsKey(ip)) {
				id = this.index.get(ip);
				base = this.nodeTable.json().getJSONObject(id);
				
				if (base.has("protocol")) {
					switch(base.getString("protocol")) {
					case "snmp":
						
						return;
					case "icmp":
						removeNode(id);
					}
				}
				
				base
					.put("protocol", "snmp")
					.put("profile", profile)
					.put("status", true);
			}
			else {
				id = String.format("%s%d", PREFIX_NODE, ++this.nodeNum);
				base = new JSONObject()
					.put("id", id)
					.put("ip", ip)
					.put("protocol", "snmp")
					.put("profile", profile)
					.put("role", "node")
					.put("status", true);
				
				addIndex(base);
			}
			
			this.nodeTable.json().put(id, base);
			
			this.nodeTable.save();
			
			Node node = createSNMPNode(id, ip, this.prfTable.json().getJSONObject(profile));
			
			startNode(id, node);
			
			Agent.event().put(new JSONObject()
				.put("origin", "search")
				.put("id", id)
				.put("name", "System")
				.put("status", true)
				.put("protocol", "snmp")
				.put("message", String.format("%s SNMP 탐지 성공", ip)), false);
		}
	}
	
	public void onDetect(TempNode.Tester tester, boolean success) throws IOException {
		JSONObject
			base = this.nodeTable.json().getJSONObject(tester.id),
			event = new JSONObject()
				.put("origin", "test")
				.put("id", tester.id)
				.put("name", "System");
		String protocol = "";
		
		if (success) {
			Node node = null;
			
			if (tester instanceof TempNode.SNMP) {
				String profile = ((TempNode.SNMP)tester).profile;
				
				base
					.put("protocol", "snmp")
					.put("profile", profile)
					.put("status", true);
				
				this.nodeTable.save();
				
				node = createSNMPNode(tester.id, tester.ip, this.prfTable.json().getJSONObject(profile));
				
				event.put("protocol", protocol = "snmp");
			}
			else if (tester instanceof TempNode.ICMP) {
				base
					.put("protocol", "icmp")
					.put("status", true);
			
				this.nodeTable.save();
			
				node = new ICMPNode(this, tester.id, tester.ip);
				
				event.put("protocol", protocol = "icmp");
			}
			else if (tester instanceof TempNode.TCP) {
				base
					.put("protocol", "tcp")
					.put("status", true);
				
				this.nodeTable.save();
				
				node = new TCPNode(this, tester.id, tester.ip);
				
				event.put("protocol", protocol = "tcp");
			}
			
			if (node != null) {
				startNode(tester.id, node);
			}
			
			event
				.put("status", true)
				.put("message",
					String.format("%s %s 등록 성공.", Agent.node().getNodeName(tester.id), protocol.toUpperCase()));
		}
		else {
			if (tester instanceof TempNode.SNMP) {
				event.put("protocol", protocol = "snmp");
			}
			else if (tester instanceof TempNode.ICMP) {
				event.put("protocol", protocol = "icmp");
			}
			else if (tester instanceof TempNode.TCP) {
				event.put("protocol", protocol = "tcp");
			}
			
			event
				.put("status", false)
				.put("message",
					String.format("%s %s 등록 실패.", Agent.node().getNodeName(tester.id), protocol.toUpperCase()));
		}
		
		Agent.event().put(event, false);
	}
	
	// User로 부터, 무조건 생성 | TCPNode 인 경우 별도 처리해야함
	public boolean createBase(JSONObject base) throws IOException {
		synchronized(this.index) {
			if (base.has("ip") && this.index.containsKey(base.getString("ip"))) {
				return false;
			}
			
			String id = String.format("%s%d", PREFIX_NODE, ++this.nodeNum);
		
			this.nodeTable.json().put(id, base);
			
			base.put("id", id);
			
			this.nodeTable.save();
			
			if (base.has("ip") && base.has("role") && base.getString("role").equals("application")) {
				// 테스트
				new TempNode(id, base.getString("ip"), TempNode.Protocol.TCP);
			}
			
			addIndex(base);
			
			return true;
		}
	}
	
	public void modifyBase(String id, JSONObject base) throws IOException {
		JSONObject node = nodeTable.json().getJSONObject(id);
		String key;
		
		for (Object o : base.keySet()) {
			key = (String)o;
			
			node.put(key, base.get(key));
		}
		
		nodeTable.save();
	}
	
	public void removeBase(String id) throws IOException {
		if (!this.nodeTable.json().has(id)) {
			return;
		}
		
		removeNode(id);
		
		JSONObject base = (JSONObject)this.nodeTable.json().remove(id);
		
		removeIndex(base);
		
		nodeTable.json().remove(id);
		
		nodeTable.save();
		
		for (Object key : this.lineTable.json().keySet()) {
			if (((String)key).indexOf(id) != -1) {
				this.lineTable.json().remove((String)key);
			}
		}
		
		this.lineTable.save();
	}
	
	public void addUSMUser(JSONObject profile) {
		switch (profile.getInt("level")) {
		case SecurityLevel.AUTH_PRIV:
			super.getUSM().addUser(new UsmUser(new OctetString(profile.getString("user")),
				profile.has("sha")? AuthSHA.ID: AuthMD5.ID,
				new OctetString(profile.getString(profile.has("sha")? "sha": "md5")),
				PrivDES.ID,
				new OctetString(profile.getString("des"))));
			
			break;
		case SecurityLevel.AUTH_NOPRIV:
			super.getUSM().addUser(new UsmUser(new OctetString(profile.getString("user")),
				profile.has("sha")? AuthSHA.ID: AuthMD5.ID,
				new OctetString(profile.getString(profile.has("sha")? "sha": "md5")),
				null, null));
			
			break;
		default:
			super.getUSM().addUser(new UsmUser(new OctetString(profile.getString("user")),
				null, null, null, null));	
		}
	}
	
	public void removeUSMUser(String user) {
		super.getUSM().removeAllUsers(new OctetString(user));
	}
	
	public boolean isInUseProfile(String profile) {
		JSONObject base;
		String id;
		
		for (Object o : this.nodeTable.json().keySet()) {
			id = (String)o;
			
			base = this.nodeTable.json().getJSONObject(id);
			
			if (!base.has("profile")) {
				continue;
			}
			
			if (base.getString("profile").equals(profile)) {
				return true;
			}
		}
		
		return false;
	}
	
	public String getNodeName(String id) {
		JSONObject base = this.nodeTable.json().getJSONObject(id);
		
		if (base == null) {
			return id;
		}
		
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
	
	public JSONObject getSNMP(String id) throws IOException {
		Node node = this.map.get(id);
		
		if (node != null && node != null && node instanceof ITAhMNode) {
			return ((ITAhMNode)node).snmp();
		}
		
		return null;
	}
	
	public ITAhMNode getITAhMNode(String id) {
		if (this.map.containsKey(id)) {
			Node node  = this.map.get(id);
			
			if (node instanceof ITAhMNode) {
				return (ITAhMNode)node;
			}
		}
		
		return null;
	}
	
	public JSONObject getTraffic(JSONObject jsono) {
		JSONObject line;
		Pattern pattern = Pattern.compile("line\\.(\\d*)\\.(\\d*)");
		Matcher matcher;
		String id;
		ITAhMNode node;
		Object link;
		
		for (Object key : jsono.keySet()) {
			id = (String)key;
			
			matcher = pattern.matcher(id);
			
			if (!matcher.matches()) {
				continue;
			}
			
			line = jsono.getJSONObject(id);
			
			for (String nodeID : new String [] {"node."+ matcher.group(1), "node."+ matcher.group(2)}) {
			
				if (line.has(nodeID)) {
					node = getITAhMNode(nodeID);
					
					if (node != null) {
						link = line.get(nodeID);
						
						if (link instanceof JSONArray) {
							JSONArray jsona = (JSONArray)link;
							for (int i=0, _i=jsona.length(); i<_i; i++) {
								if (!jsona.isNull(i)) {
									node.getInterface(jsona.getJSONObject(i));
								}
							}
						}
						else if (link instanceof JSONObject) {
							node.getInterface((JSONObject)link);	
						}
					}
				}
			}
		}
		
		return jsono;
	}
	
	private Node createSNMPNode(String id, String ip, JSONObject profile) throws IOException {
		Node node;
		
		switch(profile.getString("version")) {
		case "v3":
			node = new SNMPV3Node(id, ip,
				profile.getInt("udp"),
				profile.getString("user"),
				profile.getInt("level"));
			
			break;
		case "v2c":
			node = new SNMPDefaultNode(id, ip,
				profile.getInt("udp"),
				profile.getString("community"));
			
			break;
		default:
			node = new SNMPDefaultNode(id, ip,
				profile.getInt("udp"),
				profile.getString("community"),
				SnmpConstants.version1);
		}
		
		return node;
	}
	
	private void startNode(String id, Node node) {
		this.map.put(id, node);
		
		node.setHealth(Agent.Config.timeout(), Agent.Config.retry());
		node.ping(0);
	}
	
	private void removeNode(String id) {
		Node node = this.map.remove(id);
		
		if (node != null) {
			node.close();
			
			removeTop(id);
		}
	}
	
	public boolean setMonitor(String id, String protocol) throws IOException {
		JSONObject base = this.nodeTable.json().has(id)?
			this.nodeTable.json().getJSONObject(id):
			null;
		
		if (base == null || !base.has("ip")) {
			return false;
		};
		
		removeNode(id);
		
		base.remove("protocol");
		base.remove("profile");
		
		if (protocol != null) {
			switch (protocol) {
			case "snmp":
				new TempNode(id, base.getString("ip"), TempNode.Protocol.SNMP);
				
				break;
			case "icmp":
				new TempNode(id, base.getString("ip"), TempNode.Protocol.ICMP);
				
				break;
			}
		}
		
		return true;
	}
	
	private void createMonitor(JSONObject base) throws IOException {
		if (!base.has("protocol") || !base.has("ip")) {
			return;
		}
		
		Node node = null;
		String id = base.getString("id");
		
		switch (base.getString("protocol")) {
		case "snmp":
			if (base.has("profile")) {
				JSONObject profile = this.prfTable.json().getJSONObject(base.getString("profile"));
				
				node = createSNMPNode(id, base.getString("ip"), profile);
			}
			
			break;
		case "icmp":
			node = new ICMPNode(this, id, base.getString("ip"));
			
			break;
		case "tcp":
			node = new TCPNode(this, id, base.getString("ip"));
			
			break;
		}
		
		if (node != null) {
			startNode(id, node);
		}
	}
	
	public void setCritical(String id, JSONObject critical) throws IOException {
		Node node = this.map.get(id);
		
		if (node != null && node instanceof ITAhMNode) {
			((ITAhMNode)node).setCritical(critical);
		}
	}
	
	public void setUpDown(String id, JSONObject updown) throws IOException {
		Node node = this.map.get(id);
		
		if (node != null && node instanceof ITAhMNode) {
			((ITAhMNode)node).setUpDown(updown);
		}
	}
	
	public void setSpeed(String id, JSONObject speed) throws IOException {
		Node node = this.map.get(id);
		
		if (node != null && node instanceof ITAhMNode) {
			((ITAhMNode)node).setSpeed(speed);
		}
	}
	/*
	public void save (String id, String key, JSONObject jsono) throws IOException {
		Base base = this.map.get(id);
		
		if (base == null) {
			return;
		}
		
		data.json.put(key, jsono);
		
		data.save();
	}
	*/
	
	public void submitTop(String id, Resource resource, TopTable.Value value) {
		this.topTable.submit(resource, id, value);
	}
	
	// snmp 응답이 없을때
	// 모니터가 snmp에서 변경될때
	// 노드 삭제시
	public void removeTop(String id) {
		this.topTable.remove(id);
	}
	
	public JSONObject getTop(JSONArray list) {
		return list == null?
			this.topTable.getTop(Agent.Config.top()):
			this.topTable.getTop(Agent.Config.top(), list);
		
	}
		
	public final long calcLoad() {
		Node node;
		BigInteger bi = BigInteger.valueOf(0);
		long size = 0;
		
		for (String id : this.map.keySet()) {
			node = this.map.get(id);
			
			if (node instanceof ITAhMNode) {
				bi = bi.add(BigInteger.valueOf(((ITAhMNode)node).getLoad()));
			}
			
			size++;
		}
		
		return size > 0? bi.divide(BigInteger.valueOf(size)).longValue(): 0;
	}
	
	public void setHealth(int timeout, int retry) {
		Node node;
		
		for (String id : this.map.keySet()) {
			node = this.map.get(id);
			
			if (node != null) {
				node.setHealth(timeout, retry);
			}
		}
	}
	
	public long getResourceCount() {
		Node node;
		long count = 0;
		
		for (String id : this.map.keySet()) {
			node = this.map.get(id);
		
			if (node != null && node instanceof ITAhMNode) {
				count += ((ITAhMNode)node).getResourceCount();
			}
		}
		
		return count;
	}
	
	public void onCritical(Node node, boolean status) throws IOException {
		if (!nodeTable.json().has(node.id)) {
			return;
		}
		
		nodeTable.json().getJSONObject(node.id).put("critical", status);
		
		nodeTable.save();
	}

	@Override
	public void onSuccess(Node node, long time) {
		if (!this.map.containsKey(node.id) || !nodeTable.json().has(node.id)) {
			return;
		}
		
		JSONObject base = this.nodeTable.json().getJSONObject(node.id);
		String
			protocol =
				node instanceof ICMPNode? "icmp":
				node instanceof TCPNode? "tcp": "",
			name = getNodeName(node.id);
		
		if (base.has("status") && !base.getBoolean("status")) {
			base.put("status", true);
			
			try {
				nodeTable.save();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
			
			Agent.event().put(new JSONObject()
				.put("origin", "status")
				.put("id", node.id)
				.put("protocol", protocol)
				.put("name", name)
				.put("status", true)
				.put("message", String.format("%s %s 응답 정상.", name, protocol.toUpperCase())), false);
		}
		
		node.ping(Agent.Config.snmpInterval());
	}

	@Override
	public void onFailure(Node node) {
		if (!this.map.containsKey(node.id) || !nodeTable.json().has(node.id)) {
			return;
		}
		
		JSONObject base = this.nodeTable.json().getJSONObject(node.id);
		String protocol =
				node instanceof ICMPNode? "ICMP":
				node instanceof TCPNode? "TCP": "";
		
		if (!base.has("status")) {
			base.put("status", false);
			
			try {
				Agent.db().get("node").save();
				
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
		}
		else if (base.getBoolean("status")) {
			String name = getNodeName(node.id);
			
			base.put("status", false);
			
			try {
				Agent.db().get("node").save();
				
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
		
		node.ping(0);
	}
	
}
