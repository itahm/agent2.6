package com.itahm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Calendar;

import javax.mail.MessagingException;

import com.itahm.http.HTTPListener;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.smtp.Message;
import com.itahm.util.DailyFile;
import com.itahm.util.Util;

public class EventManager extends DailyFile {
	enum SMTP {
		DEFAULT, TLS, SSL;
	};
	
	private final File backup;
	private JSONObject log;	
	private long index = 0;	
	private SMTP type;
	private String
		server,
		user,
		password;
	private boolean smtp = false;
	
	public EventManager(File eventRoot) throws IOException {
		super(eventRoot);
		
		this.backup = new File(eventRoot, "backup");
		
		if (!super.file.isFile() || ((this.log = Util.getJSONFromFile(super.file)) == null && !restore())) {
			this.log = new JSONObject();
			
			save();
		}
		
		for (Object key : this.log.keySet()) {
			try {
				this.index = Math.max(this.index, Long.parseLong((String)key));
			}
			catch (NumberFormatException nfe) {
				System.err.print(nfe);
			}
		}
		
		setSMTP(Agent.Config.smtp());
	}

	public void sendMail(String title, String body, Object... to) throws MessagingException {
		Message message;
		
		switch (this.type) {
		case TLS:
			message = Message.getTLSInstance(this.server, this.user, this.password);
			
			break;
		case SSL:
			message = Message.getSSLInstance(this.server, this.user, this.password);
			
			break;
		default:
			message = Message.getInstance(this.server, this.user);	
		}
		
		message.title(title);
		message.body(body);
		
		for (Object email : to) {
			message.to((String)email);
		}
		
		message.send();
	}
	
	public void setSMTP(JSONObject smtp) {
		if(smtp == null) {
			this.smtp = false;
		}
		else {
			this.user = smtp.getString("username");
			this.server = smtp.getString("server");
			
			if (smtp.has("protocol")) {
				this.password = smtp.getString("password");
				
				if ("tls".equals(smtp.getString("protocol").toLowerCase())) {
					this.type = SMTP.TLS;
				}
				else {
					this.type = SMTP.SSL;
				}
			}
			else {
				this.type = SMTP.DEFAULT;
				
				this.password = null;
			}
			
			try {
				sendMail("ITAhM Message", "ITAhM Message가 계정과 연결되었습니다.", this.user);
				
				this.smtp = true;
				
			} catch (MessagingException e) {
			}
		}
	}
	
	public JSONObject getEvent(String index) {
		if (this.log.has(index)) {
			return this.log.getJSONObject(index);
		}
		
		return null;
	}
	
	public String getLog(long date) throws IOException {
		byte [] bytes = super.read(date);
		
		return bytes == null? new JSONObject().toString(): new String(bytes, StandardCharsets.UTF_8.name());
	}
	
	public synchronized void put(JSONObject event, boolean broadcast) {
		String index = Long.toString(this.index++);
		
		event.put("event", index);
		event.put("date", Calendar.getInstance().getTimeInMillis());
		
		try {
			if (super.roll()) {
				this.log.clear();
			}
		} catch (IOException ioe) {
			System.err.print(ioe);
		}
		
		this.log.put(index, event);
		
		try {
			save();
		} catch (IOException ioe) {
			System.err.print(ioe);
		}
		
		if (broadcast && this.smtp) {
			try {
				sendMail("ITAhM Message",
					event.has("message")? event.getString("message"): "",
					Agent.db().get("email").json().keySet().toArray());
			} catch (MessagingException | JSONException e) {
				System.err.print(e);
			}
		}
		
		HTTPListener listener = Agent.Config.server();
		
		if (listener != null) {
			listener.sendEvent(event, broadcast);
		}
	}
	
	private void save() throws IOException {
		byte [] ba = this.log.toString().getBytes(StandardCharsets.UTF_8);
		
		super.write(ba);
		
		DailyFile.write(this.backup, ba);
	}
	
	private boolean restore() throws IOException {
		if (!this.backup.isFile()) {
			return false;
		}
		
		Calendar c = Calendar.getInstance();
		long today = Util.trimDate(c).getTimeInMillis();
		
		c.setTimeInMillis(this.backup.lastModified());
		
		if (Util.trimDate(c).getTimeInMillis() != today) {
			return false;
		}
		
		try (FileOutputStream fos = new FileOutputStream(super.file)) {
			fos.write(Files.readAllBytes(this.backup.toPath()));
			
			this.log = Util.getJSONFromFile(super.file);
			
			if (this.log == null) {
				return false;
			}
		}
		
		return true;
	}
	
}
