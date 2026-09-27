package com.teampulse.coreapi.exception;

import java.util.UUID;

/**
 * Thrown by the service layer when an id doesn't resolve. The web layer is what turns
 * this into a 404 — the service itself stays free of HTTP concepts.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, UUID id) {
        super(resource + " " + id + " not found");
    }
}
