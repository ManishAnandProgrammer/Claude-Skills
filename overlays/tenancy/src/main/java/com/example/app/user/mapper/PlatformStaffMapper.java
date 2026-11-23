package com.example.app.user.mapper;

import com.example.app.user.domain.PlatformUser;
import com.example.app.user.dto.response.PlatformStaffResponse;
import org.springframework.stereotype.Component;

@Component
public class PlatformStaffMapper {

    public PlatformStaffResponse toResponse(PlatformUser staff) {
        return new PlatformStaffResponse(staff.getId(), staff.getEmail(), staff.getDeactivatedAt());
    }
}
