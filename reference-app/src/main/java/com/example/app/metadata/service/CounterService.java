package com.example.app.metadata.service;

import com.example.app.common.security.CurrentUser;
// tenancy-row:begin
// import com.example.app.common.tenancy.TenantContext;
// tenancy-row:end
import com.example.app.metadata.api.Counters;
import com.example.app.metadata.domain.Counter;
import com.example.app.metadata.repository.CounterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CounterService implements Counters {

    private final CounterRepository repository;
    private final CurrentUser currentUser;
    private final Clock clock;

    @Override
    @Transactional
    public void create(String name, long lastValue) {
        repository.saveAndFlush(Counter.startingAfter(name, lastValue));
    }

    @Override
    @Transactional
    public void createIfAbsent(String name, long lastValue) {
        // tenancy-row:begin
        // repository.insertIfAbsent(TenantContext.id(), name, lastValue, clock.instant(), currentUser.findId().orElse(null));
        // tenancy-row:else
        repository.insertIfAbsent(name, lastValue, clock.instant(), currentUser.findId().orElse(null));
        // tenancy-row:end
    }

    /**
     * {@code MANDATORY}: in a transaction of its own the number would commit at once, and a caller
     * that then fails would leave a gap. A missing counter is a bug (its seeding didn't run), not
     * something a client can cause: 500.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String name) {
        // tenancy-row:begin
        // return repository.increment(TenantContext.id(), name, clock.instant(), currentUser.findId().orElse(null))
        // tenancy-row:else
        return repository.increment(name, clock.instant(), currentUser.findId().orElse(null))
        // tenancy-row:end
                .orElseThrow(() -> new IllegalStateException("No counter %s: create it when its context is created".formatted(name)));
    }

    @Override
    @Transactional
    public void delete(String name) {
        repository.deleteByKey(name);
        repository.flush();
    }
}
