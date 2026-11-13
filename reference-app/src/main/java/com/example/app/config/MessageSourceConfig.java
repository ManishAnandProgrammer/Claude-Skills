package com.example.app.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

@Configuration
class MessageSourceConfig {

    @Bean
    static MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename("classpath:messages/message");
        // The files are UTF-8; read as the default ISO-8859-1, every non-ASCII character would be garbled.
        messageSource.setDefaultEncoding("UTF-8");
        // Without it a message used with no arguments skips MessageFormat, and '' or an unfilled {0}
        // reaches the client raw.
        messageSource.setAlwaysUseMessageFormat(true);
        messageSource.setFallbackToSystemLocale(false);
        // Any language without its own file (de, pa, hi, ...) is answered in English; without it the
        // lookup fails and the error response itself breaks.
        messageSource.setDefaultLocale(Locale.ENGLISH);
        return messageSource;
    }

    @Bean
    static LocalValidatorFactoryBean validator(MessageSource messageSource) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        return validator;
    }

    @Bean
    static LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
        localeResolver.setSupportedLocales(List.of(Locale.ENGLISH));
        localeResolver.setDefaultLocale(Locale.ENGLISH);
        return localeResolver;
    }
}
