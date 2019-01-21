package com.itahm.enterprise;

import java.util.Map;

import org.snmp4j.PDU;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import com.itahm.ITAhMNode;
import com.itahm.json.JSONObject;

public class Enterprise {
	
	public static void setEnterprisePDU(PDU pdu, String pen) {
		String [] penArray = pen.split(".");
		
		if (penArray.length < 7) {
			return;
		}
		
		switch(penArray[6]) {
		case "9": //CISCO
			pdu.add(new VariableBinding(OID_busyPer));
			pdu.add(new VariableBinding(OID_cpmCPUTotal5sec));
			pdu.add(new VariableBinding(OID_cpmCPUTotal5secRev));
			
			break;
			
		case "6296": //DASAN
			pdu.add(new VariableBinding(OID_dsCpuLoad5s));
			pdu.add(new VariableBinding(OID_dsTotalMem));
			pdu.add(new VariableBinding(OID_dsUsedMem));
			
			break;
			
		case "37288": //AXGATE
			pdu.add(new VariableBinding(OID_axgateCPU));;
			break;
		}
	}
	
	
	public static boolean parseEnterprise(ITAhMNode node, OID response, Variable variable, OID request) {
		if (request.startsWith(OID_cisco)) {
			return parseCisco(node, response, variable, request);
		}
		else if (request.startsWith(OID_dasan)) {
			return parseDasan(node, response, variable, request);
		}
		else if (request.startsWith(OID_axgate)) {
			return parseAgate(node, response, variable, request);
		}
		
		return false;
	}
	
	private static boolean parseCisco(ITAhMNode node, OID response, Variable variable, OID request) {
		Map<String, JSONObject> hrProcessorEntry = node.getProcessorEntry();
		String index = Integer.toString(response.last());
		
		if (request.startsWith(OID_busyPer) && response.startsWith(OID_busyPer)) {
			hrProcessorEntry.put(index, new JSONObject().put("hrProcessorLoad", (int)((Gauge32)variable).getValue()));
		}
		else if (request.startsWith(OID_cpmCPUTotal5sec) && response.startsWith(OID_cpmCPUTotal5sec)) {
			hrProcessorEntry.put(index, new JSONObject().put("hrProcessorLoad", (int)((Gauge32)variable).getValue()));
			
		}
		else if (request.startsWith(OID_cpmCPUTotal5secRev) && response.startsWith(OID_cpmCPUTotal5secRev)) {
			hrProcessorEntry.put(index, new JSONObject().put("hrProcessorLoad", (int)((Gauge32)variable).getValue()));
		}
		else {
			return false;
		}
		
		return true;
	}
	
	private static boolean parseDasan(ITAhMNode node, OID response, Variable variable, OID request) {
		Map<String, JSONObject> hrProcessorEntry = node.getProcessorEntry();
		Map<String, JSONObject> hrStorageEntry = node.getStorageEntry();
		String index = Integer.toString(response.last());
		JSONObject storageData = hrStorageEntry.get(index);
		
		if (storageData == null) {
			storageData = new JSONObject();
			
			hrStorageEntry.put("0", storageData = new JSONObject());
			
			storageData.put("hrStorageType", 2);
			storageData.put("hrStorageAllocationUnits", 1);
		}
		
		if (request.startsWith(OID_dsCpuLoad5s) && response.startsWith(OID_dsCpuLoad5s)) {
			hrProcessorEntry.put(index, new JSONObject().put("hrProcessorLoad", (int)((Integer32)variable).getValue()));
		}
		else if (request.startsWith(OID_dsTotalMem) && response.startsWith(OID_dsTotalMem)) {
			storageData.put("hrStorageSize", (int)((Integer32)variable).getValue());
		}
		else if (request.startsWith(OID_dsUsedMem) && response.startsWith(OID_dsUsedMem)) {
			storageData.put("hrStorageUsed", (int)((Integer32)variable).getValue());
		}
		else {
			return false;
		}
		
		return true;
	}
	
	private static boolean parseAgate(ITAhMNode node, OID response, Variable variable, OID request) {
		Map<String, JSONObject> hrProcessorEntry = node.getProcessorEntry();
		String index = Integer.toString(response.last());
		
		if (request.startsWith(OID_axgateCPU) && response.startsWith(OID_axgateCPU)) {
			hrProcessorEntry.put(index,  new JSONObject().put("hrProcessorLoad", (int)((Integer32)variable).getValue()));
		}
		else {
			return false;
		}
		
		return true;
	}
	
	private final static OID OID_cisco = new OID(new int [] {1,3,6,1,4,1,9});
	private final static OID OID_busyPer = new OID(new int [] {1,3,6,1,4,1,9,2,1,5,6});
	private final static OID OID_cpmCPUTotal5sec = new OID(new int [] {1,3,6,1,4,1,9,9,109,1,1,1,1,3});
	private final static OID OID_cpmCPUTotal5secRev = new OID(new int [] {1,3,6,1,4,1,9,9,109,1,1,1,1,6});
	private final static OID OID_dasan = new OID(new int [] {1,3,6,1,4,1,6296});
	private final static OID OID_dsCpuLoad5s = new OID(new int [] {1,3,6,1,4,1,6296,9,1,1,1,8});
	private final static OID OID_dsTotalMem = new OID(new int [] {1,3,6,1,4,1,6296,9,1,1,1,14});
	private final static OID OID_dsUsedMem = new OID(new int [] {1,3,6,1,4,1,6296,9,1,1,1,15});
	private final static OID OID_axgate = new OID(new int [] {1,3,6,1,4,1,37288});
	private final static OID OID_axgateCPU = new OID(new int [] {1,3,6,1,4,1,37288,1,1,3,1,1});
}
