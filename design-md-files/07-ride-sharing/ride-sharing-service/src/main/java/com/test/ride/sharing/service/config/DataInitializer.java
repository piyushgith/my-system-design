package com.test.ride.sharing.service.config;

import com.test.ride.sharing.service.identity.City;
import com.test.ride.sharing.service.identity.CityRepository;
import com.test.ride.sharing.service.identity.Driver;
import com.test.ride.sharing.service.identity.DriverRepository;
import com.test.ride.sharing.service.identity.Rider;
import com.test.ride.sharing.service.identity.RiderRepository;
import com.test.ride.sharing.service.identity.Vehicle;
import com.test.ride.sharing.service.identity.VehicleRepository;
import com.test.ride.sharing.service.shared.VehicleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    public static final UUID BANGALORE_CITY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID SEED_RIDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID SEED_DRIVER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    public static final UUID SEED_VEHICLE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    /** @deprecated use {@link #BANGALORE_CITY_ID} */
    @Deprecated
    public static final UUID MUMBAI_CITY_ID = BANGALORE_CITY_ID;

    @Bean
    CommandLineRunner seedDemoData(CityRepository cityRepository,
                                   RiderRepository riderRepository,
                                   DriverRepository driverRepository,
                                   VehicleRepository vehicleRepository) {
        return args -> {
            if (cityRepository.count() > 0) {
                return;
            }

            City bangalore = new City();
            bangalore.setCityId(BANGALORE_CITY_ID);
            bangalore.setCode("BLR");
            bangalore.setName("Bangalore");
            bangalore.setCountryCode("IN");
            cityRepository.save(bangalore);

            Rider rider = new Rider();
            rider.setRiderId(SEED_RIDER_ID);
            rider.setPhoneNumber("+919900000001");
            rider.setFullName("Demo Rider");
            rider.setEmail("rider@example.com");
            riderRepository.save(rider);

            Driver driver = new Driver();
            driver.setDriverId(SEED_DRIVER_ID);
            driver.setPhoneNumber("+919900000002");
            driver.setFullName("Demo Driver");
            driver.setCityId(BANGALORE_CITY_ID);
            driverRepository.save(driver);

            Vehicle vehicle = new Vehicle();
            vehicle.setVehicleId(SEED_VEHICLE_ID);
            vehicle.setDriverId(SEED_DRIVER_ID);
            vehicle.setRegistrationNumber("KA-01-AB-1234");
            vehicle.setVehicleType(VehicleType.ECONOMY);
            vehicle.setMake("Toyota");
            vehicle.setModel("Etios");
            vehicle.setYear(2022);
            vehicle.setColor("White");
            vehicleRepository.save(vehicle);

            log.info("""
                    Seeded demo data (Bangalore):
                      city_id={} (BLR)
                      rider: X-Uid=rider
                      driver: X-Uid=driver
                      vehicle_id={}
                      sample pickup: 12.9716, 77.5946 (MG Road)
                      sample destination: 12.9352, 77.6245 (Koramangala)
                    """, BANGALORE_CITY_ID, SEED_VEHICLE_ID);
        };
    }
}
