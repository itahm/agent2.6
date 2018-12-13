package com.itahm.snmp;

import java.io.Closeable;
import java.io.IOException;

import java.net.InetAddress;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.itahm.Agent;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

public abstract class Node implements Runnable, Closeable {
	
	private final static int MAX_REQUEST = 100;	
	public final static int TIMEOUT_DEF = 10000;
	
	public PDU pdu;
	private PDU nextPDU;
	private final Snmp snmp;
	private final InetAddress ip;
	private final Address address;
	private final Thread thread;
	private Target target;
	private int
		timeout = TIMEOUT_DEF,
		retry = 1;
	private Integer enterprise;
	private long failureCount = 0;
	private final BlockingQueue<PDU> queue = new LinkedBlockingQueue<>();
	
	protected long lastResponse;
	protected long responseTime;
	/**
	 * 이전 데이터 보관소
	 */
	protected JSONObject data = new JSONObject();
	
	/**
	 * 최신 데이터 보관소
	 */
	protected final Map<String, Integer> hrProcessorEntry = new HashMap<>();
	protected final Map<String, JSONObject> hrStorageEntry = new HashMap<>();
	protected final Map<String, JSONObject> ifEntry = new HashMap<>();
	protected final Map<String, String> hrSWRunName = new HashMap<>();
	
	public Node(Snmp snmp, String ip, int udp) throws IOException {
		this.snmp = snmp;
		this.ip = InetAddress.getByName(ip);
		this.address = new UdpAddress(this.ip, udp);
		thread = new Thread(this);
		
		thread.setName("ITAhM SNMP Node "+ ip);
		thread.start();
	}
	
	private void set(int version) {
		this.pdu.setType(PDU.GETNEXT);
		this.nextPDU.setType(PDU.GETNEXT);		
		
		this.target.setVersion(version);
		
		this.target.setTimeout(TIMEOUT_DEF);
		this.target.setRetries(0);
	}
	
	public void setUser(OctetString user, int level) {
		this.pdu = new ScopedPDU();
		
		this.nextPDU = new ScopedPDU();
		
		this.target = new UserTarget();
		
		this.target.setAddress(this.address);
		this.target.setSecurityName(user);
		this.target.setSecurityLevel(level);
		
		set(SnmpConstants.version3);
	}
	
	public void setCommunity(OctetString community, int version) {
		this.pdu = new PDU();
		
		this.nextPDU = new PDU();
		
		this.target = new CommunityTarget(this.address, community);
		
		set(version);
	}

	public void setHealth(int timeout, int retry) {
		this.timeout = timeout;
		this.retry = retry;
	}
	
	@Override
	public void close() throws IOException {
		this.thread.interrupt();
		
		try {
			this.queue.put(new PDU());
		} catch (InterruptedException e) {
		}
	}
	
	@Override
	public void run() {
		PDU pdu;
		long sent;
		
		init: while (!this.thread.isInterrupted()) {
			try {
				pdu = this.queue.take();
				
				if (this.thread.isInterrupted()) {
					throw new InterruptedException();
				}
				
				sent = System.currentTimeMillis();
				
				for (int i=0; i < this.retry; i++) {
					if (this.thread.isInterrupted()) {
						throw new InterruptedException();
					}
					
					if (ip.isReachable(this.timeout)) {
						this.data.put("responseTime", this.responseTime = System.currentTimeMillis() - sent);
						
						onTimeout(true);
						
						parseResponse(this.snmp.send(pdu, this.target));
						
						continue init;
					}
				}
				
				onTimeout(false);
				
			} catch (InterruptedException ie) {
				break;
			} catch (IOException ioe) {
				onException(ioe);
			}
		}
	}
	
	public void request() throws IOException {
		// 존재하지 않는 index 지워주기 위해 초기화
		hrProcessorEntry.clear();
		hrStorageEntry.clear();
		ifEntry.clear();
		hrSWRunName.clear();
		
		this.pdu.setRequestID(new Integer32(0));
		
		this.queue.add(this.pdu);
	}
	
	public long getFailureRate() {		
		return this.failureCount;
	}
	
	public void resetResponse() {
		this.failureCount = 0;
	}
	
	private final boolean parseSystem(OID response, Variable variable, OID request) {
		if (request.startsWith(RequestOID.sysDescr) && response.startsWith(RequestOID.sysDescr)) {
			this.data.put("sysDescr", new String(((OctetString)variable).getValue()));
		}
		else if (request.startsWith(RequestOID.sysObjectID) && response.startsWith(RequestOID.sysObjectID)) {
			this.data.put("sysObjectID", ((OID)variable).toDottedString());
			
			if (this.enterprise == null) {
				this.enterprise = ((OID)variable).size() > 6? ((OID)variable).get(6): -1;
				
				setEnterprise(this.enterprise);
			}
		}
		else if (request.startsWith(RequestOID.sysName) && response.startsWith(RequestOID.sysName)) {
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
		
		if (request.startsWith(RequestOID.ifDescr) && response.startsWith(RequestOID.ifDescr)) {
			ifData.put("ifDescr", new String(((OctetString)variable).getValue()));
		}
		else if (request.startsWith(RequestOID.ifType) && response.startsWith(RequestOID.ifType)) {			
			ifData.put("ifType", ((Integer32)variable).getValue());
		}
		else if (request.startsWith(RequestOID.ifSpeed) && response.startsWith(RequestOID.ifSpeed)) {			
			ifData.put("ifSpeed", ((Gauge32)variable).getValue());
			ifData.put("timestamp", Calendar.getInstance().getTimeInMillis());
		}
		else if (request.startsWith(RequestOID.ifPhysAddress) && response.startsWith(RequestOID.ifPhysAddress)) {
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
		else if (request.startsWith(RequestOID.ifAdminStatus) && response.startsWith(RequestOID.ifAdminStatus)) {
			ifData.put("ifAdminStatus", ((Integer32)variable).getValue());
		}
		else if (request.startsWith(RequestOID.ifOperStatus) && response.startsWith(RequestOID.ifOperStatus)) {			
			ifData.put("ifOperStatus", ((Integer32)variable).getValue());
		}
		else if (request.startsWith(RequestOID.ifInOctets) && response.startsWith(RequestOID.ifInOctets)) {
			ifData.put("ifInOctets", ((Counter32)variable).getValue());
		}
		else if (request.startsWith(RequestOID.ifOutOctets) && response.startsWith(RequestOID.ifOutOctets)) {
			ifData.put("ifOutOctets", ((Counter32)variable).getValue());
		}
		else if (request.startsWith(RequestOID.ifInErrors) && response.startsWith(RequestOID.ifInErrors)) {
			ifData.put("ifInErrors", ((Counter32)variable).getValue());
		}
		else if (request.startsWith(RequestOID.ifOutErrors) && response.startsWith(RequestOID.ifOutErrors)) {
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
		
		if (request.startsWith(RequestOID.ifName) && response.startsWith(RequestOID.ifName)) {
			ifData.put("ifName", new String(((OctetString)variable).getValue()));
		}
		else if (request.startsWith(RequestOID.ifAlias) && response.startsWith(RequestOID.ifAlias)) {
			ifData.put("ifAlias", new String(((OctetString)variable).getValue()));
		}
		else if (request.startsWith(RequestOID.ifHCInOctets) && response.startsWith(RequestOID.ifHCInOctets)) {
			ifData.put("ifHCInOctets", ((Counter64)variable).getValue());
		}
		else if (request.startsWith(RequestOID.ifHCOutOctets) && response.startsWith(RequestOID.ifHCOutOctets)) {
			ifData.put("ifHCOutOctets", ((Counter64)variable).getValue());
		}
		else if (request.startsWith(RequestOID.ifHighSpeed) && response.startsWith(RequestOID.ifHighSpeed)) {
			ifData.put("ifHighSpeed", ((Gauge32)variable).getValue() * 1000000L);
		}
		else {
			return false;
		}
		
		return true;
	}
	
	private final boolean parseHost(OID response, Variable variable, OID request) throws JSONException, IOException {
		if (request.startsWith(RequestOID.hrSystemUptime) && response.startsWith(RequestOID.hrSystemUptime)) {
			this.data.put("hrSystemUptime", ((TimeTicks)variable).toMilliseconds());
			
			return false;
		}
		
		String index = Integer.toString(response.last());
		
		if (request.startsWith(RequestOID.hrProcessorLoad) && response.startsWith(RequestOID.hrProcessorLoad)) {
			this.hrProcessorEntry.put(index, ((Integer32)variable).getValue());
		}
		else if (request.startsWith(RequestOID.hrSWRunName) && response.startsWith(RequestOID.hrSWRunName)) {
			this.hrSWRunName.put(index, new String(((OctetString)variable).getValue()));
		}
		else if (request.startsWith(RequestOID.hrStorageEntry) && response.startsWith(RequestOID.hrStorageEntry)) {
			JSONObject storageData = this.hrStorageEntry.get(index);
			
			if (storageData == null) {
				this.hrStorageEntry.put(index, storageData = new JSONObject());
			}
			
			if (request.startsWith(RequestOID.hrStorageType) && response.startsWith(RequestOID.hrStorageType)) {
				storageData.put("hrStorageType", ((OID)variable).last());
			}
			else if (request.startsWith(RequestOID.hrStorageDescr) && response.startsWith(RequestOID.hrStorageDescr)) {
				storageData.put("hrStorageDescr", new String(((OctetString)variable).getValue()));
			}
			else if (request.startsWith(RequestOID.hrStorageAllocationUnits) && response.startsWith(RequestOID.hrStorageAllocationUnits)) {
				storageData.put("hrStorageAllocationUnits", ((Integer32)variable).getValue());
			}
			else if (request.startsWith(RequestOID.hrStorageSize) && response.startsWith(RequestOID.hrStorageSize)) {
				storageData.put("hrStorageSize", ((Integer32)variable).getValue());
			}
			else if (request.startsWith(RequestOID.hrStorageUsed) && response.startsWith(RequestOID.hrStorageUsed)) {
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
	/*
	private final boolean parseCisco(OID response, Variable variable, OID request) {
		String index = Integer.toString(response.last());
		
		if (request.startsWith(RequestOID.busyPer) && response.startsWith(RequestOID.busyPer)) {
			this.hrProcessorEntry.put(index, (int)((Gauge32)variable).getValue());
		}
		else if (request.startsWith(RequestOID.cpmCPUTotal5sec) && response.startsWith(RequestOID.cpmCPUTotal5sec)) {
			this.hrProcessorEntry.put(index, (int)((Gauge32)variable).getValue());
			
		}
		else if (request.startsWith(RequestOID.cpmCPUTotal5secRev) && response.startsWith(RequestOID.cpmCPUTotal5secRev)) {
			this.hrProcessorEntry.put(index, (int)((Gauge32)variable).getValue());
		}
		else {
			return false;
		}
		
		return true;
	}
	
	private final boolean parseDasan(OID response, Variable variable, OID request) {
		String index = Integer.toString(response.last());
		JSONObject storageData = this.hrStorageEntry.get(index);
		
		if (storageData == null) {
			storageData = new JSONObject();
			
			this.hrStorageEntry.put("0", storageData = new JSONObject());
			
			storageData.put("hrStorageType", 2);
			storageData.put("hrStorageAllocationUnits", 1);
		}
		
		if (request.startsWith(RequestOID.dsCpuLoad5s) && response.startsWith(RequestOID.dsCpuLoad5s)) {
			this.hrProcessorEntry.put(index, (int)((Integer32)variable).getValue());
		}
		else if (request.startsWith(RequestOID.dsTotalMem) && response.startsWith(RequestOID.dsTotalMem)) {
			storageData.put("hrStorageSize", (int)((Integer32)variable).getValue());
		}
		else if (request.startsWith(RequestOID.dsUsedMem) && response.startsWith(RequestOID.dsUsedMem)) {
			storageData.put("hrStorageUsed", (int)((Integer32)variable).getValue());
		}
		else {
			return false;
		}
		
		return true;
	}
	
	private final boolean parseAgate(OID response, Variable variable, OID request) {
		String index = Integer.toString(response.last());
		
		if (request.startsWith(RequestOID.axgateCPU) && response.startsWith(RequestOID.axgateCPU)) {
			this.hrProcessorEntry.put(index,  (int)((Integer32)variable).getValue());
		}
		else {
			return false;
		}
		
		return true;
	}
	*/
	/**
	 * Parse.
	 * 
	 * @param response
	 * @param variable
	 * @param reqest
	 * @return true get-next가 계속 진행되는 경우
	 * @throws IOException 
	 */
	private final boolean parseResponse (OID response, Variable variable, OID request) throws IOException {
		// 1,3,6,1,2,1,1,5
		if (request.startsWith(RequestOID.system)) {
			return parseSystem(response, variable, request);
		}
		// 1,3,6,1,2,1,2,2,1
		else if (request.startsWith(RequestOID.ifEntry)) {
			return parseIFEntry(response, variable, request);
		}
		// 1,3,6,1,2,1,31,1,1,1
		else if (request.startsWith(RequestOID.ifXEntry)) {
			return parseIFXEntry(response, variable, request);
		}
		// 1,3,6,1,2,1,25
		else if (request.startsWith(RequestOID.host)) {
			return parseHost(response, variable, request);
		}
		else if (request.startsWith(RequestOID.enterprises)) {
			parseEnterprise(response, variable, request);
		}
		
		return false;
	}
	
	private final boolean hasNextRequest(PDU request, PDU response) throws IOException {
		Vector<? extends VariableBinding> requestVBs = request.getVariableBindings();
		Vector<? extends VariableBinding> responseVBs = response.getVariableBindings();
		Vector<VariableBinding> nextRequests = new Vector<VariableBinding>();
		VariableBinding requestVB, responseVB;
		Variable value;
		
		for (int i=0, length = responseVBs.size(); i<length; i++) {
			requestVB = (VariableBinding)requestVBs.get(i);
			responseVB = (VariableBinding)responseVBs.get(i);
			value = responseVB.getVariable();
			
			if (value == Null.endOfMibView) {
				continue;
			}
			
			try {
				if (parseResponse(responseVB.getOid(), value, requestVB.getOid())) {
					nextRequests.add(responseVB);
				}
			} catch(JSONException jsone) { 
				System.err.print(jsone);
			}
		}
		
		this.nextPDU.clear();
		this.nextPDU.setRequestID(new Integer32(0));
		this.nextPDU.setVariableBindings(nextRequests);
		
		return nextRequests.size() > 0;
	}
	
	public void parseResponse(ResponseEvent event) throws IOException {
		PDU response = event.getResponse();
		
		if (response == null || event.getSource() instanceof Snmp.ReportHandler) {
			this.failureCount = Math.min(MAX_REQUEST, this.failureCount +1);
			
			onResponse(false);
			
			return;
		}
		
		PDU request = event.getRequest();
		int status = response.getErrorStatus();
		
		if (status != PDU.noError) {
			onError(this.ip, status);
			
			return;
		}
		
		if (hasNextRequest(request, response)) {
			parseResponse(this.snmp.send(this.nextPDU, this.target));
		}
		else {
			this.lastResponse = Calendar.getInstance().getTimeInMillis();
			this.data.put("lastResponse", this.lastResponse);
			
			this.failureCount = Math.max(0, this.failureCount -1);
			
			// 원하지 않는 인터페이스 정보 삭제.
			JSONObject jsono;
			for (Iterator<String> it = this.ifEntry.keySet().iterator(); it.hasNext();) {
				jsono = this.ifEntry.get(it.next());
				
				if (!jsono.has("ifType") || !Agent.isValidIFType(jsono.getInt("ifType"))) {
					it.remove();
				}
			}
						
			onResponse(true);
			
			this.data.put("hrProcessorEntry", this.hrProcessorEntry);
			this.data.put("hrStorageEntry", this.hrStorageEntry);
			this.data.put("hrSWRunName", this.hrSWRunName);			
			this.data.put("ifEntry", this.ifEntry);
		}
	}
	
	abstract protected void setEnterprise(int pen);
	abstract protected boolean parseEnterprise(OID response, Variable variable, OID request);
	abstract protected void onError(InetAddress address, int status);
	abstract protected void onResponse(boolean success);
	abstract protected void onTimeout(boolean success);
	abstract protected void onException(Exception e);
	
	public static void main(String [] args) throws IOException {
	}
	
}
