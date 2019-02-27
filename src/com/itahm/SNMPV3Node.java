package com.itahm;
import java.io.IOException;
import java.net.InetAddress;

import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.UserTarget;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;

public class SNMPV3Node extends ITAhMNode {

	public SNMPV3Node(String id, String ip, int udp, String user, int level)
			throws IOException {
		super(id, ip, new UserTarget());
		
		super.target.setSecurityName(new OctetString(user));
		super.target.setAddress(new UdpAddress(InetAddress.getByName(ip), udp));
		super.target.setSecurityLevel(level);
		super.target.setVersion(SnmpConstants.version3);
	}
	
	@Override
	public PDU createPDU() {
		PDU pdu = new ScopedPDU();
		
		pdu.setType(PDU.GETNEXT);
		
		return pdu;
	}
	
}
