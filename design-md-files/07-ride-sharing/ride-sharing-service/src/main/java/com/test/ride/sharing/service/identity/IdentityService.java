package com.test.ride.sharing.service.identity;

import com.test.ride.sharing.service.shared.UserRole;
import com.test.ride.sharing.service.shared.Uuids;
import com.test.ride.sharing.service.web.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class IdentityService {

    private final RiderRepository riderRepository;
    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final CityRepository cityRepository;

    public IdentityService(RiderRepository riderRepository,
                           DriverRepository driverRepository,
                           VehicleRepository vehicleRepository,
                           CityRepository cityRepository) {
        this.riderRepository = riderRepository;
        this.driverRepository = driverRepository;
        this.vehicleRepository = vehicleRepository;
        this.cityRepository = cityRepository;
    }

    public Rider getRider(UUID riderId) {
        return riderRepository.findById(riderId)
                .orElseThrow(() -> new ResourceNotFoundException("Rider not found: " + riderId));
    }

    public Driver getDriver(UUID driverId) {
        return driverRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found: " + driverId));
    }

    public Vehicle getActiveVehicle(UUID driverId) {
        return vehicleRepository.findByDriverIdAndActiveTrue(driverId).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No active vehicle for driver: " + driverId));
    }

    public Vehicle getVehicle(UUID vehicleId) {
        return vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + vehicleId));
    }

    public City getCity(UUID cityId) {
        return cityRepository.findById(cityId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));
    }

    public City getCityByCodeOrThrow(String code) {
        return cityRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + code));
    }

    @Transactional
    public Rider registerRider(String phoneNumber, String fullName, String email) {
        return riderRepository.findByPhoneNumber(phoneNumber).orElseGet(() -> {
            Rider rider = new Rider();
            rider.setRiderId(Uuids.v7());
            rider.setPhoneNumber(phoneNumber);
            rider.setFullName(fullName);
            rider.setEmail(email);
            return riderRepository.save(rider);
        });
    }

    @Transactional
    public Driver registerDriver(String phoneNumber, String fullName, UUID cityId) {
        getCity(cityId);
        return driverRepository.findByPhoneNumber(phoneNumber).orElseGet(() -> {
            Driver driver = new Driver();
            driver.setDriverId(Uuids.v7());
            driver.setPhoneNumber(phoneNumber);
            driver.setFullName(fullName);
            driver.setCityId(cityId);
            return driverRepository.save(driver);
        });
    }

    public boolean userExists(UUID userId, UserRole role) {
        return switch (role) {
            case RIDER -> riderRepository.existsById(userId);
            case DRIVER -> driverRepository.existsById(userId);
            case ADMIN -> true;
        };
    }
}
