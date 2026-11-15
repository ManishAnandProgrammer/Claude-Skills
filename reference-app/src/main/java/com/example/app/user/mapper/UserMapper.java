package com.example.app.user.mapper;

import com.example.app.user.domain.User;
import com.example.app.user.dto.response.UserResponse;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                // tenancy:begin
                // tenancy:else
                user.getRole(),
                // tenancy:end
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getCreatedBy(),
                user.getUpdatedBy(),
                user.getVersion());
    }
}
