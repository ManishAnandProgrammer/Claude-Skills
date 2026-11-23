package com.example.app.tenant.api;

public interface SupportAccessPolicy {

    boolean allows(SupportAccessRequest request);
}
