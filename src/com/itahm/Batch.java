package com.itahm;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import com.itahm.json.JSONObject;
import com.itahm.util.DataCleaner;

public class Batch {

	private final static int QUEUE_SIZE = 24;
	private final static long MINUTE1 = 60 *1000;
	private final static long MINUTE10 = MINUTE1 *10;
	
	private final File dataRoot;
	private final Timer timer = new Timer();
	private DataCleaner cleaner;
	private TimerTask schedule;
	
	public long lastDiskUsage = 0;
	public JSONObject load = new JSONObject();
	
	public Batch(File root) {
		dataRoot = root;
	}
	
	public void stop() {
		this.timer.cancel();
		
		if (this.cleaner != null) {
			this.cleaner.cancel();
		}
	}
	
	public final void scheduleUsageMonitor() {
		File nodeRoot = new File(this.dataRoot, "node");
		Calendar c = Calendar.getInstance();
		
		c.set(Calendar.DATE, c.get(Calendar.DATE) +1);
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		
		this.timer.schedule(new TimerTask() {

			@Override
			public void run() {
				Calendar c = Calendar.getInstance();
				File dir;
				long size = 0;
				
				c.set(Calendar.DATE, c.get(Calendar.DATE) -1);
				c.set(Calendar.HOUR_OF_DAY, 0);
				c.set(Calendar.MINUTE, 0);
				c.set(Calendar.SECOND, 0);
				c.set(Calendar.MILLISECOND, 0);
				
				if (nodeRoot.isDirectory()) {
					for (File node: nodeRoot.listFiles()) {
						try {
							InetAddress.getByName(node.getName());
							
							if (node.isDirectory()) {
								for (File rsc : node.listFiles()) {
									if (rsc.isDirectory()) {
										for (File index : rsc.listFiles()) {
											if (index.isDirectory()) {
												dir = new File(index, Long.toString(c.getTimeInMillis()));
												
												if (dir.isDirectory()) {
													
													for (File file : dir.listFiles()) {
														size += file.length();
													}
												}
											}
										}
									}
								}
							}
						} catch (UnknownHostException uhe) {
						}
					}
				}
				
				lastDiskUsage = size;
			}
		}, c.getTime(), 24 * 60 * 60 * 1000);
		
		System.out.println("Disk usage monitor up.");
	}
	
	public final void scheduleDiskMonitor() {
		this.timer.schedule(new TimerTask() {
			private final static long MAX = 100;
			private final static long CRITICAL = 10;
			
			private long lastFreeSpace = MAX;
			private long freeSpace;
			
			@Override
			public void run() {
				freeSpace = MAX * dataRoot.getUsableSpace() / dataRoot.getTotalSpace();
				
				if (freeSpace < lastFreeSpace && freeSpace < CRITICAL) {
					Agent.log(new JSONObject().
						put("origin", "system").
						put("message", String.format("저장소 여유공간이 %d%% 남았습니다.", freeSpace)), true);
				}
				
				lastFreeSpace = freeSpace;
			}
		}, 0, MINUTE1);
		
		System.out.println("Free space monitor up.");
	}

	public final void scheduleLoadMonitor() {
		this.timer.schedule(new TimerTask() {
			private Long [] queue = new Long[QUEUE_SIZE];
			private Map<Long, Long> map = new HashMap<>();
			private Calendar c;
			private int position = 0;
			
			@Override
			public void run() {
				long key;
				
				c = Calendar.getInstance();
				
				c.set(Calendar.MINUTE, 0);
				c.set(Calendar.SECOND, 0);
				c.set(Calendar.MILLISECOND, 0);
				
				key = c.getTimeInMillis();
				
				if (this.map.put(key, Agent.calcLoad()) == null) {
					if (this.queue[this.position] != null) {
						this.map.remove(this.queue[this.position]);
					}
					
					this.queue[this.position++] = key;
					
					this.position %= QUEUE_SIZE;
					
					load = new JSONObject(this.map);
				}
				
			}}, MINUTE10, MINUTE10);
		
		System.out.println("Server load monitor up.");
	}

	private final void scheduleDiskCleaner(final int period) {
		Calendar c = Calendar.getInstance();
		
		c.set(Calendar.DATE, c.get(Calendar.DATE) +1);
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		
		/*
		c.set(Calendar.MINUTE, c.get(Calendar.MINUTE) +1);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		*/
		
		this.timer.schedule(this.schedule = new TimerTask() {

			@Override
			public void run() {
				clean(period);
			}
			
		}, c.getTime());
	}
	
	synchronized public void clean(final int period) {
		if (this.schedule != null) {
			this.schedule.cancel();
		}
		
		if (this.cleaner != null) {
			this.cleaner.cancel();
		}
		
		if (period <= 0) {
			return;
		}
		
		Calendar c = Calendar.getInstance();
		
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		
		c.add(Calendar.DATE, -1 *period);
		
		try {
			this.cleaner = new DataCleaner(new File(this.dataRoot, "node"), c.getTimeInMillis(), 3) {
				private final long start = System.currentTimeMillis();
				
				@Override
				public void onDelete(File file) {
				}

				@Override
				public void onComplete(long count) {
					if (count < 0) {
						Agent.log(new JSONObject()
							.put("origin", "system")
							.put("message", String.format("파일 정리 취소.")), false);
					}
					else {
						
						scheduleDiskCleaner(period);
						
						Agent.log(new JSONObject()
							.put("origin", "system")
							.put("message", String.format("파일 정리 %d 건, 소요시간 %d ms", count, System.currentTimeMillis() - this.start)), false);
					}
				}
			};
		} catch (IOException e) {
			Agent.log(new JSONObject()
				.put("origin", "system")
				.put("message", String.format("파일 정리 오류.")), false);
		}
	}
	
}