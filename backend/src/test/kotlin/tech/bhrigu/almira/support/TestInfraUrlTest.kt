package tech.bhrigu.almira.support

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The check every test connection passes first refuses the development
 * database however its URL is spelled.
 */
@DisplayName("The test database URL cannot be the development database")
class TestInfraUrlTest {

    @Test
    fun `the development database is refused with parameters, a trailing slash or any case`() {
        listOf(
            "jdbc:postgresql://localhost:55432/almira",
            "jdbc:postgresql://localhost:55432/almira/",
            "jdbc:postgresql://localhost:55432/almira?sslmode=disable",
            "jdbc:postgresql://localhost:55432/ALMIRA?ApplicationName=tests",
        ).forEach { url ->
            assertThatThrownBy { TestInfra.refuseDevelopmentDatabase(url) }
                .describedAs(url)
                .hasMessageContaining("development database")
        }
    }

    @Test
    fun `a test database is accepted`() {
        listOf(
            "jdbc:postgresql://localhost:55432/almira_test",
            "jdbc:postgresql://127.0.0.1:15532/almira_v4wf7e?sslmode=disable",
        ).forEach { url ->
            assertThatCode { TestInfra.refuseDevelopmentDatabase(url) }.describedAs(url).doesNotThrowAnyException()
        }
    }
}
