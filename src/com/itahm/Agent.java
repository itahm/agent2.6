package com.itahm;

import java.io.File;
import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.mail.MessagingException;

import com.itahm.SNMPAgent;
import com.itahm.ICMPAgent;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.smtp.Message;
import com.itahm.http.HTTPListener;
import com.itahm.command.Command;
import com.itahm.http.Response;
import com.itahm.table.Account;
import com.itahm.table.Config;
import com.itahm.table.Critical;
import com.itahm.table.Device;
import com.itahm.table.Monitor;
import com.itahm.table.Profile;
import com.itahm.table.Table;

public class Agent {

	public final static String VERSION = "2.0.3.6";
	
	private static Map<Table.Name, Table> tables = new HashMap<>();
	private static Set<Integer> validIFType = null;
	private static LogFile dailyFile;
	private static SNMPAgent snmp;
	private static ICMPAgent icmp;
	private static JSONObject config;
	private static Batch batch;
	public static File root;
	private static File dataRoot;
	private static HTTPListener listener = null;
	private static int limit = 0;
	private static long expire = 0;
	
	public static void initialize(File path) throws IOException {
		root = path;
		dataRoot = new File(root, "data");
		
		dataRoot.mkdir();
		
		dailyFile = new LogFile(new File(dataRoot, "log"));
		
		tables.put(Table.Name.CONFIG, new Config(dataRoot));
		tables.put(Table.Name.ACCOUNT, new Account(dataRoot));
		tables.put(Table.Name.PROFILE, new Profile(dataRoot));
		tables.put(Table.Name.DEVICE, new Device(dataRoot));
		tables.put(Table.Name.POSITION, new Table(dataRoot, Table.Name.POSITION));
		tables.put(Table.Name.MONITOR, new Monitor(dataRoot));
		tables.put(Table.Name.ICON, new Table(dataRoot, Table.Name.ICON));
		tables.put(Table.Name.CRITICAL, new Critical(dataRoot));
		tables.put(Table.Name.SMS, new Table(dataRoot, Table.Name.SMS));
		tables.put(Table.Name.SMTP, new Table(dataRoot, Table.Name.SMTP));
		
		config = getTable(Table.Name.CONFIG).getJSONObject();
		
		if (config.has("iftype")) {
			validIFType = parseIFType(config.getString("iftype"));
		}
		
		batch = new Batch(dataRoot);
		
		batch.scheduleDiskMonitor();
		batch.scheduleUsageMonitor();
		batch.scheduleLoadMonitor();
		
		if (config.has("clean")) {
			clean(config.getInt("clean"));
		}
		
		System.out.format("ITAhM Agent version %s ready.\n", VERSION);
	}
	
	public static void setLimit(int i) {
		limit = i;
	}
	
	public static void setExpire(long l) {
		expire = l;
	}
	
	public static void setListener(HTTPListener httpl) {
		listener = httpl;
	}
	
	public static void start() throws IOException {
		snmp = new SNMPAgent(dataRoot, limit);
		
		try {
			icmp = new ICMPAgent();
		} catch (IOException e) {
			snmp.close();
			
			throw e;
		}
		
		if (config.has("health")) {
			int health = config.getInt("health");
			int timeout = Byte.toUnsignedInt((byte)(health & 0x0f)) *1000;
			int retry = Byte.toUnsignedInt((byte)((health >>= 4)& 0x0f));
			
			snmp.setHealth(timeout, retry);
			icmp.setHealth(timeout, retry);
		}
		
		if (config.has("requestTimer")) {
			long interval = config.getLong("requestTimer");
			
			snmp.setInterval(interval);
			icmp.setInterval(interval);
		}
		
		if (config.has("interval")) {
			snmp.setRollingInterval(config.getInt("interval"));
		}
		
		try {
			snmp.start();
		} catch (IOException e) {
			snmp.close();
			icmp.close();
			
			throw e;
		}
		
		icmp.start();
	}
	
	public static JSONObject signIn(JSONObject data) {
		String username = data.getString("username");
		String password = data.getString("password");
		JSONObject accountData = getTable(Table.Name.ACCOUNT).getJSONObject();
		
		if (accountData.has(username)) {
			 JSONObject account = accountData.getJSONObject(username);
			 
			 if (account.getString("password").equals(password)) {
				return account;
			 }
		}
		
		return null;
	}
	
	public static Table getTable(Table.Name name) {
		return tables.get(name);
	}
	
	public static Table getTable(String name) {
		try {
			return tables.get(Table.Name.getName(name));
		}
		catch (IllegalArgumentException iae) {
			return null;
		}
	}
	
	public static JSONObject backup() {
		JSONObject backup = new JSONObject();
		
		for (Table.Name name : Table.Name.values()) {
			backup.put(name.toString(), getTable(name).getJSONObject());
		}
		
		return backup;
	}
	
	public static void clean(int period) {
		batch.clean(period);
	}
	
	public static boolean setSMTP(JSONObject smtp) {
		try {
			String user = smtp.getString("username");
			Message msg;
			
			if (smtp.has("protocol")) {
				if ("tls".equals(smtp.getString("protocol").toLowerCase())) {
					msg = Message.getTLSInstance(smtp.getString("server"), user, smtp.getString("password"));
				}
				else {
					msg = Message.getSSLInstance(smtp.getString("server"), user, smtp.getString("password"));
				}
			}
			else {
				msg = Message.getInstance(smtp.getString("server"), user);
			}
			
			msg.to(user)
			.title("ITAhM Message")
			.body("ITAhM Message가 계정과 연결되었습니다.")
			.send();
		} catch (MessagingException me) {
			return false;
		}
		
		config("smtp", smtp);
		
		return true;
	}

	public static void setClean(int period) {
		config("clean", period);
		
		clean(period);
	}
	
	public static void setRollingInterval(int interval) {
		config("interval", interval);
		
		snmp.setRollingInterval(interval);
	}
	
	public static void setInterval(long interval) {
		config("requestTimer", interval);
		
		snmp.setInterval(interval);
		icmp.setInterval(interval);
	}
	
	public static void setHealth(int health) throws IOException {
		int timeout = Byte.toUnsignedInt((byte)(health & 0x0f)) *1000,
			retry = Byte.toUnsignedInt((byte)((health >> 4)& 0x0f));
		
		snmp.setHealth(timeout, retry);
		icmp.setHealth(timeout, retry);
		
		config("health", health);
	}
	
	public static void config(String key, Object value) {
		config.put(key, value);
		
		try {
			getTable(Table.Name.CONFIG).save();
		} catch (IOException ioe) {
			System.err.print(ioe);
		}
	}
	
	private static Set<Integer> parseIFType(String iftype) {
		Set<Integer> ts = new TreeSet<>();
		
		for (String type : iftype.split(",")) {
			try {
				ts.add(Integer.parseInt(type));
			}
			catch (NumberFormatException nfe) {}
		}
		
		return ts.size() == 0? null: ts;
	}
	
	public static void setValidIFType(String iftype) {
		validIFType = parseIFType(iftype);
		
		config("iftype", iftype);
	}
	
	public static boolean isValidIFType(int type) {
		if (validIFType == null) {
			return true;
		}
		
		return validIFType.contains(type);
	}
	
	public static void log(JSONObject event, boolean broadcast) {		
		try {
			dailyFile.write(event.put("date", Calendar.getInstance().getTimeInMillis()));
		} catch (IOException ioe) {
			System.err.print(ioe);
		}
		
		if (broadcast && config.has("smtp")) {
			JSONObject
				smtp = config.getJSONObject("smtp"),
				smtpData =getTable(Table.Name.SMTP).getJSONObject();
			Message msg;
			
			if (smtp.has("protocol")) {
				if ("tls".equals(smtp.getString("protocol").toLowerCase())) {
					msg = Message.getTLSInstance(smtp.getString("server"), smtp.getString("username"), smtp.getString("password"));
				}
				else {
					msg = Message.getSSLInstance(smtp.getString("server"), smtp.getString("username"), smtp.getString("password"));
				}
			}
			else {
				msg = Message.getInstance(smtp.getString("server"), smtp.getString("username"));
			}
			
			for (Object key : smtpData.keySet()) {
				try {
					msg.to((String)key);
				} catch (MessagingException me) {
					System.err.print(me);
				}
			}
			
			try {
				msg.title("ITAhM Message")
				.body(event.has("message")? event.getString("message"): "")
				.send();
			} catch (JSONException | MessagingException e) {
				System.err.print(e);
			}
		}
		
		if (listener != null) {
			listener.sendEvent(event, broadcast);
		}
	}
	
	public static void restore(JSONObject backup) throws JSONException, IOException {
		Table.Name name;
		Table table;
		
		stop();
		
		for (Object key : backup.keySet()) {
			name = Table.Name.getName((String)key);
			
			if (name != null) {
				table = Agent.getTable(name);
				
				if (table != null) {
					table.save(backup.getJSONObject(name.toString()));
				}
			}
		}
		
		start();
	}
	
	public static long calcLoad() {
		return snmp.calcLoad();
	}
	
	public static boolean removeSNMPNode(String ip) {
		return snmp.removeNode(ip);
	}
	
	/**
	 * 
	 * @param ip
	 * @param id search로부터 오면 null, monitor로부터 오면 device id
	 */
	public static void testSNMPNode(String ip, String id) {
		snmp.testNode(ip, id);
	}
	
	public static boolean removeICMPNode(String ip) {
		return icmp.removeNode(ip);
	}
	
	public static void testICMPNode(String ip) {
		icmp.testNode(ip);
	}
	
	/**
	 * user가 일괄설정으로 임계설정을 변경하였을때
	 * @param ip
	 * @param resource
	 * @param rate
	 * @param overwrite
	 * @throws IOException 
	 */
	public static void setCritical(String ip, String resource, int rate, boolean overwrite) throws IOException {
		snmp.setCritical(ip, resource, rate, overwrite);
	}
	
	/**
	 * user가 node의 임계설정을 변경하였을때
	 * @param ip
	 * @param critical
	 * @throws IOException 
	 */
	public static void setCritical(String ip, JSONObject critical) throws IOException {
		snmp.setCritical(ip, critical);
	}
	
	public static boolean addUSM(JSONObject usm) {
		return snmp.addUSM(usm);
	}
	
	public static void removeUSM(String usm) {
		snmp.removeUSM(usm);
	}
	
	public static boolean isIdleProfile(String name) {
		return snmp.isIdleProfile(name);
	}
	
	public static JSONObject getNodeData(JSONObject data) {
		SNMPNode node = snmp.getNode(data.getString("ip"));
		
		if (node == null) {
			return null;
		}
		
		return node.getData(data.getString("database"),
			String.valueOf(data.getInt("index")),
			data.getLong("start"),
			data.getLong("end"),
			data.has("summary")? data.getBoolean("summary"): false); 
	}
	
	public static void setInterface(JSONObject device) {
		if (!device.has("ip")) {
			return;
		}
		
		SNMPNode node = snmp.getNode(device.getString("ip"));
		
		if (node == null) {
			return;
		}
		
		node.setInterface(device.has("ifSpeed")? device.getJSONObject("ifSpeed"): new JSONObject());
	}
	
	public static JSONObject getNodeData(String ip, boolean offline) {
		return snmp.getNodeData(ip, offline);
	}
	
	public static JSONObject snmpTest() {
		return snmp.test();
	}
	
	public static JSONObject getTop(int count) {
		return snmp.getTop(count);
	}
	
	public static void resetResponse(String ip) {
		snmp.resetResponse(ip);
	}
	
	public static JSONObject getFailureRate(String ip) {
		return snmp.getFailureRate(ip);
	}
	
	public static JSONObject getEvent(long index) {
		
		return dailyFile.getEvent(index);
	}
	
	public static void setMonitor(String ip, JSONObject monitor) {
		snmp.setMonitor(ip, monitor);
	}
	
	public static String getLog(long date) throws IOException {
		byte [] bytes = dailyFile.read(date);
		
		return bytes == null? new JSONObject().toString(): new String(bytes, StandardCharsets.UTF_8.name());
	}
	
	/**
	 * snmp, icmp 서비스만 멈춤
	 */
	public static void stop() {
		if (snmp != null) {
			snmp.close();
		}
		
		if (icmp != null) {
			icmp.close();
		}
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
	
	/**
	 * agent 종료
	 */
	public static void close() {
		stop();
		
		batch.stop();
		
		for (Table.Name name : tables.keySet()) {
			try {
				tables.get(name).close();
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
		}
		
		System.out.println("ITAhM agent down.");
	}

	public static void getInformation(JSONObject jsono) {
		jsono.put("space", root == null? 0: root.getUsableSpace())
		.put("version", VERSION)
		.put("load", batch.load)
		.put("resource", snmp.getResourceCount())
		.put("usage", batch.lastDiskUsage)
		.put("java", System.getProperty("java.version"))
		.put("path", root.getAbsoluteFile().toString())
		.put("expire", expire);
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
	
	public static void main(String [] args) {
	}
	
}