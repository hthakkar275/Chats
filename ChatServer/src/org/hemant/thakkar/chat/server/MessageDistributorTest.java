package org.hemant.thakkar.chat.server;

import java.util.ArrayList;
import java.util.List;

import org.easymock.EasyMock;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageDistributorTest {

	
	private MessageObserverImpl one;
	private MessageObserverImpl two;
	private MessageObserverImpl three;

	private MessageDistributor messageDistributor;

	@BeforeEach
	void setup() {
		one = new MessageObserverImpl("one");
		two = new MessageObserverImpl("two");
		three = new MessageObserverImpl("three");		
		messageDistributor = new MessageDistributor();
	}
	
	/**
	 * Test that there are no exceptions or errors when
	 * there are no chat clients connected.
	 */
	@Test
	void testNoClient() {
		Message mockMessage = EasyMock.createMock(Message.class);
		EasyMock.expect(mockMessage.getDestination()).andReturn(null);
		
		EasyMock.replay(mockMessage);
		messageDistributor.distributeMessage(mockMessage);
		EasyMock.verify(mockMessage);
		
		Assertions.assertTrue(one.getReceivedMessages().isEmpty());
		Assertions.assertTrue(two.getReceivedMessages().isEmpty());
		Assertions.assertTrue(three.getReceivedMessages().isEmpty());
		
	}
	
	/**
	 * When there is only one client, there should not be any
	 * messages distributed since the source of the message 
	 * (i.e. the single client that is connected) should not
	 * receive its own message.
	 */
	@Test
	void testSingleClient() {
		Message mockMessage = EasyMock.createMock(Message.class);
		EasyMock.expect(mockMessage.getDestination()).andReturn(null);
		EasyMock.expect(mockMessage.getSource()).andReturn("one");
		
		EasyMock.replay(mockMessage);
		messageDistributor.addObserver(one);
		messageDistributor.distributeMessage(mockMessage);
		EasyMock.verify(mockMessage);
		
		Assertions.assertTrue(one.getReceivedMessages().isEmpty());
		Assertions.assertTrue(two.getReceivedMessages().isEmpty());
		Assertions.assertTrue(three.getReceivedMessages().isEmpty());

	}
	
	/**
	 * When there are multiple clients, clients other than the
	 * message source should receive the message.
	 */
	@Test 
	void testMultipleClients() {
		Message mockMessage = EasyMock.createMock(Message.class);
		EasyMock.expect(mockMessage.getDestination()).andReturn(null);
		EasyMock.expect(mockMessage.getSource()).andReturn("one").anyTimes();
		EasyMock.expect(mockMessage.getData()).andReturn("Message from one").times(2);
		messageDistributor.addObserver(one);
		messageDistributor.addObserver(two);
		messageDistributor.addObserver(three);

		EasyMock.replay(mockMessage);
		messageDistributor.distributeMessage(mockMessage);
		EasyMock.verify(mockMessage);
		
		Assertions.assertTrue(one.getReceivedMessages().isEmpty());
		Assertions.assertEquals(1, two.getReceivedMessages().size());
		Assertions.assertEquals(1, three.getReceivedMessages().size());
		Assertions.assertEquals("one: Message from one", two.getReceivedMessages().get(0));
		Assertions.assertEquals("one: Message from one", three.getReceivedMessages().get(0));

	}
	
	/**
	 * When a source client sends a message directed to a specific
	 * client, the message should be received only by that directed
	 * client.
	 */
	@Test
	void testDirectedClient() {
		Message mockMessage = EasyMock.createMock(Message.class);
		EasyMock.expect(mockMessage.getDestination()).andReturn("two");
		EasyMock.expect(mockMessage.getSource()).andReturn("one").times(1);
		EasyMock.expect(mockMessage.getData()).andReturn("Message from one").times(1);
		messageDistributor.addObserver(one);
		messageDistributor.addObserver(two);
		messageDistributor.addObserver(three);

		EasyMock.replay(mockMessage);
		messageDistributor.distributeMessage(mockMessage);
		EasyMock.verify(mockMessage);
		
		Assertions.assertTrue(one.getReceivedMessages().isEmpty());
		Assertions.assertEquals(1, two.getReceivedMessages().size());
		Assertions.assertEquals(0, three.getReceivedMessages().size());
		Assertions.assertEquals("one: Message from one", two.getReceivedMessages().get(0));
	}
	
	/**
	 * A client that requests a message to be blocked from a specific
	 * client should not receive messages from that blocked client.
	 */
	@Test
	void testBlockedClient() {
		Message mockMessage = EasyMock.createMock(Message.class);
		EasyMock.expect(mockMessage.getDestination()).andReturn(null);
		EasyMock.expect(mockMessage.getSource()).andReturn("one").anyTimes();
		EasyMock.expect(mockMessage.getData()).andReturn("Message from one").times(2);
		messageDistributor.addObserver(one);
		messageDistributor.addObserver(two);
		messageDistributor.addObserver(three);
		messageDistributor.blockSource("one", "two");

		EasyMock.replay(mockMessage);
		messageDistributor.distributeMessage(mockMessage);
		EasyMock.verify(mockMessage);
		
		Assertions.assertTrue(one.getReceivedMessages().isEmpty());
		Assertions.assertEquals(0, two.getReceivedMessages().size());
		Assertions.assertEquals(1, three.getReceivedMessages().size());
		Assertions.assertEquals("one: Message from one", three.getReceivedMessages().get(0));
	}
	
	/**
	 * A client that was previously blocked but has now been unblocked
	 * should have its messages sent to the recipient that had blocked
	 * this client.
	 */
	@Test
	void testUnblockedClient() {
		Message mockMessage = EasyMock.createMock(Message.class);
		EasyMock.expect(mockMessage.getDestination()).andReturn(null);
		EasyMock.expect(mockMessage.getSource()).andReturn("one").anyTimes();
		EasyMock.expect(mockMessage.getData()).andReturn("Message from one").times(2);
		messageDistributor.addObserver(one);
		messageDistributor.addObserver(two);
		messageDistributor.addObserver(three);
		messageDistributor.blockSource("one", "two");
		messageDistributor.unblockSource("one", "two");

		EasyMock.replay(mockMessage);
		messageDistributor.distributeMessage(mockMessage);
		EasyMock.verify(mockMessage);
		
		Assertions.assertTrue(one.getReceivedMessages().isEmpty());
		Assertions.assertEquals(1, two.getReceivedMessages().size());
		Assertions.assertEquals(1, three.getReceivedMessages().size());
		Assertions.assertEquals("one: Message from one", two.getReceivedMessages().get(0));
		Assertions.assertEquals("one: Message from one", three.getReceivedMessages().get(0));
	}
	
	/**
	 * Test that a removed client does not receive the messages.
	 */
	@Test
	void testRemovedClient() {
		Message mockMessage = EasyMock.createMock(Message.class);
		EasyMock.expect(mockMessage.getDestination()).andReturn(null).times(2);
		EasyMock.expect(mockMessage.getSource()).andReturn("one").anyTimes();
		EasyMock.expect(mockMessage.getData()).andReturn("Message from one").times(3);
		messageDistributor.addObserver(one);
		messageDistributor.addObserver(two);
		messageDistributor.addObserver(three);

		EasyMock.replay(mockMessage);
		messageDistributor.distributeMessage(mockMessage);
		messageDistributor.removeObserver(three);
		messageDistributor.distributeMessage(mockMessage);
		EasyMock.verify(mockMessage);
		
		Assertions.assertTrue(one.getReceivedMessages().isEmpty());
		Assertions.assertEquals(2, two.getReceivedMessages().size());
		Assertions.assertEquals(1, three.getReceivedMessages().size());
		Assertions.assertEquals("one: Message from one", two.getReceivedMessages().get(0));
		Assertions.assertEquals("one: Message from one", two.getReceivedMessages().get(1));
		Assertions.assertEquals("one: Message from one", three.getReceivedMessages().get(0));
	}
	
	
	private class MessageObserverImpl implements MessageObserver {

		private String id;
		private List<String> receivedMessages = new ArrayList<>();
		
		public MessageObserverImpl(String id) {
			this.id = id;
		}
		
		@Override
		public String getId() {
			return id;
		}

		@Override
		public void messageReceived(String message) {
			receivedMessages.add(message);
		}
		
		public List<String> getReceivedMessages() {
			return receivedMessages;
		}
	}

}
