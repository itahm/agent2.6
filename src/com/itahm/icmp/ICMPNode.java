package com.itahm.icmp;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ICMPNode implements Runnable, Closeable {

	private final ICMPListener listener;
	private final InetAddress target;
	private final Thread thread;
	private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
	
	private int timeout = 10000,
		retry = 1;
	
	public final String ip;
	
	public ICMPNode(ICMPListener listener, String ip) throws UnknownHostException {
		this.listener = listener;
		this.ip = ip;
		
		target = InetAddress.getByName(ip);
		
		thread = new Thread(this);
		
		thread.setName("ITAhM ICMPNode "+ ip);
		
		thread.start();
	}
	
	@Override
	public void run() {
		long delay, sent;
		
		init: while (!this.thread.isInterrupted()) {
			try {
				try {
					delay = this.queue.take();
					
					if (delay > 0) {
						Thread.sleep(delay);
					}
					else if (delay < 0) {
						throw new InterruptedException();
					}
					
					sent = System.currentTimeMillis();
					
					for (int i=0; i<this.retry; i++) {
						if (this.thread.isInterrupted()) {
							throw new InterruptedException();
						}
						
						if (this.target.isReachable(this.timeout)) {
							this.listener.onSuccess(this, System.currentTimeMillis() - sent);
							
							continue init;
						}
					}
					
				} catch (IOException e) {}
				
				this.listener.onFailure(this);
				
			} catch (InterruptedException e) {
				
				break;
			}
		}
	}

	public void setHealth(int timeout, int retry) {
		this.timeout = timeout;
		this.retry = retry;
	}
	
	public void ping(long delay) {
		try {
			this.queue.put(delay);
		} catch (InterruptedException e) {
		}
	}
	
	@Override
	public void close() throws IOException {
		this.thread.interrupt();
		
		try {
			this.queue.put(-1L);
		} catch (InterruptedException ie) {}
	}
	
}
