package com.example.app.todo.repository.custom;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.todo.dto.request.TodoListFilter;
import com.example.app.todo.dto.response.TodoListResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CustomTodoListRepository {

    Page<TodoListResponse> findByFilter(TodoListFilter filter, UUID ownerId, Pageable pageable);

    CursorWrapper<DropdownItem> findDropdownItems(DropdownFilter filter, UUID ownerId, int size);
}
