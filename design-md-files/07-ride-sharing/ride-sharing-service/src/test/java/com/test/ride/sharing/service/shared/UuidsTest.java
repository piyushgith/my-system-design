package com.test.ride.sharing.service.shared;

import org.junit.jupiter.api.RepeatedTest;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidsTest {

    @RepeatedTest(5)
    void generatesUuidVersion7() {
        UUID id = Uuids.v7();
        assertThat(id.version()).isEqualTo(7);
    }

    @RepeatedTest(3)
    void generatesUniqueIds() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(Uuids.v7());
        }
        assertThat(ids).hasSize(100);
    }

    @RepeatedTest(3)
    void isMonotonicallyIncreasing() throws InterruptedException {
        UUID first = Uuids.v7();
        Thread.sleep(2);
        UUID second = Uuids.v7();
        assertThat(second.compareTo(first)).isGreaterThan(0);
    }
}
