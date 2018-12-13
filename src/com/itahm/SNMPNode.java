package com.itahm;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.snmp4j.PDU;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import com.itahm.enterprise.Enterprise;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.json.RollingFile;
import com.itahm.snmp.Node;
import com.itahm.snmp.RequestOID;
import com.itahm.util.Util;

public class SNMPNode extends Node {
	
	private final static OID OID_TRAP = new OID(new int [] {1,3,6,1,6,3,1,1,5});
	private final static OID OID_LINKDOWN = new OID(new int [] {1,3,6,1,6,3,1,1,5,3});
	private final static OID OID_LINKUP = new OID(new int [] {1,3,6,1,6,3,1,1,5,4});
	
	public enum Rolling {
		HRPROCESSORLOAD("hrProcessorLoad"),
		IFINOCTETS("ifInOctets"),
		IFOUTOCTETS("ifOutOctets"),
		IFINERRORS("ifInErrors"),
		IFOUTERRORS("ifOutErrors"),
		HRSTORAGEUSED("hrStorageUsed"),
		RESPONSETIME("responseTime");
		
		private String database;
		
		private Rolling(String database) {
			this.database = database;
		}
		
		public String toString() {
			return this.database;
		}
	}
	
	private File nodeRoot;
	private final Map<Rolling, HashMap<String, RollingFile>> rollingMap = new HashMap<Rolling, HashMap<String, RollingFile>>();
	private final String ip;
	private final SNMPAgent agent;
	private final Critical critical;
	private JSONObject ifSpeed = new JSONObject();
	private JSONObject monitor = new JSONObject();
	private int rollingInterval = 1; // 단위: 분 (s, ms 아님!)
	
	public SNMPNode(SNMPAgent agent, String ip, int udp, int version, String name, int level) throws IOException {
		super(agent, ip, udp);
		
		if (version == SnmpConstants.version3) {
			super.setUser(new OctetString(name), level);
		}
		else {
			super.setCommunity(new OctetString(name), version);
		}
		
		this.agent = agent;
		this.ip = ip;
		this.nodeRoot = new File(agent.nodeRoot, this.ip);
		this.nodeRoot.mkdirs();
		
		for (Rolling database : Rolling.values()) {
			rollingMap.put(database, new HashMap<String, RollingFile>());
			
			new File(nodeRoot, database.toString()).mkdir();
		}
		
		load();
		
		this.critical = new Critical() {
			@Override
			public void onCritical(boolean isCritical, String resource, String index, long rate, String description) {
				agent.onCritical(ip, resource, index, isCritical, rate, description);
			}};
		
		setRequestOID(super.pdu);
	}
	
	public void setCritical(JSONObject critical) {
		this.critical.set(critical);
	}
	
	public void setInterface(JSONObject ifSpeed) {
		this.ifSpeed = ifSpeed;
	}
	
	public void setRollingInterval(int interval) {
		this.rollingInterval = interval;
	}
	
	public void setMonitor(JSONObject monitor) {
		this.monitor = monitor;
		
		save();
	}
	
	private void putData(Rolling database, String index, long value) throws IOException {
		Map<String, RollingFile> rollingMap = this.rollingMap.get(database);
		RollingFile rollingFile = rollingMap.get(index);
		
		if (rollingFile == null) {
			rollingMap.put(index, rollingFile = new RollingFile(new File(this.nodeRoot, database.toString()), index));
		}
		
		rollingFile.roll(value, this.rollingInterval);
	}
	
	private void parseResponseTime() throws IOException {
		this.putData(Rolling.RESPONSETIME, "0", super.responseTime);
		
		this.agent.onSubmitTop(this.ip, TopTable.Resource.RESPONSETIME, new TopTable.Value(responseTime, -1, "0"));
	}
	
	private void parseProcessor() throws IOException {
		TopTable.Value max = null;
		long value;
		
		for(String index: super.hrProcessorEntry.keySet()) {
			value = super.hrProcessorEntry.get(index);
			
			this.putData(Rolling.HRPROCESSORLOAD, index, value);
			
			if (this.critical != null) {
				this.critical.analyze(Critical.Resource.PROCESSOR, index, 100, value);
			}
			
			if (max == null || max.getValue() < value) {
				max = new TopTable.Value(value, value, index);
			}
		}
		
		if (max != null) {
			this.agent.onSubmitTop(this.ip, TopTable.Resource.PROCESSOR, max);
		}
	}
	
	private void parseStorage() throws IOException {
		JSONObject storage;
		TopTable.Value
			max = null,
			maxRate = null;
		long value,
			capacity,
			tmpValue;
		int type;
		boolean modified = false;
		Set<String> entry = null;
		
		
		if (super.data.has("hrStorageEntry")) {
			entry = new HashSet<>();
			
			for (Object index : super.data.getJSONObject("hrStorageEntry").keySet()) {
				entry.add((String)index);
			}
		}
		
		//TODO cme 발생
		for(String index: super.hrStorageEntry.keySet()) {
			storage = super.hrStorageEntry.get(index);
			
			if (entry != null) {
				if (entry.contains(index)) {
					entry.remove(index);
				}
				else {
					modified = true;
				}
			}
			
			try {
				capacity = storage.getInt("hrStorageSize");
				
				if (capacity <= 0) {
					continue;
				}
				
				tmpValue = storage.getInt("hrStorageUsed");
				value = 1L* tmpValue * storage.getInt("hrStorageAllocationUnits");
				type = storage.getInt("hrStorageType");
			} catch (JSONException jsone) {
				continue;
			}
			
			this.putData(Rolling.HRSTORAGEUSED, index, value);
			
			switch(type) {
			case 2:
				// 물리적 memory는하나뿐이므로 한번에 끝나고 
				if (this.critical != null) {
					this.critical.analyze(Critical.Resource.MEMORY, index, capacity, tmpValue);
				}
				
				this.agent.onSubmitTop(this.ip, TopTable.Resource.MEMORY, new TopTable.Value(value, tmpValue *100 / capacity, index));
				this.agent.onSubmitTop(this.ip, TopTable.Resource.MEMORYRATE, new TopTable.Value(value, tmpValue *100 / capacity, index));
				
				break;
			//case 5:
			case 4:
				// 스토리지는 여러 볼륨중 가장 높은값을 submit
				if (this.critical != null) {
					this.critical.analyze(Critical.Resource.STORAGE, index, capacity, tmpValue);
				}
				
				if (max == null || max.getValue() < value) {
					max = new TopTable.Value(value, tmpValue *100L / capacity, index);
				}
				
				if (maxRate == null || maxRate.getRate() < (tmpValue *100L / capacity)) {
					maxRate = new TopTable.Value(value, tmpValue *100L / capacity, index);
				}
				
				break;
				
			//default:
			}
		}
		
		if (entry != null && entry.size() > 0) {
			modified = true;
		}
		
		if (modified) {
			Agent.log(new JSONObject()
				.put("origin", "warning")
				.put("ip", this.ip)
				.put("message", String.format("%s 저장소 상태 변화 감지", this.ip))
				, false);
		}
		
		if (max != null) {
			this.agent.onSubmitTop(this.ip, TopTable.Resource.STORAGE, max);
		}
		
		if (maxRate != null) {
			this.agent.onSubmitTop(this.ip, TopTable.Resource.STORAGERATE, maxRate);
		}
	}
	
	private void parseInterface() throws IOException {
		JSONObject
			lastEntry = super.data.has("ifEntry")? super.data.getJSONObject("ifEntry"): null,
			data, lastData;
		
		if (lastEntry == null) {
			return;
		}
		
		long 
			iValue, oValue,
			rate,
			capacity,
			duration,
			status;
		TopTable.Value
			max = null,
			maxRate = null,
			maxErr = null;
		
		for(String index: super.ifEntry.keySet()) {
			// 특정 index가 새로 생성되었다면 보관된 값이 없을수도 있음.
			if (!lastEntry.has(index)) {
				continue;
			}
			
			lastData = lastEntry.getJSONObject(index);
			data = super.ifEntry.get(index);
			capacity = 0;
			
			// ifAdminStatus를 제공하지 않거나 비활성 인터페이스는 파싱하지 않는다.
			// administratively 상태가 변하는 것은 체크하지 않는다.
			if (!data.has("ifAdminStatus") || data.getInt("ifAdminStatus") != 1) {
				continue;
			}
			
			// ifOperStatus를 제공하지 않는 인터페이스는 파싱하지 않는다.
			if (!data.has("ifOperStatus")) {
				continue;
			}
			
			status = data.getInt("ifOperStatus");
			
			if (lastData.has("ifOperStatus")) {
				if (lastData.getInt("ifOperStatus") != status
					&& this.monitor.has(index)
					&& this.monitor.getBoolean(index)) {
					Agent.log(new JSONObject()
						.put("origin", "monitor")
						.put("ip", this.ip)
						.put("monitor", status == 1? true: false)
						.put("message"
							, String.format("%s interface %s %s"
								, this.ip
								, data.has("ifName")? data.getString("ifName"): index
								, status==1? "up": "down"
							)
						), true
					);
				}
			}
			
			// 연결이 없는 인터페이스는 파싱하지 않는다.
			if (status != 1) {
				continue;
			}
			
			//custom speed가 있는 경우
			if (this.ifSpeed.has(index)) {
				capacity = this.ifSpeed.getLong(index);
			}
			else if (data.has("ifHighSpeed")) {
				capacity = data.getLong("ifHighSpeed");
			}
			else if (data.has("ifSpeed")) {
				capacity = data.getLong("ifSpeed");
			}
			
			if (capacity <= 0) {
				continue;
			}
			
			if (!data.has("timestamp") || !lastData.has("timestamp")) {
				continue;
			}
				
			duration = data.getLong("timestamp") - lastData.getLong("timestamp");
			
			if (duration <= 0) {
				continue;
			}
				
			if (data.has("ifInErrors") && lastData.has("ifInErrors")) {
				long value = data.getInt("ifInErrors") - lastData.getInt("ifInErrors");
				
				data.put("ifInErrors", value);
				
				this.putData(Rolling.IFINERRORS, index, value);
				
				if (maxErr == null || maxErr.getValue() < value) {
					maxErr = new TopTable.Value(value, -1, index);
				}
			}
			
			if (data.has("ifOutErrors") && lastData.has("ifOutErrors")) {
				long value = data.getInt("ifOutErrors") - lastData.getInt("ifOutErrors");
				
				data.put("ifOutErrors", value);
				
				this.putData(Rolling.IFOUTERRORS, index, value);
				
				if (maxErr == null || maxErr.getValue() < value) {
					maxErr = new TopTable.Value(value, -1, index);
				}
			}
			
			iValue = -1;
			
			if (data.has("ifHCInOctets") && lastData.has("ifHCInOctets")) {
				iValue = data.getLong("ifHCInOctets") - lastData.getLong("ifHCInOctets");
			}
			
			if (data.has("ifInOctets") && lastData.has("ifInOctets")) {
				iValue = Math.max(iValue, data.getLong("ifInOctets") - lastData.getLong("ifInOctets"));
			}
			
			if (iValue  > -1) {
				iValue = iValue *8000 / duration;
				
				data.put("ifInBPS", iValue);
				
				this.putData(Rolling.IFINOCTETS, index, iValue);
				
				rate = iValue*100L / capacity;
				
				if (max == null ||
					max.getValue() < iValue ||
					max.getValue() == iValue && max.getRate() < rate) {
					max = new TopTable.Value(iValue, rate, index);
				}
				
				if (maxRate == null ||
					maxRate.getRate() < rate ||
					maxRate.getRate() == rate && maxRate.getValue() < iValue) {
					maxRate = new TopTable.Value(iValue, rate, index);
				}
			}
			
			oValue = -1;
			
			if (data.has("ifHCOutOctets") && lastData.has("ifHCOutOctets")) {
				oValue = data.getLong("ifHCOutOctets") - lastData.getLong("ifHCOutOctets");
			}
			
			if (data.has("ifOutOctets") && lastData.has("ifOutOctets")) {
				oValue = Math.max(oValue, data.getLong("ifOutOctets") - lastData.getLong("ifOutOctets"));
			}
			
			if (oValue > -1) {
				oValue = oValue *8000 / duration;
				
				data.put("ifOutBPS", oValue);
				
				this.putData(Rolling.IFOUTOCTETS, index, oValue);
				
				rate = oValue*100L / capacity;
				
				if (max == null ||
					max.getValue() < oValue ||
					max.getValue() == oValue && max.getRate() < rate) {
					max = new TopTable.Value(oValue, rate, index);
				}
				
				if (maxRate == null ||
					maxRate.getRate() < rate ||
					maxRate.getRate() == rate && maxRate.getValue() < oValue) {
					maxRate = new TopTable.Value(oValue, rate, index);
				}
			}
		
			if (this.critical != null) {
				long value = Math.max(iValue, oValue);
				
				if (value > -1) {					
					this.critical.analyze(Critical.Resource.THROUGHPUT, index, capacity, value);
				}
			}
		}
		
		if (max != null) {
			this.agent.onSubmitTop(this.ip, TopTable.Resource.THROUGHPUT, max);
		}
		
		if (maxRate != null) {
			this.agent.onSubmitTop(this.ip, TopTable.Resource.THROUGHPUTRATE, maxRate);
		}
		
		if (maxErr != null) {
			this.agent.onSubmitTop(this.ip, TopTable.Resource.THROUGHPUTERR, maxErr);
		}
	}
	
	public Map<String, Integer> getProcessorEntry() {
		return super.hrProcessorEntry;
	}
	
	public Map<String, JSONObject> getStorageEntry() {
		return super.hrStorageEntry;
	}
	
	public Map<String, JSONObject> getIFEntry() {
		return super.ifEntry;
	}
	
	public void parseTrap(OID trap, Variable variable) {
		if (trap.startsWith(OID_TRAP)) {
			if (trap.startsWith(OID_LINKUP)) {
				
			}
			else if (trap.startsWith(OID_LINKDOWN)) {
				
			}
		}
	}
	
	public JSONObject test() {
		return new JSONObject()
			.put("sysObjectID", super.data.has("sysObjectID")? super.data.getString("sysObjectID"): "")
			.put("hrProcessorEntry", super.hrProcessorEntry.size())
			.put("hrStorageEntry", super.hrStorageEntry.size())
			.put("ifEntry", super.ifEntry.size());
	}
	
	public long getLoad() {
		Map<String, RollingFile> map;
		long sum = 0;
		long count = 0;
		
		for (Rolling resource : this.rollingMap.keySet()) {
			map = this.rollingMap.get(resource);
			
			for (String index : map.keySet()) {
				sum += map.get(index).getLoad();
				count++;
			}
		}
		
		return count > 0? (sum / count): 0;
	}
	
	public long getResourceCount() {
		long count = 0;
		
		for (Rolling resource : this.rollingMap.keySet()) {
			count += this.rollingMap.get(resource).size();
		}
		
		return count;
	}
	
	public void save() {
		setMonitor();
		
		try {
			Util.putJSONtoFile(new File(this.nodeRoot, "node"), super.data);
		} catch (IOException ioe) {
			System.err.print(ioe);
		}
	}
	
	private void load() {
		File f = new File(this.nodeRoot, "node");
		
		if (!f.isFile()) {
			return;
		}
		
		try {
			super.data = Util.getJSONFromFile(f);
		} catch (IOException ioe) {
			System.err.print(ioe);
			
			return;
		}
		
		if (!super.data.has("ifEntry")) {
			return;
		}
		
		JSONObject ifEntry = super.data.getJSONObject("ifEntry"),
			data;
		String index;
		
		for (Object o: ifEntry.keySet()) {
			index = (String)o;
			data = ifEntry.getJSONObject(index);
			 
			if (data.has("monitor")) {
				this.monitor.put(index, data.getBoolean("monitor"));
			}
		}		
	}
	
	/**
	 * super.data에 monitor 정보 넣어주기
	 */
	private void setMonitor() {
		if (super.data.has("ifEntry")) {
			JSONObject ifEntry = super.data.getJSONObject("ifEntry");
			String index;
			
			for (Object o: this.monitor.keySet()) {
				index = (String)o;
				
				if (ifEntry.has(index)) {
					ifEntry.getJSONObject(index).put("monitor", this.monitor.getBoolean(index));
				}
			}
		}
	}
	
	public JSONObject getData() {
		setMonitor();
		
		super.data.put("failure", super.getFailureRate());
		
		return super.data;
	}
	
	public JSONObject getData(String database, String index, long start, long end, boolean summary) {
		try {
			RollingFile rollingFile
				= this.rollingMap.get(Rolling.valueOf(database.toUpperCase())).get(index);
			
			if (rollingFile == null) {
				rollingFile = new RollingFile(new File(this.nodeRoot, database), index);
			}
			
			if (rollingFile != null) {
				return rollingFile.getData(start, end, summary);
			}
		}
		catch (IllegalArgumentException iae) {
		}
		catch (IOException ioe) {
			System.err.print(ioe);
		}
		
		return null;
	}
	
	public static PDU setRequestOID(PDU pdu) {
		pdu.add(new VariableBinding(RequestOID.sysDescr));
		pdu.add(new VariableBinding(RequestOID.sysObjectID));
		pdu.add(new VariableBinding(RequestOID.sysName));
		pdu.add(new VariableBinding(RequestOID.sysServices));
		pdu.add(new VariableBinding(RequestOID.ifDescr));
		pdu.add(new VariableBinding(RequestOID.ifType));
		pdu.add(new VariableBinding(RequestOID.ifSpeed));
		pdu.add(new VariableBinding(RequestOID.ifPhysAddress));
		pdu.add(new VariableBinding(RequestOID.ifAdminStatus));
		pdu.add(new VariableBinding(RequestOID.ifOperStatus));
		pdu.add(new VariableBinding(RequestOID.ifName));
		pdu.add(new VariableBinding(RequestOID.ifInOctets));
		pdu.add(new VariableBinding(RequestOID.ifInErrors));
		pdu.add(new VariableBinding(RequestOID.ifOutOctets));
		pdu.add(new VariableBinding(RequestOID.ifOutErrors));
		pdu.add(new VariableBinding(RequestOID.ifHCInOctets));
		pdu.add(new VariableBinding(RequestOID.ifHCOutOctets));
		pdu.add(new VariableBinding(RequestOID.ifHighSpeed));
		pdu.add(new VariableBinding(RequestOID.ifAlias));
		pdu.add(new VariableBinding(RequestOID.hrSystemUptime));
		pdu.add(new VariableBinding(RequestOID.hrProcessorLoad));
		pdu.add(new VariableBinding(RequestOID.hrSWRunName));
		pdu.add(new VariableBinding(RequestOID.hrStorageType));
		pdu.add(new VariableBinding(RequestOID.hrStorageDescr));
		pdu.add(new VariableBinding(RequestOID.hrStorageAllocationUnits));
		pdu.add(new VariableBinding(RequestOID.hrStorageSize));
		pdu.add(new VariableBinding(RequestOID.hrStorageUsed));
		
		return pdu;
	}
	
	/**
	 * snmp 응답
	 */
	@Override
	protected void onResponse(boolean success) {
		if (success) {
			try {
				parseResponseTime();
				
				parseProcessor();
				
				parseStorage();
				
				parseInterface();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
		}
		
		this.agent.onResponse(this.ip, success);
		
		this.agent.onSubmitTop(this.ip, TopTable.Resource.FAILURERATE, new TopTable.Value(this.getFailureRate(), this.getFailureRate(), "-1"));
	}

	/**
	 * icmp 응답
	 */
	@Override
	protected void onTimeout(boolean success) {
		this.agent.onTimeout(this.ip, success);
	}
	
	@Override
	protected void onError(InetAddress address, int status) {
		Agent.log(new JSONObject()
			.put("origin", "system")
			.put("message", String.format("Node %s reports error status %d.", address, status)), false);
	}
	
	@Override
	protected void onException(Exception e) {
		this.agent.onException(this.ip);
		
		System.err.print(e);
	}
	
	@Override
	protected void setEnterprise(int pen) {
		Enterprise.setEnterprise(super.pdu, pen);
	}
	
	@Override
	protected boolean parseEnterprise(OID response, Variable variable, OID request) {
		return Enterprise.parseEnterprise(this, response, variable, request);
	}
}