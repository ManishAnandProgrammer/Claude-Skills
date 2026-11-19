package com.example.app.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The one place a {@link Decision} becomes an {@link AllowedAction}: each refusal's code and its message, read from
 * the error messages in the request's language with the refusal's arguments, so a reason reads as the detail the
 * write's error response gives. Each flag is logged at TRACE on the authorization logger, by its action's name and
 * codes only (see {@link Decision}).
 */
@Slf4j(topic = "com.example.app.authorization")
@Component
@RequiredArgsConstructor
public class AllowedActionMapper {

    private final MessageSource messageSource;

    /** {@code action} is the flag's name in the response ({@code edit}), for the log line only. */
    public AllowedAction toAllowedAction(String action, Decision decision) {
        log.trace("Allowed action {}: {}", action, decision.isAllowed() ? "allowed" : decision.codes());
        List<AllowedActionReason> reasons = decision.refusals().stream().map(this::toReason).toList();
        return new AllowedAction(decision.isAllowed(), reasons);
    }

    private AllowedActionReason toReason(Decision.Refusal refusal) {
        String message = messageSource.getMessage(refusal.code().name(), refusal.arguments().toArray(),
                LocaleContextHolder.getLocale());
        return new AllowedActionReason(refusal.code().name(), message);
    }
}
