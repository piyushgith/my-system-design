package com.test.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class ChatServiceApplicationTests {

	@MockBean
	private StringRedisTemplate stringRedisTemplate;

	@Test
	void contextLoads() {
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.increment(anyString())).thenReturn(1L);
		when(valueOps.get(anyString())).thenReturn(null);
	}
}
