package com.test.chat.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages")
public class Message {

	@Id
	@Column(name = "message_id")
	private UUID messageId;

	@Column(name = "conversation_id", nullable = false)
	private UUID conversationId;

	@Column(name = "sender_id", nullable = false)
	private UUID senderId;

	@Column(name = "sequence_num", nullable = false)
	private long sequenceNum;

	@Column(name = "content_type", nullable = false, length = 20)
	private String contentType;

	@Column(nullable = false)
	private String content;

	@Column(name = "sent_at", nullable = false)
	private Instant sentAt;

	@Column(name = "server_received_at", nullable = false)
	private Instant serverReceivedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MessageStatus status;

	@Column(name = "is_deleted", nullable = false)
	private boolean deleted;

	@Column(name = "idempotency_key", length = 64)
	private String idempotencyKey;

	protected Message() {
	}

	public Message(
			UUID conversationId,
			UUID senderId,
			long sequenceNum,
			String contentType,
			String content,
			String idempotencyKey) {
		this.messageId = UUID.randomUUID();
		this.conversationId = conversationId;
		this.senderId = senderId;
		this.sequenceNum = sequenceNum;
		this.contentType = contentType;
		this.content = content;
		this.sentAt = Instant.now();
		this.serverReceivedAt = Instant.now();
		this.status = MessageStatus.SENT;
		this.deleted = false;
		this.idempotencyKey = idempotencyKey;
	}

	public UUID getMessageId() {
		return messageId;
	}

	public UUID getConversationId() {
		return conversationId;
	}

	public UUID getSenderId() {
		return senderId;
	}

	public long getSequenceNum() {
		return sequenceNum;
	}

	public String getContentType() {
		return contentType;
	}

	public String getContent() {
		return content;
	}

	public Instant getSentAt() {
		return sentAt;
	}

	public Instant getServerReceivedAt() {
		return serverReceivedAt;
	}

	public MessageStatus getStatus() {
		return status;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}
}
