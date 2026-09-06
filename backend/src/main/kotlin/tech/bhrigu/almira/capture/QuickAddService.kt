package tech.bhrigu.almira.capture

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.catalog.CatalogService
import tech.bhrigu.almira.household.HouseholdService
import java.time.LocalDate
import java.util.UUID

/**
 * Builds the vocabulary the parser needs, then hands it the text.
 *
 * The vocabulary comes from the household's own taxonomy and institution list,
 * so a custom type someone added is matched exactly like a built-in one — the
 * "record anything" promise has to hold for quick-add too, or the fast path
 * quietly only works for things we predefined.
 */
@Service
class QuickAddService(
    private val catalog: CatalogService,
    private val households: HouseholdService,
) {

    @Transactional(readOnly = true)
    fun parse(householdId: UUID, text: String, today: LocalDate = LocalDate.now()): QuickAddParser.Parsed {
        households.get(householdId)

        val types = catalog.taxonomy(householdId).flatMap { (category, types) ->
            types.map { type ->
                QuickAddParser.TypeVocabulary(
                    typeId = type.id,
                    code = type.code,
                    label = type.label,
                    keywords = keywordsFor(type.code, type.label, category.label),
                )
            }
        }

        val institutions = catalog.institutions(householdId, null, null).map {
            QuickAddParser.InstitutionVocabulary(it.id, it.name)
        }

        return QuickAddParser.parse(
            text, QuickAddParser.Vocabulary(types, institutions), today,
        )
    }

    /**
     * The words someone would actually type for a type.
     *
     * The label and code are the baseline; the extras are the shorthand people
     * use out loud — "FD", "SIP", "flat" — which is the whole point of a quick
     * add. Anything not listed still matches by label, so a custom type is never
     * worse off than an unlisted built-in.
     */
    private fun keywordsFor(code: String, label: String, categoryLabel: String): Set<String> {
        val base = buildSet {
            add(label.lowercase())
            add(code.replace('_', ' '))
            // "Mutual Fund — SIP" should also match on "mutual fund".
            label.lowercase().substringBefore(" —").trim().let(::add)
        }
        return base + (SHORTHAND[code] ?: emptySet())
    }

    private companion object {
        val SHORTHAND: Map<String, Set<String>> = mapOf(
            "fd" to setOf("fd", "fixed deposit", "deposit"),
            "rd" to setOf("rd", "recurring deposit"),
            "tax_saver_fd" to setOf("tax saver fd", "elss fd"),
            "gold_physical" to setOf("gold", "coins", "bar", "bars"),
            "gold_jewelry" to setOf("jewellery", "jewelry", "bangles", "chain", "necklace"),
            "gold_digital" to setOf("digital gold"),
            "gold_sgb" to setOf("sgb", "sovereign gold bond"),
            "silver" to setOf("silver"),
            "mf_sip" to setOf("sip", "mutual fund", "mf"),
            "mf_lumpsum" to setOf("lumpsum", "lump sum"),
            "stock_listed" to setOf("shares", "stock", "stocks", "equity"),
            "stock_unlisted" to setOf("unlisted", "unlisted shares"),
            "esop_rsu" to setOf("esop", "rsu", "esops"),
            "ppf" to setOf("ppf"),
            "epf" to setOf("epf", "pf", "provident fund"),
            "nps" to setOf("nps"),
            "ssy" to setOf("ssy", "sukanya"),
            "nsc_kvp" to setOf("nsc", "kvp"),
            "scss" to setOf("scss"),
            "insurance_term" to setOf("term", "term plan", "term cover", "lic"),
            "insurance_health" to setOf("health insurance", "mediclaim"),
            "insurance_endowment" to setOf("endowment", "money back"),
            "insurance_ulip" to setOf("ulip"),
            "property" to setOf("flat", "plot", "land", "house", "property", "apartment"),
            "reit" to setOf("reit", "invit"),
            "bond" to setOf("bond", "bonds", "ncd", "tbill"),
            "chit_fund" to setOf("chit", "chit fund"),
            "crypto" to setOf("crypto", "bitcoin", "btc", "eth"),
            "savings_buffer" to setOf("savings", "buffer", "emergency fund"),
            "cash_on_hand" to setOf("cash"),
            "loan_given" to setOf("lent", "loan given"),
            "universal" to setOf("other", "misc"),
        )
    }
}
