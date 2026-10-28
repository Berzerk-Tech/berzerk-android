package br.com.berzerk.coletor.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Uma linha por EPC impresso na iTag (espelho de `GET /revisao-rfid/itag/epcs`). */
@Entity(tableName = "epcs")
data class EpcEntity(
    @PrimaryKey val epc: String,
    val ean13: String?,
    val nome: String?,
    val tamanho: String?,
    val cor: String?,
    val referencia: String?,
    val dataGerado: String,
)

@Entity(tableName = "imagens")
data class ImagemEntity(@PrimaryKey val ean13: String, val sku: String?, val imagemUrl: String)

/** Estado do download incremental (uma linha, id = 1). */
@Entity(tableName = "sync_estado")
data class SyncEstado(
    @PrimaryKey val id: Int = 1,
    /** `desde` da PRÓXIMA sincronização: maior `dataGerado` já gravado. */
    val desde: String? = null,
    /** Cursor da página em andamento quando o download foi interrompido. */
    val cursorPendente: String? = null,
    val desdePendente: String? = null,
    val concluidoEm: Long? = null,
    val imagensEm: Long? = null,
    /** Versão do índice `modelos` (`VERSAO_INDICE`); 0 = nunca indexado. */
    val indiceVersao: Int = 0,
)

/**
 * Índice por modelo, derivado de `epcs` (`categoria - modelo - tamanho` no `nome` da iTag).
 * Reconstruído por `ItagSync.reindexarModelos()` depois de cada sync. `qtd` = nº de EPCs.
 */
@Entity(tableName = "modelos", primaryKeys = ["categoria", "modelo", "tamanho", "ean13"])
data class ModeloEntity(
    val categoria: String,
    val modelo: String,
    /** Uppercase; `""` se a iTag não trouxe. */
    val tamanho: String,
    /** `""` se a iTag não trouxe. */
    val ean13: String,
    val qtd: Int,
)

/** Resultado da agregação sobre `epcs` (antes do parse do nome). */
data class LinhaAgregada(val nome: String?, val ean13: String?, val tamanho: String?, val qtd: Int)

@Dao
interface EpcDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserir(linhas: List<EpcEntity>)

    @Query("SELECT * FROM epcs WHERE epc IN (:epcs)")
    suspend fun buscar(epcs: List<String>): List<EpcEntity>

    @Query("SELECT COUNT(*) FROM epcs")
    suspend fun total(): Long

    @Query("SELECT MAX(dataGerado) FROM epcs")
    suspend fun maiorDataGerado(): String?

    @Query("SELECT * FROM sync_estado WHERE id = 1")
    suspend fun estado(): SyncEstado?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun gravarEstado(estado: SyncEstado)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserirImagens(linhas: List<ImagemEntity>)

    @Query("SELECT * FROM imagens WHERE ean13 IN (:eans)")
    suspend fun imagens(eans: List<String>): List<ImagemEntity>

    @Query("SELECT * FROM imagens ORDER BY ean13")
    suspend fun todasImagens(): List<ImagemEntity>

    @Query("SELECT COUNT(*) FROM imagens")
    suspend fun totalImagens(): Long

    @Query("SELECT nome, ean13, tamanho, COUNT(*) AS qtd FROM epcs GROUP BY nome, ean13, tamanho")
    suspend fun agregarPorNome(): List<LinhaAgregada>

    @Query("SELECT * FROM modelos")
    suspend fun listarModelos(): List<ModeloEntity>

    @Query("SELECT COUNT(DISTINCT lower(categoria) || '|' || lower(modelo)) FROM modelos")
    suspend fun totalModelos(): Long

    @Query("DELETE FROM modelos") suspend fun apagarModelos()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserirModelos(linhas: List<ModeloEntity>)

    @Transaction
    suspend fun substituirModelos(linhas: List<ModeloEntity>) {
        apagarModelos(); inserirModelos(linhas)
    }

    @Query("DELETE FROM epcs") suspend fun apagarEpcs()
    @Query("DELETE FROM imagens") suspend fun apagarImagens()
    @Query("DELETE FROM sync_estado") suspend fun apagarEstado()

    /** Troca todas as imagens de uma vez (não acumula EAN que saiu do catálogo). */
    @Transaction
    suspend fun substituirImagens(linhas: List<ImagemEntity>) {
        apagarImagens(); inserirImagens(linhas)
    }

    @Transaction
    suspend fun limparTudo() {
        apagarEstado(); apagarEpcs(); apagarImagens(); apagarModelos()
    }

    @Transaction
    suspend fun inserirPagina(linhas: List<EpcEntity>, estado: SyncEstado) {
        inserir(linhas)
        gravarEstado(estado)
    }
}

/** De onde vem a leitura. `referencia` = sessão de inventário / chave do modelo / id do lote / `""` (livre). */
object Origem {
    const val INVENTARIO = "inventario"
    const val REVISAO = "revisao"
    const val CONFERENCIA = "conferencia"
    const val LIVRE = "livre"
}

/**
 * EPC lido neste aparelho. PK composta `(origem, referencia, epc)`: a mesma peça lida 50 vezes na
 * mesma sessão é UMA linha. `referencia` nunca é nula (`""` = sem referência): NULL em PK não
 * deduplica no SQLite. `enviadoEm` nulo = ainda não subiu pro Nexus (só um 2xx marca). `pastaId` nulo =
 * pasta ainda não escolhida (linha legada ou lida depois do último SALVAR).
 */
@Entity(tableName = "leituras", primaryKeys = ["origem", "referencia", "epc"])
data class LeituraEntity(
    val epc: String,
    val lidoEm: Long,
    val origem: String,
    val referencia: String = "",
    val enviadoEm: Long? = null,
    /** Pasta (sessão de contagem) escolhida no SALVAR. Nula = ainda não escolhida: a linha não sobe sozinha. */
    val pastaId: String? = null,
)

/** Última resposta de `GET revisao-rfid/sessoes/{id}/inventario` (demora 15–25 s numa sessão aberta). */
@Entity(tableName = "inventario_cache")
data class InventarioCacheEntity(
    @PrimaryKey val sessaoId: String,
    val json: String,
    /** `RfidSessaoDto` da sessão (cabeçalho offline). */
    val sessaoJson: String?,
    val atualizadoEm: Long,
)

/** Leituras de Revisão com vários modelos ainda não enviadas, por referência (`multi:<hash>`). */
data class PendenteReferencia(val referencia: String, val n: Int)

data class ContagemLeituras(val lidas: Int, val enviadas: Int)
data class GrupoLeitura(val origem: String, val referencia: String, val pastaId: String)

@Dao
interface LeituraDao {
    /** `IGNORE`: EPC já gravado na mesma (origem, referencia) não vira linha nova nem perde o `enviadoEm`. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun inserir(linhas: List<LeituraEntity>)

    @Query(
        "SELECT COUNT(*) AS lidas, COALESCE(SUM(CASE WHEN enviadoEm IS NOT NULL THEN 1 ELSE 0 END), 0) AS enviadas " +
            "FROM leituras WHERE origem = :origem AND referencia = :referencia",
    )
    fun contagem(origem: String, referencia: String): Flow<ContagemLeituras>

    @Query("SELECT referencia, COUNT(*) AS n FROM leituras WHERE origem = :origem AND enviadoEm IS NULL AND referencia LIKE 'multi:%' GROUP BY referencia")
    fun pendentesMulti(origem: String): Flow<List<PendenteReferencia>>

    @Query("SELECT epc FROM leituras WHERE origem = :origem AND referencia = :referencia ORDER BY lidoEm, epc")
    suspend fun epcs(origem: String, referencia: String): List<String>

    /** Pendentes DESTA pasta. Linha sem pasta nunca sai daqui: espera o SALVAR. */
    @Query("SELECT epc FROM leituras WHERE origem = :origem AND referencia = :referencia AND pastaId = :pastaId AND enviadoEm IS NULL ORDER BY lidoEm, epc LIMIT :limite")
    suspend fun pendentes(origem: String, referencia: String, pastaId: String, limite: Int): List<String>

    @Query("UPDATE leituras SET enviadoEm = :em WHERE origem = :origem AND referencia = :referencia AND pastaId = :pastaId AND epc IN (:epcs)")
    suspend fun marcarEnviadas(origem: String, referencia: String, pastaId: String, epcs: List<String>, em: Long)

    /**
     * SALVAR: os pendentes SEM pasta (ou cuja pasta já foi marcada fechada, [fechadas]) desta (origem, referencia)
     * passam a ir pra [pastaId]. Linha de pasta com envio vivo nunca é reatribuída (iria duplicada pra duas pastas).
     */
    @Query("UPDATE leituras SET pastaId = :pastaId WHERE origem = :origem AND referencia = :referencia AND enviadoEm IS NULL AND (pastaId IS NULL OR pastaId IN (:fechadas))")
    suspend fun atribuirPasta(origem: String, referencia: String, pastaId: String, fechadas: List<String>)

    /** Pendentes que já têm pasta (o worker só reenvia estes). */
    @Query("SELECT DISTINCT origem, referencia, pastaId FROM leituras WHERE enviadoEm IS NULL AND pastaId IS NOT NULL")
    suspend fun gruposPendentes(): List<GrupoLeitura>

    @Query("SELECT COUNT(*) FROM leituras WHERE origem = :origem AND referencia = :referencia AND enviadoEm IS NULL AND pastaId IS NULL")
    fun contarSemPasta(origem: String, referencia: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM leituras WHERE origem = :origem AND referencia = :referencia AND enviadoEm IS NULL AND pastaId IS NULL")
    suspend fun contarSemPastaAgora(origem: String, referencia: String): Int

    /** Leituras que esperam pasta, de qualquer origem (Pastas e Home). */
    @Query("SELECT COUNT(*) FROM leituras WHERE enviadoEm IS NULL AND pastaId IS NULL")
    fun contarSemPastaTotal(): Flow<Int>

    @Query("DELETE FROM leituras WHERE origem = :origem AND referencia = :referencia")
    suspend fun apagar(origem: String, referencia: String)

    /** Revisão/livre não acumulam: ao abrir a tela, o que já subiu sai (os pendentes ficam). */
    @Query("DELETE FROM leituras WHERE origem = :origem AND referencia = :referencia AND enviadoEm IS NOT NULL")
    suspend fun apagarEnviadas(origem: String, referencia: String)

    /** Limpeza geral: enviadas antes de [limite] (epoch ms), de qualquer origem. */
    @Query("DELETE FROM leituras WHERE enviadoEm IS NOT NULL AND enviadoEm < :limite")
    suspend fun apagarEnviadasAntes(limite: Long)

    @Query("SELECT COUNT(*) FROM leituras WHERE enviadoEm IS NULL")
    suspend fun contarNaoEnviadas(): Int

    @Query("SELECT * FROM inventario_cache WHERE sessaoId = :sessaoId")
    suspend fun cacheInventario(sessaoId: String): InventarioCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun gravarCacheInventario(linha: InventarioCacheEntity)

    /** Entrou leitura nova na pasta: o inventário em cache ficou velho. */
    @Query("DELETE FROM inventario_cache WHERE sessaoId = :sessaoId")
    suspend fun apagarCacheInventario(sessaoId: String)
}

@Database(entities = [EpcEntity::class, ImagemEntity::class, SyncEstado::class, ModeloEntity::class, LeituraEntity::class, InventarioCacheEntity::class], version = 5, exportSchema = false)
abstract class ColetorDb : RoomDatabase() {
    abstract fun epcs(): EpcDao
    abstract fun leituras(): LeituraDao

    companion object {
        /** 1 → 2: só cria `modelos` (preenchida pelo `reindexarModelos`); NÃO mexe em `epcs` (2,8 M linhas). */
        private val MIGRACAO_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `modelos` (`categoria` TEXT NOT NULL, `modelo` TEXT NOT NULL, " +
                        "`tamanho` TEXT NOT NULL, `ean13` TEXT NOT NULL, `qtd` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`categoria`, `modelo`, `tamanho`, `ean13`))",
                )
            }
        }

        /** 2 → 3: `sync_estado.indiceVersao` (força reindexar `modelos`); NÃO mexe em `epcs`. */
        private val MIGRACAO_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_estado ADD COLUMN indiceVersao INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 3 → 4: cria `leituras` e `inventario_cache`; NÃO mexe em `epcs` (2,8 M linhas). */
        private val MIGRACAO_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `leituras` (`epc` TEXT NOT NULL, `lidoEm` INTEGER NOT NULL, " +
                        "`origem` TEXT NOT NULL, `referencia` TEXT NOT NULL, `enviadoEm` INTEGER, " +
                        "PRIMARY KEY(`origem`, `referencia`, `epc`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inventario_cache` (`sessaoId` TEXT NOT NULL, `json` TEXT NOT NULL, " +
                        "`sessaoJson` TEXT, `atualizadoEm` INTEGER NOT NULL, PRIMARY KEY(`sessaoId`))",
                )
            }
        }

        /**
         * 4 → 5: `leituras.pastaId` (nula = pasta ainda não escolhida); NÃO mexe em `epcs` (2,8 M linhas).
         * Inventário legado já tem destino (a referência é a sessão); Revisão e Livre esperam o SALVAR.
         */
        val SQL_4_5 = listOf(
            "ALTER TABLE leituras ADD COLUMN pastaId TEXT",
            "UPDATE leituras SET pastaId = referencia WHERE origem = 'inventario' AND referencia <> '' AND enviadoEm IS NULL",
        )

        val MIGRACAO_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SQL_4_5.forEach { db.execSQL(it) }
            }
        }

        fun abrir(context: Context): ColetorDb =
            Room.databaseBuilder(context.applicationContext, ColetorDb::class.java, "coletor.db")
                .addMigrations(MIGRACAO_1_2, MIGRACAO_2_3, MIGRACAO_3_4, MIGRACAO_4_5)
                .build()
    }
}
