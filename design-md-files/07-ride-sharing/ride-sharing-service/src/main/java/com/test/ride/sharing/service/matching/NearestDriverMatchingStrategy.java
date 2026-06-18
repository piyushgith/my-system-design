package com.test.ride.sharing.service.matching;

import com.test.ride.sharing.service.location.NearbyDriver;
import com.test.ride.sharing.service.shared.GeoPoint;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class NearestDriverMatchingStrategy implements MatchingStrategy {

    @Override
    public String name() {
        return "nearest";
    }

    @Override
    public Optional<NearbyDriver> selectDriver(List<NearbyDriver> candidates, GeoPoint pickup) {
        return candidates.stream()
                .min(Comparator.comparingDouble(NearbyDriver::distanceKm));
    }
}
