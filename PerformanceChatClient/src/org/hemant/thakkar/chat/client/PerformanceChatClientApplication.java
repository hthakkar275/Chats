package org.hemant.thakkar.chat.client;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * An performance test chat client main application. It is intended to test the
 * chat server's performance to handle large number of simultaneous chat session.
 *
 * To support large number of chat session from single running JVM without having
 * to run potentially unsustainable number of threads the test application runs 
 * several groups of sessions. Each session group operates in a separate thread
 * and each session group runs a number of chat sessions.  
 * 
 * The group count and the sessions count per group are configurable by following
 * VM arguments
 * 
 * SessionGroups:  Number of session groups (Default:  2)
 * SessionsPerGroup:  Number of chat session in each group (Default: 10)
 * 
 * The total number of threads run by the application is the count specified 
 * by SessionGroups as each session group operates in its own thread. 
 * 
 * The total number of chat sessions is the SessionsPerGroup * SessionGroups. So in
 * the case of the default values (SessionGroups = 2 and SessionsPerGroup = 10), the
 * total number of chat sessions is 20. In case of SessionGroups = 20 and SessionsPerGroup = 100,
 * the total number of chat sessions is 2,000.
 * 
 * Here is how it works
 * 
 * 1.  Main application starts a thread pool
 * 2.  Each thread in the thread pool runs a session group.
 * 3.  Each session group starts number of chat sessions
 * 4.  Each session creates a single selector to listen for message receipts from the chat
 *     sessions create in #3.
 * 5.  Each session group sends 10 messages with random delay of up to two seconds between
 *     each message transmission
 * 
 * The java source code file for the performance chat client contains all java 
 * classes in to this one single source code file. 
 * 
 * To run the Performance Chat Client it is only necessary to compile this single java source
 * code and run it.
 * 
 * 1.  Copy this PerformanceChatClientApplication.java file into a directory
 * 2.  Change to the directory where the PerformanceChatClientApplication.java is copied
 * 2.  Compile with JDK 7 or higher using command:  javac -d . PerformanceChatClientApplication.java
 * 3.  Run the chat client with command: java -cp . org.hemant.thakkar.chat.client.PerformanceChatClientApplication
 * 4.  Monitor the PerformanceChatClientLog.xx.txt where xx = [0-9]
 * 5.  The output (i.e. stauts and received messages) are sent to file named PerformanceChatClient.out
 *     in same directory as where the application is running.
 * 
 * By default the client will listen on loopback interface (127.0.0.1) on TCP port 7000. 
 * However this default using console input commands to set the server host/IP address and
 * TCP port. The default TCP address can be specified as JVM arguments when launching the
 * chat client application. An example of how to specify a different interface and port. 
 * The server address is specified as host:port or IP:port. 
 * 
 * java -cp . -Dserver=192.168.2.74:7005 org.hemant.thakkar.chat.client.PerformanceChatClientApplication
 * 
 * @author Hemant Thakkar
 *
 */
public class PerformanceChatClientApplication {

	/**
	 * Setup java util logging. The chat server log is created in the same directory where 
	 * the chat application runs. The log files are rotating log files up to 1 MB in size
	 * and up to 10 files before the first file is overwritten.
	 * 
	 * Log file names patter is PerformanceChatClientLog.xx.txt where xx is [0-9]
	 * 
	 * By default INFO and higher order logging is enabled.
	 */
	static {
        try {
            System.setProperty("java.util.logging.SimpleFormatter.format",
                    "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS.%1$tL %4$-7s - %2$s - %5$s %6$s%n");

            // Set up for rotating log files up to 10 each of 1MB size.
            final FileHandler fileHandler = new FileHandler("PerformanceChatClientLog.%g.txt", 1024 * 1024, 10, true);
            fileHandler.setLevel(Level.INFO);
            fileHandler.setFormatter(new SimpleFormatter());

            final Logger rootLogger = Logger.getLogger("");
            rootLogger.setLevel(Level.INFO);
            rootLogger.addHandler(fileHandler);
        } catch (Exception e) {
        		System.out.println("Failed to setup logging configuration");
        }
	}
	
	private static final Logger logger = Logger.getLogger(PerformanceChatClientApplication.class.getName());

	public static void main(String[] args) {
		logger.info("Starting performance chat client");
		
		// Get the session groups and sessions per group configuration
		// Total number of threads = SessionGroups
		// Total number of chat sessions = SessionGroups * SessionsPerGroup
		int groupCount = Integer.parseInt(System.getProperty("SessionGroups", "2"));
		int sessionsPerGroup = Integer.parseInt(System.getProperty("SessionsPerGroup", "10"));
		
		// Start threadpool to run each sesssion group.
		ExecutorService executorService = Executors.newFixedThreadPool(groupCount);
		
		// Maintain the Future reference for each session group
		Map<String, Future<Boolean>> userSessionGroupFutures = new HashMap<>();
		
		// Start the file based output. 
		UserOutputStrategy userOutputStrategy = new TextUserOutput();
		userOutputStrategy.start();

		userOutputStrategy.sendStatusOutput("Starting performance test with " 
				+ groupCount + " groups of " + sessionsPerGroup + " sessions per group");

		String serverAddress = System.getProperty("server", "0.0.0.0:7000");
		String[] serverAddressParts = serverAddress.trim().split(":");
		String serverIPAddress = "127.0.0.1";
		int serverPort = Integer.parseInt(serverAddressParts[1]);
		if (!serverAddressParts[0].equals("0.0.0.0")) {
			serverIPAddress = serverAddressParts[0];
		} 
		UserConfiguration.getInstance().setServerIPAddress(serverIPAddress);
		UserConfiguration.getInstance().setServerPort(serverPort);
		
		// Create session group Callable instances and submit to the threadpool
		String localHost = "";
		try {
			localHost = InetAddress.getLocalHost().getHostName();
			logger.info("Will use host name as session group prefix: " + localHost);
		} catch (UnknownHostException e1) {
			localHost = UUID.randomUUID().toString();
			logger.warning("Couldn't determnine local host name, so will use UUID as session group prefix: " + localHost);
		}
		boolean sendMessages = false;
		String groupNamePrefix = localHost + "-SessionGroup-";
		for (int index = 0; index < groupCount; index++) {
			String groupName = groupNamePrefix + index;
			
			// We want to have only one session in one group send messages
			// At this point we don't have control of the individual sessions,
			// but we can enable last session group to direct one of its
			// session to send messages.
			if (index == (groupCount - 1)) {
				sendMessages = true;
			}

			UserSessionGroup userSessionGroup = 
					new UserSessionGroup(groupNamePrefix + index, sessionsPerGroup, sendMessages, userOutputStrategy);
			Future<Boolean> future = executorService.submit(userSessionGroup);
			userSessionGroupFutures.put(groupName, future);
		}
		
		// Monitor for the completion of all session groups. In other
		// words wait for all session groups to push 10 messages per chat session for every
		// session in its group.
		try {
			int completedCount = 0;
			while (completedCount < groupCount) {
				Thread.sleep(2000);
				Set<String> runningGroupNames = userSessionGroupFutures.keySet();
				for (String groupName : runningGroupNames) {
					Future<Boolean> userSessionGroupFuture = userSessionGroupFutures.get(groupName);
					if (userSessionGroupFuture.isDone()) {
						userOutputStrategy.sendStatusOutput("Group " + groupName 
								+ " completed successfuly: " + userSessionGroupFuture.get());
						completedCount++;
					}
				}
			}
			executorService.shutdown();
			executorService.awaitTermination(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			logger.severe("Error while running session groups. " + e.getMessage());
		}
		userOutputStrategy.sendStatusOutput("Completed performance test with " 
				+ groupCount + " groups of " + sessionsPerGroup + " sessions per group");
	}

}

/**
 * Session Group manager. Each session group creates number of chat sessions,
 * pushes 10 messages on each chat session with a random delay of up to two seconds.
 * 
 * The session group also starts a thread to receive messages. Once it pushes 10 
 * messages per chat session for all chat session, it sleeps for sometime to allow
 * for all messages to be received from and then exits.
 * 
 * 
 * @author Hemant Thakkar
 *
 */
class UserSessionGroup implements Callable<Boolean> {

	private static Logger logger = Logger.getLogger(UserSessionGroup.class.getName());
	
	private int sessionCount;
	private String groupName;
	private UserOutputStrategy userOutputStrategy;
	private boolean sendMessages;
	
	public UserSessionGroup(String groupName, int sessionCount, boolean sendMessages,
			UserOutputStrategy userOutputStrategy) {
		this.sessionCount = sessionCount;
		this.groupName = groupName;
		this.userOutputStrategy = userOutputStrategy;
		this.sendMessages = sendMessages;
	}
	
	@Override
	public Boolean call() throws Exception {
		Boolean result = true;
		userOutputStrategy.sendStatusOutput("Starting session group " + groupName);
		try {
			// Seed the randomizer with group name so as to get some variablity.
			Random random = new Random(groupName.hashCode());
			
			// Create the chat sessions.
			List<UserSession> userSessions = new ArrayList<>();
			for (int index = 0; index < sessionCount; index++) {
				String username = groupName + "_" + index;
				UserSession userSession = new UserSession(username, userOutputStrategy);
				userSessions.add(userSession);
			}

			// Establish the chat session with server following the chat session protocol
			for (UserSession userSession : userSessions) {
				if (!userSession.establishSession()) {
					userOutputStrategy.sendStatusOutput("User session " + userSession.getUsername() + " failed to establish");
				} 
				int randomSleep = random.nextInt(2000);
				while (randomSleep < 1500) {
					randomSleep = random.nextInt(2000);
				}
				Thread.sleep(randomSleep);
			}
			
			// Spawn a message receiver in separate thread. This thread will listen
			// for messages from all chat sessions contained int his group.
			ExecutorService executorService = Executors.newSingleThreadExecutor();
			MessageReceiver messageReciever = new MessageReceiver(userSessions, userOutputStrategy);
			Future<Boolean> messageReceiverFuture = executorService.submit(messageReciever);
			
			Thread.sleep(30000);

			// Send messages from one session only if this session group is enabled
			// to send messages. If yes, then instruct the last session to send 
			// messages with 2 to 5 second random delay between each message transmission.
			if (sendMessages) {
				userOutputStrategy.sendStatusOutput("Starting to send messages from session group " + groupName);
				UserSession messageSendingSession = userSessions.get(userSessions.size() - 1);
				for (int index = 0; index < 1; index++) {
					String message = "User Session " + messageSendingSession.getUsername() + " message number " + index;
					messageSendingSession.sendMessage(message);
					int randomSleep = random.nextInt(5000);
					while (randomSleep < 2000) {
						randomSleep = random.nextInt(5000);
					}
					Thread.sleep(randomSleep);
				}
				userOutputStrategy.sendStatusOutput("Completed sending messages for session group " + groupName);
			}
			
			//Wait for some time for all message receipts to catch=up
			Thread.sleep(60000);
			messageReciever.setExit(true);
			boolean messsageReceiverStatus = messageReceiverFuture.get();
			
			userOutputStrategy.sendStatusOutput("Shutting down sessions for " + groupName);
			userOutputStrategy.sendStatusOutput("Message reciever for " + groupName + " returned " + messsageReceiverStatus);

			for (UserSession userSession : userSessions) {
				userSession.shutdown();
				int randomSleep = random.nextInt(2000);
				while (randomSleep < 1500) {
					randomSleep = random.nextInt(2000);
				}
				Thread.sleep(randomSleep);
			}
		} catch (Exception e) {
			logger.severe("Error in session group " + groupName + ": " + e.getMessage());
			result = false;
		}
		return result;
	}
	
}

/**
 * Chat session to the server. It provides means to specify message to send to the
 * server for broadcast to other users or unicast to a specific user.
 * 
 * Unlike the UserSession in the interactive chat client, this UserSession does
 * not encapsulate individual MessageReceiver per session. Instead the messages
 * received on each chat session are received in a single message receiver per
 * session group
 * 
 * @author Hemant Thakkar
 *
 */
class UserSession {
	
	private static Logger logger = Logger.getLogger(UserSession.class.getName());
	
	private SocketChannel socketChannel;
	private Selector selector;
	private ByteBuffer sendBuffer;
	private ByteBuffer recvBuffer;
	private String username;
	private UserOutputStrategy userOutputStrategy;
	private boolean chatSessionEstablished;
	
	public UserSession(String username, UserOutputStrategy userOutputStrategy) {
		this.username = username;
		this.userOutputStrategy = userOutputStrategy;
		this.chatSessionEstablished = false;
		sendBuffer = ByteBuffer.allocate(256);
		recvBuffer = ByteBuffer.allocate(256);
	}

	public SocketChannel getSocketChannel() {
		return this.socketChannel;
	}

	public String getUsername() {
		return this.username;
	}
	
	/**
	 * Implements the chat session handshake protocol with server.
	 * 
	 * 1.  Open a TCP connection to the server
	 * 2.  Send the previously configured username to the server
	 * 3.  Wait for server to send a "ready" message
	 * 4.  Chat session established once "ready" is received from server.
	 * 
	 * Note username and server TCP address are required to already have been 
	 * configured before chat session establishment is attempted.
	 * 
	 * @return true if session successfully established, false otherwise.
	 */
	public boolean establishSession() {
		try {
			socketChannel = SocketChannel.open();
			String serverIPAddress = UserConfiguration.getInstance().getServerIPAddress();
			int serverPort = UserConfiguration.getInstance().getServerPort();
			if (serverIPAddress == null) {
				throw new Exception("Server address is not defined");
			}
			if (username == null) {
				throw new Exception("Username is not defined");
			}
			socketChannel.socket().connect(new InetSocketAddress(serverIPAddress, serverPort));
			socketChannel.socket().setTcpNoDelay(true);
//			socketChannel.configureBlocking(false);
			socketChannel.socket().getOutputStream().write(username.getBytes());
//			sendBuffer.put(username.getBytes());
//			sendBuffer.flip();
//			socketChannel.write(sendBuffer);
//			sendBuffer.clear();
			//System.out.println("Sent the username " + username + " with " + bytesWritten + " bytes");
//			selector = Selector.open();
//			socketChannel.register(selector, SelectionKey.OP_READ);
//			selector.select();
//			int readBytes = socketChannel.read(recvBuffer);
			byte[] readyBytes = new byte[8];
			int readBytes = socketChannel.socket().getInputStream().read(readyBytes);
			if (readBytes == -1) {
				throw new Exception("Server connection unexpectedly closed.");
			} else if (readBytes > 0) {
//				recvBuffer.flip();
//				byte[] readyMessage = new byte[recvBuffer.limit()];
//				recvBuffer.get(readyMessage);
//				String ready = new String(readyMessage);
				String ready = new String(readyBytes);
				if (ready.contains("ready")) {
					userOutputStrategy.sendStatusOutput("Chat session established. Ready to send/recv messages.");
					recvBuffer.clear();
					chatSessionEstablished = true;
				} else {
					throw new Exception("Server connected, but failed to establish chat session with the server.");
				}
			}
		} catch (IOException ioe) {
			logger.severe("Error while attempt to establish chat session to the server. " + ioe.getMessage());
			userOutputStrategy.sendStatusOutput("Error while attempt to establish chat session to the server.");
		} catch (Exception e) {
			userOutputStrategy.sendStatusOutput(e.getMessage());
			logger.severe(e.getMessage());
		}
		if (!chatSessionEstablished) {
			closeResources();
		} 
		return chatSessionEstablished;
	}
	
	public void shutdown() {
		closeResources();
	}
	
	private void closeResources() {
		try {
			if (selector != null) {
				selector.close();
			}
			if (socketChannel != null) {
				socketChannel.close();
			}
		} catch (IOException e) {
			logger.severe("Failed to close resources");
		} 
	}

	/**
	 * Send a message to the server. Message is suffixed with a newline
	 * character to signify message delimeter. Server expects a newline
	 * character as individual message separator.
	 * 
	 * Note that this method is invoked in the context of the user input
	 * thread which is the main applicaiton thread.
	 * 
	 * @param message
	 */
	public void sendMessage(String message) {
		if (chatSessionEstablished) {
			try {
				if (message.length() > 255) {
					message = message.substring(0, 254);
				}
				message += "\n";
				sendBuffer.put(message.getBytes());
				sendBuffer.flip();
				socketChannel.write(sendBuffer);
				sendBuffer.clear();
			} catch (IOException ioe) {
				logger.severe("Error while sending message to the server." + ioe.getMessage());
				userOutputStrategy.sendStatusOutput("Error while sending message to the server.");
			}
		} else {
			logger.severe("Chat session is not established");
			userOutputStrategy.sendStatusOutput("Chat session is not established");
		}
	}
	
}


interface UserOutputStrategy {
	public void start();
	public void shutdown();
	public void sendStatusOutput(String output);
	public void sendMessageOutput(String output);
}

/**
 * Concrete file-based implementation of the UserOutputStrategy. The output is
 * written to a text file. The idea is that the user would enter the input command
 * and messages on one console window while tailing the file to view the
 * output.
 * 
 * The file is created, if absent, in the current directory where the application
 * is running. If already present, the file is opened for appending. The filename
 * is username.out where username is the username supplied for the chat session.
 * 
 * @author Hemant Thakkar
 *
 */
class TextUserOutput implements UserOutputStrategy {
	
	private static Logger logger = Logger.getLogger(TextUserOutput.class.getName());
	private BufferedWriter outputFileWriter;
	
	@Override
	public void start() {
		try {
			Path outputfilePath = Paths.get(".", "PerformanceChatClient.out");
			if (!Files.exists(outputfilePath)) {
				Files.createFile(outputfilePath);
			}
			outputFileWriter = Files.newBufferedWriter(outputfilePath, StandardOpenOption.APPEND);
		} catch (IOException ioe) {
			logger.severe("Failed to open file for text-based user output destination");
		}
	}

	@Override
	public void shutdown() {
		if (outputFileWriter != null) {
			try {
				outputFileWriter.close();
			} catch (IOException e) {
				logger.severe("Failed to close file for text-based user output destination");
			}
		}
	}
		
	public void sendStatusOutput(String message) { 
		sendOutput("status: " + message);
	}

	public void sendMessageOutput(String message) { 
		sendOutput(message);
	}

	private void sendOutput(String message) {
		try {
			if (outputFileWriter != null) {
				outputFileWriter.write(message);
				outputFileWriter.newLine();
				outputFileWriter.flush();
			} else {
				logger.severe("File for text-based output is not open");
			}
		} catch (IOException e) {
			logger.severe("Failed to write to file for text-based user output destination");
		}
	}

}

class UserConfiguration {
	private static UserConfiguration self = new UserConfiguration();
	private String username;
	private String serverIPAddress;
	private int serverPort;
	private List<String> blockedUsers;
	
	private UserConfiguration() {
		this.blockedUsers = new ArrayList<String>();
	}
	
	public static UserConfiguration getInstance() {
		return self;
	}
	
	public void blockUser(String user) {
		this.blockedUsers.add(user);
	}
	
	public void unblockUser(String user) {
		this.blockedUsers.remove(user);
	}
	
	public void setUsername(String username) {
		this.username = username;
	}
	
	public String getUsername() {
		return this.username;
	}
	
	public void setServerIPAddress(String serverIPAddress) {
		this.serverIPAddress = serverIPAddress;
	}
	
	public String getServerIPAddress() {
		return this.serverIPAddress;
	}
	
	public void setServerPort(int serverPort) {
		this.serverPort = serverPort;
	}
	
	public int getServerPort() {
		return this.serverPort;
	}
	
	public boolean isUserBlocked(String username) {
		return blockedUsers.contains(username);
	}
}

class MessageReceiver implements Callable<Boolean> {

	private static Logger logger = Logger.getLogger(MessageReceiver.class.getName());
	private boolean exit;
	private List<UserSession> userSessions;
	private UserOutputStrategy userOutputStrategy;
	private Boolean error;
	private ByteBuffer recvBuffer;
	
	public MessageReceiver(List<UserSession> userSessions, 
			UserOutputStrategy userOutputStrategy) {
		this.error = false;
		this.exit = false;
		this.recvBuffer = ByteBuffer.allocate(256);
		this.userSessions = userSessions;
		this.userOutputStrategy = userOutputStrategy;
	}
	
	public void setExit(boolean exit) {
		this.exit = exit;
	}
	
	@Override
	public Boolean call() {
		try {
			Selector selector = Selector.open();
			for (UserSession userSession : userSessions) {
				userSession.getSocketChannel().configureBlocking(false);
				userSession.getSocketChannel().register(selector, SelectionKey.OP_READ, userSession.getUsername());
			}
			while (!exit) {
				selector.select(1000);
				Set<SelectionKey> selectedKeys = selector.selectedKeys();
				Iterator<SelectionKey> selectedKeysIter = selectedKeys.iterator();
				while (selectedKeysIter.hasNext()) {
					SelectionKey selectedKey = selectedKeysIter.next();
					SocketChannel socketChannel = (SocketChannel) selectedKey.channel();
					receiveMessage(socketChannel);
				}
				if (exit) {
					userOutputStrategy.sendStatusOutput("Exit signal received");
				}
			}
		} catch (IOException ioe) {
			this.error = true;
			logger.severe("Unexpected chat session error while receiving messages. " + ioe.getMessage());
		} catch (Exception e) {
			this.error = true;
			logger.severe("Unexpected error while receiving messages. " + e.getMessage());
		} catch (Throwable t) {
			this.error = true;
			logger.severe("Unexpected THROWABLE while receiving messages. " + t.getMessage());
		} finally {
			userOutputStrategy.sendStatusOutput("Message receiver stopped");
		}
		return this.error;
	}
	
	private boolean receiveMessage(SocketChannel socketChannel) throws Exception {
		boolean connectionClosed = false;
		int readBytes = socketChannel.read(recvBuffer);
		if (readBytes == -1) {
			connectionClosed = true;
		}
		StringBuffer recvMessage = new StringBuffer();
		while (readBytes > 0) {
			recvBuffer.flip();
			while (recvBuffer.hasRemaining()) {
				byte b = recvBuffer.get();
				String str = new String(new byte[] {b});
				recvMessage.append(str);
			}
			recvBuffer.compact();
			readBytes = socketChannel.read(recvBuffer);
		}
		if (recvMessage.length() > 0) {
			userOutputStrategy.sendMessageOutput(recvMessage.toString());
		}
		return connectionClosed;
	}

}



