package dev.carlosnobuyuki.bank.api;

import dev.carlosnobuyuki.bank.application.auth.LoginService;
import dev.carlosnobuyuki.bank.application.auth.RegistrationService;
import dev.carlosnobuyuki.bank.application.port.AccessToken;
import dev.carlosnobuyuki.bank.domain.auth.User;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
class AuthController {

  private final RegistrationService registration;
  private final LoginService login;

  AuthController(RegistrationService registration, LoginService login) {
    this.registration = registration;
    this.login = login;
  }

  /** Registers a customer (ADR-0013). There is no user resource to point a Location header at. */
  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  RegistrationResponse register(@Valid @RequestBody RegisterRequest request) {
    User user = registration.register(request.username(), request.password());
    return RegistrationResponse.from(user);
  }

  /** Exchanges a username and password for an access token (ADR-0014). */
  @PostMapping("/login")
  ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
    AccessToken token = login.login(request.username(), request.password());
    // A token in a shared or browser cache could be replayed by the next user of that cache.
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LoginResponse.from(token));
  }
}
