package com.ota.desktop

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.data.AdviceDraft
import com.example.otaapplication.data.BalanceSide
import com.example.otaapplication.data.BankDetails
import com.example.otaapplication.data.BillDetails
import com.example.otaapplication.data.LineAmounts
import com.example.otaapplication.data.Party
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PaymentMode
import com.ota.desktop.data.CatalogStore
import com.ota.desktop.data.Numbers
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Db
import com.ota.desktop.db.Kv
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** A realistic, deterministic data set (about 6 months of an agency's work) for tests and screenshots. */
private val TRANSPORTS = listOf("VRL Logistics", "Patel Roadways", "Shree Maruti Courier", "Gati Ltd", "Om Logistics")

class DemoData(val db: Db, val today: LocalDate = LocalDate.of(2026, 11, 8)) {
    private var clock = today
    val kv = Kv(db)
    val numbers = Numbers(db, kv) { clock }
    val orders = OrderStore(db)
    private val serials = HashMap<String, Int>()

    /** Old staff/day numbers before 1 Nov 2026, the agency-wide FY numbers from then on (as after the switch). */
    private fun orderNo(day: LocalDate, staff: Int): String =
        if (day < LocalDate.of(2026, 11, 1)) {
            val prefix = OrderNumber.prefix(staff, day)
            OrderNumber.format(prefix, serials.merge(prefix, 1, Int::plus)!!)
        } else OrderNumber.formatFy(day, serials.merge(OrderNumber.fyPrefix(day), 1, Int::plus)!!)
    val payments = PaymentStore(db, numbers)
    val catalog = CatalogStore(db)

    val buyers = listOf(
        "Shree Ganesh Garments" to "Indore", "Mahalaxmi Fashions" to "Ujjain", "Raj Tailors" to "Dewas",
        "Kapoor Readymade" to "Bhopal", "Agrawal Cloth Stores" to "Ratlam", "New Style Shirts" to "Indore"
    )
    val suppliers = listOf("Raymond Ltd" to "Thane", "Arvind Mills" to "Ahmedabad", "Siyaram Silk Mills" to "Mumbai", "Bombay Dyeing" to "Mumbai")
    private val cloths = listOf(
        "Cotton shirting 58\" white" to "M", "Poly-viscose suiting navy" to "M", "Linen blend checks" to "M",
        "Terry-rayon suiting grey" to "T", "Formal shirt pieces" to "P", "Safari suit set" to "S", "Denim bale 10 oz" to "B"
    )
    private val fmt = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun seed(): DemoData {
        val rnd = Random(7)
        buyers.forEachIndexed { i, (n, city) ->
            catalog.save(Party(type = PartyType.BUYER, name = n, address = "${12 + i}, Main Road", city = city, phone = "98260${10000 + i * 731}", gstin = "23ABCDE${1000 + i}F1Z5"))
        }
        suppliers.forEachIndexed { i, (n, city) ->
            catalog.save(
                Party(
                    type = PartyType.SUPPLIER, name = n, city = city, phone = "0222${4000000 + i * 1111}",
                    bank = BankDetails(accountName = n, bankName = "HDFC Bank", accountNo = "5010${(10000000 + i * 4321)}", ifsc = "HDFC0000${100 + i}", branch = city)
                )
            )
        }
        var billNo = 2040
        for (daysAgo in 180 downTo 0 step 2) {
            val day = today.minusDays(daysAgo.toLong())
            clock = day
            repeat(1 + rnd.nextInt(3)) {
                val (b, bc) = buyers[rnd.nextInt(buyers.size)]
                val (s, sc) = suppliers[rnd.nextInt(suppliers.size)]
                val items = (1..1 + rnd.nextInt(3)).map { n ->
                    val (p, u) = cloths[rnd.nextInt(cloths.size)]
                    val q = when (u) { "M" -> 50 * (1 + rnd.nextInt(10)); "T" -> 2 + rnd.nextInt(10); "B" -> 1 + rnd.nextInt(3); else -> 10 * (1 + rnd.nextInt(5)) }
                    OrderItem("$n", p, "$q", "${80 + rnd.nextInt(300)}", u)
                }
                val staff = listOf(1, 2, 4)[rnd.nextInt(3)]
                val o = orders.create(
                    OrderFormData(
                        date = day.format(fmt), buyer = "$b\n$bc", supplier = "$s\n$sc", items = items,
                        deliveryReady = "${7 + rnd.nextInt(20)} days", transportTo = bc, cashPayment = "30 days", lessDisc = "2%"
                    ),
                    orderNo(day, staff)
                )
                // Most orders older than ~10 days have a bill.
                if (daysAgo > 10 && rnd.nextInt(10) < 8) {
                    val amount = (20_000 + rnd.nextInt(180_000)).toDouble()
                    orders.attachBill(
                        listOf(o.id), "INV-${billNo++}",
                        BillDetails(billDate = day.plusDays(3L + rnd.nextInt(6)), amount = amount, lrNo = "LR ${7000 + rnd.nextInt(2000)}", cdPercent = 2.0, cdAmount = amount * 0.02,
                            transport = TRANSPORTS[rnd.nextInt(TRANSPORTS.size)])
                    )
                }
            }
        }
        // A bill entered by hand from the supplier's paper bill, with no order behind it.
        orders.saveBill(
            null, "RL/8812",
            BillDetails(
                billDate = today.minusDays(4), amount = 30_250.0, lrNo = "LR 9120", transport = "Patel Roadways", buyer = "Kapoor Readymade\nBhopal", supplier = "Raymond Ltd\nThane",
                items = listOf(OrderItem("1", "Poly-viscose suiting navy", "125", "180", "M"), OrderItem("2", "Safari suit set", "25", "310", "S"))
            ),
            emptyList()
        )
        // Payments: per buyer/supplier pair, settle older bills fully or partly.
        for ((b, _) in buyers) for ((s, _) in suppliers) {
            val bills = payments.pairBills(b, s).filter { it.bill.billDate != null && it.bill.billDate!!.isBefore(today.minusDays(25)) }
            bills.chunked(2).forEachIndexed { i, chunk ->
                if (rnd.nextInt(10) >= 7) return@forEachIndexed
                val payDay = chunk.maxOf { it.bill.billDate!! }.plusDays(20L + rnd.nextInt(30)).let { if (it.isAfter(today)) today else it }
                clock = payDay
                val partial = rnd.nextInt(4) == 0
                payments.save(
                    AdviceDraft(
                        payDate = payDay, buyer = b, supplier = s,
                        mode = PaymentMode.entries[i % 3], reference = if (i % 3 == 2) "" else "${100000 + rnd.nextInt(900000)}",
                        balanceAmount = if (partial) 15_000.0 else null, balanceSide = if (partial) BalanceSide.DR else null, remarks = "",
                        lines = chunk.map { pb ->
                            val amt = pb.bill.amount ?: 0.0
                            val cd = amt * 0.02
                            val gr = if (rnd.nextInt(5) == 0) 1_000.0 else null
                            val rd = if (rnd.nextInt(6) == 0) 500.0 else null
                            // Now and then the buyer cuts a claim for defective cloth.
                            val claim = if (rnd.nextInt(7) == 0) 1_250.0 else null
                            val paid = amt - cd - (gr ?: 0.0) - (rd ?: 0.0) - (claim ?: 0.0) - (if (partial) 15_000.0 else 0.0)
                            pb.bill.id to LineAmounts(paid.coerceAtLeast(0.0), 2.0, cd, gr, rd, claim, if (claim != null) "1 than shade variation" else "")
                        }
                    ),
                    listOf(1, 2, 4)[rnd.nextInt(3)]
                )
            }
        }
        // One bill paid in two instalments, i.e. listed on two advices.
        val split = orders.saveBill(
            null, "RL/8815",
            BillDetails(
                billDate = today.minusDays(40), amount = 48_000.0, lrNo = "LR 9204", transport = "VRL Logistics", buyer = "Kapoor Readymade\nBhopal",
                supplier = "Raymond Ltd\nThane", items = listOf(OrderItem("1", "Terry-rayon suiting grey", "200", "240", "M"))
            ),
            emptyList()
        )
        for ((daysAgo, paid) in listOf(20L to 20_000.0, 6L to 27_040.0)) {
            clock = today.minusDays(daysAgo)
            payments.save(
                AdviceDraft(
                    payDate = clock, buyer = "Kapoor Readymade", supplier = "Raymond Ltd", mode = PaymentMode.ONLINE, reference = "UTR${880000 + daysAgo}",
                    balanceAmount = null, balanceSide = null, remarks = "",
                    lines = listOf(split.id to if (daysAgo == 6L) LineAmounts(paid, 2.0, 960.0) else LineAmounts(paid))
                ),
                1
            )
        }
        clock = today
        return this
    }
}
