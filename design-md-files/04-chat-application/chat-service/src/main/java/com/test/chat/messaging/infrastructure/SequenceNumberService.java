package com.test.chat.messaging.infrastructure;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SequenceNumberService {

	private final StringRedisTemplate redisTemplate;
	private final MessageRepository messageRepository;

	public SequenceNumberService(StringRedisTemplate redisTemplate, MessageRepository messageRepository) {
		this.redisTemplate = redisTemplate;
		this.messageRepository = messageRepository;
	}

	public long nextSequence(UUID conversationId) {
		String key = "seq:" + conversationId;
		Long value = redisTemplate.opsForValue().increment(key);
		if (value != null && value > 1) {
			return value;
		}
		long max = messageRepository.findMaxSequence(conversationId);
		redisTemplate.opsForValue().set(key, Long.toString(max + 1));
		return max + 1;
	}
}
