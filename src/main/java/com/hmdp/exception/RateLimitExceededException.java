package com.hmdp.exception;

/**
 * RateLimitExceededException component.
 *
 * @author NeighborLife
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
