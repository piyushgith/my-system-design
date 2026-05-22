package com.test.chat.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversations")
public class Conversation {

	@Id
	@Column(name = "conversation_id")
	private UUID conversationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ConversationType type;

	private String name;

	@Column(name = "creator_id", nullable = false)
	private UUID creatorId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_message_at")
	private Instant lastMessageAt;

	@Column(name = "member_count", nullable = false)
	private int memberCount;

	@Column(name = "is_deleted", nullable = false)
	private boolean deleted;

	protected Conversation() {
	}

	public Conversation(ConversationType type, String name, UUID creatorId) {
		this.conversationId = UUID.randomUUID();
		this.type = type;
		this.name = name;
		this.creatorId = creatorId;
		this.createdAt = Instant.now();
		this.memberCount = 0;
		this.deleted = false;
	}

	public UUID getConversationId() {
		return conversationId;
	}

	public ConversationType getType() {
		return type;
	}

	public String getName() {
		return name;
	}

	public UUID getCreatorId() {
		return creatorId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastMessageAt() {
		return lastMessageAt;
	}

	public int getMemberCount() {
		return memberCount;
	}

	public boolean isDeleted() {
		return deleted;
	}

	public void setLastMessageAt(Instant lastMessageAt) {
		this.lastMessageAt = lastMessageAt;
	}

	public void incrementMemberCount() {
		this.memberCount++;
	}
}
