package com.example.app.media.mapper;

import com.example.app.media.domain.UploadPolicy;
import com.example.app.media.dto.response.UploadPolicyResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UploadPolicyMapper {

    public UploadPolicyResponse toResponse(UploadPolicy policy, boolean canUpload) {
        return new UploadPolicyResponse(
                policy.getPurpose(),
                policy.getMinSizeBytes(),
                policy.getMaxSizeBytes(),
                policy.getMinFiles(),
                policy.getMaxFiles(),
                List.copyOf(policy.getContentTypes()),
                canUpload,
                policy.getCreatedAt(),
                policy.getUpdatedAt(),
                policy.getCreatedBy(),
                policy.getUpdatedBy(),
                policy.getVersion());
    }
}
