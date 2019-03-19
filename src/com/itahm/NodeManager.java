package com.itahm;

import java.io.IOException;
import java.math.BigInteger;
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
	private final Table posTable;
	private final Table prfTable;
	private final Table lineTable;
	private final TopTable topTable = new TopTable();
	
	public NodeManager() throws NumberFormatException, JSONException, IOException {
		super(new DefaultUdpTransportMapping());
		
		Path dataRoot = Paths.get(Agent.Config.root().toURI());
		
		this.snmp = dataRoot.resolve("snmp");
		
		this.nodeTable = Agent.db().get("node");
		this.posTable = Agent.db().get("position");
		this.prfTable = Agent.db().get("profile");
		this.lineTable = Agent.db().get("line");
		
		Files.createDirectories(this.snmp);
		
		SecurityModels.getInstance()
			.addSecurityModel(new USM(SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0));
	}
	
	private void addIndex(JSONObject base) throws IOException {
		if (base != null && base.has("ip")) {
			this.index.put(base.getString("ip"), base.getString("id"));
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
	public String onSearch(String ip, String profile) throws IOException {
		synchronized(this.index) {
			if (this.index.containsKey(ip)) {
				return null;
			}
		
			String id = String.format("%s%d", PREFIX_NODE, ++this.nodeNum);
			JSONObject base = new JSONObject()
				.put("id", id)
				.put("ip", ip)
				.put("protocol", "snmp")
				.put("profile", profile)
				.put("status", true);
			
			addIndex(base);
			
			this.nodeTable.json().put(id, base);
			
			this.nodeTable.save();
		
			Node node = createSNMPNode(id, ip, this.prfTable.json().getJSONObject(profile));
			
			this.map.put(id, node);
			
			node.ping(0);
			
			return id;
		}
	}
	
	public void onDetect(String ip, String profile) throws IOException {
		String id = this.index.get(ip);
		JSONObject base = this.nodeTable.json().getJSONObject(id);
		
		base
			.put("protocol", "snmp")
			.put("profile", profile)
			.put("status", true);
		
		this.nodeTable.save();
		
		Node node = createSNMPNode(id, ip, this.prfTable.json().getJSONObject(profile));
		
		this.map.put(id, node);
		
		node.ping(0);
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
			
			if (base.has("ip") && base.has("type") && base.getString("type").equals("application")) {
				// 테스트
				new TCPNode(this, id, base.getString("ip")).ping(0);
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
		
		if (base.has("type") && base.getString("type").equals("group")) {
			JSONObject
				pos = this.posTable.json().getJSONObject(id),
				child;
			String parent = null;
			
			if (pos.has("parent")) {
				parent = pos.getString("parent");
			}
			
			for (Object key : this.posTable.json().keySet()) {
				child = this.posTable.json().getJSONObject((String)key);
				
				if (child.has("parent") && id.equals(child.getString("parent"))) {
					if (parent != null) {
						child.put("parent", parent);
					}
					else {
						child.remove("parent");
					}
				}
			}
		}
		
		this.posTable.json().remove(id);
		
		this.posTable.save();
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
		JSONObject indexData;
		Pattern pattern = Pattern.compile("line\\.(\\d*)\\.(\\d*)");
		Matcher matcher;
		String id;
		ITAhMNode node;
		
		for (Object key : jsono.keySet()) {
			id = (String)key;
			
			matcher = pattern.matcher(id);
			
			if (!matcher.matches()) {
				continue;
			}
			
			indexData = jsono.getJSONObject(id);
			
			id = "node."+ matcher.group(1);
			
			if (indexData.has(id)) {
				node = getITAhMNode(id);
				
				if (node != null) {
					node.getInterface(indexData.getJSONObject(id));
				}
			}
			
			id = "node."+ matcher.group(2);
			
			if (indexData.has(id)) {
				node = getITAhMNode(id);
				
				if (node != null) {
					node.getInterface(indexData.getJSONObject(id));
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
				new TempNode(id, base.getString("ip"));
				
				break;
			case "icmp":
				new ICMPNode(this, id, base.getString("ip")).ping(0);
				
				break;
			}
		
		}
		
		return true;
	}
	
	public void createMonitor(JSONObject base) throws IOException {
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
			node.ping(0);
			
			this.map.put(id, node);
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
		if (!nodeTable.json().has(node.id)) {
			return;
		}
		
		JSONObject base = this.nodeTable.json().getJSONObject(node.id);
		String
			protocol =
				node instanceof ICMPNode? "icmp":
				node instanceof TCPNode? "tcp": "",
			name = getNodeName(node.id);
		long delay = 0;
		
		if (!this.map.containsKey(node.id)) {
			this.map.put(node.id, node);
			
			base
				.put("protocol", protocol)
				.put("status", true);
			
			try {
				nodeTable.save();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
			
			Agent.event().put(new JSONObject()
				.put("origin", "test")
				.put("id", node.id)
				.put("protocol", protocol)
				.put("name", name)
				.put("status", true)
				.put("message", String.format("%s %s 등록 성공", name, protocol.toUpperCase())), false);
		}
		else {
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
			
			delay = Agent.Config.snmpInterval();
		}
		
		node.ping(delay);
	}

	/**
	 * Node로 부터 호출
	 */
	@Override
	public void onFailure(Node node) {
		if (!this.nodeTable.json().has(node.id)) {
			return;
		}
		
		JSONObject base = this.nodeTable.json().getJSONObject(node.id);
		String protocol =
				node instanceof ICMPNode? "ICMP":
				node instanceof TCPNode? "TCP": "";
		
		if (!this.map.containsKey(node.id)) {
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
	
}
