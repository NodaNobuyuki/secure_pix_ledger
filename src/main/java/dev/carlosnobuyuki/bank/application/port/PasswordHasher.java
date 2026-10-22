package dev.carlosnobuyuki.bank.application.port;

import dev.carlosnobuyuki.bank.domain.auth.Password;
import dev.carlosnobuyuki.bank.domain.auth.PasswordHash;

/** One-way, salted, deliberately slow password hashing (ADR-0013). */
public interface PasswordHasher {

  PasswordHash hash(Password password);
}
