package com.example.app.todo.dto.internal;

// media:begin
import com.example.app.media.api.MediaFileView;
// media:end
import com.example.app.todo.dto.response.TodoAllowedActionsResponse;
// tenancy:begin
// import com.example.app.todo.dto.response.TodoRequesterResponse;
// tenancy:end
import com.example.app.todo.dto.response.TodoTagResponse;

import java.util.List;

/** What a todo's response shows beside the todo's own columns, read by TodoService for its mapper. */
public record TodoResponseParts(
        // media:begin
        List<MediaFileView> attachments,
        // media:end
        // tenancy:begin
        // TodoRequesterResponse requester,
        // tenancy:end
        List<TodoTagResponse> tags,
        TodoAllowedActionsResponse allowedActions) {

    public TodoResponseParts {
        // media:begin
        attachments = List.copyOf(attachments);
        // media:end
        tags = List.copyOf(tags);
    }
}
