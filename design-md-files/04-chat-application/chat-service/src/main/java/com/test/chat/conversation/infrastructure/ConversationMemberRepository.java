package com.test.chat.conversation.infrastructure;

import com.test.chat.conversation.domain.ConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, ConversationMember.ConversationMemberId> {

	@Query("""
			SELECT m FROM ConversationMember m
			WHERE m.conversationId = :conversationId AND m.removed = false
			""")
	List<ConversationMember> findActiveMembers(@Param("conversationId") UUID conversationId);

	@Query("""
			SELECT m FROM ConversationMember m
			WHERE m.conversationId = :conversationId AND m.userId = :userId AND m.removed = false
			""")
	Optional<ConversationMember> findActiveMember(
			@Param("conversationId") UUID conversationId,
			@Param("userId") UUID userId);

	boolean existsByConversationIdAndUserIdAndRemovedFalse(UUID conversationId, UUID userId);
}
