package dev.carlosnobuyuki.bank;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/** Tests share cached Spring contexts, so a clock moved by one test must not leak into the next. */
final class ResetClockExtension implements AfterEachCallback {

  @Override
  public void afterEach(ExtensionContext context) {
    SpringExtension.getApplicationContext(context).getBean(MutableClock.class).reset();
  }
}
