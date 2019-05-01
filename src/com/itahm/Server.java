package com.itahm;

import org.snmp4j.PDU;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;

import com.itahm.http.HTTPListener;

public interface Server extends HTTPListener {
	public void setEnterprisePDU(PDU pdu, String pen);
	public boolean parseEnterprise(ITAhMNode node, OID response, Variable variable, OID request);
}
