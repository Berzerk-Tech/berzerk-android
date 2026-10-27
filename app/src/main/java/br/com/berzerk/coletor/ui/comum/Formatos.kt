package br.com.berzerk.coletor.ui.comum

import java.text.NumberFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PT_BR = Locale("pt", "BR")

/** `3851` → `3.851`. */
fun formatarInteiro(n: Number): String = NumberFormat.getIntegerInstance(PT_BR).format(n)

/** ISO do Nexus (`2026-10-20T14:03:00.000Z` ou com offset) → `20/10/2026 às 11:03` (America/Sao_Paulo); texto ilegível volta como veio. */
fun formatarDataHora(iso: String, zona: ZoneId = ZoneId.of("America/Sao_Paulo")): String =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zona).format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")) }.getOrDefault(iso)

/** ISO do Nexus → `há 5 min` / `há 3 h` / `há 2 d`; ilegível volta vazio. */
fun formatarHa(iso: String, agora: java.time.Instant = java.time.Instant.now()): String {
    val t = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull() ?: return ""
    val min = java.time.Duration.between(t, agora).toMinutes().coerceAtLeast(0)
    return when {
        min < 1 -> "agora há pouco"
        min < 60 -> "há $min min"
        min < 60 * 24 -> "há ${min / 60} h"
        else -> "há ${min / (60 * 24)} d"
    }
}
