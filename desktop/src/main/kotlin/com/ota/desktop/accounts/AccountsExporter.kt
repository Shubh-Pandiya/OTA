package com.ota.desktop.accounts

import java.io.File
import java.time.LocalDate
import java.time.YearMonth

enum class ExportFormat(val label: String, val ext: String) { XLSX("Excel", "xlsx"), CSV("CSV", "csv"), PDF("PDF", "pdf") }

enum class ExportLayout(val label: String) {
    WORKBOOK("One file, a sheet per report"),
    PER_REPORT("A file per report"),
    PER_PARTY("A file per party")
}

/** Every table in the Accounts section that can be exported. */
enum class ExportSheet(val label: String, val report: ReportKind? = null) {
    LEDGERS("Ledgers"),
    TRADE_STATEMENTS("Trade statements"),
    VOUCHERS("Vouchers"),
    DAY_BOOK("Day book", ReportKind.DAY_BOOK),
    TRIAL_BALANCE("Trial balance", ReportKind.TRIAL_BALANCE),
    PROFIT_LOSS("Profit & loss", ReportKind.PROFIT_LOSS),
    BALANCE_SHEET("Balance sheet", ReportKind.BALANCE_SHEET),
    COMMISSION("Commission", ReportKind.COMMISSION),
    AGEING("Ageing", ReportKind.AGEING),
    TRADE_OUTSTANDING("Trade outstanding", ReportKind.TRADE_OUTSTANDING),
    CASH_BOOK("Cash / bank book", ReportKind.CASH_BOOK),
    BUYERS("Buyers"),
    SUPPLIERS("Suppliers"),
    CHART("Chart of accounts"),
    AUDIT("Audit log");

    companion object {
        fun of(kind: ReportKind) = entries.first { it.report == kind }
    }
}

/** Date presets; quarters follow the Indian financial year (Apr–Jun, Jul–Sep, Oct–Dec, Jan–Mar). */
enum class DatePreset(val label: String) {
    THIS_FY("This FY"), LAST_FY("Last FY"), THIS_QUARTER("This quarter"), LAST_QUARTER("Last quarter"),
    THIS_MONTH("This month"), LAST_MONTH("Last month"), ALL("All dates"), CUSTOM("Custom");

    /** From and to for [today]; CUSTOM gives nulls (the caller's own dates). */
    fun bounds(today: LocalDate): Pair<LocalDate?, LocalDate?> {
        fun quarter(d: LocalDate) = d.withDayOfMonth(1).withMonth((d.monthValue - 1) / 3 * 3 + 1)
        return when (this) {
            THIS_FY -> fyStart(today) to fyStart(today).plusYears(1).minusDays(1)
            LAST_FY -> fyStart(today).minusYears(1) to fyStart(today).minusDays(1)
            THIS_QUARTER -> quarter(today) to quarter(today).plusMonths(3).minusDays(1)
            LAST_QUARTER -> quarter(today).minusMonths(3) to quarter(today).minusDays(1)
            THIS_MONTH -> YearMonth.from(today).let { it.atDay(1) to it.atEndOfMonth() }
            LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
            ALL, CUSTOM -> null to null
        }
    }
}

data class ExportRequest(
    val sheets: Set<ExportSheet>,
    val from: LocalDate?,
    val to: LocalDate?,
    val filter: ReportFilter = ReportFilter(),
    val format: ExportFormat = ExportFormat.XLSX,
    val layout: ExportLayout = ExportLayout.WORKBOOK,
    /** Column titles (without " (₹)") to leave out. */
    val excludedColumns: Set<String> = emptySet(),
    val cashAccountId: String? = null
)

/** Builds the requested Accounts tables and writes them in the chosen format and layout. */
class AccountsExporter(private val reports: AccountReports, private val store: AccountsStore) {

    /** The tables in [ExportSheet] order, filtered and without the excluded columns. */
    fun tables(req: ExportRequest): List<Pair<ExportSheet, ReportTable>> {
        val f = req.filter
        val asOf = req.to ?: LocalDate.now()
        val chosen by lazy { store.accounts().filter { f.allows(it) }.sortedWith(compareBy({ it.group }, { it.code }, { it.name.lowercase() })) }
        val out = ArrayList<Pair<ExportSheet, ReportTable>>()
        for (sheet in ExportSheet.entries.filter { it in req.sheets }) {
            val tables: List<ReportTable> = when (sheet) {
                ExportSheet.LEDGERS -> reports.ledgers(chosen.map { it.id }, req.from, req.to, f = f)
                ExportSheet.TRADE_STATEMENTS -> chosen.filter { it.isParty }
                    .map { reports.partyStatement(it.id, req.from, req.to, f) }
                    .filter { t -> !f.hideZero || t.rows.any { r -> r.style == RowStyle.NORMAL || (r.style == RowStyle.HEADING && (r.cells.getOrNull(5) as? Double ?: 0.0) != 0.0) } }
                ExportSheet.VOUCHERS -> listOf(reports.voucherList(req.from, req.to, f))
                ExportSheet.BUYERS -> listOf(reports.partyList(PartyKind.BUYER, f))
                ExportSheet.SUPPLIERS -> listOf(reports.partyList(PartyKind.SUPPLIER, f))
                ExportSheet.CHART -> listOf(reports.chart(asOf, f))
                ExportSheet.AUDIT -> listOf(reports.auditLog(req.from, req.to, f))
                else -> {
                    val kind = sheet.report!!
                    listOf(if (kind.asOf) reports.report(kind, null, asOf, req.cashAccountId, f) else reports.report(kind, req.from, req.to, req.cashAccountId, f))
                }
            }
            tables.map { it.withoutTitles(req.excludedColumns) }.filter { it.columns.isNotEmpty() }.forEach { out += sheet to it }
        }
        return out
    }

    /**
     * Writes the export. [target] is the file for [ExportLayout.WORKBOOK] and a folder for the other layouts.
     * Returns the files written.
     */
    fun export(req: ExportRequest, target: File): List<File> {
        val tables = tables(req)
        require(tables.isNotEmpty()) { "Nothing to export with these filters" }
        val ext = req.format.ext
        val groups: List<Pair<String, List<ReportTable>>> = when (req.layout) {
            ExportLayout.WORKBOOK -> listOf(target.name to tables.map { it.second })
            ExportLayout.PER_REPORT -> tables.groupBy({ it.first }, { it.second }).map { (sheet, list) -> "${sheet.label}.$ext" to list }
            ExportLayout.PER_PARTY -> {
                val names = store.accounts().associate { it.id to it.name }
                val (party, rest) = tables.map { it.second }.partition { it.accountId != null }
                party.groupBy { it.accountId!! }.map { (id, list) -> "${names[id] ?: id}.$ext" to list } +
                    (if (rest.isEmpty()) emptyList() else listOf("Reports.$ext" to rest))
            }
        }
        val dir = if (req.layout == ExportLayout.WORKBOOK) target.absoluteFile.parentFile else target
        dir.mkdirs()
        val used = HashSet<String>()
        return groups.map { (name, list) ->
            val file = File(dir, unique(fileName(name), used))
            when (req.format) {
                ExportFormat.XLSX -> AccountsExport.xlsx(file, list)
                ExportFormat.CSV -> AccountsExport.csv(file, list)
                ExportFormat.PDF -> AccountsExport.pdf(file, list)
            }
        }
    }

    companion object {
        fun fileName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Export" }

        private fun unique(name: String, used: MutableSet<String>): String {
            var candidate = name
            var n = 2
            while (!used.add(candidate.lowercase())) candidate = name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.').also { n++ }
            return candidate
        }
    }
}
