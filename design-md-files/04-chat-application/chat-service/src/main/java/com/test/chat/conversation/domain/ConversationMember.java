package com.test.chat.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversation_members")
@IdClass(ConversationMember.ConversationMemberId.class)
public class ConversationMember {

	@Id
	@Column(name = "conversation_id")
	private UUID conversationId;

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private MemberRole role;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	@Column(name = "last_read_seq", nullable = false)
	private long lastReadSeq;

	@Column(name = "is_removed", nullable = false)
	private boolean removed;

	protected ConversationMember() {
	}

	public ConversationMember(UUID conversationId, UUID userId, MemberRole role) {
		this.conversationId = conversationId;
		this.userId = userId;
		this.role = role;
		this.joinedAt = Instant.now();
		this.lastReadSeq = 0;
		this.removed = false;
	}

	public UUID getConversationId() {
		return conversationId;
	}

	public UUID getUserId() {
		return userId;
	}

	public MemberRole getRole() {
		return role;
	}

	public Instant getJoinedAt() {
		return joinedAt;
	}

	public long getLastReadSeq() {
		return lastReadSeq;
	}

	public boolean isRemoved() {
		return removed;
	}

	public static class ConversationMemberId implements Serializable {
		private UUID conversationId;
		private UUID userId;

		public ConversationMemberId() {
		}

		public ConversationMemberId(UUID conversationId, UUID userId) {
			this.conversationId = conversationId;
			this.userId = userId;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ConversationMemberId that)) {
				return false;
			}
			return conversationId.equals(that.conversationId) && userId.equals(that.userId);
		}

		@Override
		public int hashCode() {
			return conversationId.hashCode() * 31 + userId.hashCode();
		}
	}
}
