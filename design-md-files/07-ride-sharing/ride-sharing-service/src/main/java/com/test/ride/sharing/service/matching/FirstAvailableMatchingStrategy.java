package com.test.ride.sharing.service.matching;

import com.test.ride.sharing.service.location.NearbyDriver;
import com.test.ride.sharing.service.shared.GeoPoint;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class FirstAvailableMatchingStrategy implements MatchingStrategy {

    @Override
    public String name() {
        return "first";
    }

    @Override
    public Optional<NearbyDriver> selectDriver(List<NearbyDriver> candidates, GeoPoint pickup) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.getFirst());
    }
}
