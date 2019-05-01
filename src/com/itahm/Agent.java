package com.itahm;

import java.io.File;
import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.Timer;
import java.util.TimerTask;

import javax.mail.MessagingException;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.smtp.Message;
import com.itahm.util.DataCleaner;
import com.itahm.http.HTTPListener;
import com.itahm.command.Command;
import com.itahm.database.Database;
import com.itahm.http.Response;

public class Agent {
	
	private static EventManager event;
	private static Database db;
	private static NodeManager node;
	
	private static DataCleaner cleaner = new DataCleaner() {
		@Override
		public void onDelete(File file) {				
		}

		@Override
		public void onComplete(long count, long elapse) {
			if (count != 0) {
				event.put(new JSONObject()
					.put("origin", "system")
					.put("name", "System")
					.put("status", true)
					.put("message", count < 0?
						String.format("파일 정리 취소."):
						String.format("파일 정리 %d 건, 소요시간 %d ms", count, elapse)), false);
			}
		}};
	
	private static Timer timer = new Timer("ITAhM Cleaner");
	private static TimerTask clean;
	public static boolean ready = false;
	
	public static boolean start() throws IOException {
		if(Config.root == null) {
			return false;
		}
		
		db = new Database();
		
		Config.initialize(db.get("config").json());
		
		event = new EventManager(new File(Config.root, "event"));
		
		Calendar c = Calendar.getInstance();
		
		c.set(Calendar.DATE, c.get(Calendar.DATE) +1);
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		
		timer.schedule(clean = new TimerTask() {

			private final File f = new File(Config.root, "node");
			
			@Override
			public void run() {
				if (Config.clean() > 0) {
					cleaner.clean(f, 3, Config.clean());
				}
			}}, c.getTimeInMillis(), 1000*60*60*24);
		
		System.out.format("ITAhM Agent version %s ready.\n", Config.version);
		
		node = new NodeManager();
		
		node.start();
		
		return ready = true;
	}
	
	public static void stop() throws IOException {
		ready = false;
		
		clean.cancel();
		
		node.stop();
		
		System.out.println("ITAhM Agent destroyed.");
	}
	
	public static JSONObject signIn(JSONObject data) {
		String username = data.getString("username");
		String password = data.getString("password");
		JSONObject accountData = db().get("account").json();
		
		if (accountData.has(username)) {
			 JSONObject account = accountData.getJSONObject(username);
			 
			 if (account.getString("password").equals(password)) {
				return account;
			 }
		}
		
		return null;
	}
	
	public static void sendMail(String user, String server, String protocol, String password) throws MessagingException {
		Message msg;
		
		if (protocol != null) {
			if ("tls".equals(protocol.toLowerCase())) {
				msg = Message.getTLSInstance(server, user, password);
			}
			else {
				msg = Message.getSSLInstance(server, user, password);
			}
		}
		else {
			msg = Message.getInstance(server, user);
		}
		
		msg
			.to(user)
			.title("ITAhM Message")
			.body("ITAhM Message가 계정과 연결되었습니다.")
			.send();
	}
	
	public static JSONObject getEvent(String id) {
		return event.getEvent(id);
	}
	
	public static Database db() {
		return db;
	}
	
	public static NodeManager node() {
		return node;
	}
	
	public static EventManager event() {
		return event;
	}
	
	public static boolean request(JSONObject request, Response response) {		
		Command command = Command.valueOf(request.getString("command"));
		
		if (command == null) {
			return false;
		}
		
		try {
			command.execute(request, response);
		} catch (JSONException jsone) {
			response.write(new JSONObject().
				put("error", jsone.getMessage()).toString());
			
			response.setStatus(Response.Status.BADREQUEST);
			
		} catch (IOException ioe) {
			response.write(new JSONObject().
				put("error", ioe.getMessage()).toString());
			
			response.setStatus(Response.Status.SERVERERROR);
		}
		
		return true;
	}

	public static boolean isValidLicense(byte [] mac) {
		if (mac == null) {
			return true;
		}
		
		Enumeration<NetworkInterface> e;
		
		try {
			e = NetworkInterface.getNetworkInterfaces();
		
			NetworkInterface ni;
			byte [] ba;
			
			while(e.hasMoreElements()) {
				ni = e.nextElement();
				
				if (ni.isUp() && !ni.isLoopback() && !ni.isVirtual()) {
					 ba = ni.getHardwareAddress();
					 
					 if(ba!= null) {
						 if (Arrays.equals(mac, ba)) {
							 return true; 
						 }
					 }
				}
			}
		} catch (SocketException se) {
		}
		
		return false;
	}
	
	public static class Config {
		private static int saveInterval = 1; //minutes
		private static long snmpInterval = 10000; // milliseconds
		private static JSONObject smtp = null;
		private static Server server = null;
		private static long limit = 0;
		private static int store = 0;
		private static File root = null;
		private static long expire = 0;
		private static int top = 5;
		private static int timeout = 5000;
		private static int retry = 2;
		public final static String version = "3.0.6";
		
		public static void health(int i, boolean b) {
			timeout = Byte.toUnsignedInt((byte)(i & 0x0f)) *1000;
			retry = Byte.toUnsignedInt((byte)((i >> 4)& 0x0f));
		
			if (b) {
				node().setHealth(timeout, retry);
			}
		}
		
		public static long expire() {
			return expire;
		}
		
		public static void expire(long l) {
			expire = l;
		}

		public static int saveInterval() {
			return saveInterval;
		}
		
		public static void saveInterval(int i) {
			saveInterval = i;
		}
		
		public static long snmpInterval() {
			return snmpInterval;
		}
		
		public static void snmpInterval(long l) {
			snmpInterval = l;
		}
		
		public static void smtp(JSONObject jsono, boolean set) {
			smtp = jsono;
			
			if (set) {
				event.setSMTP(smtp);
			}
		}
		
		public static JSONObject smtp() {
			return smtp;
		}
		
		public static void server(Server itahm) {
			server = itahm;
		}
		
		public static HTTPListener server() {
			return server;
		}
		
		public static void limit(long l) {
			limit = l;
		}
		
		public static long limit() {
			return limit;
		}
		
		public static void clean(int i, boolean clean) {
			store = i;
			
			if (clean) {
				cleaner.clean(new File(Config.root, "node"), 3, i);
			}
		}
		
		public static int clean() {
			return store;
		}
		
		public static void root(File f) {
			File dataRoot = new File(f, "data");
			
			dataRoot.mkdir();
			
			root = dataRoot;
		}
		
		public static File root() {
			return root;
		}
		
		public static void top(int i) {
			top = i;
		}
		
		public static int top() {
			return top;
		}
		
		public static int timeout() {
			return timeout;
		}
		
		public static int retry() {
			return retry;
		}
		
		private static void initialize(JSONObject config) {
			String key;
			for (Object o : config.keySet()) {
				key = (String)o;
				
				switch((key).toLowerCase()) {
				case "health":
					health(config.getInt(key), false);
					
					break;
				case "snmpInterval":
					snmpInterval(config.getLong(key));
					
					break;
				case "clean":
					clean(config.getInt(key), false);
					
					break;
				case "saveInterval":
					saveInterval(config.getInt(key));
					
					break;
				case "top":
					top(config.getInt(key));
					
					break;
				case "smtp":
					smtp(config.getJSONObject(key), false);
					
					break;
				}
			}
		}
	}
	
	public static void main(String ... args) {
	}
	
}