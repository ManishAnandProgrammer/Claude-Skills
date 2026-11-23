package com.example.app.todo.dto.request;

/**
 * The pattern fixes the length, so the field needs no {@code @MaxLength}, only while every part of it is bounded:
 * a pattern that tolerates surrounding spaces ({@code \s*}) would accept megabytes of them.
 */
final class TagColor {

    static final String PATTERN = "(#[0-9A-Fa-f]{6})?";
    static final String MESSAGE = "must be a colour as #RRGGBB";

    private TagColor() {
    }
}
