package br.com.berzerk.coletor.data

/**
 * Junta as tags que o leitor emite (o SDK repete a mesma várias vezes por segundo) até o próximo
 * `drenar()` (~250 ms): a lista que vai pro Room em lote tem cada EPC uma vez. O `INSERT OR IGNORE`
 * cuida de quem já está gravado de leituras anteriores.
 */
class BufferLeituras {
    private val novos = LinkedHashSet<String>()

    /** Toda tag emitida, repetida ou não (o "M leituras" do console). */
    var leituras: Int = 0
        private set

    /** EPCs distintos esperando gravação. */
    val tamanho: Int get() = novos.size

    /** Tag que já está gravada nesta sessão: conta como leitura, não vai de novo pro Room. */
    fun registrarRepetida() { leituras++ }

    fun registrar(epc: String) {
        leituras++
        novos.add(epc)
    }

    /** Devolve os EPCs distintos desde a última chamada e esvazia o buffer. */
    fun drenar(): List<String> {
        if (novos.isEmpty()) return emptyList()
        val saida = novos.toList()
        novos.clear()
        return saida
    }

    fun zerar() {
        novos.clear()
        leituras = 0
    }

    /** Leituras já gravadas que voltam do Room ao reabrir a tela: contam como leituras também. */
    fun somarLeituras(n: Int) { leituras += n }
}
