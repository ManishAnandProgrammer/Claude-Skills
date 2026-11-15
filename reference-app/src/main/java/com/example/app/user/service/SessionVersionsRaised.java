package com.example.app.user.service;

import java.util.Map;
import java.util.UUID;

/**
 * Session versions an access change raised, published inside its transaction; {@link SessionVersionService}
 * writes them to Redis once it commits. Keyed by the id each number belongs to.
 */
record SessionVersionsRaised(Map<UUID, Integer> versions) {
}
