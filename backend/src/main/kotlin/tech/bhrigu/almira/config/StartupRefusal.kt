package tech.bhrigu.almira.config

/**
 * A bean that can refuse to start, and therefore runs before the database is
 * migrated.
 *
 * The rule (docs/known-issues.md, "A guard runs before the action it guards"):
 * a refusal that comes after Flyway has already applied every pending migration
 * — column drops included — has refused too late. Beans are built in whatever
 * order their dependencies happen to pull them, and the web server builds
 * JwtAuthFilter first, which needs the database, which builds Flyway. So a
 * refusal in an ordinary bean lands behind migration unless something forces
 * it in front, and twice nothing did (KeyEncryptionKeyCheck, S3DocumentStorage).
 *
 * This is the something. [DatabaseConfig.flyway] takes every bean of this type
 * as a parameter — so Spring builds each one, running any refusal in its
 * constructor, before Flyway exists — and calls [verifyBeforeMigrating] on each,
 * in `@Order`, before it configures Flyway. Implementing this interface is the
 * whole of the wiring; nothing else has to name an implementation.
 *
 * What an implementation must not do: depend, even indirectly, on Flyway or on
 * a table a pending migration creates. The first is a circular dependency and
 * fails loudly at startup — and note that Spring Boot makes every JdbcTemplate
 * and NamedParameterJdbcTemplate bean depend on Flyway, so take a DataSource and
 * build the template. The second is the implementation's to handle (see
 * KeyEncryptionKeyCheck, which treats a table that does not exist yet as
 * nothing to check). Prefer `ObjectProvider` for anything with side effects in
 * its constructor — every refusal bean is built before any one of them is
 * verified.
 *
 * Settings that need no database are refused earlier still, before any context
 * exists, in [StartupSettingsCheck]. StartupRefusalOrderTest fails when a class
 * that says "Refusing to start" is neither.
 */
fun interface StartupRefusal {
    /** Throws to refuse. Called once, before Flyway migrates. */
    fun verifyBeforeMigrating()
}
