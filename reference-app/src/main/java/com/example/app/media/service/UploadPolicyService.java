package com.example.app.media.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.ListWrapper;
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

/**
 * The purposes' limits: read by everyone signed in (the frontend's hints), changed by an admin. One
 * row per purpose, read by its unique purpose: no cache.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UploadPolicyService {

    private final UploadPolicyRepository repository;
    private final UploadPolicyMapper mapper;
    private final CurrentUser currentUser;

    public ListWrapper<UploadPolicyResponse> findAll() {
        return ListWrapper.fromList(repository.findAllByOrderByPurpose().stream().map(this::toResponse).toList());
    }

    /** Takes effect on the next request; a file already attached keeps its slot even when the range shrinks. */
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

    /** 404 {@code UPLOAD_POLICY_NOT_FOUND} when the purpose's seed changeset didn't run. */
    public UploadPolicy getPolicy(UploadPurpose purpose) {
        return repository.findByPurpose(purpose).orElseThrow(AppException.supply(ErrorCode.UPLOAD_POLICY_NOT_FOUND, purpose));
    }
    // tenancy-row:begin

    // /** The purpose's first policy in the current tenant; a purpose that has one keeps it. */
    // @Transactional
    // public void define(UploadPurpose purpose, UploadLimits limits) {
    //     if (repository.findByPurpose(purpose).isEmpty()) {
    //         List<String> contentTypes = limits.contentTypes().stream().sorted().toList();
    //         repository.save(UploadPolicy.builder().purpose(purpose).minSizeBytes(limits.minSizeBytes())
    //                 .maxSizeBytes(limits.maxSizeBytes()).minFiles(limits.minFiles()).maxFiles(limits.maxFiles())
    //                 .contentTypes(contentTypes).build());
    //     }
    // }
    // tenancy-row:end

    /**
     * 400 unless the file fits the policy. The type must also be in the code's allow-list, so a type
     * removed from it is refused even while an old policy row still lists it.
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
        return mapper.toResponse(policy, policy.getPurpose().roles().stream().anyMatch(currentUser::hasRole));
    }
}
