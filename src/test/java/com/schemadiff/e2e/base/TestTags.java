package com.schemadiff.e2e.base;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom JUnit 5 tags for categorizing E2E tests.
 *
 * Usage in Maven:
 *   mvn test -Dtest.groups=fast              # Run only fast tests
 *   mvn test -Dtest.groups=mysql             # Run only MySQL tests
 *   mvn test -Dtest.groups="fast & mysql"    # Run fast MySQL tests
 *   mvn test -Dtest.excludedGroups=slow      # Exclude slow tests
 */
public final class TestTags {

    private TestTags() {} // Utility class

    // === Speed Tags ===

    /** Fast tests using minimal schemas - run on every commit */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("fast")
    public @interface Fast {}

    /** Slow tests using full WSO2 schema - run on PR merge */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("slow")
    public @interface Slow {}

    /** Full schema integration tests */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("full-schema")
    public @interface FullSchema {}

    // === Database Tags ===

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("mysql")
    public @interface MySQL {}

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("postgres")
    public @interface Postgres {}

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("oracle")
    public @interface Oracle {}

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("mssql")
    public @interface MSSQL {}

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("db2")
    public @interface DB2 {}

    // === Object Type Tags ===

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("tables")
    public @interface Tables {}

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("columns")
    public @interface Columns {}

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("constraints")
    public @interface Constraints {}

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("indexes")
    public @interface Indexes {}

    // === Change Type Tags ===

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("missing")
    public @interface Missing {}

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("extra")
    public @interface Extra {}

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("modified")
    public @interface Modified {}

    // === Special Tags ===

    /** Complex scenarios testing multiple changes at once */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("complex")
    public @interface Complex {}

    /** Smoke tests - critical path validation */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("smoke")
    public @interface Smoke {}

    /** CLI JAR execution test (ProcessBuilder) */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Tag("cli")
    public @interface CLI {}
}

