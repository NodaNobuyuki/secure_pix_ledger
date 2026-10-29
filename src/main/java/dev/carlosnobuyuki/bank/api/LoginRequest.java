package dev.carlosnobuyuki.bank.api;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /auth/login}. Format rules are not checked here: input that breaks them gets
 * the same 401 as a wrong password, not a 400 (ADR-0014).
 */
record LoginRequest(@NotNull String username, @NotNull String password) {

  // Records print every field by default; a logged request must never show the password.
  @Override
  public String toString() {
    return "LoginRequest[username=" + username + ", password=***]";
  }
}
