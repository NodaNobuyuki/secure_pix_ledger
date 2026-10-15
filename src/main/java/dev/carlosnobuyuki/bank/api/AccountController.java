package dev.carlosnobuyuki.bank.api;

import dev.carlosnobuyuki.bank.application.AccountNotFoundException;
import dev.carlosnobuyuki.bank.application.AccountService;
import dev.carlosnobuyuki.bank.domain.Account;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
class AccountController {

  private final AccountService accounts;

  AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  /** Opens a synthetic BRL account with a zero balance. Phase 2 ties it to an owner. */
  @PostMapping
  ResponseEntity<AccountResponse> open() {
    Account account = accounts.open();
    // Relative on purpose: an absolute URL would be built from the client-controlled Host header.
    return ResponseEntity.created(URI.create("/accounts/" + account.id()))
        .body(AccountResponse.from(account));
  }

  @GetMapping("/{id}")
  AccountResponse get(@PathVariable UUID id) {
    return accounts
        .find(id)
        .map(AccountResponse::from)
        .orElseThrow(() -> new AccountNotFoundException(id));
  }
}
