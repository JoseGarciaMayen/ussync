package es.us.ussync.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun localDateTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("Fecha no disponible")

fun localDateShort(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

fun isFutureDate(value: String): Boolean = runCatching {
    Instant.parse(value).isAfter(Instant.now())
}.getOrDefault(false)
