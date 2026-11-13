package com.example.app.common.pagination;

/**
 * A filter with a free-text {@code query}. Lets one predicate helper serve two filter types (a
 * resource's list and its dropdown) and still take the decision from the filter.
 */
public interface QueryFilter {

    String getQuery();

    boolean requireQueryPredicate();
}
