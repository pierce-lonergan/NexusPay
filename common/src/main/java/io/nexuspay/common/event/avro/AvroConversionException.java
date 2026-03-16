package io.nexuspay.common.event.avro;

/**
 * Thrown when JSON-to-Avro or Avro-to-Map conversion fails.
 * Used by serialization infrastructure to signal format conversion errors
 * that should trigger fallback to JSON-only publishing.
 */
public class AvroConversionException extends RuntimeException {

    public AvroConversionException(String message) {
        super(message);
    }

    public AvroConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
