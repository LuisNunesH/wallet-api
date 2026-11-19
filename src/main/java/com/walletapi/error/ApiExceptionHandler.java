package com.walletapi.error;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(WalletException.class)
    public ResponseEntity<Object> handleWalletException(WalletException exception, HttpServletRequest request) {
        ErrorCode code = exception.code();
        if (code.status().is5xxServerError()) {
            log.error("Falha em {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage(), exception);
        } else {
            log.info("Requisição rejeitada em {} {}: {} - {}", request.getMethod(), request.getRequestURI(), code,
                    exception.getMessage());
        }

        HttpHeaders headers = new HttpHeaders();
        if (code == ErrorCode.WALLET_BUSY) {
            headers.set(HttpHeaders.RETRY_AFTER, "1");
        }
        return ResponseEntity.status(code.status())
                .headers(headers)
                .body(problem(code, exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Erro inesperado em {} {}", request.getMethod(), request.getRequestURI(), exception);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status())
                .body(problem(code, "Ocorreu um erro inesperado. Tente novamente mais tarde.", request.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationProblem(violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                for (FieldError fieldError : parameterErrors.getFieldErrors()) {
                    violations.add(new FieldViolation(fieldError.getField(), fieldError.getDefaultMessage()));
                }
            } else {
                String name = parameterName(result);
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    violations.add(new FieldViolation(name, error.getDefaultMessage()));
                }
            }
        }
        return validationProblem(violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = frameworkErrorCode(exception, statusCode);
        if (code.status().value() != statusCode.value()) {
            log.error("Erro inesperado do framework em {}", path(request), exception);
        }
        ProblemDetail problem = problem(code, frameworkDetail(exception, code), path(request));
        return ResponseEntity.status(code.status()).headers(headers).body(problem);
    }

    private ResponseEntity<Object> validationProblem(List<FieldViolation> violations, WebRequest request) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_ERROR,
                "Um ou mais campos da requisição são inválidos", path(request));
        problem.setProperty("errors", violations);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status()).body(problem);
    }

    static ProblemDetail problem(ErrorCode code, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        problem.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        problem.setInstance(URI.create(path));
        return problem;
    }

    static ErrorCode frameworkErrorCode(Exception exception, HttpStatusCode statusCode) {
        if (exception instanceof HttpMessageNotReadableException) {
            return ErrorCode.MALFORMED_REQUEST;
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            return ErrorCode.INTERNAL_ERROR;
        }
        return switch (status) {
            case BAD_REQUEST -> ErrorCode.VALIDATION_ERROR;
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> ErrorCode.NOT_ACCEPTABLE;
            case UNSUPPORTED_MEDIA_TYPE -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }

    private static String frameworkDetail(Exception exception, ErrorCode code) {
        if (exception instanceof HttpMessageNotReadableException) {
            return "Corpo da requisição ausente, JSON malformado ou campo com tipo/valor inválido "
                    + "(type aceita CREDIT ou DEBIT)";
        }
        if (exception instanceof MissingRequestHeaderException missingHeader) {
            return "Header obrigatório '" + missingHeader.getHeaderName() + "' ausente";
        }
        if (exception instanceof TypeMismatchException typeMismatch) {
            return "Parâmetro '" + typeMismatch.getPropertyName() + "' com valor inválido: " + typeMismatch.getValue();
        }
        if (exception instanceof NoResourceFoundException) {
            return "Recurso não encontrado";
        }
        if (exception instanceof HttpRequestMethodNotSupportedException methodNotSupported) {
            return "Método " + methodNotSupported.getMethod() + " não suportado para este recurso";
        }
        if (exception instanceof HttpMediaTypeNotSupportedException) {
            return "Content-Type não suportado; use application/json";
        }
        if (code == ErrorCode.INTERNAL_ERROR) {
            return "Ocorreu um erro inesperado. Tente novamente mais tarde.";
        }
        return code.title();
    }

    private static String parameterName(ParameterValidationResult result) {
        RequestHeader header = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isBlank()) {
            return header.name();
        }
        if (header != null && !header.value().isBlank()) {
            return header.value();
        }
        RequestParam param = result.getMethodParameter().getParameterAnnotation(RequestParam.class);
        if (param != null && !param.name().isBlank()) {
            return param.name();
        }
        return result.getMethodParameter().getParameterName();
    }

    private static String path(WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            return servletRequest.getRequest().getRequestURI();
        }
        return "/";
    }
}
