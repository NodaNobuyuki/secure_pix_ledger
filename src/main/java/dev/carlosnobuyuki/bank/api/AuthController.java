package dev.carlosnobuyuki.bank.api;

import dev.carlosnobuyuki.bank.application.auth.RegistrationService;
import dev.carlosnobuyuki.bank.domain.auth.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
class AuthController {

  private final RegistrationService registration;

  AuthController(RegistrationService registration) {
    this.registration = registration;
  }

  /** Registers a customer (ADR-0013). There is no user resource to point a Location header at. */
  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  RegistrationResponse register(@Valid @RequestBody RegisterRequest request) {
    User user = registration.register(request.username(), request.password());
    return RegistrationResponse.from(user);
  }
}
