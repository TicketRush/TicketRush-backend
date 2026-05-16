package com.ticketrush.support;

import com.ticketrush.global.config.JacksonConfig;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@WebMvcTest
@Import(JacksonConfig.class)
public @interface WebMvcSliceTest {

  @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
  Class<?>[] value() default {};
}
