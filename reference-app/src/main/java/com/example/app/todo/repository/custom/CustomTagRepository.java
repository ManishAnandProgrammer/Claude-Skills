package com.example.app.todo.repository.custom;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.todo.dto.request.TagFilter;
import com.example.app.todo.dto.response.TagResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CustomTagRepository {

    Page<TagResponse> findByFilter(TagFilter filter, UUID ownerId, Pageable pageable);

    CursorBatch<DropdownItem> findDropdownItems(DropdownFilter filter, UUID ownerId, int size);
}
