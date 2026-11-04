package io.spring.api.exception;

public class InvalidAuthenticationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public InvalidAuthenticationException() {
    super("invalid email or password");
  }
}
