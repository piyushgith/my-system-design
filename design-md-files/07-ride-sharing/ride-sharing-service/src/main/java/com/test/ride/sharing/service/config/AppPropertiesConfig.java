package com.test.ride.sharing.service.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import com.test.ride.sharing.service.event.EventProperties;
import com.test.ride.sharing.service.location.LocationProperties;
import com.test.ride.sharing.service.matching.MatchingProperties;
import com.test.ride.sharing.service.notification.NotificationProperties;
import com.test.ride.sharing.service.payment.PaymentProperties;
import com.test.ride.sharing.service.routing.RoutingProperties;
import com.test.ride.sharing.service.trip.TripProperties;

@Configuration
@EnableConfigurationProperties({
        RoutingProperties.class,
        MatchingProperties.class,
        LocationProperties.class,
        PaymentProperties.class,
        NotificationProperties.class,
        EventProperties.class,
        TripProperties.class
})
public class AppPropertiesConfig {
}
