package dev.carlosnobuyuki.bank;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * System time plus an offset that tests can move forward, so time-dependent rules such as token
 * expiry are tested without sleeping. {@link ResetClockExtension} puts it back after each test.
 */
public final class MutableClock extends Clock {

  private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

  public void advance(Duration amount) {
    offset.accumulateAndGet(amount, Duration::plus);
  }

  void reset() {
    offset.set(Duration.ZERO);
  }

  @Override
  public Instant instant() {
    return Instant.now().plus(offset.get());
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return Clock.offset(Clock.system(zone), offset.get());
  }
}
