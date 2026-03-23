package org.example.exception;

public class RedirectLoopException extends RuntimeException {

    public RedirectLoopException(String longUrl) {
        super("URL points to this service and would create a redirect loop: " + longUrl);
    }
}
