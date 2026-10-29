package dev.carlosnobuyuki.bank.application.port;

import dev.carlosnobuyuki.bank.domain.auth.User;

/** Issues short-lived access tokens to users who have proven who they are (ADR-0014). */
public interface AccessTokenIssuer {

  AccessToken issue(User user);
}
