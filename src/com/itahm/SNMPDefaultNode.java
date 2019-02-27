package com.itahm;
import java.io.IOException;
import java.net.InetAddress;

import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;

public class SNMPDefaultNode extends ITAhMNode {

	public SNMPDefaultNode(String id, String ip, int udp, String community, int version)
			throws IOException {
		this(id, ip, udp, community);
		
		super.target.setVersion(version);
	}
	
	public SNMPDefaultNode(String id, String ip, int udp, String community)
			throws IOException {
		super(id, ip, new CommunityTarget(new UdpAddress(InetAddress.getByName(ip), udp), new OctetString(community)));
		
		super.target.setVersion(SnmpConstants.version2c);
	}

	@Override
	public PDU createPDU() {
		PDU pdu = new PDU();
		
		pdu.setType(PDU.GETNEXT);
		
		return pdu;
	}

}
