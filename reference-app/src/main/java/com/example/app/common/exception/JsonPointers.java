package com.example.app.common.exception;

import tools.jackson.core.JacksonException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON Pointers (RFC 6901) in their URI fragment form, the form RFC 9457's {@code errors} use: {@code #}
 * is the whole body, {@code #/items/0/name} a value in it. A token's {@code ~} is written {@code ~0} and
 * its {@code /} {@code ~1}, so a map key holding either still names one member.
 */
public final class JsonPointers {

    public static final String WHOLE_BODY = "#";

    /** A node of a bean property path: a bracketed index or map key, or a plain property name. */
    private static final Pattern PROPERTY_PATH_NODE = Pattern.compile("\\[([^]]*)]|([^.\\[]+)");

    private JsonPointers() {
    }

    /**
     * From the path Spring and Bean Validation give a field ({@code items[0].name}, {@code labels[key]}).
     * A JSON property is named like its Java property: the application maps no other names. An element
     * of a set has no position ({@code tagIds[]}), so its pointer names the set.
     */
    public static String fromPropertyPath(String propertyPath) {
        List<String> tokens = new ArrayList<>();
        Matcher node = PROPERTY_PATH_NODE.matcher(propertyPath);
        while (node.find()) {
            if (Objects.nonNull(node.group(2))) {
                tokens.add(node.group(2));
            } else if (!node.group(1).isEmpty()) {
                tokens.add(node.group(1));
            }
        }
        return of(tokens);
    }

    /** The tokens of the location Jackson reports for a value it couldn't read, outermost first. */
    public static List<String> tokensOf(JacksonException exception) {
        List<String> tokens = new ArrayList<>();
        for (JacksonException.Reference reference : exception.getPath()) {
            if (Objects.nonNull(reference.getPropertyName())) {
                tokens.add(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                tokens.add(String.valueOf(reference.getIndex()));
            }
        }
        return tokens;
    }

    public static String of(List<String> tokens) {
        StringBuilder pointer = new StringBuilder(WHOLE_BODY);
        for (String token : tokens) {
            pointer.append('/').append(token.replace("~", "~0").replace("/", "~1"));
        }
        return pointer.toString();
    }
}
