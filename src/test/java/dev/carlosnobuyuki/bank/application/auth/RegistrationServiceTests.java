package dev.carlosnobuyuki.bank.application.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.carlosnobuyuki.bank.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class RegistrationServiceTests {

  private static final int ATTEMPTS = 10;

  @Autowired RegistrationService registration;
  @Autowired JdbcTemplate jdbc;

  @Test
  void concurrentRegistrationsOfOneNameCreateOneUser() throws Exception {
    String username = "race-" + UUID.randomUUID().toString().substring(0, 8);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Object>> results = new ArrayList<>();

    // Released together, the attempts all pass the existence check before any of them has finished
    // hashing, so the losers are caught by the unique constraint instead.
    try (ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS)) {
      for (int i = 0; i < ATTEMPTS; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    return registration.register(username, "correct horse battery");
                  } catch (UsernameTakenException e) {
                    return e;
                  }
                }));
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    }

    List<Object> outcomes = new ArrayList<>();
    for (Future<Object> result : results) {
      outcomes.add(result.get());
    }
    assertThat(outcomes).filteredOn(o -> !(o instanceof UsernameTakenException)).hasSize(1);
    assertThat(outcomes).filteredOn(UsernameTakenException.class::isInstance).hasSize(ATTEMPTS - 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username = ?", Integer.class, username))
        .isEqualTo(1);
  }
}
