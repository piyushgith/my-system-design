package com.test.ride.sharing.service.matching;

import com.test.ride.sharing.service.location.NearbyDriver;
import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.VehicleType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MatchingStrategy {

    String name();

    Optional<NearbyDriver> selectDriver(List<NearbyDriver> candidates, GeoPoint pickup);
}
