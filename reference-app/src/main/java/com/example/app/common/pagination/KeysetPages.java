package com.example.app.common.pagination;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.wrapper.CursorWrapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * One batch of a cursor-paged list as a projection. The query seeks past the cursor's position
 * ({@code (k1, …, id) > (v1, …, id)}, written out as nested comparisons) instead of skipping rows,
 * so batch 1,000 costs what batch 1 costs; it fetches one row more than asked, to know whether a
 * next batch exists without counting.
 *
 * <p>The cursor is opaque to clients: Base64url JSON of the sort name and the last row's keys. It
 * carries only a position; the caller's own predicates (ownership) still apply to every batch.</p>
 */
@Component
public class KeysetPages {

    private static final String AFTER = "after";
    private static final Map<Class<?>, Function<String, Object>> READERS = Map.of(
            UUID.class, UUID::fromString,
            LocalDate.class, LocalDate::parse,
            Instant.class, Instant::parse,
            String.class, text -> text,
            Long.class, Long::valueOf);

    private final ObjectMapper objectMapper;

    public KeysetPages(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private record CursorContent(String sort, List<String> keys) {
    }

    public <T, R> CursorWrapper<R> fetch(EntityManager entityManager, Class<T> entity, Class<R> row,
                                         OffsetPages.Projection<T, R> projection, OffsetPages.Conditions<T> conditions,
                                         Keyset<T, R> keyset, String after, int size) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<R> query = builder.createQuery(row);
        Root<T> root = query.from(entity);
        query.select(projection.select(builder, root));
        List<Predicate> predicates = new ArrayList<>(conditions.build(builder, root));
        if (Objects.nonNull(after)) {
            predicates.add(seekPast(builder, root, keyset, decode(after, keyset)));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(orders(builder, root, keyset));
        List<R> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        if (rows.size() <= size) {
            return CursorWrapper.of(rows, null);
        }
        List<R> batch = rows.subList(0, size);
        return CursorWrapper.of(batch, encode(keyset, batch.getLast()));
    }

    private static <T, R> List<Order> orders(CriteriaBuilder builder, Root<T> root, Keyset<T, R> keyset) {
        List<Order> orders = new ArrayList<>();
        for (KeyColumn<T, R, ?> column : keyset.columns()) {
            Expression<?> path = column.path().apply(root);
            orders.add(keyset.descending() ? builder.desc(path) : builder.asc(path));
        }
        return orders;
    }

    /** {@code k1 > v1 OR (k1 = v1 AND k2 > v2) OR …}, with {@code <} when descending. */
    private static <T, R> Predicate seekPast(CriteriaBuilder builder, Root<T> root, Keyset<T, R> keyset, List<Object> values) {
        List<Predicate> alternatives = new ArrayList<>();
        for (int position = 0; position < keyset.columns().size(); position++) {
            List<Predicate> conjunction = new ArrayList<>();
            for (int earlier = 0; earlier < position; earlier++) {
                conjunction.add(equal(builder, root, keyset.columns().get(earlier), values.get(earlier)));
            }
            conjunction.add(beyond(builder, root, keyset.columns().get(position), values.get(position), keyset.descending()));
            alternatives.add(builder.and(conjunction.toArray(new Predicate[0])));
        }
        return builder.or(alternatives.toArray(new Predicate[0]));
    }

    private static <T, R, V extends Comparable<? super V>> Predicate equal(CriteriaBuilder builder, Root<T> root,
                                                                          KeyColumn<T, R, V> column, Object value) {
        return builder.equal(column.path().apply(root), column.type().cast(value));
    }

    private static <T, R, V extends Comparable<? super V>> Predicate beyond(CriteriaBuilder builder, Root<T> root,
                                                                           KeyColumn<T, R, V> column, Object value,
                                                                           boolean descending) {
        Expression<V> path = column.path().apply(root);
        V key = column.type().cast(value);
        return descending ? builder.lessThan(path, key) : builder.greaterThan(path, key);
    }

    private <T, R> String encode(Keyset<T, R> keyset, R last) {
        List<String> keys = new ArrayList<>();
        for (KeyColumn<T, R, ?> column : keyset.columns()) {
            keys.add(column.value().apply(last).toString());
        }
        byte[] json = objectMapper.writeValueAsBytes(new CursorContent(keyset.name(), keys));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    private <T, R> List<Object> decode(String cursor, Keyset<T, R> keyset) {
        try {
            CursorContent content = objectMapper.readValue(Base64.getUrlDecoder().decode(cursor), CursorContent.class);
            if (Objects.isNull(content) || !keyset.name().equals(content.sort()) || Objects.isNull(content.keys())
                    || content.keys().size() != keyset.columns().size() || content.keys().contains(null)) {
                throw invalidCursor();
            }
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < keyset.columns().size(); index++) {
                values.add(read(keyset.columns().get(index).type(), content.keys().get(index)));
            }
            return values;
        } catch (IllegalArgumentException | JacksonException | DateTimeParseException unreadable) {
            throw invalidCursor();
        }
    }

    private static Object read(Class<?> type, String text) {
        Function<String, Object> reader = READERS.get(type);
        if (Objects.isNull(reader)) {
            throw new IllegalStateException("No cursor reader for key type " + type.getName());
        }
        return reader.apply(text);
    }

    private static AppException invalidCursor() {
        return AppException.of(ErrorCode.REQUEST_PARAMETER_INVALID).withExtension("parameter", AFTER);
    }
}
