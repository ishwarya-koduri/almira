package tech.bhrigu.almira.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
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
     */
    @Bean(initMethod = "migrate")
    fun flyway(ownerDataSource: HikariDataSource): Flyway =
        Flyway.configure()
            .dataSource(ownerDataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .validateOnMigrate(true)
            .load()

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
