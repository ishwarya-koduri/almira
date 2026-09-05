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
     * The runtime datasource: a non-owner role, wrapped so every connection
     * carries the caller's identity for RLS. This is the @Primary bean, so
     * anything injecting a DataSource gets the safe one by default.
     */
    @Bean
    @Primary
    fun dataSource(userContext: RequestUserContext): DataSource =
        RlsDataSource(
            delegate = hikari(
                user = props.db.appUser,
                password = props.db.appPassword,
                poolName = "almira-app",
                maxPoolSize = props.db.maxPoolSize,
            ),
            userContext = userContext,
        )

    @Bean
    fun jdbc(dataSource: DataSource) = NamedParameterJdbcTemplate(dataSource)

    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
        DataSourceTransactionManager(dataSource)

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
