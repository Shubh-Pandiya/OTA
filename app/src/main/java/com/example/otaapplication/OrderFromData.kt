package com.example.otaapplication

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

private val orderItemKeys = AtomicLong(0)

data class OrderItem(
    val serialNo: String = "",
    val particulars: String = "",
    val quantity: String = "",
    val rate: String = "",
    // UI-only identity so rows keep their state/animations when others are added or removed.
    // Never persisted to JSON or the PDF.
    val key: Long = orderItemKeys.incrementAndGet()
)

data class OrderFormData(
    val date: String = LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")),
    val offerNo: String = "",
    val buyer: String = "",
    val supplier: String = "",
    val items: List<OrderItem> = listOf(OrderItem(serialNo = "1")), // Starts with one row
    val remarks: String = "",
    val deliveryReady: String = "",
    val transportTo: String = "",
    val cashPayment: String = "",
    val lessDisc: String = "",
    val side: String = ""
)
