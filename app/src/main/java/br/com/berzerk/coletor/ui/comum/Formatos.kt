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
