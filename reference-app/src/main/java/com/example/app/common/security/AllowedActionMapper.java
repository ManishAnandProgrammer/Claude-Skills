package com.example.app.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j(topic = "com.example.app.authorization")
@Component
@RequiredArgsConstructor
public class AllowedActionMapper {

    private final MessageSource messageSource;

    public AllowedAction toAllowedAction(String flagName, Decision decision) {
        log.trace("Allowed action {}: {}", flagName, decision.isAllowed() ? "allowed" : decision.codes());
        List<AllowedActionReason> reasons = decision.refusals().stream().map(this::toReason).toList();
        return new AllowedAction(decision.isAllowed(), reasons);
    }

    private AllowedActionReason toReason(Decision.Refusal refusal) {
        String message = messageSource.getMessage(refusal.code().name(), refusal.arguments().toArray(),
                LocaleContextHolder.getLocale());
        return new AllowedActionReason(refusal.code().name(), message);
    }
}
