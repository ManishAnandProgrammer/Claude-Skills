package com.example.app.common.security;

import com.example.app.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j(topic = "com.example.app.authorization")
@Component
@RequiredArgsConstructor
public class AllowedActionMapper {

    private final MessageSource messageSource;
    private final CurrentUser currentUser;

    /** {@code required} is what the action's endpoint {@code @Requires}: without it the flag is that call's 403. */
    public AllowedAction toAllowedAction(String flagName, Permission required, Decision decision) {
        return toAllowedAction(flagName, Set.of(required), decision);
    }

    public AllowedAction toAllowedAction(String flagName, Set<Permission> anyOfRequired, Decision decision) {
        Decision flagged = anyOfRequired.stream().anyMatch(currentUser::holds) ? decision : Decision.refused(ErrorCode.ACCESS_DENIED);
        log.trace("Allowed action {}: {}", flagName, flagged.isAllowed() ? "allowed" : flagged.codes());
        List<AllowedActionReason> reasons = flagged.refusals().stream().map(this::toReason).toList();
        return new AllowedAction(flagged.isAllowed(), reasons);
    }

    private AllowedActionReason toReason(Decision.Refusal refusal) {
        String message = messageSource.getMessage(refusal.code().name(), refusal.arguments().toArray(),
                LocaleContextHolder.getLocale());
        return new AllowedActionReason(refusal.code().name(), message);
    }
}
