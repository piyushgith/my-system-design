package com.test.notification.domain.repository;

import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.model.UserNotificationPreference;
import com.test.notification.domain.model.UserPreferenceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserPreferenceRepository extends JpaRepository<UserNotificationPreference, UserPreferenceId> {

    List<UserNotificationPreference> findByUserId(UUID userId);

    Optional<UserNotificationPreference> findByUnsubscribeToken(String token);

    List<UserNotificationPreference> findByUserIdAndChannel(UUID userId, Channel channel);
}
