package org.hemant.thakkar.chat.server;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.easymock.IMocksControl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UserSessionTest {

	@Test
	void testProcessDataBeforeChatSessionEstablished() {
		SocketChannel mockSocketChannel = EasyMock.createMock(SocketChannel.class);
		UserSession userSession = new UserSession(mockSocketChannel);
		String expectedUsername = "hemant";
		try {
			EasyMock.expect(mockSocketChannel.read(EasyMock.anyObject(ByteBuffer.class))).andAnswer(new IAnswer<Integer>() {
				@Override
				public Integer answer() throws Throwable {
					ByteBuffer buffer = (ByteBuffer) EasyMock.getCurrentArguments()[0];
					buffer.put(expectedUsername.getBytes());
					return expectedUsername.length();
				}
			});
		} catch (IOException e) {
			fail("Unexpected exception");
		}
		EasyMock.replay(mockSocketChannel);
		
		userSession.processData();
		
		EasyMock.verify(mockSocketChannel);
		
		UserSessionState userSessionState = userSession.getSessionState();
		Assertions.assertEquals(UserSessionState.ConnectedWithUsername, userSessionState);
		Assertions.assertEquals(expectedUsername, userSession.getId());
	}

	@Test
	void testReadyToReceive() {
		Capture<ByteBuffer> byteBufferCapture = EasyMock.newCapture();
		IMocksControl mockControl = EasyMock.createStrictControl();
		SocketChannel mockSocketChannel = mockControl.createMock(SocketChannel.class);
		MessageDistributor mockMessageDistributor = mockControl.createMock(MessageDistributor.class);
		
		// Set up the user session in a chat session established state
		// with username 'hemant'
		UserSession userSession = new UserSession(mockSocketChannel);
		userSession.setMessageDistributor(mockMessageDistributor);
		userSession.setUsername("hemant");
		userSession.setUserSessionState(UserSessionState.ConnectedWithUsername);

		mockControl.checkOrder(true);
		
		try {
			EasyMock.expect(mockSocketChannel.write(EasyMock.capture(byteBufferCapture))).andReturn(0);
			mockMessageDistributor.addObserver(userSession);
			EasyMock.expectLastCall();
		} catch (IOException e) {
			fail("Unexpected exception");
		}
		
		mockControl.replay();
		
		userSession.setReadyToReceive();
		
		mockControl.verify();
		
		// Verify that the chat session and id remained unchanged.
		UserSessionState userSessionState = userSession.getSessionState();
		Assertions.assertEquals(UserSessionState.ConnectedReady, userSessionState);
		Assertions.assertEquals("hemant", userSession.getId());
		
		// Check if the "ready" was written to the captured buffer.
		ByteBuffer byteBuffer = byteBufferCapture.getValue();
		byte[] readyBuffer = new byte[5];
		byteBuffer.get(readyBuffer);
		Assertions.assertEquals("ready", new String(readyBuffer));
		

	}
	

	/**
	 * Test a complete message received from the client. Message is complete 
	 * when a newline '\n' is encountered in the data.
	 */
	@Test
	void testProcessDataForFullMessage() {
		
		// Expected full message - one that ends with newline.
		String expectedMessage = "Message from thakkar\n";
		
		// Create message capture to intercept the Message object when
		// UserSession invokes the MessageProcessor. We want to check
		// the message data.
		Capture<Message> messageCapture = EasyMock.newCapture();
		
		// Create a mock control to check the order of invocation
		// Expected:  1. Read SocketChannel 2. Invoke the Message Processor since full message is received
		// 3.  Invoke the SocketChannel to check for any more data
		IMocksControl mockControl = EasyMock.createStrictControl();
		SocketChannel mockSocketChannel = mockControl.createMock(SocketChannel.class);
		MessageProcessor mockMessageProcessor = mockControl.createMock(MessageProcessor.class);
		mockControl.checkOrder(true);
		
		try {
			// Simulate the mock to copy the expected data from socket channel onto a byte buffer
			// internally used by the implementation.
			EasyMock.expect(mockSocketChannel.read(EasyMock.anyObject(ByteBuffer.class))).andAnswer(new IAnswer<Integer>() {

				@Override
				public Integer answer() throws Throwable {
					ByteBuffer buffer = (ByteBuffer) EasyMock.getCurrentArguments()[0];
					buffer.put(expectedMessage.getBytes());
					return expectedMessage.length();
				}
				
			});
			
			// Invoke the MessageProcessor since a newline was encountered
			mockMessageProcessor.process(EasyMock.capture(messageCapture));
			EasyMock.expectLastCall();

			// Check for more data if any .... we are not expecting any
			EasyMock.expect(mockSocketChannel.read(EasyMock.anyObject(ByteBuffer.class))).andReturn(0);
		} catch (IOException e) {
			fail("Unexpected exception");
		}
		
		// Set up the user session in a chat session established state
		// with username 'hemant'
		UserSession userSession = new UserSession(mockSocketChannel);
		userSession.setMessageProcessor(mockMessageProcessor);
		userSession.setUsername("hemant");
		userSession.setUserSessionState(UserSessionState.ConnectedWithUsername);
		
		mockControl.replay();
		
		userSession.processData();
		
		mockControl.verify();
		
		// Verify that the chat session and id remained unchanged.
		UserSessionState userSessionState = userSession.getSessionState();
		Assertions.assertEquals(UserSessionState.ConnectedWithUsername, userSessionState);
		Assertions.assertEquals("hemant", userSession.getId());
		
		// Verify the Message object intercepted in the invocation to the
		// MessageProcessor has the simulated data transmission.
		Assertions.assertEquals(expectedMessage, messageCapture.getValue().getData());

	}

	/**
	 * Test a partial message received from the client. Message is partial 
	 * when a newline '\n' is NOT encountered in the data. Since the message
	 * is partial, we don't expect invocation on the MessageProcessor
	 */
	@Test
	void testProcessDataForPartialData() {
		
		// Expected full message - one that ends with newline.
		String expectedMessage = "Partial message data ...";
				
		// Create a mock control to check the order of invocation
		// Expected:  1. Read SocketChannel 2.  Invoke the SocketChannel to check for any more data
		IMocksControl mockControl = EasyMock.createStrictControl();
		SocketChannel mockSocketChannel = mockControl.createMock(SocketChannel.class);
		mockControl.checkOrder(true);
		
		try {
			// Simulate the mock to copy the expected data from socket channel onto a byte buffer
			// internally used by the implementation. It will simulate receipt of partial message which
			// is one that does not have a newline.
			EasyMock.expect(mockSocketChannel.read(EasyMock.anyObject(ByteBuffer.class))).andAnswer(new IAnswer<Integer>() {

				@Override
				public Integer answer() throws Throwable {
					ByteBuffer buffer = (ByteBuffer) EasyMock.getCurrentArguments()[0];
					buffer.put(expectedMessage.getBytes());
					return expectedMessage.length();
				}
				
			});
			
			// Check for more data if any .... we are not expecting any
			EasyMock.expect(mockSocketChannel.read(EasyMock.anyObject(ByteBuffer.class))).andReturn(0);
			EasyMock.expectLastCall();
		} catch (IOException e) {
			fail("Unexpected exception");
		}
		
		// Set up the user session in a chat session established state
		// with username 'hemant'
		UserSession userSession = new UserSession(mockSocketChannel);
		userSession.setUsername("hemant");
		userSession.setUserSessionState(UserSessionState.ConnectedReady);
		
		mockControl.replay();
		
		userSession.processData();
		
		mockControl.verify();
		
		// Verify that the chat session and id remained unchanged.
		UserSessionState userSessionState = userSession.getSessionState();
		Assertions.assertEquals(UserSessionState.ConnectedReady, userSessionState);
		Assertions.assertEquals("hemant", userSession.getId());
		
	}

	@Test
	void testMessageReceived() {
		String receivedMessage = "Received message\n";
		Capture<ByteBuffer> byteBufferCapture = EasyMock.newCapture();
		IMocksControl mockControl = EasyMock.createStrictControl();
		SocketChannel mockSocketChannel = mockControl.createMock(SocketChannel.class);
		
		// Set up the user session in a chat session established state
		// with username 'hemant'
		UserSession userSession = new UserSession(mockSocketChannel);
		userSession.setUsername("hemant");
		userSession.setUserSessionState(UserSessionState.ConnectedReady);

		mockControl.checkOrder(true);
		
		try {
			EasyMock.expect(mockSocketChannel.write(EasyMock.capture(byteBufferCapture))).andReturn(0);
		} catch (IOException e) {
			fail("Unexpected exception");
		}
		
		mockControl.replay();
		
		userSession.messageReceived(receivedMessage);
		
		mockControl.verify();
		
		// Verify that the chat session and id remained unchanged.
		UserSessionState userSessionState = userSession.getSessionState();
		Assertions.assertEquals(UserSessionState.ConnectedReady, userSessionState);
		Assertions.assertEquals("hemant", userSession.getId());
		
		// Check if the "ready" was written to the captured buffer.
		ByteBuffer byteBuffer = byteBufferCapture.getValue();
		byte[] readyBuffer = new byte[receivedMessage.length()];
		byteBuffer.get(readyBuffer);
		Assertions.assertEquals(receivedMessage, new String(readyBuffer));
	}
	

}
