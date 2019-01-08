package com.itahm;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.snmp4j.PDU;
import org.snmp4j.Target;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import com.itahm.enterprise.Enterprise;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.json.RollingFile;
import com.itahm.node.SNMPNode;
import com.itahm.node.NodeListener;

abstract public class ITAhMNode extends SNMPNode {

	enum Rolling {
		HRPROCESSORLOAD("hrProcessorLoad"),
		IFINOCTETS("ifInOctets"),
		IFOUTOCTETS("ifOutOctets"),
		IFINERRORS("ifInErrors"),
		IFOUTERRORS("ifOutErrors"),
		HRSTORAGEUSED("hrStorageUsed"),
		RESPONSETIME("responseTime");
		
		private String resource;
		
		private Rolling(String resource) {
			this.resource = resource;
		}
		
		public String toString() {
			return this.resource;
		}
	}
	
	/**
	 * 데이터 보관소. 인덱스가 없는 정보는 바로 저장된다. node.snmp 와 동일한 장소
	 */
	protected JSONObject data = new JSONObject();
	
	/**
	 * 임시 보관소. 인덱스가 변경되는 경우 기존 인덱스 무시하도록 설계
	 */
	private final NodeManager nodeManager = Agent.node();
	private final Map<String, Integer> hrProcessorEntry = new HashMap<>();
	private final Map<String, JSONObject> hrStorageEntry = new HashMap<>();
	private final Map<String, JSONObject> ifEntry = new HashMap<>();
	private final Map<String, String> hrSWRunName = new HashMap<>();
	private final Map<Rolling, HashMap<String, RollingFile>> rolling = new HashMap<Rolling, HashMap<String, RollingFile>>();
	private PDU pdu = createDefaultPDU();
	private String enterprise;
	private final File nodeRoot;
	private Map<String, Long> speed = new HashMap<>();
	private Map<String, Boolean> updown = new HashMap<>();
	private Map<String, Map<String, Critical>> critical = new HashMap<>();
	private int status = SnmpConstants.SNMP_ERROR_SUCCESS;
	
	public ITAhMNode(NodeListener nodeListener, String id, String ip, Target target) throws IOException {
		super(nodeListener, id, ip, target);
		
		this.nodeRoot = new File(new File(Agent.Setting.root(), "node"), id);
		
		target.setRetries(Agent.Setting.retry());
		target.setTimeout(Agent.Setting.timeout());
	}
	
	public void setSpeed(JSONObject speed) {
		this.speed.clear();
		
		String index;
		
		for (Object key : speed.keySet()) {
			index = (String)key;
			
			this.speed.put(index, speed.getJSONObject(index).getLong("speed"));
		}
	}
	
	public void setUpDown(JSONObject updown) {
		this.updown.clear();
		
		String index;
		
		for (Object key : updown.keySet()) {
			index = (String)key;
			
			this.updown.put(index, updown.getJSONObject(index).getBoolean("updown"));
		}
	}
	
	public void setCritical(JSONObject critical) {
		this.critical.clear();
		
		String
			resource, index;
		Map<String, Critical> map;
		JSONObject
			entry, indexData;
		
		for (Object key1 : critical.keySet()) {
			resource = (String)key1;
			
			map = new HashMap<> ();
			
			this.critical.put(resource, map);
			
			entry = critical.getJSONObject(resource);
			
			for (Object key2 : entry.keySet()) {
				index = (String)key2;
				
				indexData = entry.getJSONObject(index);
				
				map.put(index, new Critical(indexData.getInt("critical"), indexData.has("status")? indexData.getBoolean("status"): false));
			}
		}
	}
	
	public Map<String, Integer> getProcessorEntry() {
		return this.hrProcessorEntry;
	}
	
	public Map<String, JSONObject> getStorageEntry() {
		return this.hrStorageEntry;
	}
	
	public Map<String, JSONObject> getIFEntry() {
		return this.ifEntry;
	}
	
	public long getLoad() {
		Map<String, RollingFile> map;
		long sum = 0;
		long count = 0;
		
		for (Rolling resource : this.rolling.keySet()) {
			map = this.rolling.get(resource);
			
			for (String index : map.keySet()) {
				sum += map.get(index).getLoad();
				count++;
			}
		}
		
		return count > 0? (sum / count): 0;
	}
	
	public long getResourceCount() {
		long count = 0;
		
		for (Rolling resource : this.rolling.keySet()) {
			count += this.rolling.get(resource).size();
		}
		
		return count;
	}
	
	public JSONObject
	getData(String resource, String index, long start, long end, boolean summary)
	throws IOException {
		RollingFile rollingFile
			= this.rolling.get(Rolling.valueOf(resource.toUpperCase())).get(index);
		
		if (rollingFile == null) {
			return new JSONObject();
		}
		else {
			return rollingFile.getData(start, end, summary);
		}
	}
	
	private void putData(Rolling resource, String index, long value) throws IOException {
		Map<String, RollingFile> rolling = this.rolling.get(resource);
		RollingFile rollingFile = rolling.get(index);
		
		if (rollingFile == null) {
			rolling.put(index, rollingFile = new RollingFile(new File(this.nodeRoot, resource.toString()), index));
		}
		
		rollingFile.roll(value, Agent.Setting.rollingInterval());
	}
	
	/**
	 * ICMPNode.onSuccess override
	 * 최종적으로 super.onSuccess를 호출해 주어야 한다.
	 */
	@Override
	public void onSuccess(long rtt) {
		try {
			putData(Rolling.RESPONSETIME, "0", rtt);
		} catch (IOException ioe) {
			System.err.print(ioe);
		}
		
		this.nodeManager.submitTop(super.id, TopTable.Resource.RESPONSETIME, new TopTable.Value(rtt, -1, "0"));
		
		// sysObjectID 가 변동되었으면
		if (this.data.has("sysObjectID") && !this.data.getString("sysObjectID").equals(this.enterprise)) {
			// 최초일수도 있지만 변경된 경우 기존 정보를 삭제해 주기 위해 초기화
			this.pdu = createDefaultPDU();
				
			Enterprise.setEnterprisePDU(this.pdu, this.enterprise);
			
			this.enterprise = this.data.getString("sysObjectID");
		}
		
		this.pdu.setRequestID(new Integer32(0));		
		
		// 기존 정보를 삭제해 주기 위해 초기화
		this.hrProcessorEntry.clear();
		this.hrStorageEntry.clear();
		this.ifEntry.clear();
		this.hrSWRunName.clear();
		
		int status = tryRequest(this.pdu);
		
		// 성공
		if (status == SnmpConstants.SNMP_ERROR_SUCCESS) {
			try {
				parseProcessor();
				parseStorage();
				parseInterface();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
			
			this.data.put("hrProcessorEntry", this.hrProcessorEntry);
			this.data.put("hrStorageEntry", this.hrStorageEntry);
			this.data.put("hrSWRunName", this.hrSWRunName);			
			this.data.put("ifEntry", this.ifEntry);
			
			try {
				save();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
			
			if (this.nodeManager.getNode(super.id) == null) {
				this.nodeManager.setNode(super.id, this);
			}
		}
		else {
			this.nodeManager.removeTop(super.id);
		}
		
		if (this.status != status) {
			this.status = status;
			
			Agent.event().put(new JSONObject()
				.put("origin", "snmp")
				.put("id", super.id)
				.put("status", status)
				.put("message", String.format("%s SNMP %s",
					this.nodeManager.getNodeName(super.id),
					status == SnmpConstants.SNMP_ERROR_SUCCESS? "응답 정상": status == SnmpConstants.SNMP_ERROR_TIMEOUT? "응답 없음.": ("오류 코드 "+ status))), true);
		}
		// 할 일 다 마치고 호출해야 한다.
		super.onSuccess(rtt);
	}
	
	/**
	 * asynchronous call
	 * callback = onResponse
	 * @param pdu
	 */
	private int tryRequest(final PDU pdu) {
		try {
			return super.sendRequest(pdu);
		}
		catch (IOException ioe) {
			System.err.print(ioe);
			
			try {
				Thread.sleep(10000);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
			
			return tryRequest(pdu);
		}
	}
	
	private void save() throws IOException {		
		Map<String, Critical> map;
		JSONObject
			entry,
			indexData;
		Critical critical;
		
		for (String resource: this.critical.keySet()) {
			if (!this.data.has(resource)) {
				continue;
			}
			
			map = this.critical.get(resource);
			entry = this.data.getJSONObject(resource);
			
			for (String index : map.keySet()) {
				if (!entry.has(index)) {
					continue;
				}
				
				indexData = entry.getJSONObject(index);
				
				critical = map.get(index);
				
				indexData.put("critical", critical.critical);
				indexData.put("status", critical.status);
			}
		}
		
		if (this.data.has("ifEntry")) {
			entry = this.data.getJSONObject("ifEntry");
				
			for (String index: this.updown.keySet()) {
				if (!entry.has(index)) {
					continue;
				}
				
				entry.getJSONObject(index).put("updown", this.updown.get(index));
			}
			
			for (String index: this.speed.keySet()) {
				if (!entry.has(index)) {
					continue;
				}
					
				entry.getJSONObject(index).put("speed", this.speed.get(index));
			}
		}
		
		this.nodeManager.save(id, this.data);
	}
	
	public void analyze(String resource, String index, long max, long value) {
		if (!this.critical.containsKey(resource)) {
			return;
		}
		
		Map<String, Critical> entry = this.critical.get(resource);
		
		if (resource.equals("hrProcessorEntry")) {
			Critical critical = entry.get(index);
			
			if (critical == null) {
				if (!entry.containsKey("0")) {
					return;
				}
				
				critical = entry.get("0").clone();
					
				entry.put(index, critical);
			}
					
			long rate = value *100 / max;
					
			if (critical.diff(rate)) {
				for (String key : entry.keySet()) {
					if ("0".equals(key) || index.equals(key)) {
						continue;
					}
					
					// 같은 상태가 하나라도 있으면 not diff
					if (entry.get(key).status == critical.status) {
						return;
					}
				}
				
				onCritical(resource, "0", critical.status, rate);
			}
		}
		else {			
			Critical critical = entry.get(index);
		
			if (critical == null) {
				return;
			}
			
			long rate = value *100 / max;
				
			if (critical.diff(rate)) {
				onCritical(resource, index, critical.status, rate);
			}
		}
	}
	
	private void onCritical(String resource, String index, boolean critical, long rate) {				
		Agent.event().put(new JSONObject()
			.put("origin", "critical")
			.put("id", super.id)
			.put("resource", resource)
			.put("index", index)
			.put("critical", critical)
			.put("rate", rate)
			.put("message", String.format("%s [%s] %d%% 임계 %s",
					this.nodeManager.getNodeName(super.id),
					resource,
					rate,
					critical? "정상": "초과")), true);	
	}
	
	@Override
	public boolean hasNextPDU(OID response, Variable variable, OID request) throws IOException {
		// 1,3,6,1,2,1,1,5
		if (request.startsWith(OID_system)) {
			return parseSystem(response, variable, request);
		}
		// 1,3,6,1,2,1,2,2,1
		else if (request.startsWith(OID_ifEntry)) {
			return parseIFEntry(response, variable, request);
		}
		// 1,3,6,1,2,1,31,1,1,1
		else if (request.startsWith(OID_ifXEntry)) {
			return parseIFXEntry(response, variable, request);
		}
		// 1,3,6,1,2,1,25
		else if (request.startsWith(OID_host)) {
			return parseHost(response, variable, request);
		}
		else if (request.startsWith(OID_enterprises)) {
			return Enterprise.parseEnterprise(this, response, variable, request);
		}
		
		return false;
	}

	private final boolean parseSystem(OID response, Variable variable, OID request) {
		if (request.startsWith(OID_sysDescr) && response.startsWith(OID_sysDescr)) {
			this.data.put("sysDescr", new String(((OctetString)variable).getValue()));
		}
		else if (request.startsWith(OID_sysObjectID) && response.startsWith(OID_sysObjectID)) {
			this.data.put("sysObjectID", ((OID)variable).toDottedString());
		}
		else if (request.startsWith(OID_sysName) && response.startsWith(OID_sysName)) {
			this.data.put("sysName", new String(((OctetString)variable).getValue()));
		}
		
		return false;
	}
	
	private final boolean parseIFEntry(OID response, Variable variable, OID request) throws IOException {
		String index = Integer.toString(response.last());
		JSONObject ifData = this.ifEntry.get(index);
		
		if(ifData == null) {
			this.ifEntry.put(index, ifData = new JSONObject());
			
			ifData.put("ifInBPS", 0);
			ifData.put("ifOutBPS", 0);
		}
		
		if (request.startsWith(OID_ifDescr) && response.startsWith(OID_ifDescr)) {
			ifData.put("ifDescr", new String(((OctetString)variable).getValue()));
		}
		else if (request.startsWith(OID_ifType) && response.startsWith(OID_ifType)) {			
			ifData.put("ifType", ((Integer32)variable).getValue());
		}
		else if (request.startsWith(OID_ifSpeed) && response.startsWith(OID_ifSpeed)) {			
			ifData.put("ifSpeed", ((Gauge32)variable).getValue());
			ifData.put("timestamp", Calendar.getInstance().getTimeInMillis());
		}
		else if (request.startsWith(OID_ifPhysAddress) && response.startsWith(OID_ifPhysAddress)) {
			byte [] mac = ((OctetString)variable).getValue();
			
			String macString = "";
			
			if (mac.length > 0) {
				macString = String.format("%02X", 0L |mac[0] & 0xff);
				
				for (int i=1; i<mac.length; i++) {
					macString += String.format("-%02X", 0L |mac[i] & 0xff);
				}
			}
			
			ifData.put("ifPhysAddress", macString);
		}
		else if (request.startsWith(OID_ifAdminStatus) && response.startsWith(OID_ifAdminStatus)) {
			ifData.put("ifAdminStatus", ((Integer32)variable).getValue());
		}
		else if (request.startsWith(OID_ifOperStatus) && response.startsWith(OID_ifOperStatus)) {			
			ifData.put("ifOperStatus", ((Integer32)variable).getValue());
		}
		else if (request.startsWith(OID_ifInOctets) && response.startsWith(OID_ifInOctets)) {
			ifData.put("ifInOctets", ((Counter32)variable).getValue());
		}
		else if (request.startsWith(OID_ifOutOctets) && response.startsWith(OID_ifOutOctets)) {
			ifData.put("ifOutOctets", ((Counter32)variable).getValue());
		}
		else if (request.startsWith(OID_ifInErrors) && response.startsWith(OID_ifInErrors)) {
			ifData.put("ifInErrors", ((Counter32)variable).getValue());
		}
		else if (request.startsWith(OID_ifOutErrors) && response.startsWith(OID_ifOutErrors)) {
			ifData.put("ifOutErrors", ((Counter32)variable).getValue());
		}
		else {
			return false;
		}
		
		return true;
	}
	
	private final boolean parseIFXEntry(OID response, Variable variable, OID request) throws IOException {
		String index = Integer.toString(response.last());
		JSONObject ifData = this.ifEntry.get(index);
		
		if(ifData == null) {
			this.ifEntry.put(index, ifData = new JSONObject());
		}
		
		if (request.startsWith(OID_ifName) && response.startsWith(OID_ifName)) {
			ifData.put("ifName", new String(((OctetString)variable).getValue()));
		}
		else if (request.startsWith(OID_ifAlias) && response.startsWith(OID_ifAlias)) {
			ifData.put("ifAlias", new String(((OctetString)variable).getValue()));
		}
		else if (request.startsWith(OID_ifHCInOctets) && response.startsWith(OID_ifHCInOctets)) {
			ifData.put("ifHCInOctets", ((Counter64)variable).getValue());
		}
		else if (request.startsWith(OID_ifHCOutOctets) && response.startsWith(OID_ifHCOutOctets)) {
			ifData.put("ifHCOutOctets", ((Counter64)variable).getValue());
		}
		else if (request.startsWith(OID_ifHighSpeed) && response.startsWith(OID_ifHighSpeed)) {
			ifData.put("ifHighSpeed", ((Gauge32)variable).getValue() * 1000000L);
		}
		else {
			return false;
		}
		
		return true;
	}
	
	private final boolean parseHost(OID response, Variable variable, OID request) throws JSONException, IOException {
		if (request.startsWith(OID_hrSystemUptime) && response.startsWith(OID_hrSystemUptime)) {
			this.data.put("hrSystemUptime", ((TimeTicks)variable).toMilliseconds());
			
			return false;
		}
		
		String index = Integer.toString(response.last());
		
		if (request.startsWith(OID_hrProcessorLoad) && response.startsWith(OID_hrProcessorLoad)) {
			this.hrProcessorEntry.put(index, ((Integer32)variable).getValue());
		}
		else if (request.startsWith(OID_hrSWRunName) && response.startsWith(OID_hrSWRunName)) {
			this.hrSWRunName.put(index, new String(((OctetString)variable).getValue()));
		}
		else if (request.startsWith(OID_hrStorageEntry) && response.startsWith(OID_hrStorageEntry)) {
			JSONObject storageData = this.hrStorageEntry.get(index);
			
			if (storageData == null) {
				this.hrStorageEntry.put(index, storageData = new JSONObject());
			}
			
			if (request.startsWith(OID_hrStorageType) && response.startsWith(OID_hrStorageType)) {
				storageData.put("hrStorageType", ((OID)variable).last());
			}
			else if (request.startsWith(OID_hrStorageDescr) && response.startsWith(OID_hrStorageDescr)) {
				storageData.put("hrStorageDescr", new String(((OctetString)variable).getValue()));
			}
			else if (request.startsWith(OID_hrStorageAllocationUnits) && response.startsWith(OID_hrStorageAllocationUnits)) {
				storageData.put("hrStorageAllocationUnits", ((Integer32)variable).getValue());
			}
			else if (request.startsWith(OID_hrStorageSize) && response.startsWith(OID_hrStorageSize)) {
				storageData.put("hrStorageSize", ((Integer32)variable).getValue());
			}
			else if (request.startsWith(OID_hrStorageUsed) && response.startsWith(OID_hrStorageUsed)) {
				storageData.put("hrStorageUsed", ((Integer32)variable).getValue());
			}
			else {
				return false;
			}
		}
		else {
			return false;
		}
		
		return true;
	}
	
	private void parseProcessor() throws IOException {
		TopTable.Value max = null;
		long value;
		
		for(String index: this.hrProcessorEntry.keySet()) {
			value = this.hrProcessorEntry.get(index);
			
			this.putData(Rolling.HRPROCESSORLOAD, index, value);
			
			analyze("hrProcessorEntry", index, 100, value);
			
			if (max == null || max.value < value) {
				max = new TopTable.Value(value, value, index);
			}
		}
		
		if (max != null) {
			this.nodeManager.submitTop(super.id, TopTable.Resource.PROCESSOR, max);
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
		
		// index 변화를 감지하기 위해
		if (this.data.has("hrStorageEntry")) {
			entry = new HashSet<>();
			
			for (Object index : this.data.getJSONObject("hrStorageEntry").keySet()) {
				entry.add((String)index);
			}
		}
		
		for(String index: this.hrStorageEntry.keySet()) {
			storage = this.hrStorageEntry.get(index);
			
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
				
				analyze("hrStorageEntry", index, capacity, tmpValue);
				
				this.nodeManager.submitTop(super.id, TopTable.Resource.MEMORY, new TopTable.Value(value, tmpValue *100 / capacity, index));
				this.nodeManager.submitTop(super.id, TopTable.Resource.MEMORYRATE, new TopTable.Value(value, tmpValue *100 / capacity, index));
				
				break;
			//case 5:
			case 4:
				// 스토리지는 여러 볼륨중 가장 높은값을 submit
				
				analyze("hrStorageEntry", index, capacity, tmpValue);
				
				if (max == null || max.value < value) {
					max = new TopTable.Value(value, tmpValue *100L / capacity, index);
				}
				
				if (maxRate == null || maxRate.rate < (tmpValue *100L / capacity)) {
					maxRate = new TopTable.Value(value, tmpValue *100L / capacity, index);
				}
				
				break;
				
			//default:
			}
		}
		
		if (modified || (entry != null && entry.size() > 0)) {
			Agent.event().put(new JSONObject()
				.put("origin", "warning")
				.put("id", super.id)
				.put("message", String.format("%s 저장소 상태 변화 감지", this.nodeManager.getNodeName(super.id)))
				, false);
		}
		
		if (max != null) {
			this.nodeManager.submitTop(super.id, TopTable.Resource.STORAGE, max);
		}
		
		if (maxRate != null) {
			this.nodeManager.submitTop(super.id, TopTable.Resource.STORAGERATE, maxRate);
		}
	}
	
	private void parseInterface() throws IOException {
		JSONObject
			lastEntry = this.data.has("ifEntry")? this.data.getJSONObject("ifEntry"): null,
			indexData, lastData;
		
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
		
		for(String index: this.ifEntry.keySet()) {
			// 특정 index가 새로 생성되었다면 보관된 값이 없을수도 있음.
			if (!lastEntry.has(index)) {
				continue;
			}
			
			lastData = lastEntry.getJSONObject(index);
			indexData = this.ifEntry.get(index);
			capacity = 0;
			
			// ifAdminStatus를 제공하지 않거나 비활성 인터페이스는 파싱하지 않는다.
			// administratively 상태가 변하는 것은 체크하지 않는다.
			if (!indexData.has("ifAdminStatus") || indexData.getInt("ifAdminStatus") != 1) {
				continue;
			}
			
			// ifOperStatus를 제공하지 않는 인터페이스는 파싱하지 않는다.
			if (!indexData.has("ifOperStatus")) {
				continue;
			}
			
			status = indexData.getInt("ifOperStatus");
			
			if (lastData.has("ifOperStatus")) {
				if (lastData.getInt("ifOperStatus") != status
					&& this.updown.containsKey(index)
					&& this.updown.get(index)) {
					Agent.event().put(new JSONObject()
						.put("origin", "monitor")
						.put("ip", super.id)
						.put("monitor", status == 1? true: false)
						.put("message"
							, String.format("%s interface %s %s",
								this.nodeManager.getNodeName(super.id),
								indexData.has("ifName")? indexData.getString("ifName"): index,
								status==1? "up": "down"
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
			if (this.speed.containsKey(index)) {
				capacity = this.speed.get(index);
			}
			else if (indexData.has("ifHighSpeed")) {
				capacity = indexData.getLong("ifHighSpeed");
			}
			else if (indexData.has("ifSpeed")) {
				capacity = indexData.getLong("ifSpeed");
			}
			
			if (capacity <= 0) {
				continue;
			}
			
			if (!indexData.has("timestamp") || !lastData.has("timestamp")) {
				continue;
			}
				
			duration = indexData.getLong("timestamp") - lastData.getLong("timestamp");
			
			if (duration <= 0) {
				continue;
			}
				
			if (indexData.has("ifInErrors") && lastData.has("ifInErrors")) {
				long value = indexData.getInt("ifInErrors") - lastData.getInt("ifInErrors");
				
				indexData.put("ifInErrors", value);
				
				this.putData(Rolling.IFINERRORS, index, value);
				
				if (maxErr == null || maxErr.value < value) {
					maxErr = new TopTable.Value(value, -1, index);
				}
			}
			
			if (indexData.has("ifOutErrors") && lastData.has("ifOutErrors")) {
				long value = indexData.getInt("ifOutErrors") - lastData.getInt("ifOutErrors");
				
				indexData.put("ifOutErrors", value);
				
				this.putData(Rolling.IFOUTERRORS, index, value);
				
				if (maxErr == null || maxErr.value < value) {
					maxErr = new TopTable.Value(value, -1, index);
				}
			}
			
			iValue = -1;
			
			if (indexData.has("ifHCInOctets") && lastData.has("ifHCInOctets")) {
				iValue = indexData.getLong("ifHCInOctets") - lastData.getLong("ifHCInOctets");
				
				if (iValue > capacity) {
					indexData.put("ifHCInOctets", lastData.getLong("ifHCInOctets") + capacity);
					
					iValue = capacity;
				}
			}
			else if (indexData.has("ifInOctets") && lastData.has("ifInOctets")) {
				iValue = indexData.getLong("ifInOctets") - lastData.getLong("ifInOctets");
				
				if (iValue > capacity) {
					indexData.put("ifInOctets", lastData.getLong("ifInOctets") + capacity);
					
					iValue = capacity;
				}
			}
			
			if (iValue  > -1) {
				iValue = iValue *8000 / duration;
				
				indexData.put("ifInBPS", iValue);
				
				this.putData(Rolling.IFINOCTETS, index, iValue);
				
				rate = iValue*100L / capacity;
				
				if (max == null ||
					max.value < iValue ||
					max.value == iValue && max.rate < rate) {
					max = new TopTable.Value(iValue, rate, index);
				}
				
				if (maxRate == null ||
					maxRate.rate < rate ||
					maxRate.rate == rate && maxRate.value < iValue) {
					maxRate = new TopTable.Value(iValue, rate, index);
				}
			}
			
			oValue = -1;
			
			if (indexData.has("ifHCOutOctets") && lastData.has("ifHCOutOctets")) {
				oValue = indexData.getLong("ifHCOutOctets") - lastData.getLong("ifHCOutOctets");
				
				if (oValue > capacity) {
					indexData.put("ifHCOutOctets", lastData.getLong("ifHCOutOctets") + capacity);
					
					oValue = capacity;
				}
			}
			else if (indexData.has("ifOutOctets") && lastData.has("ifOutOctets")) {
				oValue = indexData.getLong("ifOutOctets") - lastData.getLong("ifOutOctets");
				
				if (oValue > capacity) {
					indexData.put("ifOutOctets", lastData.getLong("ifOutOctets") + capacity);
					
					oValue = capacity;
				}
			}
			
			if (oValue > -1) {
				oValue = oValue *8000 / duration;
				
				indexData.put("ifOutBPS", oValue);
				
				this.putData(Rolling.IFOUTOCTETS, index, oValue);
				
				rate = oValue*100L / capacity;
				
				if (max == null ||
					max.value < oValue ||
					max.value == oValue && max.rate < rate) {
					max = new TopTable.Value(oValue, rate, index);
				}
				
				if (maxRate == null ||
					maxRate.rate < rate ||
					maxRate.rate == rate && maxRate.value < oValue) {
					maxRate = new TopTable.Value(oValue, rate, index);
				}
			}
		
			
			long value = Math.max(iValue, oValue);
			
			if (value > -1) {					
				analyze("ifEntry", index, capacity, value);
			}
		}
		
		if (max != null) {
			this.nodeManager.submitTop(super.id, TopTable.Resource.THROUGHPUT, max);
		}
		
		if (maxRate != null) {
			this.nodeManager.submitTop(super.id, TopTable.Resource.THROUGHPUTRATE, maxRate);
		}
		
		if (maxErr != null) {
			this.nodeManager.submitTop(super.id, TopTable.Resource.THROUGHPUTERR, maxErr);
		}
	}
	
	private PDU createDefaultPDU() {
		PDU pdu = createPDU();
		
		pdu.add(new VariableBinding(OID_sysDescr));
		pdu.add(new VariableBinding(OID_sysObjectID));
		pdu.add(new VariableBinding(OID_sysName));
		pdu.add(new VariableBinding(OID_ifDescr));
		pdu.add(new VariableBinding(OID_ifType));
		pdu.add(new VariableBinding(OID_ifSpeed));
		pdu.add(new VariableBinding(OID_ifPhysAddress));
		pdu.add(new VariableBinding(OID_ifAdminStatus));
		pdu.add(new VariableBinding(OID_ifOperStatus));
		pdu.add(new VariableBinding(OID_ifName));
		pdu.add(new VariableBinding(OID_ifInOctets));
		pdu.add(new VariableBinding(OID_ifInErrors));
		pdu.add(new VariableBinding(OID_ifOutOctets));
		pdu.add(new VariableBinding(OID_ifOutErrors));
		pdu.add(new VariableBinding(OID_ifHCInOctets));
		pdu.add(new VariableBinding(OID_ifHCOutOctets));
		pdu.add(new VariableBinding(OID_ifHighSpeed));
		pdu.add(new VariableBinding(OID_ifAlias));
		pdu.add(new VariableBinding(OID_hrSystemUptime));
		pdu.add(new VariableBinding(OID_hrProcessorLoad));
		pdu.add(new VariableBinding(OID_hrSWRunName));
		pdu.add(new VariableBinding(OID_hrStorageType));
		pdu.add(new VariableBinding(OID_hrStorageDescr));
		pdu.add(new VariableBinding(OID_hrStorageAllocationUnits));
		pdu.add(new VariableBinding(OID_hrStorageSize));
		pdu.add(new VariableBinding(OID_hrStorageUsed));
		
		return pdu;
	}
	
	private class Critical {
		
		private final int critical;
		private boolean status = false;
		
		private Critical(int critical, boolean status) {
			this.critical = critical;
			this.status = status;
		}
		
		private boolean diff(long value) {
			boolean status = this.critical <= value;
			
			if (this.status == status) { // 상태가 같으면 none
				return false;
			}
			
			this.status = status; // 바뀐 상태 입력
			
			return true;
		}
		
		@Override
		public Critical clone() {
			return new Critical(this.critical, this.status);
		}
		
	}

	private final static OID OID_system = new OID(new int [] {1,3,6,1,2,1,1});
	private final static OID OID_sysDescr = new OID(new int [] {1,3,6,1,2,1,1,1});
	private final static OID OID_sysObjectID = new OID(new int [] {1,3,6,1,2,1,1,2});
	private final static OID OID_sysName =  new OID(new int [] {1,3,6,1,2,1,1,5});
	private final static OID OID_ifEntry =  new OID(new int [] {1,3,6,1,2,1,2,2,1});
	private final static OID OID_ifDescr = new OID(new int [] {1,3,6,1,2,1,2,2,1,2});
	private final static OID OID_ifType =  new OID(new int [] {1,3,6,1,2,1,2,2,1,3});
	private final static OID OID_ifSpeed =  new OID(new int [] {1,3,6,1,2,1,2,2,1,5});
	private final static OID OID_ifPhysAddress =  new OID(new int [] {1,3,6,1,2,1,2,2,1,6});
	private final static OID OID_ifAdminStatus =  new OID(new int [] {1,3,6,1,2,1,2,2,1,7});
	private final static OID OID_ifOperStatus =  new OID(new int [] {1,3,6,1,2,1,2,2,1,8});
	private final static OID OID_ifInOctets =  new OID(new int [] {1,3,6,1,2,1,2,2,1,10});
	private final static OID OID_ifInErrors =  new OID(new int [] {1,3,6,1,2,1,2,2,1,14});
	private final static OID OID_ifOutOctets =  new OID(new int [] {1,3,6,1,2,1,2,2,1,16});
	private final static OID OID_ifOutErrors =  new OID(new int [] {1,3,6,1,2,1,2,2,1,20});
	private final static OID OID_host = new OID(new int [] {1,3,6,1,2,1,25});
	private final static OID OID_hrSystemUptime = new OID(new int [] {1,3,6,1,2,1,25,1,1});
	private final static OID OID_hrStorageEntry = new OID(new int [] {1,3,6,1,2,1,25,2,3,1});
	private final static OID OID_hrStorageType = new OID(new int [] {1,3,6,1,2,1,25,2,3,1,2});
	private final static OID OID_hrStorageDescr = new OID(new int [] {1,3,6,1,2,1,25,2,3,1,3});
	private final static OID OID_hrStorageAllocationUnits = new OID(new int [] {1,3,6,1,2,1,25,2,3,1,4});
	private final static OID OID_hrStorageSize = new OID(new int [] {1,3,6,1,2,1,25,2,3,1,5});
	private final static OID OID_hrStorageUsed = new OID(new int [] {1,3,6,1,2,1,25,2,3,1,6});
	private final static OID OID_hrProcessorLoad = new OID(new int [] {1,3,6,1,2,1,25,3,3,1,2});
	private final static OID OID_hrSWRunName = new OID(new int [] {1,3,6,1,2,1,25,4,2,1,2});
	private final static OID OID_ifXEntry = new OID(new int [] {1,3,6,1,2,1,31,1,1,1});
	private final static OID OID_ifName =  new OID(new int [] {1,3,6,1,2,1,31,1,1,1,1});
	private final static OID OID_ifHCInOctets =  new OID(new int [] {1,3,6,1,2,1,31,1,1,1,6});
	private final static OID OID_ifHCOutOctets =  new OID(new int [] {1,3,6,1,2,1,31,1,1,1,10});
	private final static OID OID_ifHighSpeed = new OID(new int [] {1,3,6,1,2,1,31,1,1,1,15});
	private final static OID OID_ifAlias = new OID(new int [] {1,3,6,1,2,1,31,1,1,1,18});
	private final static OID OID_enterprises = new OID(new int [] {1,3,6,1,4,1});
}
