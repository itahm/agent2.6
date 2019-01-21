package com.itahm.node;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

public class TCPNode extends Node {

	private final NodeListener listener;
	private final InetSocketAddress target;
	
	public TCPNode(NodeListener listener, String id, String ip) throws UnknownHostException {
		super(id);
		
		this.listener = listener;
		
		super.thread.setName(String.format("ITAhM TCPNode %s", ip));
		
		String [] address = ip.split(":");
		
		if (address.length != 2) {
			throw new UnknownHostException(ip);
		}
		
		target = new InetSocketAddress(InetAddress.getByName(address[0]), Integer.parseInt(address[1]));
	}

	
	@Override
	public boolean isReachable() throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(this.target, super.timeout);
		} catch (IOException e) {	
			return false;
		}
		
		return true;
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
