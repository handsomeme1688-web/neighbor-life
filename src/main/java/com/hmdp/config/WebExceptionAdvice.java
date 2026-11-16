package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.exception.RateLimitExceededException;
import com.hmdp.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

import javax.validation.ConstraintViolationException;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * WebExceptionAdvice component.
 *
 * @author NeighborLife
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handleBusiness(BusinessException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Result.fail(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Result handleRateLimit(RateLimitExceededException e) {
        return Result.fail("RATE_LIMITED", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleBodyValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().isEmpty()
                ? "请求参数不正确"
                : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return Result.fail("INVALID_ARGUMENT", message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleParameterValidation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().isEmpty()
                ? "请求参数不正确"
                : e.getConstraintViolations().iterator().next().getMessage();
        return Result.fail("INVALID_ARGUMENT", message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleIllegalArgument(IllegalArgumentException e) {
        return Result.fail("INVALID_ARGUMENT", e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleMalformedRequest(Exception exception) {
        return Result.fail("INVALID_ARGUMENT", "请求参数格式错误");
    }

    @ExceptionHandler({RuntimeException.class, IOException.class})
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result handleRuntimeException(Exception e) {
        log.error("请求处理失败", e);
        return Result.fail("INTERNAL_ERROR", "服务器异常");
    }
}
