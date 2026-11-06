package dev.translator.config;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RequestBodyConfig {
    @Bean
    FilterRegistrationBean<RequestSizeFilter> requestBodyFilter(TranslatorProperties properties) {
        var registration = new FilterRegistrationBean<>(new RequestSizeFilter(properties));
        // Spring Security's default filter order is -100. Authenticate protected routes first.
        registration.setOrder(-90);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        return registration;
    }
}
