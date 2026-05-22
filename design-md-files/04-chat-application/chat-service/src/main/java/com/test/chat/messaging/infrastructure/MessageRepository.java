package com.test.chat.messaging.infrastructure;

import com.test.chat.messaging.domain.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

	Optional<Message> findByConversationIdAndIdempotencyKey(UUID conversationId, String idempotencyKey);

	@Query("""
			SELECT m FROM Message m
			WHERE m.conversationId = :conversationId AND m.deleted = false
			  AND (:beforeSeq IS NULL OR m.sequenceNum < :beforeSeq)
			ORDER BY m.sequenceNum DESC
			""")
	List<Message> findHistory(
			@Param("conversationId") UUID conversationId,
			@Param("beforeSeq") Long beforeSeq,
			Pageable pageable);

	@Query("""
			SELECT COALESCE(MAX(m.sequenceNum), 0) FROM Message m
			WHERE m.conversationId = :conversationId
			""")
	long findMaxSequence(@Param("conversationId") UUID conversationId);
}
