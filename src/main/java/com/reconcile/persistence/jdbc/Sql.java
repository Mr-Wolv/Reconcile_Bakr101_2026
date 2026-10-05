package com.reconcile.persistence.jdbc;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A thin wrapper over {@link JdbcClient} that binds {@link Instant} correctly.
 *
 * <p>The PostgreSQL driver cannot infer a SQL type for {@code java.time.Instant} through
 * {@code setObject}, and every write in this system has at least one timestamp. Fixing that at each
 * call site would be both verbose and easy to get wrong — and the two obvious workarounds are worse
 * than the problem:
 *
 * <ul>
 *   <li>{@code Timestamp.from(instant)} silently reinterprets the instant in the JVM's default zone,
 *       so the same write produces different stored values on different machines.</li>
 *   <li>A string rendering loses the type entirely and makes PostgreSQL guess again.</li>
 * </ul>
 *
 * <p>Converting to {@link OffsetDateTime} at UTC is unambiguous: the driver maps it to
 * {@code timestamptz} and stores the instant it names, whatever the JVM zone happens to be. Every
 * timestamp this system writes is UTC, so this is the invariant rather than a conversion.
 *
 * <p>Otherwise a plain delegate. {@code LocalDate} and {@code OffsetDateTime} are left alone: the
 * driver already handles both, and converting a {@code DATE} to an instant would be wrong — a
 * settlement date is a calendar day in the provider's frame, not an instant.
 */
@Component
public class Sql {

    private final JdbcClient jdbc;

    public Sql(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Statement sql(String sql) {
        return new Statement(jdbc.sql(sql));
    }

    /**
     * Reads a {@code timestamptz} column as an {@link Instant}.
     *
     * <p>The driver will not convert to {@code Instant} directly, so the value comes back as an
     * {@link OffsetDateTime} and is narrowed here. Doing this once beats the alternative of letting
     * every row mapper remember, because the failure mode of forgetting is a runtime exception on
     * the first row that has a timestamp.
     *
     * @return the instant, or null when the column is null
     */
    public static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** As {@link #instant(ResultSet, String)}, for the first column of a result. */
    public static Instant instant(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** One statement, with parameters already normalised. */
    public static final class Statement {

        private final JdbcClient.StatementSpec spec;

        private Statement(JdbcClient.StatementSpec spec) {
            this.spec = spec;
        }

        /** A named parameter. */
        public Statement param(String name, Object value) {
            spec.param(name, convert(value));
            return this;
        }

        /** A positional parameter. */
        public Statement param(Object value) {
            spec.param(convert(value));
            return this;
        }

        public Statement params(Object... values) {
            spec.params(convertAll(values));
            return this;
        }

        /** Maps to a single-column result, for scalar queries. */
        public <T> List<T> list(Class<T> type) {
            try {
                return spec.query(type).list();
            } catch (DataAccessException e) {
                throw translate(e);
            }
        }

        public <T> Optional<T> optional(Class<T> type) {
            try {
                return spec.query(type).optional();
            } catch (DataAccessException e) {
                throw translate(e);
            }
        }

        /**
         * Maps to a single column and demands exactly one row.
         *
         * @throws org.springframework.dao.IncorrectResultSizeDataAccessException if the query did
         *         not return exactly one row — which for a {@code COUNT(*)} means the query is
         *         wrong, and is far better caught here than silently read as the first row
         */
        public <T> T single(Class<T> type) {
            try {
                return spec.query(type).single();
            } catch (DataAccessException e) {
                throw translate(e);
            }
        }

        public <T> List<T> list(RowMapper<T> mapper) {
            try {
                return spec.query(mapper).list();
            } catch (DataAccessException e) {
                throw translate(e);
            }
        }

        public <T> Optional<T> optional(RowMapper<T> mapper) {
            try {
                return spec.query(mapper).optional();
            } catch (DataAccessException e) {
                throw translate(e);
            }
        }

        public int update() {
            try {
                return spec.update();
            } catch (DataAccessException e) {
                throw translate(e);
            }
        }
    }

    /**
     * PostgreSQL's lock timeout, re-typed as the contention it is.
     *
     * <p>Every raw statement in this application goes through this class, which is why the
     * translation lives here rather than in a translator bean: a bean is only consulted if the
     * {@code JdbcTemplate} in play happens to be wired from the context, and a lock timeout that
     * arrives as {@code UncategorizedSQLException} is invisible to the retry policy in
     * {@code LedgerService} — so a contended posting would neither retry nor report itself as
     * contention, it would surface as an unexplained 500.
     *
     * <p>Spring maps {@code 40P01} (deadlock) and {@code 55P03} (lock timeout) to
     * {@code CannotAcquireLockException} only for error codes its vendor table knows; PostgreSQL's
     * 55P03 is not in it, so it arrives uncategorised. Matching on the SQL state is exact rather
     * than heuristic, and {@code 55P03} has exactly one meaning in PostgreSQL.
     */
    private static RuntimeException translate(DataAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                return new CannotAcquireLockException(
                        "the statement exceeded lock_timeout while waiting for a row lock", e);
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return e;
    }

    /** Converts one parameter value, and values nested in a list or map. */
    private static Object convert(Object value) {
        if (value instanceof Instant instant) {
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> converted = new java.util.LinkedHashMap<>();
            map.forEach((key, entry) -> converted.put(key, convert(entry)));
            return converted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Sql::convert).toList();
        }
        return value;
    }

    private static Object[] convertAll(Object[] values) {
        Object[] converted = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            converted[i] = convert(values[i]);
        }
        return converted;
    }
}