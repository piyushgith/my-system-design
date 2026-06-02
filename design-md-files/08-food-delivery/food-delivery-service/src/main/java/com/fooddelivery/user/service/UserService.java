package com.fooddelivery.user.service;

import com.fooddelivery.common.exception.ConflictException;
import com.fooddelivery.common.exception.NotFoundException;
import com.fooddelivery.common.security.JwtService;
import com.fooddelivery.restaurant.repository.RestaurantRepository;
import com.fooddelivery.user.domain.Address;
import com.fooddelivery.user.domain.User;
import com.fooddelivery.user.domain.UserStatus;
import com.fooddelivery.user.repository.AddressRepository;
import com.fooddelivery.user.repository.UserRepository;
import com.fooddelivery.user.service.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String OTP_PREFIX = "otp:";
    private static final Duration OTP_TTL = Duration.ofMinutes(5);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final RestaurantRepository restaurantRepository;
    private final JwtService jwtService;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;

    /** Send OTP — in MVP we log it; production would call SMS gateway */
    public void sendOtp(String phone) {
        String otp = generateOtp();
        redisTemplate.opsForValue().set(OTP_PREFIX + phone, otp, OTP_TTL);
        // TODO: integrate SMS gateway (Twilio / Kaleyra)
        log.info("[SMS STUB] OTP for {}: {}", phone, otp);
    }

    @Transactional
    public AuthResponse verifyOtp(String phone, String otp) {
        String stored = redisTemplate.opsForValue().get(OTP_PREFIX + phone);
        if (stored == null || !stored.equals(otp)) {
            throw new IllegalArgumentException("Invalid or expired OTP");
        }
        redisTemplate.delete(OTP_PREFIX + phone);

        User user = userRepository.findByPhone(phone).orElseGet(() -> createUser(phone));
        String role = restaurantRepository.existsByOwnerId(user.getId()) ? "RESTAURANT_OWNER" : "CUSTOMER";
        String token = jwtService.generateToken(user.getId().toString(), role);
        return new AuthResponse(token, user.getId(), user.getName(), user.getPhone());
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        return UserProfileResponse.from(findActiveUser(userId));
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = findActiveUser(userId);
        if (request.name() != null) {
            user.setName(request.name());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        try {
            return UserProfileResponse.from(userRepository.save(user));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Email already in use");
        }
    }

    @Transactional
    public AddressResponse addAddress(UUID userId, AddAddressRequest request) {
        findActiveUser(userId);
        Address address = Address.builder()
                .userId(userId)
                .label(request.label())
                .fullAddress(request.fullAddress())
                .city(request.city())
                .pinCode(request.pinCode())
                .country(request.country() != null ? request.country() : "IN")
                .latitude(request.latitude())
                .longitude(request.longitude())
                .landmark(request.landmark())
                .isDefault(request.isDefault())
                .build();
        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> listAddresses(UUID userId) {
        return addressRepository.findByUserId(userId)
                .stream().map(AddressResponse::from).toList();
    }

    @Transactional
    public void deleteAddress(UUID userId, UUID addressId) {
        Address address = addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new NotFoundException("Address not found"));
        addressRepository.delete(address);
    }

    private User createUser(String phone) {
        User user = User.builder()
                .phone(phone)
                .name("User")
                .email(phone + "@placeholder.local")
                .status(UserStatus.ACTIVE)
                .loyaltyPoints(0)
                .build();
        return userRepository.save(user);
    }

    private User findActiveUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (!user.isActive()) {
            throw new IllegalStateException("User account is not active");
        }
        return user;
    }

    private static String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }
}
