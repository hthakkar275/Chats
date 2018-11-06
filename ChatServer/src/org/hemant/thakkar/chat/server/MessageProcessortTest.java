package org.hemant.thakkar.chat.server;

import org.easymock.EasyMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageProcessortTest {

	private MessageProcessor messageProcessor;
	private MessageDistributor mockMessageDistributor;
	
	@BeforeEach 
	void setup() {
		mockMessageDistributor = EasyMock.createMock(MessageDistributor.class);
		messageProcessor = MessageProcessor.getInstance();
		messageProcessor.setMessageDistributor(mockMessageDistributor);
	}
	
	@Test
	void testMessage() {
		Message message = new Message();
		message.setData("This is a regular message");
		mockMessageDistributor.distributeMessage(EasyMock.eq(message));
		EasyMock.expectLastCall();
		EasyMock.replay(mockMessageDistributor);
		
		messageProcessor.process(message);
		
		EasyMock.verify(mockMessageDistributor);
	}

	@Test
	void testBlockCommand() {
		Message message = new Message();
		message.setSource("user1");
		message.setData("\\\\block user2");
		mockMessageDistributor.blockSource(EasyMock.eq("user1"), EasyMock.eq("user2"));
		EasyMock.expectLastCall();
		EasyMock.replay(mockMessageDistributor);
		
		messageProcessor.process(message);
		
		EasyMock.verify(mockMessageDistributor);
	}
	
	@Test 
	void testUnblockCommand() {
		Message message = new Message();
		message.setSource("user1");
		message.setData("\\\\unblock user2");
		mockMessageDistributor.unblockSource(EasyMock.eq("user1"), EasyMock.eq("user2"));
		EasyMock.expectLastCall();
		EasyMock.replay(mockMessageDistributor);
		
		messageProcessor.process(message);
		
		EasyMock.verify(mockMessageDistributor);
	}
}
