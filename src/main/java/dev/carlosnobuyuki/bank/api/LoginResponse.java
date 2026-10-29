package dev.carlosnobuyuki.bank.api;

import dev.carlosnobuyuki.bank.application.port.AccessToken;

/** OAuth-style token response (RFC 6749 section 5.1); {@code expiresIn} is in seconds. */
record LoginResponse(String accessToken, String tokenType, long expiresIn) {

  static LoginResponse from(AccessToken token) {
    return new LoginResponse(token.value(), "Bearer", token.expiresIn().toSeconds());
  }

  // Whoever holds the token acts as the user until it expires, so it must not reach a log.
  @Override
  public String toString() {
    return "LoginResponse[accessToken=***, tokenType="
        + tokenType
        + ", expiresIn="
        + expiresIn
        + "]";
  }
}
