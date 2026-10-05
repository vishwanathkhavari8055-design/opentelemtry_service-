package com.trinity.observability.util;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import java.util.Map;

public class QueryBuilderUtil {

    private QueryBuilderUtil() {
    }

    public static Query buildBoolQuery(Map<String, String> filters, String search, String startTime, String endTime,
            String timestampField, String... existsFields) {

        return Query.of(q -> q.bool(b -> {
            // 1. Term/Match Filters
            addMatchFilters(b, filters);

            // 2. Full-text search (Robust & Lenient)
            addFullTextSearch(b, search);

            // 3. Exists filters
            addExistsFilters(b, existsFields);

            // 4. Date Range Filter (Stabilized Canonical Pattern)
            addDateRangeFilter(b, startTime, endTime, timestampField);

            return b;
        }));
    }

    private static void addMatchFilters(BoolQuery.Builder b, Map<String, String> filters) {
        if (filters != null) {
            filters.forEach((key, value) -> {
                if (value != null && !value.isEmpty()) {
                    b.must(m -> m.match(ma -> ma.field(key).query(value)));
                }
            });
        }
    }

    private static void addFullTextSearch(BoolQuery.Builder b, String search) {
        if (search != null && !search.trim().isEmpty()) {
            b.must(m -> m.queryString(qs -> qs
                .fields("message", "body.text", "service.name", "resource.attributes.service.name",
                        "host.name", "transaction.name", "span.name", "trace.id", "trace_id", "span.id")
                .query(search)
                .lenient(true)
                .defaultOperator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)
            ));
        }
    }

    private static void addExistsFilters(BoolQuery.Builder b, String... existsFields) {
        if (existsFields != null && existsFields.length > 0) {
            b.must(m -> m.bool(bb -> {
                for (String field : existsFields) {
                    bb.should(s -> s.exists(e -> e.field(field)));
                }
                return bb.minimumShouldMatch("1");
            }));
        }
    }

    private static void addDateRangeFilter(BoolQuery.Builder b, String startTime, String endTime,
            String timestampField) {
        String trimmedStart = trimToEmpty(startTime);
        String trimmedEnd = trimToEmpty(endTime);

        if (!trimmedStart.isEmpty() || !trimmedEnd.isEmpty()) {
            b.filter(f -> f.range(r -> applyRangeBounds(r, timestampField, trimmedStart, trimmedEnd)));
        }
    }

    private static String trimToEmpty(String value) {
        return (value != null) ? value.trim() : "";
    }

    private static RangeQuery.Builder applyRangeBounds(RangeQuery.Builder r, String timestampField,
            String trimmedStart, String trimmedEnd) {
        r.field(timestampField);
        if (!trimmedStart.isEmpty()) {
            r.gte(co.elastic.clients.json.JsonData.of(trimmedStart));
        }
        if (!trimmedEnd.isEmpty()) {
            r.lte(co.elastic.clients.json.JsonData.of(trimmedEnd));
        }
        return r;
    }
}
