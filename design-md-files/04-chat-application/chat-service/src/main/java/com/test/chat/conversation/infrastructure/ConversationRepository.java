package com.test.chat.conversation.infrastructure;

import com.test.chat.conversation.domain.Conversation;
import com.test.chat.conversation.domain.ConversationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

	@Query("""
			SELECT c FROM Conversation c
			JOIN ConversationMember m ON m.conversationId = c.conversationId
			WHERE m.userId = :userId AND m.removed = false AND c.deleted = false
			ORDER BY c.lastMessageAt DESC NULLS LAST, c.createdAt DESC
			""")
	Page<Conversation> findForUser(@Param("userId") UUID userId, Pageable pageable);

	@Query("""
			SELECT c FROM Conversation c
			JOIN ConversationMember m1 ON m1.conversationId = c.conversationId
			JOIN ConversationMember m2 ON m2.conversationId = c.conversationId
			WHERE c.type = :type AND c.deleted = false
			  AND m1.userId = :userA AND m2.userId = :userB
			  AND m1.removed = false AND m2.removed = false
			""")
	Optional<Conversation> findDirectConversation(
			@Param("type") ConversationType type,
			@Param("userA") UUID userA,
			@Param("userB") UUID userB);

	List<Conversation> findByConversationIdInAndDeletedFalse(List<UUID> ids);
}
