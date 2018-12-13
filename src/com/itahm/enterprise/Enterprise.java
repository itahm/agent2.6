package com.itahm.enterprise;

import java.util.Map;

import org.snmp4j.PDU;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import com.itahm.SNMPNode;
import com.itahm.json.JSONObject;

public class Enterprise {

	public final static OID	cisco = new OID(new int [] {1,3,6,1,4,1,9});
	public final static OID	busyPer = new OID(new int [] {1,3,6,1,4,1,9,2,1,5,6});
	public final static OID	cpmCPUTotal5sec = new OID(new int [] {1,3,6,1,4,1,9,9,109,1,1,1,1,3});
	public final static OID	cpmCPUTotal5secRev = new OID(new int [] {1,3,6,1,4,1,9,9,109,1,1,1,1,6});
	
	public final static OID	dasan = new OID(new int [] {1,3,6,1,4,1,6296});
	public final static OID	dsCpuLoad5s = new OID(new int [] {1,3,6,1,4,1,6296,9,1,1,1,8});
	public final static OID	dsTotalMem = new OID(new int [] {1,3,6,1,4,1,6296,9,1,1,1,14});
	public final static OID	dsUsedMem = new OID(new int [] {1,3,6,1,4,1,6296,9,1,1,1,15});
	
	public final static OID	axgate = new OID(new int [] {1,3,6,1,4,1,37288});
	public final static OID	axgateCPU = new OID(new int [] {1,3,6,1,4,1,37288,1,1,3,1,1});
	
	public static void setEnterprise(PDU pdu, int pen) {
		switch(pen) {
		case 9: //CISCO
			pdu.add(new VariableBinding(busyPer));
			pdu.add(new VariableBinding(cpmCPUTotal5sec));
			pdu.add(new VariableBinding(cpmCPUTotal5secRev));
			
			break;
			
		case 6296: //DASAN
			pdu.add(new VariableBinding(dsCpuLoad5s));
			pdu.add(new VariableBinding(dsTotalMem));
			pdu.add(new VariableBinding(dsUsedMem));
			
			break;
			
		case 37288: //AXGATE
			pdu.add(new VariableBinding(axgateCPU));;
			break;
		}
	}
	
	
	public static boolean parseEnterprise(SNMPNode node, OID response, Variable variable, OID request) {
		if (request.startsWith(cisco)) {
			return parseCisco(node, response, variable, request);
		}
		else if (request.startsWith(dasan)) {
			return parseDasan(node, response, variable, request);
		}
		else if (request.startsWith(axgate)) {
			return parseAgate(node, response, variable, request);
		}
		
		return false;
	}
	
	private static boolean parseCisco(SNMPNode node, OID response, Variable variable, OID request) {
		Map<String, Integer> hrProcessorEntry = node.getProcessorEntry();
		String index = Integer.toString(response.last());
		
		if (request.startsWith(busyPer) && response.startsWith(busyPer)) {
			hrProcessorEntry.put(index, (int)((Gauge32)variable).getValue());
		}
		else if (request.startsWith(cpmCPUTotal5sec) && response.startsWith(cpmCPUTotal5sec)) {
			hrProcessorEntry.put(index, (int)((Gauge32)variable).getValue());
			
		}
		else if (request.startsWith(cpmCPUTotal5secRev) && response.startsWith(cpmCPUTotal5secRev)) {
			hrProcessorEntry.put(index, (int)((Gauge32)variable).getValue());
		}
		else {
			return false;
		}
		
		return true;
	}
	
	private static boolean parseDasan(SNMPNode node, OID response, Variable variable, OID request) {
		Map<String, Integer> hrProcessorEntry = node.getProcessorEntry();
		Map<String, JSONObject> hrStorageEntry = node.getStorageEntry();
		String index = Integer.toString(response.last());
		JSONObject storageData = hrStorageEntry.get(index);
		
		if (storageData == null) {
			storageData = new JSONObject();
			
			hrStorageEntry.put("0", storageData = new JSONObject());
			
			storageData.put("hrStorageType", 2);
			storageData.put("hrStorageAllocationUnits", 1);
		}
		
		if (request.startsWith(dsCpuLoad5s) && response.startsWith(dsCpuLoad5s)) {
			hrProcessorEntry.put(index, (int)((Integer32)variable).getValue());
		}
		else if (request.startsWith(dsTotalMem) && response.startsWith(dsTotalMem)) {
			storageData.put("hrStorageSize", (int)((Integer32)variable).getValue());
		}
		else if (request.startsWith(dsUsedMem) && response.startsWith(dsUsedMem)) {
			storageData.put("hrStorageUsed", (int)((Integer32)variable).getValue());
		}
		else {
			return false;
		}
		
		return true;
	}
	
	private static boolean parseAgate(SNMPNode node, OID response, Variable variable, OID request) {
		Map<String, Integer> hrProcessorEntry = node.getProcessorEntry();
		String index = Integer.toString(response.last());
		
		if (request.startsWith(axgateCPU) && response.startsWith(axgateCPU)) {
			hrProcessorEntry.put(index,  (int)((Integer32)variable).getValue());
		}
		else {
			return false;
		}
		
		return true;
	}
}
