package com.itahm.node;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

abstract public class Node implements Runnable, Closeable {

	protected boolean isClosed = false;
	protected int
		timeout = 5000,
		retry = 1;
	protected final Thread thread;
	private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
	
	public final String id;
	
	public Node(String id) {
		this.id = id;
				
		thread = new Thread(this);
		thread.start();
	}

	@Override
	public void run() {
		long delay, sent;
		
		loop: while (!this.thread.isInterrupted()) {
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
						break loop;
					}
					
					try {
						if (isReachable()) {
							onSuccess(System.currentTimeMillis() - sent);
							
							continue loop;
						}
					} catch (IOException e) {
						System.err.print(e);
					}
				}
				
				onFailure();
				
			} catch (InterruptedException e) {
				System.err.print(e);
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
		} catch (InterruptedException ie) {
			this.thread.interrupt();
		}
	}
	
	@Override
	public void close() {
		this.isClosed = true;
		
		this.thread.interrupt();
		
		try {
			this.thread.join();
		} catch (InterruptedException ie) {
			this.thread.interrupt();
		}
	}
	
	abstract public boolean isReachable() throws IOException;
	abstract public void onSuccess(long rtt);
	abstract public void onFailure();
}
