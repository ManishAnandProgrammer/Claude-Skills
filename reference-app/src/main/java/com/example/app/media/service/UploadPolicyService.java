package com.example.app.media.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.BoundedListWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
// tenancy-row:begin
// import com.example.app.media.api.UploadLimits;
// tenancy-row:end
import com.example.app.media.api.UploadPurpose;
import com.example.app.media.domain.UploadPolicy;
import com.example.app.media.dto.request.UploadFileRequest;
import com.example.app.media.dto.request.UploadPolicyUpdateRequest;
import com.example.app.media.dto.response.UploadPolicyResponse;
import com.example.app.media.mapper.UploadPolicyMapper;
import com.example.app.media.repository.UploadPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UploadPolicyService {

    private final UploadPolicyRepository repository;
    private final UploadPolicyMapper mapper;
    private final CurrentUser currentUser;

    public BoundedListWrapper<UploadPolicyResponse> findAll() {
        return BoundedListWrapper.fromList(repository.findAllByOrderByPurpose().stream().map(this::toResponse).toList());
    }

    @Transactional
    public ObjectWrapper<UploadPolicyResponse> update(UploadPurpose purpose, UploadPolicyUpdateRequest request) {
        UploadPolicy policy = getPolicy(purpose);
        policy.checkVersion(request.version());
        for (String contentType : request.contentTypes()) {
            if (!AllowedContentTypes.isAllowed(contentType)) {
                throw AppException.of(ErrorCode.UPLOAD_TYPE_NOT_ALLOWED, contentType);
            }
        }
        // Sorted (as the seed rows are), so the same set sent again equals the stored list and changes nothing, not even the version.
        List<String> contentTypes = request.contentTypes().stream().sorted().toList();
        policy.replaceLimits(request.minSizeBytes(), request.maxSizeBytes(), request.minFiles(), request.maxFiles(), contentTypes);
        repository.flush();
        return ObjectWrapper.withObject(toResponse(policy));
    }

    public UploadPolicy getPolicy(UploadPurpose purpose) {
        return repository.findByPurpose(purpose).orElseThrow(AppException.supply(ErrorCode.UPLOAD_POLICY_NOT_SEEDED, purpose));
    }
    // tenancy-row:begin

    // @Transactional
    // public void defineIfAbsent(UploadPurpose purpose, UploadLimits limits) {
    //     if (repository.findByPurpose(purpose).isEmpty()) {
    //         List<String> contentTypes = limits.contentTypes().stream().sorted().toList();
    //         repository.save(UploadPolicy.builder().purpose(purpose).minSizeBytes(limits.minSizeBytes())
    //                 .maxSizeBytes(limits.maxSizeBytes()).minFiles(limits.minFiles()).maxFiles(limits.maxFiles())
    //                 .contentTypes(contentTypes).build());
    //     }
    // }
    // tenancy-row:end

    /**
     * The type must also be in the code's allow-list, so a type removed from it is refused even while an
     * old policy row still lists it.
     */
    public void requireAccepts(UploadPolicy policy, UploadFileRequest file) {
        if (!AllowedContentTypes.isAllowed(file.contentType()) || !policy.allowsContentType(file.contentType())) {
            throw AppException.of(ErrorCode.UPLOAD_TYPE_NOT_ALLOWED, file.contentType());
        }
        if (!policy.allowsSize(file.sizeBytes())) {
            throw AppException.of(ErrorCode.UPLOAD_SIZE_OUT_OF_RANGE, file.fileName(), file.sizeBytes(),
                    policy.getMinSizeBytes(), policy.getMaxSizeBytes());
        }
    }

    private UploadPolicyResponse toResponse(UploadPolicy policy) {
        return mapper.toResponse(policy, policy.getPurpose().mayUpload(currentUser::holds));
    }
}
