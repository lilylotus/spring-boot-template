package com.example.template.validation;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证表单请求记录的组件和 Jakarta Validation 约束声明保持完整。
 */
class ValidationFormRequestTest {

    @Test
    void shouldDeclareExpectedComponentsAndConstraints() {
        Map<String, RecordComponent> components = Arrays.stream(ValidationFormRequest.class.getRecordComponents())
            .collect(Collectors.toMap(RecordComponent::getName, Function.identity()));

        assertThat(components).containsOnlyKeys("username", "email", "age");

        RecordComponent username = components.get("username");
        assertThat(username.getAccessor().getAnnotation(NotBlank.class)).isNotNull();
        Size usernameSize = username.getAccessor().getAnnotation(Size.class);
        assertThat(usernameSize).isNotNull();
        assertThat(usernameSize.min()).isEqualTo(2);
        assertThat(usernameSize.max()).isEqualTo(20);

        RecordComponent email = components.get("email");
        assertThat(email.getAccessor().getAnnotation(NotBlank.class)).isNotNull();
        assertThat(email.getAccessor().getAnnotation(Email.class)).isNotNull();

        RecordComponent age = components.get("age");
        assertThat(age.getAccessor().getAnnotation(NotNull.class)).isNotNull();
        assertThat(age.getAccessor().getAnnotation(Min.class).value()).isEqualTo(18);
        assertThat(age.getAccessor().getAnnotation(Max.class).value()).isEqualTo(120);
    }
}
