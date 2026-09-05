package com.example.urlshort.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.URL;

/**
 * 단축 대상 URL 요청.
 *
 * <p>여기서 받은 값이 그대로 302 Location으로 나가므로 스킴을 http/https로 못 박는다.
 * {@code @URL}만으로는 {@code file:} 같은 스킴이 통과한다.
 */
public record CreateUrlRequest(
        @NotBlank
        @URL
        @Pattern(regexp = "^https?://.+", message = "http 또는 https URL만 등록할 수 있습니다")
        String originalUrl
) {
}
