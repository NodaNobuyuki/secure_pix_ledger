package dev.carlosnobuyuki.bank.api;

import dev.carlosnobuyuki.bank.application.AccountNotFoundException;
import dev.carlosnobuyuki.bank.application.AccountService;
import dev.carlosnobuyuki.bank.domain.Account;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller is always the token's subject, never an id taken from the request (ADR-0015). */
@RestController
@RequestMapping("/accounts")
class AccountController {

  private final AccountService accounts;

  AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  /** Opens a synthetic BRL account with a zero balance, owned by the caller. */
  @PostMapping
  ResponseEntity<AccountResponse> open(@AuthenticationPrincipal Jwt caller) {
    Account account = accounts.open(Callers.id(caller));
    // Relative on purpose: an absolute URL would be built from the client-controlled Host header.
    return ResponseEntity.created(URI.create("/accounts/" + account.id()))
        .body(AccountResponse.from(account));
  }

  @GetMapping
  AccountListResponse list(@AuthenticationPrincipal Jwt caller) {
    return new AccountListResponse(
        accounts.listOwnedBy(Callers.id(caller)).stream().map(AccountResponse::from).toList());
  }

  /** Someone else's account gets the same 404 as an unknown id: a 403 would confirm it exists. */
  @GetMapping("/{id}")
  AccountResponse get(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
    return accounts
        .find(id, Callers.id(caller))
        .map(AccountResponse::from)
        .orElseThrow(() -> new AccountNotFoundException(id));
  }

  /** An object rather than a bare array, so fields such as paging can be added without breaking. */
  record AccountListResponse(List<AccountResponse> accounts) {}
}
