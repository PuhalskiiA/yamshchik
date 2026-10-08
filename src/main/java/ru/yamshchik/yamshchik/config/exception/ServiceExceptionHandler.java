package ru.yamshchik.yamshchik.config.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ru.yamshchik.yamshchik.config.exception.type.EmailNotFoundException;
import ru.yamshchik.yamshchik.config.exception.type.EmailTooLargeException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;

import java.util.stream.Collectors;


/**
 * Ошибки отдаются в формате RFC 9457.
 */
@RestControllerAdvice
@Slf4j
public class ServiceExceptionHandler {

    private static final String VIOLATIONS_DELIMITER = " ; ";

    private static final String VIOLATION_FORMAT = "%s: %s";

    private static final String INTERNAL_ERROR_DETAIL = "Internal service error";

    @ExceptionHandler(ServiceValidationException.class)
    public ProblemDetail handleServiceValidationException(ServiceValidationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult()
                .getFieldErrors().stream()
                .map(error -> VIOLATION_FORMAT.formatted(error.getField(), error.getDefaultMessage()))
                .sorted()
                .collect(Collectors.joining(VIOLATIONS_DELIMITER));

        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolationException(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> VIOLATION_FORMAT.formatted(violation.getPropertyPath(), violation.getMessage()))
                .sorted()
                .collect(Collectors.joining(VIOLATIONS_DELIMITER));

        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            MultipartException.class
    })
    public ProblemDetail handleBadRequestException(Exception ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler({
            EmailNotFoundException.class,
            NoResourceFoundException.class
    })
    public ProblemDetail handleNotFoundException(Exception ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupportedException(HttpRequestMethodNotSupportedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
    }

    // Объявлен отдельно от MultipartException: более точный тип исключения выигрывает при выборе обработчика
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
    }

    @ExceptionHandler(EmailTooLargeException.class)
    public ProblemDetail handleEmailTooLargeException(EmailTooLargeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAllExceptions(Exception ex) {
        log.error("Service unhandled exception:", ex);
        // Текст неожиданной ошибки клиенту не раскрываем: в нём могут быть детали реализации
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_DETAIL);
    }
}
