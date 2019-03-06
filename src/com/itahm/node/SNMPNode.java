package com.itahm.node;

import java.io.Closeable;
import java.io.IOException;
import java.util.Vector;

import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import com.itahm.NodeManager;
import com.itahm.json.JSONException;

abstract public class SNMPNode extends ICMPNode implements Closeable {

	private final Snmp snmp;
	protected final Target target;
	
	public SNMPNode(NodeManager manager, String id, String ip, Target target) throws IOException {
		super(manager, id, ip);

		this.snmp = manager;
		this.target = target;
	}
	
	public int sendRequest(PDU pdu) throws IOException {
		return onEvent(this.snmp.send(pdu, this.target));
	}
	
	// recursive method
	private int onEvent(ResponseEvent event) throws IOException {
		if (event == null) {
			return SnmpConstants.SNMP_ERROR_TIMEOUT;
		}
		
		PDU response = event.getResponse();
		
		if (response == null || event.getSource() instanceof Snmp.ReportHandler) {			
			return SnmpConstants.SNMP_ERROR_TIMEOUT;
		}
		
		PDU request = event.getRequest();
		int status = response.getErrorStatus();
		
		if (status != SnmpConstants.SNMP_ERROR_SUCCESS) {
			return status;
		}
		
		PDU nextPDU = getNextPDU(request, response);
		
		if (nextPDU == null) {
			return SnmpConstants.SNMP_ERROR_SUCCESS;
		}
		
		return onEvent(this.snmp.send(nextPDU, this.target));
	}
	
	private final PDU getNextPDU(PDU request, PDU response) throws IOException {
		PDU pdu = null;
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
				if (hasNextPDU(responseVB.getOid(), value, requestVB.getOid())) {
					nextRequests.add(responseVB);
				}
			} catch(JSONException jsone) { 
				System.err.print(jsone);
			}
		}
		
		if (nextRequests.size() > 0) {
			pdu = createPDU();
			
			pdu.setVariableBindings(nextRequests);
		}
		
		return pdu;
	}
	
	abstract public PDU createPDU();
	abstract public boolean hasNextPDU(OID response, Variable variable, OID request) throws IOException;
	
}
