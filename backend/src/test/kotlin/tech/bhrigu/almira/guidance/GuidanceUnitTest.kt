package tech.bhrigu.almira.guidance

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.common.ApiException

@DisplayName("Readiness scoring and the contact-us configuration")
class GuidanceUnitTest {

    @Test
    fun `every yes is no gap`() {
        val all = ReadinessQuestion.entries.associateWith { ReadinessAnswer.YES }
        assertThat(ReadinessScoring.gaps(all)).isEmpty()
    }

    @Test
    fun `a missing will outranks everything, and ties go to the earlier question`() {
        val gaps = ReadinessScoring.gaps(
            mapOf(
                ReadinessQuestion.LOCKER to ReadinessAnswer.NO,
                ReadinessQuestion.ONE_LIST to ReadinessAnswer.NO,
                ReadinessQuestion.INSURANCE to ReadinessAnswer.NO,
                ReadinessQuestion.WILL to ReadinessAnswer.PARTLY,
            ),
        )
        // will 5, one_list 6, insurance 6 (later), locker 4
        assertThat(gaps.map { it.question }).containsExactly("one_list", "insurance", "will")
    }

    @Test
    fun `parse refuses unknown questions and answers`() {
        assertThatThrownBy { ReadinessScoring.parse(mapOf("x" to "yes")) }.isInstanceOf(ApiException::class.java)
        assertThatThrownBy { ReadinessScoring.parse(mapOf("will" to "perhaps")) }.isInstanceOf(ApiException::class.java)
        assertThatThrownBy { ReadinessScoring.parse(emptyMap()) }.isInstanceOf(ApiException::class.java)
        assertThat(ReadinessScoring.parse(mapOf("will" to "not_sure"))).containsEntry(ReadinessQuestion.WILL, ReadinessAnswer.NOT_SURE)
    }

    @Test
    fun `there are eight questions and fifteen shelves, the will last`() {
        assertThat(ReadinessQuestion.entries).hasSize(8)
        assertThat(Shelf.entries).hasSize(15)
        assertThat(Shelf.entries.last()).isEqualTo(Shelf.WILL)
        ReadinessQuestion.entries.mapNotNull { it.shelf }.forEach { assertThat(Shelf.of(it)).isNotNull() }
    }

    @Test
    fun `contact us refuses a malformed channel or address and is off when blank`() {
        assertThat(SupportProperties().configured).isFalse()
        assertThat(SupportProperties("whatsapp", "+919876543210", "within a day").configured).isTrue()
        assertThat(SupportProperties("email", "help@example.in").configured).isTrue()
        assertThatThrownBy { SupportProperties("sms", "+919876543210") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SupportProperties("whatsapp", "9876543210") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SupportProperties("email", "javascript:alert(1)") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SupportProperties("whatsapp", "") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
