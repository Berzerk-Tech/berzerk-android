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

@Database(entities = [EpcEntity::class, ImagemEntity::class, SyncEstado::class, ModeloEntity::class], version = 3, exportSchema = false)
abstract class ColetorDb : RoomDatabase() {
    abstract fun epcs(): EpcDao

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

        fun abrir(context: Context): ColetorDb =
            Room.databaseBuilder(context.applicationContext, ColetorDb::class.java, "coletor.db")
                .addMigrations(MIGRACAO_1_2, MIGRACAO_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}
