package com.hmdp.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Stable business failure with an explicit HTTP contract.
 * @author NeighborLife
 */
@Getter
public class BusinessException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public BusinessException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
