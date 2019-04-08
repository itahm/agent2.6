package com.itahm.node;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class ICMPNode extends Node {

	private final NodeListener listener;
	private final InetAddress ip;
	
	public ICMPNode(NodeListener listener, String id, String ip) throws UnknownHostException {
		super(id);
		
		this.listener = listener;
		super.thread.setName(String.format("ITAhM ICMPNode %s", ip));
		
		this.ip = InetAddress.getByName(ip);
	}

	@Override
	public boolean isReachable() {
		try {
			return this.ip.isReachable(super.timeout);
		}
		catch(IOException ioe) {
			return false;
		}
	}
	
	@Override
	public void onSuccess(long rtt) {
		this.listener.onSuccess(this, rtt);
	}

	@Override
	public void onFailure() {
		this.listener.onFailure(this);
	}

}
