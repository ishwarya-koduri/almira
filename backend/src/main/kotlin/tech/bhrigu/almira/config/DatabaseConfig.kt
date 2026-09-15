package tech.bhrigu.almira.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import tech.bhrigu.almira.security.RequestUserContext
import javax.sql.DataSource

@Configuration
class DatabaseConfig(private val props: AlmiraProperties) {

    /**
     * Schema owner. Used only to run migrations at startup, never to serve a
     * request — it can bypass row-level security, which is exactly what we do
     * not want anywhere near user traffic.
     */
    /*
     * Every consumer of this pool names it with @Qualifier. Both pools are
     * HikariDataSource at runtime, so a parameter typed HikariDataSource matches
     * both once they exist, and @Primary wins over the parameter's name. That
     * silently wired `systemJdbcBypassingRls` to the RUNTIME pool: the reminder
     * sweep ran under row-level security with no user and found nothing, every
     * hour, with no error. Flyway escaped only because it happened to be created
     * before the runtime pool. See ReminderSweepTest and docs/19.
     */
    @Bean(destroyMethod = "close")
    fun ownerDataSource(): HikariDataSource = hikari(
        user = props.db.ownerUser,
        password = props.db.ownerPassword,
        poolName = "almira-owner",
        maxPoolSize = 2,
    )

    /**
     * The runtime datasource: a non-owner role, so row-level security applies
     * and cannot be opted out of. @Primary, so anything injecting a DataSource
     * gets the safe one by default — the owner pool has to be asked for by name.
     */
    @Bean
    @Primary
    fun dataSource(): DataSource = hikari(
        user = props.db.appUser,
        password = props.db.appPassword,
        poolName = "almira-app",
        maxPoolSize = props.db.maxPoolSize,
    )

    @Bean
    fun jdbc(dataSource: DataSource) = NamedParameterJdbcTemplate(dataSource)

    /**
     * A template on the OWNER connection, which bypasses row-level security.
     *
     * Background jobs have no user, so they have no visibility — and that is
     * correct, not a limitation: a scheduled sweep for due reminders genuinely
     * operates outside anyone's view of the data. It needs a way in that does
     * not involve pretending to be someone.
     *
     * The name is deliberately unpleasant. Anything injecting this is opting out
     * of the privacy model and has to justify itself; request-handling code must
     * never use it. Its consumers are ReminderWorker, the NotificationOutbox
     * worker, AlphaAllowlistAccess, and KeyEncryptionKeyCheck — all code that
     * runs without a request user.
     */
    @Bean("systemJdbcBypassingRls")
    fun systemJdbc(@Qualifier("ownerDataSource") ownerDataSource: HikariDataSource) =
        NamedParameterJdbcTemplate(ownerDataSource)

    /**
     * Stamps the caller's identity onto each transaction for RLS. See
     * [RlsTransactionManager] — the identity is transaction-scoped, so
     * PostgreSQL clears it at commit or rollback and no connection can carry
     * one borrower's identity to the next.
     */
    @Bean
    fun transactionManager(
        dataSource: DataSource,
        userContext: RequestUserContext,
    ): PlatformTransactionManager = RlsTransactionManager(dataSource, userContext)

    /**
     * Migrations run as the owner before the app serves anything. The repeatable
     * R__grants migration re-grants privileges to the runtime role each time it
     * changes, so a table added by a future migration is never unreachable.
     *
     * The page-checksum and runtime-role checks run first, so a database that
     * would be refused has had nothing written to it. The runtime-role check
     * runs once more after migrating (a migration runs as the owner and could
     * hand a table to the runtime role), which is still before the web server
     * accepts a connection. See [PageChecksumCheck] and [RuntimeRoleCheck].
     */
    @Bean(initMethod = "migrate")
    fun flyway(
        @Qualifier("ownerDataSource") ownerDataSource: HikariDataSource,
        @Qualifier("dataSource") runtimeDataSource: DataSource,
        environment: Environment,
    ): Flyway {
        PageChecksumCheck(environment).verify(ownerDataSource)
        val roleCheck = RuntimeRoleCheck(props)
        roleCheck.verify(runtimeDataSource, "before migrating")
        val configuration = Flyway.configure()
            .dataSource(ownerDataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .validateOnMigrate(true)
        // The re-check wraps migrate() rather than being a Flyway AFTER_MIGRATE
        // callback: any callback makes Flyway open one more owner connection than
        // the two this pool has, and startup then times out waiting for it.
        return object : Flyway(configuration) {
            override fun migrate(): MigrateResult =
                super.migrate().also { roleCheck.verify(runtimeDataSource, "after migrating, before serving") }
        }
    }

    private fun hikari(user: String, password: String, poolName: String, maxPoolSize: Int) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = props.db.url
                username = user
                this.password = password
                this.poolName = poolName
                maximumPoolSize = maxPoolSize
                driverClassName = "org.postgresql.Driver"
                connectionTimeout = 10_000
            },
        )
}
