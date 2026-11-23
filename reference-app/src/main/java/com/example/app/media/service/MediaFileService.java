package com.example.app.media.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.CurrentUser;
// tenancy-row:begin
// import com.example.app.common.tenancy.TenantContext;
// tenancy-row:end
import com.example.app.common.wrapper.BoundedListWrapper;
import com.example.app.media.api.FileCountRange;
// tenancy-row:begin
// import com.example.app.media.api.UploadLimits;
// tenancy-row:end
import com.example.app.media.api.MediaFileStatus;
import com.example.app.media.api.MediaFileView;
import com.example.app.media.api.UploadPurpose;
import com.example.app.media.api.Uploads;
import com.example.app.media.domain.MediaFile;
import com.example.app.media.domain.UploadPolicy;
import com.example.app.media.dto.internal.MediaFileKey;
import com.example.app.media.dto.internal.PresignedUpload;
import com.example.app.media.dto.request.UploadFileRequest;
import com.example.app.media.dto.request.UploadUrlRequest;
import com.example.app.media.dto.response.UploadUrlResponse;
import com.example.app.media.mapper.MediaFileMapper;
import com.example.app.media.repository.MediaFileRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Nothing here calls the store inside a transaction. */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(readOnly = true)
public class MediaFileService implements Uploads {

    private final MediaFileRepository repository;
    private final MediaFileMapper mapper;
    private final UploadPolicyService policyService;
    private final MediaStorage storage;
    private final MediaUrls urls;
    private final CurrentUser currentUser;
    private final MediaProperties properties;

    /**
     * Signs each file, then records them UNCLAIMED in one short transaction ({@code saveAll}'s own). Signing
     * first gives each row its URL's exact expiry; if the write fails, the URLs never reach the client.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BoundedListWrapper<UploadUrlResponse> issue(UploadUrlRequest request) {
        UploadPurpose purpose = request.purpose();
        if (!purpose.mayUpload(currentUser::holds)) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        UploadPolicy policy = policyService.getPolicy(purpose);
        new FileCountRange(1, policy.getMaxFiles()).requireWithin(request.files().size());
        for (UploadFileRequest file : request.files()) {
            policyService.requireAccepts(policy, file);
        }
        UUID uploadedBy = currentUser.id();
        List<MediaFile> files = new ArrayList<>();
        List<UploadUrlResponse> responses = new ArrayList<>();
        for (UploadFileRequest file : request.files()) {
            String key = mapper.newKey(purpose);
            PresignedUpload upload = storage.presignUpload(key, file);
            files.add(mapper.toEntity(key, purpose, file, uploadedBy, upload, properties.claimGracePeriod()));
            responses.add(mapper.toUploadUrlResponse(key, upload));
        }
        repository.saveAll(files);
        return BoundedListWrapper.fromList(responses);
    }

    @Override
    public FileCountRange fileCountRange(UploadPurpose purpose) {
        UploadPolicy policy = policyService.getPolicy(purpose);
        return new FileCountRange(policy.getMinFiles(), policy.getMaxFiles());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> claim(UploadPurpose purpose, Collection<String> uploadKeys) {
        Set<String> keys = Set.copyOf(uploadKeys);
        if (keys.isEmpty()) {
            return List.of();
        }
        // tenancy-row:begin
        // List<UUID> claimed = repository.claim(TenantContext.id(), keys, purpose.name(), currentUser.id());
        // tenancy-row:else
        List<UUID> claimed = repository.claim(keys, purpose.name(), currentUser.id());
        // tenancy-row:end
        if (claimed.size() != keys.size()) {
            throw AppException.of(ErrorCode.UPLOAD_NOT_AVAILABLE);
        }
        return claimed;
    }

    /** The ids come from the record's own files, which its version check keeps current: a mismatch is a bug. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Collection<UUID> mediaFileIds) {
        Set<UUID> ids = Set.copyOf(mediaFileIds);
        if (ids.isEmpty()) {
            return;
        }
        // tenancy-row:begin
        // int released = repository.release(TenantContext.id(), ids, currentUser.findId().orElse(null));
        // tenancy-row:else
        int released = repository.release(ids, currentUser.findId().orElse(null));
        // tenancy-row:end
        if (released != ids.size()) {
            throw new IllegalStateException("Released %d of %d attached files".formatted(released, ids.size()));
        }
    }

    @Override
    public List<MediaFileView> describe(Collection<UUID> mediaFileIds) {
        if (mediaFileIds.isEmpty()) {
            return List.of();
        }
        return repository.findByIdInOrderById(mediaFileIds).stream().map(this::toView).toList();
    }

    @Override
    public boolean allVerified(Collection<UUID> mediaFileIds) {
        Set<UUID> ids = Set.copyOf(mediaFileIds);
        return ids.isEmpty() || repository.countByIdInAndStatus(ids, MediaFileStatus.VERIFIED) == ids.size();
    }
    // tenancy-row:begin

    // @Override
    // @Transactional
    // public void definePolicyIfAbsent(UploadPurpose purpose, UploadLimits limits) {
    //     policyService.defineIfAbsent(purpose, limits);
    // }
    // tenancy-row:end

    @Transactional
    public List<MediaFileKey> leaseForVerification(int batchSize, Duration lease) {
        // tenancy-row:begin
        // return keysOf(repository.leaseForVerification(TenantContext.id(), batchSize, lease.toSeconds()));
        // tenancy-row:else
        return keysOf(repository.leaseForVerification(batchSize, lease.toSeconds()));
        // tenancy-row:end
    }

    @Transactional
    public int markVerified(Collection<UUID> mediaFileIds) {
        if (mediaFileIds.isEmpty()) {
            return 0;
        }
        // tenancy-row:begin
        // return repository.markVerified(TenantContext.id(), mediaFileIds);
        // tenancy-row:else
        return repository.markVerified(mediaFileIds);
        // tenancy-row:end
    }

    @Transactional
    public int markMissing(Collection<UUID> mediaFileIds) {
        if (mediaFileIds.isEmpty()) {
            return 0;
        }
        // tenancy-row:begin
        // return repository.markMissing(TenantContext.id(), mediaFileIds);
        // tenancy-row:else
        return repository.markMissing(mediaFileIds);
        // tenancy-row:end
    }

    @Transactional
    public List<MediaFileKey> leaseReleased(int batchSize, Duration lease) {
        // tenancy-row:begin
        // return keysOf(repository.leaseReleased(TenantContext.id(), batchSize, lease.toSeconds()));
        // tenancy-row:else
        return keysOf(repository.leaseReleased(batchSize, lease.toSeconds()));
        // tenancy-row:end
    }

    @Transactional
    public List<MediaFileKey> leaseExpiredUnclaimed(int batchSize, Duration lease) {
        // tenancy-row:begin
        // return keysOf(repository.leaseExpiredUnclaimed(TenantContext.id(), batchSize, lease.toSeconds()));
        // tenancy-row:else
        return keysOf(repository.leaseExpiredUnclaimed(batchSize, lease.toSeconds()));
        // tenancy-row:end
    }

    @Transactional
    public int deleteRemoved(Collection<UUID> mediaFileIds) {
        if (mediaFileIds.isEmpty()) {
            return 0;
        }
        // tenancy-row:begin
        // return repository.deleteRemoved(TenantContext.id(), mediaFileIds);
        // tenancy-row:else
        return repository.deleteRemoved(mediaFileIds);
        // tenancy-row:end
    }

    private List<MediaFileKey> keysOf(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return repository.findKeys(ids);
    }

    private MediaFileView toView(MediaFile file) {
        String url = file.getStatus() == MediaFileStatus.MISSING ? null : urls.readUrl(file.getKey());
        return mapper.toView(file, url);
    }
}
