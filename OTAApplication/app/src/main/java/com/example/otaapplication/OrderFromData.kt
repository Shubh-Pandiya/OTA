package com.example.otaapplication

import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class OrderItem(
    val serialNo: String = "",
    val particulars: String = "",
    val quantity: String = "",
    val rate: String = ""
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
