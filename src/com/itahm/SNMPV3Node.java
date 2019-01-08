package com.itahm;
import java.io.IOException;
import java.net.InetAddress;

import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.UserTarget;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivDES;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;

import com.itahm.json.JSONObject;
import com.itahm.node.NodeListener;

public class SNMPV3Node extends ITAhMNode {

	public SNMPV3Node(NodeListener nodeListener,  String id, String ip, int udp, String user, int level)
			throws IOException {
		super(nodeListener, id, ip, new UserTarget());
		
		super.target.setSecurityName(new OctetString(user));
		super.target.setAddress(new UdpAddress(InetAddress.getByName(ip), udp));
		super.target.setSecurityLevel(level);
		super.target.setVersion(SnmpConstants.version3);
	}

	public void setUSM(JSONObject profile) {
		String user = profile.getString("user");
		
		if (user.length() == 0) {
			return;
		}
		
		if (profile.has("md5")) {
			if (profile.has("des")) {
				super.setUSM(new OctetString(user), AuthMD5.ID, new OctetString(profile.getString("md5")), PrivDES.ID, new OctetString(profile.getString("des")));
			}
			else {
				super.setUSM(new OctetString(user), AuthMD5.ID, new OctetString(profile.getString("md5")), null, null);
			}
		}
		else if (profile.has("sha")) {
			if (profile.has("des")) {
				super.setUSM(new OctetString(user), AuthSHA.ID, new OctetString(profile.getString("sha")), PrivDES.ID, new OctetString(profile.getString("des")));
			}
			else {
				super.setUSM(new OctetString(user), AuthSHA.ID, new OctetString(profile.getString("sha")), null, null);
			}
		}
		else {
			super.setUSM(new OctetString(user), null, null, null, null);
		}
	}
	
	
	@Override
	public PDU createPDU() {
		PDU pdu = new ScopedPDU();
		
		pdu.setType(PDU.GETNEXT);
		
		return pdu;
	}
	
}
