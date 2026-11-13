package com.example.app.todo.dto.request;

/**
 * The colour format both tag requests accept: {@code #RRGGBB}, or empty for none (stored as null,
 * like an absent one). A format, not a tunable: the pattern fixes the length, so the field needs no
 * {@code @MaxLength}.
 */
final class TagColor {

    static final String PATTERN = "(#[0-9A-Fa-f]{6})?";
    static final String MESSAGE = "must be a colour as #RRGGBB";

    private TagColor() {
    }
}
