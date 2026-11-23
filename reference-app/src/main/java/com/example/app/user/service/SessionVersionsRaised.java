package com.example.app.user.service;

import java.util.Map;
import java.util.UUID;

record SessionVersionsRaised(Map<UUID, Integer> versions) {
}
