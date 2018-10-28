package org.hemant.thakkar.chat.client;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
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
 * An interactive textual user-interface base chat client main application. 
 * It the console based user-interface which consists of console inputs and the 
 * status and messages are directed to a file which is expected to be "tailed"
 * to observe those status and messages.
 * 
 * Accepts the user configuration and action commands from the console input as 
 * well as the messages intended for broadcast or single recipients. 
 * 
 * Also launches a chat session over TCP connection to the server. After exchanging
 * initial handshake the TCP connection is declared as a chat session over which
 * this chat client can send and receive chat messages.
 * 
 * Assumptions
 * 1.  Messages are 256 characters or less
 * 2.  Messages are delimited by a newline "\n" character.
 * 3.  User must tail a text file to view status messages as well messages from
 *     other users.
 * 
 * The java source code file for the chat client contains all java classes in to this
 * one single source code file.
 * 
 * To run the Chat Client it is only necessary to compile this single java source
 * code and run it.
 * 
 * 1.  Copy this ChatClientApplication.java file into a directory
 * 2.  Change to the directory where the ChatClientApplication.java is copied
 * 2.  Compile with JDK 7 or higher using command:  javac -d . ChatClientApplication.java
 * 3.  Run the chat client with command: java -cp . org.hemant.thakkar.chat.client.ChatClientApplication
 * 4.  Monitor the ChatClientLog.xx.txt where xx = [0-9]
 * 
 * By default the client will listen on loopback interface (127.0.0.1) on TCP port 7000. 
 * However this default using console input commands to set the server host/IP address and
 * TCP port. The default TCP address can be specified as JVM arguments when launching the
 * chat client application. An example of how to specify a different interface and port. 
 * The server address is specified as host:port or IP:port. 
 * 
 * java -cp . -Dserver=192.168.2.74:7005 org.hemant.thakkar.chat.client.ChatClientApplication
 * 
 * Note:  In this source file there are several classes named as User****.java where User and
 * the term Client are interchangeable.

 * @author Hemant Thakkar
 *
 */
public class ChatClientApplication {

	/**
	 * Setup java util logging. The chat server log is created in the same directory where 
	 * the chat application runs. The log files are rotating log files up to 1 MB in size
	 * and up to 10 files before the first file is overwritten.
	 * 
	 * By default INFO and higher order logging is enabled.
	 * 
	 * Log file names patter is ChatClientLog.xx.txt where xx is [0-9]
	 * 
	 */
	static {
        try {
            System.setProperty("java.util.logging.SimpleFormatter.format",
                    "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS.%1$tL %4$-7s - %2$s - %5$s %6$s%n");

            // Set up for rotating log files up to 10 each of 1MB size.
            final FileHandler fileHandler = new FileHandler("ChatClientLog.%g.txt", 1024 * 1024, 10, true);
            fileHandler.setLevel(Level.INFO);
            fileHandler.setFormatter(new SimpleFormatter());

            final Logger rootLogger = Logger.getLogger("");
            rootLogger.setLevel(Level.INFO);
            rootLogger.addHandler(fileHandler);
            rootLogger.setUseParentHandlers(false);
        } catch (Exception e) {
        		System.out.println("Failed to setup logging configuration");
        }
	}
	
	private static final Logger logger = Logger.getLogger(ChatClientApplication.class.getName());

	public static void main(String[] args) {
		logger.info("Starting chat client");
		
		// Look for listener address supplied as VM arguments. If not supplied
		// assume default to connect to server on loopback interface TCP port 7000.
		String serverAddress = System.getProperty("server", "0.0.0.0:7000");
		String[] serverAddressParts = serverAddress.trim().split(":");
		String serverIPAddress = "127.0.0.1";
		int serverPort = Integer.parseInt(serverAddressParts[1]);
		if (!serverAddressParts[0].equals("0.0.0.0")) {
			serverIPAddress = serverAddressParts[0];
		} 
		UserConfiguration.getInstance().setServerIPAddress(serverIPAddress);
		UserConfiguration.getInstance().setServerPort(serverPort);
		TextUserInterface userInterface = new TextUserInterface();
		userInterface.start();
	}
}

/**
 * Next several enums and classes form the UI strategy/interface and 
 * concrete implementation of textual interface. The textual interface
 * consists of two parts. The input is taken on console/command-line while
 * the output written to a file. The user can tail the file in a separate 
 * window thereby separating the input and output. The user interface classes
 * are designed to be extensible for other types of user interface such as
 * Java Swing UI.
 * 
 * These classes are briefly listed here with a short one-sentence description
 * Refer to the javadoc of specific classes for more details.
 * 
 * InputType: An enumeration that describes whether user's input is a command
 * for the client to interpret or message to send to server.
 * 
 * Command: An enumeration that describes various types of commands interpreted
 * by the chat client
 * 
 * UserInput: A data object used to convey user's entered information on a 
 * specific input device (i.e. console) to the UI interpreter
 * 
 * UserOutputStrategy: Interface that defines output operations. 
 * 
 * UserInterfaceStrategy:  Interface that defines user interface operations
 * 
 * TextUserInterface:  console-based concrete implementation of the 
 * UserInterfaceStrategy
 * 
 * TextUserOutput: File-based concrete implementation of the UserOutputStrategy
 * 
 */

/**
 * Describes the user's input as either a command to be interpreted by the
 * client or a message to be sent to the server
 * 
 * @author Hemant Thakkar
 *
 */
enum InputType {
	Command,
	Message
}

/**
 * If the user input is a command, what type of command?
 * 
 * Invalid:  When user's input can not be determined
 * User:  User wish to specify the username to use for the chat session
 * Server:  User wishes to configur the server TCP address
 * Block:  User wishes to block message from specified user
 * Unblock: User wishes to unblock previously blocked user
 * Quit:  Quit the chat client
 * Connect:  Connect to the server
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
	User,
	Server,
	Block,
	Unblock,
	Quit,
	Connect
}

/**
 * A data object that conveys information captured from input device
 * 
 * @author Hemant Thakkar
 *
 */
class UserInput {
	
	// Command or a Message?
	private InputType inputType;
	
	// If command, which command?
	private Command command;
	
	// Arguments, if any, associated to the command
	private String[] commandArgs;
	
	// If message, the actual message entered by the user
	private String message;
	
	
	public InputType getInputType() {
		return inputType;
	}
	public void setInputType(InputType inputType) {
		this.inputType = inputType;
	}
	public Command getCommand() {
		return command;
	}
	public void setCommand(Command command) {
		this.command = command;
	}
	public String getMessage() {
		return message;
	}
	public void setMessage(String message) {
		this.message = message;
	}
	public String[] getCommandArgs() {
		return commandArgs;
	}
	public void setCommandArgs(String[] commandArgs) {
		this.commandArgs = commandArgs;
	}
}

/**
 * Defines interface for user output operations
 * 
 * @author Hemant Thakkar
 *
 */
interface UserOutputStrategy {
	
	/**
	 * Start the user output device
	 */
	public void start();
	
	/**
	 * Close user output device
	 */
	public void shutdown();
	
	/**
	 * There are two types of output: status and message
	 * A status output conveys the state of the client or server
	 * to the user
	 * A message output conveys message received from other chat users
	 * 
	 */
	
	/**
	 * Send status message to the output device. 
	 * @param output
	 */
	public void sendStatusOutput(String output);
	
	/**
	 * Send message received from other chat users to the output device.
	 * @param output
	 */
	public void sendMessageOutput(String output);
}

/**
 * Defines interface for user interface operations
 * 
 * @author Hemant Thakkar
 *
 */
interface UserInterfaceStrategy {
	public void start();
	public void shutdown();
}

/**
 * Concrete implementation of the UserInterfaceStrategy. It encapsulates
 * the user input and output device implementation. The user input 
 * device is a command-line console while the user output is a text file
 * 
 * @author Hemant Thakkar
 *
 */
class TextUserInterface implements UserInterfaceStrategy {
	private Scanner consoleScanner;
	private boolean stop;
	
	// User configuration "database"
	private UserConfiguration userConfiguration;
	private String username;
	
	// Current chat session to the server
	private UserSession userSession;
	
	// Interface to the user output device. In this case
	// it is the TextUserOutput which is the file-based
	// output
	private UserOutputStrategy userOutput;
	
	public TextUserInterface() {
		userOutput = new TextUserOutput();
		userConfiguration = UserConfiguration.getInstance();
	}

	/**
	 * This is the main user input interpreter. It works as follows
	 * in a continuous loop until user enters a command to quit.
	 * 
	 * 1.  When user interface starts the user is prompted with '>' on the console
	 * 2.  User can enter a command or a message
	 * 3.  If wish to enter command, prefix with "\\". 
	 *     Example:  \\user hemant    This is to configure username to be hemant
	 *     Example:  \\server 192.168.2.78 7005    This is to configure server's TCP address
	 *     Example:  \\quit    This is to exit the client
	 * 4.  A message does not require a prefix, but an optional prefix can be entered
	 *     to direct message to specific user. A carriage-return/new-line forces
	 *     the message to be sent to server
	 * 5.  A message prefixed with "to:userxx " will inform the server that the message 
	 *     should be sent only to the user specified user
	 *     
	 *  Following are the commands supported. Commands are case-insensitive.
	 *  
	 *  1.  user:  Specify username for chat session. Username is necessary before a
	 *      connection is attempted to the server. (Example:  \\user hemant)
	 *  2.  server: Specify server TCP address. IF not specified, default is loopback
	 *      interface port 7000. (Example:  \\server 192.168.2.78 7005
	 *  3.  block:  Block messages from user (Example:  \\block userxyz)
	 *  4.  unblock:  Unblock a user (Example: \\unblock userxyz)
	 *  5.  connect:  Connect to the server using the username configured with user command
	 *      and server TCP address configured using the server command.
	 *  6.  quit:  Quit the chat application
	 *     
	 */
	@Override
	public void start() {
		
		// Initialize the console input device
		consoleScanner = new Scanner(System.in);
		while (!stop) {
			// Get the next line of input from the command line
			UserInput userInput = getNextInput();
			if (userInput.getInputType() == InputType.Command) {
				// Input is a command. Get any command arguments spearated by whitespaces
				String[] commandArgs = userInput.getCommandArgs();
				
				// Below are the various supported commands
				if (userInput.getCommand() == Command.Quit) {
					if (userSession != null) {
						userSession.shutdown();
					}
					stop = true;
				} else if (userInput.getCommand() == Command.Block) {
					userConfiguration.blockUser(commandArgs[0].trim());
					userSession.sendMessage("\\block " + commandArgs[0].trim());
				} else if (userInput.getCommand() == Command.Unblock) {
					userConfiguration.unblockUser(commandArgs[0].trim());
					userSession.sendMessage("\\unblock " + commandArgs[0].trim());
				} else if (userInput.getCommand() == Command.User) {
					userConfiguration.setUsername(commandArgs[0].trim()); 
					userOutput = new TextUserOutput();
					userOutput.start();
				} else if (userInput.getCommand() == Command.Server) {
					userConfiguration.setServerIPAddress(commandArgs[0].trim());
					userConfiguration.setServerPort(Integer.parseInt(commandArgs[1]));
				} if (userInput.getCommand() == Command.Connect) {
					userSession = new UserSession(userOutput);
					userSession.establishSession();
				} 
			} else if (userInput.getInputType() == InputType.Message) {
				if ((userSession != null) && (userSession.isSessionEstablished())) {
					userSession.sendMessage(userInput.getMessage());
				} else {
					userOutput.sendStatusOutput("User session is not established yet");
				}
			}
		}
	}

	@Override
	public void shutdown() {
		this.stop = true;
	}
		
	/**
	 * Read the next line from console. Determine if it is a command or a message
	 * Line beginning with "\\" is interpreted as command. Otherwise a message.
	 * Collect the information into the UserInput data object.
	 * @return
	 */
	private UserInput getNextInput() {
		System.out.print(">");
		String input = null;
		input = consoleScanner.nextLine();
		UserInput userInput = new UserInput();
		userInput.setInputType(getInputType(input));
		
		if (userInput.getInputType() == InputType.Command) {
			userInput.setCommand(getCommand(input));
			userInput.setCommandArgs(getCommandArgs(input));
		} else {
			userInput.setInputType(InputType.Message);
			userInput.setMessage(input);
		}
		return userInput;
	}
	
	/**
	 * Determine the user input as command or message. If it is a command or a message
	 * Line beginning with "\\" is interpreted as command. Otherwise a message.
	 * 
	 * @param input
	 * @return
	 */
	private InputType getInputType(String input) {
		if (input.startsWith("\\\\")) {
			return InputType.Command;
		} else {
			return InputType.Message;
		}
	}
	
	/**
	 * If command is entered, determine the command type. Commands are
	 * case insensitive.
	 * 
	 * @param input
	 * @return
	 */
	private Command getCommand(String input) {
		String inputExCommandPrefix = input.substring(2);
		String[] commandTokens = inputExCommandPrefix.split("\\s+");
		String command = commandTokens[0].trim();
		Command commandEnum = Command.Invalid;
		if (command.equalsIgnoreCase("block")) {
			commandEnum = Command.Block;
		} else if (command.equalsIgnoreCase("unblock")) {
			commandEnum = Command.Unblock;
		} else if (command.equalsIgnoreCase("connect")) {
			commandEnum = Command.Connect;
		} else if (command.equalsIgnoreCase("quit")) {
			commandEnum = Command.Quit;
		} else if (command.equalsIgnoreCase("user")) {
			commandEnum = Command.User;
		} else if (command.equalsIgnoreCase("server")) {
			commandEnum = Command.Server;
		} 
		return commandEnum;
	}
	
	private String[] getCommandArgs(String input) {
		String[] commandTokens = input.split("\\s+");
		String[] args = new String[commandTokens.length - 1];
		for (int index = 1; index < commandTokens.length; index++) {
			args[index - 1] = commandTokens[index];
		}
		return args;
	}

	public String getUsername() {
		return username;
	}
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
	
	public TextUserOutput() {
	}

	/**
	 * Username must have already been configured, because the output file name
	 * is the name of the output file with a ".out" extension.
	 * 
	 * Create text output file in the current directory if file is absent
	 * If file is present, open for append
	 * 
	 */
	@Override
	public void start() {
		String username = UserConfiguration.getInstance().getUsername();
		if (username == null || username.length() == 0) {
			logger.severe("Username is not yet defined");
		} else {
			try {
				Path outputfilePath = Paths.get(".", username + ".out");
				if (!Files.exists(outputfilePath)) {
					Files.createFile(outputfilePath);
				}
				outputFileWriter = Files.newBufferedWriter(outputfilePath, StandardOpenOption.APPEND);
			} catch (IOException ioe) {
				logger.severe("Failed to open file for text-based user output destination");
			}
		}
	}

	/**
	 * Close the output file.
	 */
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
		
	/**
	 * Status messages are prefixed with "status: " and written to the file.
	 */
	public void sendStatusOutput(String message) { 
		sendOutput("status: " + message);
	}

	/**
	 * Messages are written to the output file as received. The server sends 
	 * the messages pre-fixed with source username
	 * 
	 */
	public void sendMessageOutput(String message) { 
		if (message.length() > 0) {
			String sourceUser = message.toString().split(":")[0].trim();
			if (!UserConfiguration.getInstance().isUserBlocked(sourceUser)) {
				sendOutput(message);
			} else {
				logger.finer("Message from user " + sourceUser + " blocked");
			}
		} else {
			logger.finer("Received empty message");
		}
	}

	/**
	 * Write the message to the output file.
	 * @param message
	 */
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

/**
 * Serves as the "database" for the chat application. It is a singleton
 * 
 * @author Hemant Thakkar
 *
 */
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

/**
 * Chat session to the server. It provices means to specify message to send to the
 * server for broadcast to other users or unicast to a specific user.
 * 
 * It also encapsulates a message receiver to receive messages from other users via
 * the chat server. The received messages are directed to the UserOutputStrategy. The
 * UserOutputSrategy interface is used here to shield this class from specific 
 * implementation of the user interface strategy. For example, the UserOutputStrategy
 * could be a GUI implementation.
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
	
	// Interface to a specific user output implementation.
	private UserOutputStrategy userOutputStrategy;

	private boolean chatSessionEstablished;
	
	// A <code>Callable</code> that listens for traffic on the TCP connection
	private MessageReceiver messageReceiver;
	
	// The MessageReceiver runs in a separate thread. It reads the messages
	// from TCP and directs them to the UserOutputStrategy.
	private ExecutorService executorService;
	
	// To gracefully shutdown and wait for shutdown of the MessageReceiver
	private Future<Boolean> messageReceiverFuture;
	
	public UserSession(UserOutputStrategy userOutputStrategy) {
		this.userOutputStrategy = userOutputStrategy;
		this.chatSessionEstablished = false;
		sendBuffer = ByteBuffer.allocate(256);
		recvBuffer = ByteBuffer.allocate(256);
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
			if (UserConfiguration.getInstance().getUsername() == null) {
				throw new Exception("Username is not defined");
			}
			
			// 1. Open TCP connection to the server
			socketChannel.socket().connect(new InetSocketAddress(serverIPAddress, serverPort));
			socketChannel.socket().setTcpNoDelay(true);
			socketChannel.configureBlocking(false);
			
			// 2. Send username on the TCP connection
			sendBuffer.put(UserConfiguration.getInstance().getUsername().getBytes());
			sendBuffer.flip();
			socketChannel.write(sendBuffer);
			sendBuffer.clear();
			
			// 3. Receive "ready" from server on the TCP connection
			selector = Selector.open();
			socketChannel.register(selector, SelectionKey.OP_READ);
			selector.select();
			int readBytes = socketChannel.read(recvBuffer);
			if (readBytes == -1) {
				throw new Exception("Server connection unexpectedly closed.");
			} else if (readBytes > 0) {
				recvBuffer.flip();
				byte[] readyMessage = new byte[recvBuffer.limit()];
				recvBuffer.get(readyMessage);
				String ready = new String(readyMessage);
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
		
		// If chat session handshake success and chat session established, 
		// start the message receiver
		if (!chatSessionEstablished) {
			closeResources();
		} else {
			this.executorService = Executors.newSingleThreadExecutor();
			this.messageReceiver = new MessageReceiver(selector, socketChannel, userOutputStrategy);
			messageReceiverFuture = this.executorService.submit(messageReceiver);
		}
		return chatSessionEstablished;
	}
	
	public void shutdown() {
		closeResources();
	}
	
	private void closeResources() {
		try {
			if (messageReceiver != null) {
				messageReceiver.setExit(true);
				Thread.sleep(2000);
			}
			if (messageReceiverFuture != null) {
				messageReceiverFuture.cancel(false);
				executorService.shutdown();
				executorService.awaitTermination(2, TimeUnit.SECONDS);
			} 
			if (selector != null) {
				selector.close();
			}
			if (socketChannel != null) {
				socketChannel.close();
			}
		} catch (IOException e) {
			logger.severe("Failed to close resources");
		} catch (InterruptedException e) {
			// Do nothing
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
	
	public boolean isSessionEstablished() {
		return this.chatSessionEstablished;
	}
}

/**
 * Receives messages from the server. It is a Callable and intended to run in a
 * separate thread from the main thread which is running the user input device.
 * 
 * Received messages is sent to the user output device
 * 
 * @author Hemant Thakkar
 *
 */
class MessageReceiver implements Callable<Boolean> {

	private static Logger logger = Logger.getLogger(MessageReceiver.class.getName());
	private boolean exit;
	private Selector selector;
	private SocketChannel socketChannel;
	
	// To send received messages to the user output.
	private UserOutputStrategy userOutputStrategy;
	private Boolean error;
	private ByteBuffer recvBuffer;
	
	public MessageReceiver(Selector selector, SocketChannel socketChannel, 
			UserOutputStrategy userOutputStrategy) {
		this.error = false;
		this.exit = false;
		this.selector = selector;
		this.recvBuffer = ByteBuffer.allocate(256);
		this.socketChannel = socketChannel;
		this.userOutputStrategy = userOutputStrategy;
	}
	
	public void setExit(boolean exit) {
		this.exit = exit;
	}
	
	/**
	 * Operates in a loop until exit signal is received. The selector is
	 * not fully blocking. Instead it is blocked with timeout to allow to check
	 * for exit signal.
	 * 
	 */
	@Override
	public Boolean call() {
		try {
			while (!exit) {
				selector.select(2000);
				Set<SelectionKey> selectedKeys = selector.selectedKeys();
				if (!selectedKeys.isEmpty()) {
					logger.finer("Message received");
					if (receiveMessage()) {
						userOutputStrategy.sendStatusOutput("Looks like server is down");
						exit = true;
					}
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
		} finally {
			userOutputStrategy.sendStatusOutput("Message receiver stopped");
		}
		return this.error;
	}
	
	private boolean receiveMessage() throws Exception {
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



