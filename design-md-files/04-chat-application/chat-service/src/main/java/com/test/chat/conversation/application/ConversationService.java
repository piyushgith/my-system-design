package com.test.chat.conversation.application;

import com.test.chat.conversation.domain.Conversation;
import com.test.chat.conversation.domain.ConversationMember;
import com.test.chat.conversation.domain.ConversationType;
import com.test.chat.conversation.domain.MemberRole;
import com.test.chat.conversation.infrastructure.ConversationMemberRepository;
import com.test.chat.conversation.infrastructure.ConversationRepository;
import com.test.chat.shared.error.ChatException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ConversationService {

	private final ConversationRepository conversationRepository;
	private final ConversationMemberRepository memberRepository;
	private final int maxGroupMembers;

	public ConversationService(
			ConversationRepository conversationRepository,
			ConversationMemberRepository memberRepository,
			@Value("${chat.mvp.max-group-members}") int maxGroupMembers) {
		this.conversationRepository = conversationRepository;
		this.memberRepository = memberRepository;
		this.maxGroupMembers = maxGroupMembers;
	}

	@Transactional
	public Conversation createConversation(UUID creatorId, ConversationType type, String name, List<UUID> memberIds) {
		Set<UUID> uniqueMembers = new LinkedHashSet<>(memberIds);
		uniqueMembers.add(creatorId);

		if (type == ConversationType.DIRECT) {
			if (uniqueMembers.size() != 2) {
				throw ChatException.badRequest("INVALID_DIRECT", "Direct conversations require exactly two members");
			}
			List<UUID> members = new ArrayList<>(uniqueMembers);
			return conversationRepository.findDirectConversation(ConversationType.DIRECT, members.get(0), members.get(1))
					.orElseGet(() -> createNewConversation(creatorId, type, null, uniqueMembers));
		}

		if (type == ConversationType.GROUP && uniqueMembers.size() > maxGroupMembers) {
			throw ChatException.badRequest("GROUP_TOO_LARGE", "Group cannot exceed " + maxGroupMembers + " members in MVP");
		}

		return createNewConversation(creatorId, type, name, uniqueMembers);
	}

	@Transactional(readOnly = true)
	public Page<Conversation> listConversations(UUID userId, int limit) {
		return conversationRepository.findForUser(userId, PageRequest.of(0, Math.min(limit, 50)));
	}

	@Transactional(readOnly = true)
	public Conversation getConversation(UUID conversationId, UUID userId) {
		ensureMember(conversationId, userId);
		return conversationRepository.findById(conversationId)
				.filter(c -> !c.isDeleted())
				.orElseThrow(() -> ChatException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found"));
	}

	@Transactional(readOnly = true)
	public List<ConversationMember> getMembers(UUID conversationId, UUID userId) {
		ensureMember(conversationId, userId);
		return memberRepository.findActiveMembers(conversationId);
	}

	@Transactional
	public ConversationMember addMember(UUID conversationId, UUID requesterId, UUID newMemberId) {
		Conversation conversation = getConversation(conversationId, requesterId);
		ConversationMember requester = memberRepository.findActiveMember(conversationId, requesterId)
				.orElseThrow(() -> ChatException.forbidden("NOT_A_MEMBER", "You are not a member of this conversation"));
		if (requester.getRole() != MemberRole.OWNER && requester.getRole() != MemberRole.ADMIN) {
			throw ChatException.forbidden("FORBIDDEN", "Only owners or admins can add members");
		}
		if (conversation.getMemberCount() >= maxGroupMembers) {
			throw ChatException.badRequest("GROUP_TOO_LARGE", "Group cannot exceed " + maxGroupMembers + " members");
		}
		if (memberRepository.existsByConversationIdAndUserIdAndRemovedFalse(conversationId, newMemberId)) {
			throw ChatException.conflict("ALREADY_MEMBER", "User is already a member");
		}
		ConversationMember member = new ConversationMember(conversationId, newMemberId, MemberRole.MEMBER);
		memberRepository.save(member);
		conversation.incrementMemberCount();
		return member;
	}

	public void ensureMember(UUID conversationId, UUID userId) {
		if (!memberRepository.existsByConversationIdAndUserIdAndRemovedFalse(conversationId, userId)) {
			throw ChatException.forbidden("NOT_A_MEMBER", "You are not a member of this conversation");
		}
	}

	public List<UUID> getMemberUserIds(UUID conversationId) {
		return memberRepository.findActiveMembers(conversationId).stream()
				.map(ConversationMember::getUserId)
				.toList();
	}

	private Conversation createNewConversation(UUID creatorId, ConversationType type, String name, Set<UUID> memberIds) {
		Conversation conversation = new Conversation(type, name, creatorId);
		conversationRepository.save(conversation);
		for (UUID memberId : memberIds) {
			MemberRole role = memberId.equals(creatorId) ? MemberRole.OWNER : MemberRole.MEMBER;
			memberRepository.save(new ConversationMember(conversation.getConversationId(), memberId, role));
			conversation.incrementMemberCount();
		}
		return conversation;
	}
}
