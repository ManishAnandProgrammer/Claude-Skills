package com.example.app.media.controller;

import com.example.app.common.wrapper.ListWrapper;
import com.example.app.media.controller.api.UploadUrlApi;
import com.example.app.media.dto.request.UploadUrlRequest;
import com.example.app.media.dto.response.UploadUrlResponse;
import com.example.app.media.service.MediaFileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Signed in only: the purpose's role is checked by the service, because the purpose is in the body. */
@RestController
@RequestMapping("/api/{version}/upload-urls")
@RequiredArgsConstructor
class UploadUrlController implements UploadUrlApi {

    private final MediaFileService service;

    @Override
    @PostMapping(version = "v1+")
    @ResponseStatus(HttpStatus.CREATED)
    public ListWrapper<UploadUrlResponse> issue(@RequestBody @Valid UploadUrlRequest request) {
        return service.issue(request);
    }
}
