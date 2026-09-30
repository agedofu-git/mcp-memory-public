package dev.memory.controller;

import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorsTest {
    @Test void validationResponseIdentifiesInvalidFieldsWithoutEchoingValues() throws Exception {
        var request = new Requests.Create(null, "", null, null, null, null, null);
        var binding = new BeanPropertyBindingResult(request, "request");
        binding.addError(new FieldError("request", "content", "secret rejected value", false,
                null, null, "must not be blank"));
        binding.addError(new ObjectError("request", "cross-field constraint failed"));
        var method = MemoryController.class.getDeclaredMethod("create", Requests.Create.class);
        var problem = new ApiErrors().invalidFields(new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(method, 0), binding));

        assertThat(problem.getProperties()).containsKey("errors");
        assertThat(problem.getProperties().get("errors").toString())
                .contains("content", "must not be blank", "request", "cross-field constraint failed")
                .doesNotContain("secret rejected value");
    }
}
