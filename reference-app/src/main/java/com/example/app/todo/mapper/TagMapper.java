package com.example.app.todo.mapper;

import com.example.app.todo.domain.Tag;
import com.example.app.todo.dto.request.TagCreateRequest;
import com.example.app.todo.dto.request.TagUpdateRequest;
import com.example.app.todo.dto.response.TagResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.UUID;

@Component
public class TagMapper {

    public Tag toEntity(TagCreateRequest request, UUID ownerId) {
        return Tag.builder()
                .name(request.name().strip())
                .color(normalisedColor(request.color()))
                .ownerId(ownerId)
                .build();
    }

    public void update(Tag tag, TagUpdateRequest request) {
        tag.setName(request.name().strip());
        tag.setColor(normalisedColor(request.color()));
        tag.setActive(request.active());
    }

    public TagResponse toResponse(Tag tag) {
        return new TagResponse(
                tag.getId(),
                tag.getName(),
                tag.getColor(),
                tag.isActive(),
                tag.getOwnerId(),
                tag.getCreatedAt(),
                tag.getUpdatedAt(),
                tag.getCreatedBy(),
                tag.getUpdatedBy(),
                tag.getVersion());
    }

    /**
     * One spelling per colour, and one for none: empty is stored as null, and the check constraint in
     * changeset 0004 accepts upper-case only.
     */
    private static String normalisedColor(String color) {
        return StringUtils.hasText(color) ? color.toUpperCase(Locale.ROOT) : null;
    }
}
