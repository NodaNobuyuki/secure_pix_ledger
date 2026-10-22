package dev.carlosnobuyuki.bank.api;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /auth/register}. Format and length rules live in the domain ({@code
 * Username}, {@code Password}), in one place. There is no role field: strict JSON rejects one
 * (ADR-0013).
 */
record RegisterRequest(@NotNull String username, @NotNull String password) {

  // Records print every field by default; a logged request must never show the password.
  @Override
  public String toString() {
    return "RegisterRequest[username=" + username + ", password=***]";
  }
}
