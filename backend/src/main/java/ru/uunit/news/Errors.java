package ru.uunit.news;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e) {
    return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException e) {
    String message = e
      .getBindingResult()
      .getFieldErrors()
      .stream()
      .map(f -> f.getDefaultMessage())
      .findFirst()
      .orElse("Проверьте поля формы");
    return ResponseEntity.badRequest().body(Map.of("message", message));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
  })
  ResponseEntity<?> malformed(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("message", "Некорректный запрос"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(DataIntegrityViolationException e) {
    return ResponseEntity.status(409).body(
      Map.of("message", "Такие данные уже существуют или запись связана с другими данными")
    );
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> upload(Exception e) {
    return ResponseEntity.status(413).body(Map.of("message", "Размер файла превышает 200 МиБ"));
  }
}
