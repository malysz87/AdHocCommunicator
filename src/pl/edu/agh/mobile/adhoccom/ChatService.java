package pl.edu.agh.mobile.adhoccom;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pl.edu.agh.mobile.adhoccom.chatprotocol.ChatMessageException;
import pl.edu.agh.mobile.adhoccom.chatprotocol.Message;
import pl.edu.agh.mobile.adhoccom.flooder.AdHocFlooder;
import pl.edu.agh.mobile.adhoccom.flooder.BroadcastAdHocFlooder;
import pl.edu.agh.mobile.adhoccom.flooder.MessageListener;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;


public class ChatService extends Service implements MessageListener {

	public static final String MESSAGE_RECEIVED = "MESSAGE_RECEIVED";
	public static final String NEW_MSG_ID_EXTRA = "pl.edu.agh.mobile.adhoccom.newMsgId";
	public static final String NEW_MSG_GROUP_EXTRA = "pl.edu.agh.mobile.adhoccom.newMsgGroup";
	public static final String DEFAULT_GROUP = "default";
	private static final String LOGGER_TAG = "ChatService";
	private final IBinder mBinder = new ChatServiceBinder();
	private ListeningThread mListeningThread;
	private ChatDbAdapter mDbAdapter;
	private AdHocFlooder adHocFlooder;
	private Map<String, String> chatGroups = new HashMap<String, String>();
	private AppConfig config;
	private BroadcastReceiver mBroadcastReceiver;
	private boolean reload;
	private ServerConnection serverConnection;

	
	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	private void startService() {
		mListeningThread = new ListeningThread();
		mListeningThread.start();
	}

	@Override
	public void onCreate() {
		super.onCreate();
		config = AppConfig.getInstance();
		mDbAdapter = (new ChatDbAdapter(this)).open();
		try {
			adHocFlooder = new BroadcastAdHocFlooder(config.getPort(), config.getAddress(),
												 config.getFlooderHistorySize(), this);
		}catch(UnknownHostException e) {
			Log.e(LOGGER_TAG, e.getMessage());
		}
		serverConnection = ServerConnection.getInstance(this, adHocFlooder);
		IntentFilter filter = new IntentFilter(AppConfig.CONFIG_CHANGED);
		registerReceiver(new ConfigChangedReceiver(), filter);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		mDbAdapter.close();
		adHocFlooder.stop();
		serverConnection.disconnect();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (startId == 1) { // start listening threads only once
			startService();
		}
		return Service.START_STICKY;
	}
	
	public void sendMessage(Message msg) throws IOException {
		announceMessage(msg);
		msg.encrypt(chatGroups.get(msg.getGroupName()));
		adHocFlooder.send(msg.toByteArray());
		serverConnection.send(msg);
	}

	private void announceMessage(Message msg) {
		long newMsgId = mDbAdapter.addNewMessage(msg);
		Intent intent = new Intent(MESSAGE_RECEIVED);
		intent.putExtra("newMsgId", newMsgId);
		intent.putExtra(NEW_MSG_ID_EXTRA, newMsgId);
		intent.putExtra(NEW_MSG_GROUP_EXTRA, msg.getGroupName());
		sendBroadcast(intent);
	}

	public List<String> getChatGroups() {
		return new ArrayList<String>(chatGroups.keySet());
	}
	
	/**
	 * Join given group. Starts receiving massages for given group
	 * @param groupName
	 * @param groupCode
	 */
	public void joinGroup(String groupName, String groupCode) {
		if (!chatGroups.containsKey(groupName)) {
			chatGroups.put(groupName, groupCode);
		}
	}

	/**
	 * Leave given group. Stops receiving messages for given group
	 * @param groupName
	 */
	public void leaveGroup(String groupName) throws ChatServiceException {
		if (ChatService.DEFAULT_GROUP.equals(groupName)) {
			throw new ChatServiceException(getString(R.string.leave_default_group_error));
		}
		chatGroups.remove(groupName);
	}
	
	public boolean isAttachedToGroup(String groupName) {
		return chatGroups.containsKey(groupName);
	}

	public class ChatServiceBinder extends Binder {
		public ChatService getService() {
			return ChatService.this;
		}
	}

	private class ListeningThread extends Thread {
		@Override
		public void run() {
			ChatService.this.reload = true;
			while(ChatService.this.reload) {
				ChatService.this.reload = false;
				ChatService.this.adHocFlooder.start();
			}
		}
	}

	@Override
	public void onMessageReceive(DatagramPacket packet) {
		try {
			Message msg = Message.parseFrom(packet);
			serverConnection.send(msg);
			onMessageReceive(msg);
		} catch (ChatMessageException e) {
			Log.i(LOGGER_TAG, "Exception while parsing message: " + e.getMessage());
		}

	}
	
	public void onMessageReceive(Message msg) {
		String pass = chatGroups.get(msg.getGroupName());
		if (msg.canBeDecrypted(pass)) {
			msg.decrypt(pass);
			announceMessage(msg);
		}
	}

	private class ConfigChangedReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			BroadcastAdHocFlooder flooder = (BroadcastAdHocFlooder)ChatService.this.adHocFlooder;
			AppConfig config = AppConfig.getInstance();

			if (intent.hasExtra(AppConfig.PORT_ID) || intent.hasExtra(AppConfig.ADDRESS_ID)) {
				try {
					flooder.setAddress(InetAddress.getByName(config.getAddress()));
					flooder.setPort(config.getPort());
					ChatService.this.reload = true;
					ChatService.this.adHocFlooder.stop();
				} catch (UnknownHostException e) {
					Log.e(LOGGER_TAG, e.getMessage());
				}
			}
		}
		
	}
}
