package com.example.app.todo.service;

import com.example.app.media.api.UploadLimits;
import com.example.app.media.api.UploadPurpose;
import com.example.app.media.api.Uploads;
import com.example.app.tenant.api.TenantProvisioned;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The TODO_ATTACHMENT purpose's starting limits in every new tenant (row mode). A changeset can't seed
 * a tenant's rows: there is no tenant when it runs, and row-level security refuses the owner too. So
 * the module that attaches the files seeds them when a tenant is provisioned, under its scope and in
 * its activating transaction.
 */
@Component
@RequiredArgsConstructor
class TodoAttachmentPolicySeed {

    private static final UploadLimits STARTING_LIMITS = new UploadLimits(1, 10_485_760, 0, 5,
            List.of("application/pdf", "image/jpeg", "image/png"));

    private final Uploads uploads;

    @EventListener
    void seed(TenantProvisioned provisioned) {
        uploads.definePolicy(UploadPurpose.TODO_ATTACHMENT, STARTING_LIMITS);
    }
}
