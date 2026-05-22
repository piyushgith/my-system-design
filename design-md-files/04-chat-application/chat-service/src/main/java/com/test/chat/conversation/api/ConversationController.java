package com.test.chat.conversation.api;

import com.test.chat.conversation.api.dto.AddMemberRequest;
import com.test.chat.conversation.api.dto.ConversationListResponse;
import com.test.chat.conversation.api.dto.ConversationResponse;
import com.test.chat.conversation.api.dto.CreateConversationRequest;
import com.test.chat.conversation.application.ConversationService;
import com.test.chat.conversation.domain.Conversation;
import com.test.chat.conversation.domain.ConversationMember;
import com.test.chat.shared.security.AuthContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

	private final ConversationService conversationService;

	public ConversationController(ConversationService conversationService) {
		this.conversationService = conversationService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ConversationResponse create(@Valid @RequestBody CreateConversationRequest request) {
		UUID userId = AuthContext.currentUserId();
		Conversation conversation = conversationService.createConversation(
				userId, request.type(), request.name(), request.memberIds());
		List<ConversationMember> members = conversationService.getMembers(conversation.getConversationId(), userId);
		return toResponse(conversation, members);
	}

	@GetMapping
	public ConversationListResponse list(@RequestParam(defaultValue = "20") int limit) {
		UUID userId = AuthContext.currentUserId();
		var page = conversationService.listConversations(userId, limit);
		List<ConversationListResponse.ConversationSummary> summaries = page.getContent().stream()
				.map(c -> new ConversationListResponse.ConversationSummary(
						c.getConversationId(), c.getType(), c.getName(), c.getLastMessageAt(), c.getMemberCount()))
				.toList();
		return new ConversationListResponse(summaries, page.hasNext());
	}

	@GetMapping("/{conversationId}")
	public ConversationResponse get(@PathVariable UUID conversationId) {
		UUID userId = AuthContext.currentUserId();
		Conversation conversation = conversationService.getConversation(conversationId, userId);
		List<ConversationMember> members = conversationService.getMembers(conversationId, userId);
		return toResponse(conversation, members);
	}

	@GetMapping("/{conversationId}/members")
	public List<ConversationResponse.MemberResponse> members(@PathVariable UUID conversationId) {
		UUID userId = AuthContext.currentUserId();
		return conversationService.getMembers(conversationId, userId).stream()
				.map(m -> new ConversationResponse.MemberResponse(m.getUserId(), m.getRole(), m.getJoinedAt()))
				.toList();
	}

	@PostMapping("/{conversationId}/members")
	public ConversationResponse.MemberResponse addMember(
			@PathVariable UUID conversationId,
			@Valid @RequestBody AddMemberRequest request) {
		UUID userId = AuthContext.currentUserId();
		ConversationMember member = conversationService.addMember(conversationId, userId, request.userId());
		return new ConversationResponse.MemberResponse(member.getUserId(), member.getRole(), member.getJoinedAt());
	}

	private ConversationResponse toResponse(Conversation conversation, List<ConversationMember> members) {
		List<ConversationResponse.MemberResponse> memberResponses = members.stream()
				.map(m -> new ConversationResponse.MemberResponse(m.getUserId(), m.getRole(), m.getJoinedAt()))
				.toList();
		return new ConversationResponse(
				conversation.getConversationId(),
				conversation.getType(),
				conversation.getName(),
				conversation.getCreatedAt(),
				memberResponses);
	}
}
