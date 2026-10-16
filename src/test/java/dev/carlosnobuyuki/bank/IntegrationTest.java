package dev.carlosnobuyuki.bank;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Full application context against a Testcontainers PostgreSQL. The ledger invariants are checked
 * after every test, so no integration test can leave the books unbalanced unnoticed.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(LedgerInvariantExtension.class)
public @interface IntegrationTest {}
