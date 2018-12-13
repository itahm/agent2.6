package com.itahm;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import com.itahm.TopTable.Resource;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

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
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import com.itahm.snmp.TmpNode;
import com.itahm.table.Device;
import com.itahm.table.Table;

public class SNMPAgent extends Snmp implements Closeable {
	
	private boolean isClosed = false;
	
	public final File nodeRoot;
	
	private final Map<String, SNMPNode> nodeList = new ConcurrentHashMap<String, SNMPNode>();
	private final Device deviceTable;
	private final Table monitorTable;
	private final Table profileTable;
	private final Table criticalTable;
	private final TopTable topTable = new TopTable();
	private final Timer timer;
	
	private long interval = 10000;
	private int rollingInterval = 1;
	private int timeout = 10000;
	private int retry = 1;
	
	private final int limit;
	
	public SNMPAgent(File root, int limit) throws IOException {
		super(new DefaultUdpTransportMapping());
		
		System.out.println("SNMP manager start.");
		
		this.limit = limit;
		
		deviceTable = (Device)Agent.getTable(Table.Name.DEVICE);
		
		monitorTable = Agent.getTable(Table.Name.MONITOR);
		
		profileTable = Agent.getTable(Table.Name.PROFILE);
		
		criticalTable = Agent.getTable(Table.Name.CRITICAL);
		
		timer = new Timer();
		 
		nodeRoot = new File(root, "node");
		nodeRoot.mkdir();
		
		initUSM();
		
		initNode();
	}
	
	private void initUSM() {
		JSONObject profileData = profileTable.getJSONObject();
		JSONObject profile;
		
		SecurityModels.getInstance().addSecurityModel(new USM(SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0));
		
		for (Object key : profileData.keySet()) {
			profile = profileData.getJSONObject((String)key);
			try {
				if ("v3".equals(profile.getString("version"))) {
					addUSM(profile);
				}
			}
			catch (JSONException jsone) {
				System.err.print(jsone);
			}
		}
	}
	
	private void initNode() throws IOException {
		JSONObject monitorData = this.monitorTable.getJSONObject();
		JSONObject monitor;
		String ip;
		
		for (Object key : monitorData.keySet()) {
			ip = (String)key;
			
			monitor = monitorData.getJSONObject(ip);
		
			if ("snmp".equals(monitor.getString("protocol"))) {
				addNode(ip, monitor.getString("profile"));
			}
		}
	}
	
	public void start() throws IOException {
		super.listen();
	}
	
	/**
	 * TestNode 에서 onSuccess 시 호출됨
	 * @param ip
	 * @param profileName
	 * @return
	 */
	public boolean  registerNode(String ip, String profileName) {
		if (this.limit > 0 && this.nodeList.size() >= this.limit) {
			Agent.log(new JSONObject().
				put("origin", "system").
				put("message", String.format("라이선스 수량 %d 을(를) 초과하였습니다.  %d", this.limit)), true);
			
			return false;
		}
		else {
			try {
				addNode(ip, profileName);
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
			
			return true;
		}
	}
	
	/**
	 * 초기화시 일괄등록하거나 registerNode가 호출되는 경우
	 * @param ip
	 * @param profileName
	 * @throws IOException
	 */
	private void addNode(String ip, String profileName) throws IOException {		
		final JSONObject profile = profileTable.getJSONObject(profileName);
		
		if (profile == null) {
			return ;
		}
		
		int version = SnmpConstants.version1;
		SNMPNode node;
		JSONObject device;
		
		try {
			switch(profile.getString("version")) {
			case "v3":
				node = new SNMPNode(this, ip, profile.getInt("udp"), SnmpConstants.version3,
					profile.getString("user"),
					(profile.has("md5") || profile.has("sha"))? (profile.has("des")) ?
							SecurityLevel.AUTH_PRIV: SecurityLevel.AUTH_NOPRIV : SecurityLevel.NOAUTH_NOPRIV);
				
				break;
			
			case "v2c":
				version = SnmpConstants.version2c;
				
			default:
				node = new SNMPNode(this, ip, profile.getInt("udp"), version,
					profile.getString("community"), -1);
			}
			
			device = deviceTable.getDevicebyIP(ip);
			if (device != null) {
				if (device.has("ifSpeed")) {
					node.setInterface(device.getJSONObject("ifSpeed"));
				}
			}
			
			node.setCritical(this.criticalTable.getJSONObject(ip));
			node.setHealth(timeout, retry);
			node.setRollingInterval(this.rollingInterval);
			
			this.nodeList.put(ip, node);
			
			node.request();
		}
		catch (JSONException jsone) {
			System.err.print(jsone);
		}
	}
	
	/**
	 * table.Profile 로부터 호출.
	 * @param profile
	 * @return
	 */
	public boolean addUSM(JSONObject profile) {
		String user = profile.getString("user");
		
		if (user.length() == 0) {
			return false;
		}
		
		String authentication = profile.has("md5")? "md5": profile.has("sha")? "sha": null;
		
		if (authentication == null) {
			return addUSM(new OctetString(user)
				, null, null, null, null);
		}
		else {
			String privacy = profile.has("des")? "des": null;
		
			if (privacy == null) {
				return addUSM(new OctetString(user)
					, "sha".equals(authentication)? AuthSHA.ID: AuthMD5.ID, new OctetString(profile.getString(authentication))
					, null, null);
			}
			
			return addUSM(new OctetString(user)
				, "sha".equals(authentication)? AuthSHA.ID: AuthMD5.ID, new OctetString(profile.getString(authentication))
				, PrivDES.ID, new OctetString(profile.getString(privacy)));
		}
	}
	
	private boolean addUSM(OctetString user, OID authProtocol, OctetString authPassphrase, OID privProtocol, OctetString privPassphrase) {		
		if (super.getUSM().getUserTable().getUser(user) != null) {
			
			return false;
		}
		
		super.getUSM().addUser(new UsmUser(user, authProtocol, authPassphrase, privProtocol, privPassphrase));
		
		return true;
	}
	
	public void removeUSM(String user) {
		super.getUSM().removeAllUsers(new OctetString(user));
	}
	
	public boolean isIdleProfile(String name) {
		JSONObject monitor;
		try {
			for (Object key : this.monitorTable.getJSONObject().keySet()) {
				monitor = this.monitorTable.getJSONObject((String)key);
				
				if (monitor.has("profile") && monitor.getString("profile").equals(name)) {
					return false;
				}
			}
		}
		catch (JSONException jsone) {
			System.err.print(jsone);
			
			return false;
		}
		
		return true;
	}

	public boolean removeNode(String ip) {
		if (this.nodeList.remove(ip) == null) {
			return false;
		}
		
		this.topTable.remove(ip);
		
		return true;
	}
	
	public void resetResponse(String ip) {
		SNMPNode node = this.nodeList.get(ip);
		
		if (node == null) {
			return;
		}
		
		node.resetResponse();
	}
	
	/**
	 * User가 명시적으로 설정
	 * @param ip
	 * @param critical
	 * @throws IOException 
	 */
	public void setCritical(String ip, JSONObject critical) throws IOException {
		SNMPNode node = this.nodeList.get(ip);
		
		if (node == null) {
			return;
		}
		
		JSONObject monitor = this.monitorTable.getJSONObject(ip);
		
		if (monitor == null) {
			return;
		}
		
		monitor.put("critical", false);
		
		this.monitorTable.save();
		
		node.setCritical(critical);
	}
	
	public void setInterval(long interval) {
		this.interval = interval;
	}
	
	public void setRollingInterval(int interval) {
		for (String ip : this.nodeList.keySet()) {
			this.nodeList.get(ip).setRollingInterval(interval);
		}
	}
	
	public void setHealth(int timeout, int retry) {
		this.timeout = timeout;
		this.retry = retry;
		
		for (String ip : this.nodeList.keySet()) {
			this.nodeList.get(ip).setHealth(timeout, retry);
		}
	}
	
	/**
	 * @param critical
	 * @param index
	 * @param rate
	 * @param description
	 * @param overwrite
	 */
	private void parseCritical(JSONObject critical, String index, int rate, String description, boolean overwrite) {
		if (critical.has(index) && !overwrite) {
			return;
		}
		
		JSONObject value = new JSONObject();
		
		value.put("limit", rate);
		
		if (description != null) {
			value.put("description", description);
		}
		
		critical.put(index, value);
	}
	
	/**
	 * @param data
	 * @param critical
	 * @param resource
	 * @param rate
	 * @param overwrite
	 */
	private void parseResourceCritical(JSONObject data, JSONObject critical, String resource, int rate, boolean overwrite) {
		JSONObject rCritical = critical.has(resource)? critical.getJSONObject(resource): new JSONObject();
		
		switch(resource) {
		case "processor":
			if (data.has("hrProcessorEntry")) {
				parseCritical(rCritical, "0", rate, null, overwrite);
			}
			
			break;
		case "memory":				
			if (data.has("hrStorageEntry")) {
				JSONObject entry = data.getJSONObject("hrStorageEntry"),
					strgData;
				
				for (Object index: entry.keySet()) {
					strgData = entry.getJSONObject((String)index);
					
					if (!strgData.has("hrStorageType") || strgData.getInt("hrStorageType") != 2) {
						continue;
					}
					
					parseCritical(rCritical, (String)index, rate, 
						strgData.has("hrStorageDescr")? strgData.getString("hrStorageDescr"): null, overwrite);
				}
			}
			
			break;
		case "storage":
			if (data.has("hrStorageEntry")) {
				JSONObject entry = data.getJSONObject("hrStorageEntry"),
					strgData;
				
				for (Object index: entry.keySet()) {
					strgData = entry.getJSONObject((String)index);
					
					if (!strgData.has("hrStorageType") || strgData.getInt("hrStorageType") != 4) {
						continue;
					}
					
					parseCritical(rCritical, (String)index, rate, 
						strgData.has("hrStorageDescr")? strgData.getString("hrStorageDescr"): null, overwrite);
				}
			}
			
			break;
		case "throughput":
			if (data.has("ifEntry")) {
				JSONObject entry = data.getJSONObject("ifEntry"),
					ifData;
				
				for (Object index: entry.keySet()) {
					ifData = entry.getJSONObject((String)index);
					
					parseCritical(rCritical, (String)index, rate, 
						ifData.has("ifName")? ifData.getString("ifName"):
						ifData.has("ifAlias")? ifData.getString("ifAlias"):null, overwrite);
				}
			}
			
			break;
		}
		
		if (rCritical.keySet().size() > 0) {
			critical.put(resource, rCritical);
		}
	}
	
	/**
	 * 
	 * @param criticalData 전체 critical 테이블 데이터
	 * @param ip
	 * @param resource
	 * @param rate 0 이면 삭제
	 * @param overwrite
	 */
	private void setNodeCritical(JSONObject criticalData, String ip, String resource, int rate, boolean overwrite) {
		SNMPNode node = this.nodeList.get(ip);
		JSONObject critical;
		
		if (rate == 0) { // 삭제
			if (resource == null) {
				criticalData.remove(ip);
				
				node.setCritical(null);
			}
			else if (criticalData.has(ip)){
				critical = criticalData.getJSONObject(ip);
				
				critical.remove(resource);
				
				if (critical.keySet().size() == 0) {
					criticalData.remove(ip);
					
					node.setCritical(null);
				}
				else {
					node.setCritical(critical);
				}
			}
		}
		else { // 수정
			if (criticalData.has(ip)) {
				critical = criticalData.getJSONObject(ip);
			}
			else {
				criticalData.put(ip, critical = new JSONObject());
			}
			
			final JSONObject data = node.getData();
			
			if (resource == null) {
				parseResourceCritical(data, critical, "processor", rate, overwrite);
				parseResourceCritical(data, critical, "memory", rate, overwrite);
				parseResourceCritical(data, critical, "storage", rate, overwrite);
				parseResourceCritical(data, critical, "throughput", rate, overwrite);
			}
			else {
				parseResourceCritical(data, critical, resource, rate, overwrite);
			}
			
			node.setCritical(critical.keySet().size() > 0? critical: null);
		}
	}
	
	/**
	 * Global.setCritical
	 * @param target
	 * @param resource
	 * @param rate
	 * @param overwrite
	 * @throws IOException
	 */
	public void setCritical(String ip, String resource, int rate, boolean overwrite) throws IOException {
		Table criticalTable = Agent.getTable(Table.Name.CRITICAL),
			monitorTable = Agent.getTable(Table.Name.MONITOR);
		JSONObject criticalData = criticalTable.getJSONObject(),
			monitorData = monitorTable.getJSONObject();
		
		if (ip == null) {
			for (String key : this.nodeList.keySet()) {
				setNodeCritical(criticalData, key, resource, rate, overwrite);
				
				monitorData.getJSONObject(key).put("critical", false);
			}
		}
		else if (this.nodeList.get(ip) != null) {
			setNodeCritical(criticalData, ip, resource, rate, overwrite);
			
			monitorData.getJSONObject(ip).put("critical", false);
		}
		
		criticalTable.save();
		monitorTable.save();
	}
	
	/**
	 * 
	 * @param ip
	 * @param id search로부터 호출되면 null
	 */
	public void testNode(final String ip, String id) {
		if (this.nodeList.containsKey(ip)) {
			return;
		}
		
		final JSONObject profileData = this.profileTable.getJSONObject();
		JSONObject profile;
		
		TmpNode node = new TestNode(this, ip, id);
		
		for (Object name : profileData.keySet()) {
			profile = profileData.getJSONObject((String)name);
			
			try {
				switch(profile.getString("version")) {
				case "v3":
					node.addV3Profile((String)name, profile.getInt("udp"), new OctetString(profile.getString("user"))
							, (profile.has("md5") || profile.has("sha"))? (profile.has("des")) ? SecurityLevel.AUTH_PRIV: SecurityLevel.AUTH_NOPRIV : SecurityLevel.NOAUTH_NOPRIV);
					break;
				case "v2c":
					node.addProfile((String)name, profile.getInt("udp"), new OctetString(profile.getString("community")), SnmpConstants.version2c);
					
					break;
				case "v1":
					node.addProfile((String)name, profile.getInt("udp"), new OctetString(profile.getString("community")), SnmpConstants.version1);
					
					break;
				}
			} catch (UnknownHostException | JSONException e) {
				System.err.print(e);
			}
		}
		
		node.test();
	}
	
	public SNMPNode getNode(String ip) {
		return this.nodeList.get(ip);
	}
	
	public JSONObject getNodeData(String ip) {
		SNMPNode node = this.nodeList.get(ip);
		
		return node == null? null: node.getData();
	}
	
	public void setMonitor(String ip, JSONObject monitor) {
		SNMPNode node = this.nodeList.get(ip);
		
		if (node == null) {
			return;
		}
		
		node.setMonitor(monitor);
	}
	
	public JSONObject getNodeData(String ip, boolean offline) {
		return getNodeData(ip);
	}
	
	public JSONObject getTop(int count) {
		return this.topTable.getTop(count);
	}
	
	public JSONObject getFailureRate(String ip) {
		SNMPNode node = this.nodeList.get(ip);
		
		if (node == null) {
			return null;
		}
		
		JSONObject json = new JSONObject().put("failure", node.getFailureRate());
		
		return json;
	}
	
	public void onResponse(String ip, boolean success) {
		SNMPNode node = this.nodeList.get(ip);

		if (node == null) {
			return;
		}
		
		if (success) {
			node.save();
			
			sendNextRequest(node);
		}
		else {
			sendRequest(node);
		}
	}
	
	public void onException(String ip) {
		SNMPNode node = this.nodeList.get(ip);

		if (node == null) {
			return;
		}
		
		sendNextRequest(node);
	}
	
	/**
	 * @param ip
	 * @param success ICMP 성공 or 실패
	 */
	public void onTimeout(String ip, boolean success) {
		SNMPNode node = this.nodeList.get(ip);
		
		// 그 사이 삭제되었으면
		if (node == null) {
			return;
		}
		
		JSONObject monitor = this.monitorTable.getJSONObject(ip);
		
		if (monitor == null) {
			return;
		}
		
		if (success) {
			if (monitor.getBoolean("shutdown")) {
				monitor.put("shutdown", false);
				
				try {
					this.monitorTable.save();
				} catch (IOException ioe) {
					System.err.print(ioe);
				}
				
				Agent.log(new JSONObject()
					.put("origin", "shutdown")
					.put("ip", ip)
					.put("shutdown", false)
					.put("protocol", "snmp")
					.put("message", String.format("%s SNMP 응답 정상", ip)), true);
			}
		}
		else {
			if (!monitor.getBoolean("shutdown")) {
				monitor.put("shutdown", true);
				
				try {
					this.monitorTable.save();
				} catch (IOException ioe) {
					System.err.print(ioe);
				}
				
				Agent.log(new JSONObject()
					.put("origin", "shutdown")
					.put("ip", ip)
					.put("shutdown", true)
					.put("protocol", "snmp")
					.put("message", String.format("%s SNMP 응답 없음", ip)), true);
			}
			
			sendRequest(node);
		}
	}
	
	public void onCritical(String ip, String resource, String index, boolean isCritical, long rate, String description) {
		SNMPNode node = this.nodeList.get(ip);
		
		if (node == null) {
			return;
		}
		
		JSONObject critical = this.criticalTable.getJSONObject(ip);
		
		if (critical == null || !critical.has(resource)) {
			return;
		}
		
		JSONObject value = critical.getJSONObject(resource);
		
		if (!value.has(index)) {
			return;
		}
		
		value = value.getJSONObject(index);
		
		value.put("critical", isCritical);
		
		boolean b = false;
		
		if (isCritical) {
			b = true;
		}
		else {
			// TODO 여기에서 sync 안해도 되는지?
			loop: for (Object key : critical.keySet()) {
				value = critical.getJSONObject((String)key);
				for (Object key2 : value.keySet()) {
					if (value.getJSONObject((String)key2).getBoolean("critical")) {
						b = true;
						
						break loop;
					}
				}
			}
		}
		
		JSONObject monitor = this.monitorTable.getJSONObject(ip);
		
		if (monitor.getBoolean("critical") != b) {
			monitor.put("critical", b);
			
			try {
				this.monitorTable.save();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
		}
		
		try {
			this.criticalTable.save();
		} catch (IOException ioe) {
			System.err.print(ioe);
		}
		
		Agent.log(new JSONObject()
			.put("origin", "critical")
			.put("ip", ip)
			.put("resource", resource)
			.put("rIndex", index) // event의 index가 자동생성되므로 "index" 는 쓰면 안됨
			.put("critical", isCritical)
			.put("rate", rate)
			.put("message", String.format("%s [%s]%s %s 임계 %s",
					ip, resource, description == null? "": (" "+ description),
					rate > -1? String.format("%d%%", rate): "설정해제",
					isCritical? "초과": "정상")), true);
	
	}
	
	public void onSubmitTop(String ip, Resource resource, TopTable.Value value) {
		if (!this.nodeList.containsKey(ip)) {
			return;
		}
		
		this.topTable.submit(resource, ip, value);
	}
	
	private void sendNextRequest(final SNMPNode node) {
		if (this.isClosed) {
			return;
		}
		
		this.timer.schedule(
			new TimerTask() {

				@Override
				public void run() {
					sendRequest(node);
				}
				
			}, this.interval);
	}
	
	private final void sendRequest(SNMPNode node) {
		try {
			node.request();
		} catch (IOException ioe) {
			System.err.print(ioe);
			
			sendNextRequest(node);
		}
	}
	
	public final long calcLoad() {
		BigInteger bi = BigInteger.valueOf(0);
		long size = 0;
		
		for (String ip : this.nodeList.keySet()) {
			bi = bi.add(BigInteger.valueOf(this.nodeList.get(ip).getLoad()));
			
			size++;
		}
		
		return size > 0? bi.divide(BigInteger.valueOf(size)).longValue(): 0;
	}
	
	public final JSONObject test() {
		JSONObject jsono = new JSONObject();
		
		for (String ip : this.nodeList.keySet()) {
			jsono.put(ip,this.nodeList.get(ip).test());
		}
		
		return jsono;
	}
	public long getResourceCount() {
		long count = 0;
		
		for (String ip : this.nodeList.keySet()) {
			count += this.nodeList.get(ip).getResourceCount();
		}
		
		return count;
	}
	
	@Override
	public void close() {
		this.isClosed = true;
		
		try {
			super.close();
		} catch (IOException ioe) {
			System.err.print(ioe);
		}
		
		for (SNMPNode node: this.nodeList.values()) {
			try {
				node.close();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
		}
		
		this.timer.cancel();
		
		System.out.format("SNMP manager stop.\n");
	}
	
}
