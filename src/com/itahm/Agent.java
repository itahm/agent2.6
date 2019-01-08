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
	public static DataCleaner cleaner;
	private static Database db;
	private static NodeManager node;
	
	public static void initialize(File root) throws IOException {
		File dataRoot = new File(root, "data");
		
		dataRoot.mkdir();
		
		Setting.root(dataRoot);
		
		db = new Database();
		
		event = new EventManager(new File(dataRoot, "event"));
		
		cleaner = new DataCleaner(new File(dataRoot, "node"), 3) {

			@Override
			public void onDelete(File file) {				
			}

			@Override
			public void onComplete(long count, long elapse) {
				event()
					.put(new JSONObject()
					.put("origin", "system")
					.put("message", count < 0?
						String.format("파일 정리 취소."):
						String.format("파일 정리 %d 건, 소요시간 %d ms", count, elapse)), false);
			}};		
		
		node = new NodeManager();
		
		Calendar c = Calendar.getInstance();
		
		c.set(Calendar.DATE, c.get(Calendar.DATE) +1);
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		
		new Timer().schedule(new TimerTask() {

			@Override
			public void run() {
				int store = Setting.clean();
				
				if (store > 0) {
					cleaner.clean(store);
				}
			}}, c.getTimeInMillis(), 1000*60*60*24);
		
		System.out.format("ITAhM Agent version %s ready.\n", Setting.version);
	}
	
	public static JSONObject signIn(JSONObject data) {
		String username = data.getString("username");
		String password = data.getString("password");
		JSONObject accountData = db().get("account").json;
		
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
	
	public static JSONObject getEvent(long event) {
		return null;
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

	public static boolean isValidLicense(byte [] mac) throws SocketException {
		if (mac == null) {
			return true;
		}
		
		Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
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
		
		return false;
	}
	
	public static class Setting {
		private static int rollingInterval = 1; //minutes
		private static long requestInterval = 100000; // milliseconds
		private static JSONObject smtp = null;
		private static HTTPListener listener = null;
		private static long limit = 0;
		private static int store = 0;
		private static File dataRoot = null;
		private static long expire = 0;
		private static int top = 10;
		private static long timeout = 10000;
		public static int retry = 0;
		
		public final static String version = "3.0.1";
		
		public static long expire() {
			return expire;
		}
		
		public static void expire(long l) {
			expire = l;
		}

		public static int rollingInterval() {
			return rollingInterval;
		}
		
		public static void rollingInterval(int i) {
			rollingInterval = i;
		}
		
		public static long requestInterval() {
			return requestInterval;
		}
		
		public static void requestInterval(long l) {
			requestInterval = l;
		}
		
		public static void smtp(JSONObject jsono) {
			smtp = jsono;
		}
		
		public static JSONObject smtp() {
			return smtp;
		}
		
		public static void listener(HTTPListener httpl) {
			listener = httpl;
		}
		
		public static HTTPListener listener() {
			return listener;
		}
		
		public static void limit(long l) {
			limit = l;
		}
		
		public static long limit() {
			return limit;
		}
		
		public static void clean(int i) {
			store = i;
		}
		
		public static int clean() {
			return store;
		}
		
		public static void root(File f) {
			dataRoot = f;
		}
		
		public static File root() {
			return dataRoot;
		}
		
		public static void top(int i) {
			top = i;
		}
		
		public static int top() {
			return top;
		}
		
		public static void timeout(long l) {
			timeout = l;
		}
		
		public static long timeout() {
			return timeout;
		}
		
		public static void retry(int i) {
			retry = i;
		}
		
		public static int retry() {
			return retry;
		}
	}
	
}