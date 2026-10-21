package br.com.berzerk.coletor

import br.com.berzerk.coletor.update.UpdateManager.Companion.compararSemver
import org.junit.Assert.assertTrue
import org.junit.Test

class SemverTest {
    @Test fun maiorMenorIgual() {
        assertTrue(compararSemver("0.2.0", "0.1.9") > 0)
        assertTrue(compararSemver("1.0.0", "0.99.99") > 0)
        assertTrue(compararSemver("0.1.0", "0.1.0") == 0)
        assertTrue(compararSemver("0.1.0-debug", "0.1.0") == 0)
        assertTrue(compararSemver("v0.1.1", "0.1.0") > 0)
        assertTrue(compararSemver("0.1.0", "0.1.1") < 0)
    }
}
