package org.hemant.thakkar.chat.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Chat Server main application. It launches the UserSessionManager which handles all 
 * activities of listening for incoming chat client connection and maintaining all
 * live chat client connection traffic. It uses the java NIO capabilities using 
 * socket channels, buffer, and selector to allow for large number of chat session.
 * 
 * Assumptions
 * 1.  Messages are 256 characters or less
 * 2.  Messages are delimited by a newline "\n" character.
 * 
 * The java source code file for the chat server contains all java classes in to this
 * one single source code file.
 * 
 * To run the Chat Server it is only necessary to compile this single java source
 * code and run it.
 * 
 * 1.  Copy this ChatServerApplication.java file into a directory
 * 2.  Change to the directory where the ChatServerApplication.java is copied
 * 2.  Compile with JDK 7 or higher using command:  javac -d . ChatServerApplication.java
 * 3.  Run the chat server with command: java -cp . org.hemant.thakkar.chat.server.ChatServerApplication
 * 4.  Monitor the ChatServerLog.xx.txt where xx = [0-9]
 * 
 * By default the server will listen on all interfaces on TCP port 7000. However this default
 * can be overridden with system property passed to the JVM as VM arguments. Below is an
 * example of how to specify a different interface and port. The listener address is 
 * specified as host:port or IP:port. 
 * 
 * java -cp . -Dlistener=192.168.2.74:7005 org.hemant.thakkar.chat.server.ChatServerApplication
 * 
 * Note:  In this source file there are several classes named as User****.java where User and
 * the term Client are interchangeable.
 * 
 * @author Hemant Thakkar
 *
 */
public class ChatServerApplication {


	/**
	 * Setup java util logging. The chat server log is created in the same directory where 
	 * the chat application runs. The log files are rotating log files up to 1 MB in size
	 * and up to 10 files before the first file is overwritten.
	 * 
	 * Log file names patter is ChatServerLog.xx.txt where xx is [0-9]
	 * 
	 * By default INFO and higher order logging is enabled.
	 */
	static {
        try {
            System.setProperty("java.util.logging.SimpleFormatter.format",
                    "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS.%1$tL %4$-7s - %2$s - %5$s %6$s%n");

            // Set up for rotating log files up to 10 each of 1MB size.
            final FileHandler fileHandler = new FileHandler("ChatServerLog.%g.txt", 1024 * 1024, 10, true);
            fileHandler.setLevel(Level.INFO);
            fileHandler.setFormatter(new SimpleFormatter());

            final Logger rootLogger = Logger.getLogger("");
            rootLogger.setLevel(Level.INFO);
            rootLogger.addHandler(fileHandler);
        } catch (Exception e) {
        		System.out.println("Failed to setup logging configuration");
        }
	}
	
	private static final Logger logger = Logger.getLogger(ChatServerApplication.class.getName());

	public static void main(String[] args) {
		logger.info("Starting chat server");
		
		// Look for listener address supplied as VM arguments. If not supplied
		// assume default to listen on all interfaces on TCP port 7000.
		String listenerAddress = System.getProperty("listener", "0.0.0.0:7000");
		String[] listenerAddressParts = listenerAddress.trim().split(":");
		String listenerIPAddress = null;
		int listenerPort = Integer.parseInt(listenerAddressParts[1]);
		if (!listenerAddressParts[0].equals("0.0.0.0")) {
			listenerIPAddress = listenerAddressParts[0];
		} 
		UserSessionManager userSessionManager = new UserSessionManager(listenerIPAddress, listenerPort);
		userSessionManager.start();
	}
}

/**
 * Every client session (i.e. user session) is an observer of message is received from
 * other client sessions. This interface defines the MessageObserver
 * 
 * @author Hemant Thakkar
 *
 */
interface MessageObserver {
	public String getId();
	public void messageReceived(String message);
}

/**
 * The chat server adds a small "protocol" on top of the raw TCP stream. The
 * protocol is to establish allow client to supply the username and for the
 * server to respond with a "ready" to inform the client that it is ok to start
 * sending messages.
 * 
 * This enum defines the various states of the user session.
 * 
 * ConnectedWithoutUsername:  Just the TCP connection established
 * ConnectedWithUsername:  Client has sent and server has received and attached 
 * a username to the TCP connection
 * ConnectedReady:  Server has sent the "ready" message to inform the client
 * it can start sending messages. 
 * Disconnected: TCP connection is closed.
 * 
 * @author Hemant Thakkar
 *
 */
enum UserSessionState {
	Disconnected,
	ConnectedWithoutUsername,
	ConnectedWithUsername,
	ConnectedReady
}

/**
 * Manages the main orchestration of the incoming new socket connection and receipt of 
 * messages from client/user sessions. Each incoming "event" is identified and dissminated
 * to appropriate <code>UserSession</code>. The <code>UserSession</code> reads processes
 * the data and takes appropriate actions.
 * 
 * @author Hemant Thakkar
 *
 */
class UserSessionManager {
	
	private static final Logger logger = Logger.getLogger(UserSessionManager.class.getName());
	
	/**
	 * Before a client-supplied username is received (i.e. when there is only a TCP
	 * socket connection), the socket channel is registered with the selector using 
	 * a automatically generated username. This username is later abandoned and replaced
	 * with real username supplied by client.
	 */
	private static int userNumber = 0;
	private static String defaultUsernamePrefix = "XXX-User-";

	private String listenerIPAddress;
	private int listenerPort;
	
	/**
	 * Associates each user session with either a server-generated username (before client
	 * has supplied username) or a client-supplied username.
	 */
	private Map<String, UserSession> userSessions = new HashMap<>();
	private ServerSocketChannel serverSocketChannel;
	private Selector selector;
	private boolean stop;
	
	public UserSessionManager(String listenerIPAddress, int listenerPort) {
		this.listenerIPAddress = listenerIPAddress;
		this.listenerPort = listenerPort;
		this.stop = false;
		userSessions = new HashMap<>();
	}
	
	/**
	 * Informs the user session manager to shutdown gracefully.
	 */
	public void shutdown() {
		this.stop = true;
	}
	
	/**
	 * Bind to the listener and start accepting chat session traffic.
	 */
	public void start() {
		
		try {
			// Open the server socket and start accepting new connections on
			// the user-supplied or default TCP address.
			serverSocketChannel = ServerSocketChannel.open();
			if (listenerIPAddress == null) {
				serverSocketChannel.socket().bind(new InetSocketAddress(listenerPort));
			} else {
				serverSocketChannel.socket().bind(new InetSocketAddress(listenerIPAddress, listenerPort));
			}
			serverSocketChannel.configureBlocking(false);
			selector = Selector.open();
			serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

			// Continue monitoring for TCP events until told to stop.
			while (!stop) {
				selector.select();
				Set<SelectionKey> selectedKeys = selector.selectedKeys();
				Iterator<SelectionKey> selectedKeysIter = selectedKeys.iterator();
				while (selectedKeysIter.hasNext()) {
					SelectionKey selectedKey = selectedKeysIter.next();
					if (selectedKey.isAcceptable()) {
						
						// New TCP conneciton is received.
						ServerSocketChannel channel = (ServerSocketChannel) selectedKey.channel();
						SocketChannel socketChannel = channel.accept();
						logger.fine("Incoming socket connection from: " 
								+ socketChannel.socket().getInetAddress().getHostAddress() 
								+ " on local TCP port "
								+ socketChannel.socket().getLocalPort());
						socketChannel.socket().setTcpNoDelay(true);
						socketChannel.configureBlocking(false);
						
						// Since we don't yet know the username associated to this
						// TCP connection, associate with a server-generated unique
						// name. This will later be replaced when client has supplied the
						// real username.
						String username = defaultUsernamePrefix + userNumber++;
						UserSession userSession = new UserSession(socketChannel);
						userSessions.put(username, userSession);
						socketChannel.register(selector, SelectionKey.OP_READ, username);
					} else if (selectedKey.isReadable()) {	
						// Client has sent some data. Identify the user session instance
						// so we can ask it to process the incoming traffic and identify the necessary
						// action and state based on its current state.
						String usernameAttachedToSelectorKey = selectedKey.attachment().toString();
						UserSession userSession = userSessions.get(usernameAttachedToSelectorKey);
						if (userSession != null) {
							userSession.processData();
							
							// If the user session is just entered the connected-with-username state,
							// let's remove the old server-generated username association and replace 
							// with real client-supplied username.
							if (userSession.getSessionState() == UserSessionState.ConnectedWithUsername) {
								String clientSuppliedUsername = userSession.getId();
								userSessions.remove(usernameAttachedToSelectorKey);
								userSessions.put(clientSuppliedUsername, userSession);
								selectedKey.attach(clientSuppliedUsername);
								userSession.setReadyToReceive();
								logger.info("User " + clientSuppliedUsername + " connected");
							} else if (userSession.getSessionState() == UserSessionState.Disconnected) {
								logger.info("User " + userSession.getId() + " disconnected");
								selectedKey.cancel();
								userSessions.remove(userSession.getId());
							}
						} else {
							logger.warning("User session for selected key " 
									+ selectedKey.attachment().toString() + " is no longer available.");
							selectedKey.cancel();
						}
					}
					selectedKeysIter.remove();
				}
			}
		} catch (IOException e) {
			logger.severe("Error while looking for changed socket channel. " + e.getMessage());
		} finally {
			try {
				selector.close();
				serverSocketChannel.close();
			} catch (IOException ioe) {
				logger.severe("Error while shutting down server socket");
			}
		}
		logger.info("Shutting down chat server");
	}
}

/**
 * Manages traffic/data processing of each individual chat session. It implements a 
 * small chat session initiation "hand-shake" protocol. As the TCP connection exchanges
 * the username (client -> server) and readiness (server -> client) the current state
 * is maintained to ensure the server is not looking prematurely or after the session
 * is closed.
 * 
 * It maintains the anticipation that a message could be partially received on any
 * give TCP event. Waits until full message (delimited by a newline "\n") is recevied
 * before publishing the message to the MessageDistributor to dissiminate to other
 * users.
 * 
 * It also implements the MessageObserver interface. Registers itself with the 
 * MessageDistributor to receive messages sent by other users so that the received
 * message can be forwarded the owner of this chat session.
 * 
 * @author Hemant Thakkar
 *
 */
class UserSession implements MessageObserver {

	// Maximum message size.
	public static final int MAX_DATA_SIZE = 256;

	private static final Logger logger = Logger.getLogger(UserSession.class.getName());
	
	private String username;
	private SocketChannel socketChannel;
	
	// Maintain any partially received data on any given TCP data event. 
	// Waits until full message (delimited by a newline "\n") is received
	// before publishing the message to the MessageDistributor to dissiminate to other
	// users.
	private StringBuilder partialMessage;
	
	// Current chat session state as determined by the chat session protocol.
	private UserSessionState userSessionState;
	
	public UserSession(SocketChannel socketChannel) {
		this.socketChannel = socketChannel;
		this.partialMessage = new StringBuilder();
		this.userSessionState = UserSessionState.ConnectedWithoutUsername;
	}
	
	// Implements the message observer interface. When the MessageDistributor
	// notifies with message from another user, the message is sent to the 
	// owner of this chat session.
	@Override
	public void messageReceived(String message) {
		try {
			ByteBuffer byteBuffer = ByteBuffer.allocate(256);
			byteBuffer.put(message.getBytes());
			byteBuffer.flip();
			socketChannel.write(byteBuffer);
		} catch (IOException e) {
			logger.severe("Error while sending message to user " + username + ". Error: " + e.getMessage());
			closeSession();			
		}
	}
	
	@Override
	public String getId() {
		return this.username;
	}
	
	public UserSessionState getSessionState() {
		return this.userSessionState;
	}
	
	/**
	 * This is the main data processing logic of the user session. It uses other
	 * subordinate methods to process data during different protocol state. See
	 * inline documentation in the method.
	 */
	public void processData() {
		try {
			if (userSessionState == UserSessionState.ConnectedWithoutUsername) {
				// Current state is one of just having a plain old vanilla 
				// TCP connection. Have not yet received the username from the client
				// Look for the username information from the client
				processForUsername();
			} else {
				// At this point it is assumed that if there is data received
				// that the chat session had been established. Read and process
				// the data. Look for a newline delimiter to delineate the 
				// message boundary. When a full message is received, drop it int
				// the MessageDistributor so that it can be forwarded on to other
				// chat sessions.
				ByteBuffer byteBuffer = ByteBuffer.allocate(MAX_DATA_SIZE);
				int readBytes = socketChannel.read(byteBuffer);
				if (readBytes == -1) {
					logger.finer("End of stream on the channel. Looks like remote connection is closed.");
					closeSession();
				} else {
					while (readBytes > 0) {
						byteBuffer.flip();
						while (byteBuffer.hasRemaining()) {
							byte b = byteBuffer.get();
							String str = new String(new byte[] {b});
							partialMessage.append(str);
							if (str.equals("\n")) {
								// Message boundary found. Get the complete message.
								logger.info("Message received from " + username);
								Message message = createMessage(partialMessage.toString());
								MessageProcessor.getInstance().process(message);
								
								// Cleanup the partial message for the next message data.
								partialMessage = new StringBuilder();
							} 
						}
						byteBuffer.compact();
						readBytes = socketChannel.read(byteBuffer);
					}
				}
			}
		} catch (IOException e) {
			logger.severe("Error while reading data from socket. " + e.getMessage());
			closeSession();			
		} catch (Exception e1) {
			logger.severe("Error while reading data from socket. " + e1.getMessage());
			closeSession();			
		}
	}

	/**
	 * Send the "ready" message the client and set the session in full ready mode
	 * to start expecting data from client.
	 */
	public void setReadyToReceive() {
		try {
			ByteBuffer byteBuffer = ByteBuffer.allocate(MAX_DATA_SIZE);
			byteBuffer.put("ready".getBytes());
			byteBuffer.flip();
			socketChannel.write(byteBuffer);
			userSessionState = UserSessionState.ConnectedReady;
			MessageDistributor.getInstance().addObserver(this);
		} catch (IOException e) {
			logger.severe("Error while sending ready signal for username." + e.getMessage());
			closeSession();			
		}
	}
	
	/**
	 * Create the message object with the text message received on the TCP 
	 * @param data
	 * @return
	 */
	private Message createMessage(String data) {
		Message message = new Message();
		message.setSource(username);
		if (data.startsWith("to:")) {
			String[] dataParts = data.split("\\s+");
			String destination = dataParts[0].split(":")[1].trim();
			message.setDestination(destination);
			message.setData(data.replaceFirst("to:" + destination, ""));
		} else {
			message.setData(data);
		}
		return message;
	}

	/**
	 * Invoked when the user session has TCP connection but has not yet received
	 * the username from client. When this method is invoked, it is assumed that 
	 * data incoming on the TCP connection is the username supplied by client.
	 */
	private void processForUsername() {
		try {
			ByteBuffer byteBuffer = ByteBuffer.allocate(MAX_DATA_SIZE);
			int readBytes = socketChannel.read(byteBuffer);
			if (readBytes == -1) {
				throw new Exception("End of stream on the channel. Looks like remote connection is closed.");
			} else {
				byteBuffer.flip();
				byte[] bytes = new byte[byteBuffer.limit()];
				byteBuffer.get(bytes);
				username = new String(bytes);
				logger.fine("Username received on session: " + username);
				userSessionState = UserSessionState.ConnectedWithUsername;
			}
		} catch (IOException e) {
			logger.severe("Error while processing data for username." + e.getMessage());
			closeSession();			
		} catch (Exception e1) {
			logger.severe("Error while reading data from socket. " + e1.getMessage());
			closeSession();			
		}
	}
	
	private void closeSession() {
		try {
			MessageDistributor.getInstance().removeObserver(this);
			userSessionState = UserSessionState.Disconnected;
			socketChannel.socket().close();
			socketChannel.close();
		} catch (IOException e1) {
			logger.severe("Error while closing socket channel. " + e1.getMessage());
		}
	}
}

/**
 * If the user message is a command, what type of command?
 * 
 * Invalid:  When user's command can not be determined
 * Block:  User wishes to block message from specified user
 * Unblock: User wishes to unblock previously blocked user
 * 
 * Some commands like quit and connect do not have any arguments while
 * others may take one or more arguments. See the javadoc on 
 * TextUserInterface for more details.
 * 
 * @author Hemant Thakkar
 *
 */
enum Command {
	Invalid,
	Block,
	Unblock
}

/**
 * Describes the user's input as either a command to be interpreted by the
 * client or a message to be sent to the server
 * 
 * @author Hemant Thakkar
 *
 */
enum MessageType {
	Command,
	Message
}

/**
 * Message DTO object used to share data between producer session and consumer
 * session via the MessageDistributor
 * 
 * @author Hemant Thakkar
 *
 */
class Message {
	// Source username
	private String source;
	
	// Destination username. By default it is not set to signify that the
	// message is to be broadcast to all chat session other than the source
	// session.
	private String destination;
	
	// Actual message data
	private String data;
	
	public String getSource() {
		return source;
	}
	public void setSource(String source) {
		this.source = source;
	}
	public String getDestination() {
		return destination;
	}
	public void setDestination(String destination) {
		this.destination = destination;
	}
	public String getData() {
		return data;
	}
	public void setData(String data) {
		this.data = source + ": " + data;
	}
}

/**
 * Process received messages. Interpret whether it is a command or a
 * message to distribute to other chat sessions. 
 * 
 * This is a singleton that is initialized at creation time.
 * 
 * @author Hemant Thakkar
 *
 */
class MessageProcessor {
	private static final Logger logger = Logger.getLogger(MessageProcessor.class.getName());
	private static MessageProcessor self = new MessageProcessor();
	
	public static MessageProcessor getInstance() {
		return self;
	}
	
	private MessageProcessor() {
		// Do nothing
	}

	/**
	 * Determine if it is a command or a message. Message beginning with "\\" is 
	 * interpreted as command. Otherwise a message. 
	 * @return
	 */
	private MessageType getMessageType(String data) {
		if (data.startsWith("\\\\")) {
			return MessageType.Command;
		} else {
			return MessageType.Message;
		}
	}

	/**
	 * If message is a command, determine the command type. Commands are
	 * case insensitive.
	 * 
	 * @param input
	 * @return
	 */
	private Command getCommand(String data) {
		String dataExCommandPrefix = data.substring(2);
		String[] commandTokens = dataExCommandPrefix.split("\\s+");
		String command = commandTokens[0].trim();
		Command commandEnum = Command.Invalid;
		if (command.equalsIgnoreCase("block")) {
			commandEnum = Command.Block;
		} else if (command.equalsIgnoreCase("unblock")) {
			commandEnum = Command.Unblock;
		} 
		return commandEnum;
	}

	private String[] getCommandArgs(String data) {
		String[] commandTokens = data.split("\\s+");
		String[] args = new String[commandTokens.length - 1];
		for (int index = 1; index < commandTokens.length; index++) {
			args[index - 1] = commandTokens[index];
		}
		return args;
	}

	/**
	 * Invoked by user session when it has received a full message
	 * Message is then distributed to all other sessions unless if
	 * the message is directed to a specific user.
	 * 
	 * A chat client that wishes to send a directed/private message does so
	 * by prefixing the message with a "to:user". The distributor looks for
	 * message beginning with such pattern. Identifies the A message that is 
	 * directed to a specific user.
	 *  
	 * @param message
	 */
	public void process(Message message) {
		MessageType messageType = getMessageType(message.getData());
		if (messageType == MessageType.Message) {
			MessageDistributor.getInstance().distributeMessage(message);
		} else {
			Command command = getCommand(message.getData());
			String[] commandArgs = getCommandArgs(message.getData());
			if (command == Command.Block) {
				logger.finest("User " + message.getSource() 
						+ " requested to block messages from " + commandArgs[0]);
				MessageDistributor.getInstance().blockSource(commandArgs[0], message.getSource());
			} else if (command == Command.Unblock) {
				logger.finest("User " + message.getSource() 
				+ " requested to unblock messages from " + commandArgs[0]);
				MessageDistributor.getInstance().unblockSource(commandArgs[0], message.getSource());
			} else {
				logger.fine("Invalid command [" + message.getData() 
					+ "] from user " + message.getSource());
			}
		}
	}
	
}

/**
 * Distributes received messages to other chat sessions. Maintains
 * a collection of MessageObserver (i.e. UserSession instances) that
 * have expressed interest in receiving messages from other users.
 * 
 * This is a singleton that is initialized at creation time.
 * 
 * @author Hemant Thakkar
 *
 */
class MessageDistributor {
	private static final Logger logger = Logger.getLogger(MessageDistributor.class.getName());
	private static MessageDistributor self = new MessageDistributor();
	private Map<String, MessageObserver> observers;
	private Map<String, List<String>> blocked;
	
	public static MessageDistributor getInstance() {
		return self;
	}
	
	private MessageDistributor() {
		this.observers = new HashMap<>();
		this.blocked = new HashMap<>();
	}
	
	public void distributeMessage(Message message) {
		String destination = message.getDestination();
		// If the message has a specific detination, then only 
		// send the message to that user.
		if (destination != null) {
			MessageObserver observer = observers.get(destination);
			if (observer != null) {
				String data = message.getData();
				data = data.replaceFirst("to:" + destination, "");
				sendMessage(data, message.getSource(), observer);
			}
		} else {
			// Otherwise broadcast to all sessions.
			for (MessageObserver observer : observers.values()) {
				if (!message.getSource().equals(observer.getId())) {
					sendMessage(message.getData(), message.getSource(), observer);
				}
			}
		}		
	}
	
	private void sendMessage(String data, String source, MessageObserver observer) {
		List<String> usersBlockedByObserver = blocked.get(observer.getId());
		if ((usersBlockedByObserver == null) ||
				(!usersBlockedByObserver.contains(source))) {
			observer.messageReceived(data);
		} else {
			logger.finest("Did not send message to " + observer.getId());
		}
	}

	public void addObserver(MessageObserver observer) {
		this.observers.put(observer.getId(), observer);
	}
	
	public void removeObserver(MessageObserver observer) {
		this.observers.remove(observer.getId());
	}
	
	public void blockSource(String source, String destination) {
		List<String> destinationsBlockedBySource = blocked.get(source);
		if (destinationsBlockedBySource == null) {
			destinationsBlockedBySource = new ArrayList<String>();
			blocked.put(source, destinationsBlockedBySource);
		}
		destinationsBlockedBySource.add(destination);
	}
	
	public void unblockSource(String source, String destination) {
		List<String> destinationsBlockedBySource = blocked.get(source);
		if (destinationsBlockedBySource != null) {
			destinationsBlockedBySource.remove(destination);
		}
	}
	
}
