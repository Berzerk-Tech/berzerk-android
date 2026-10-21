package br.com.berzerk.coletor.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
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
)

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

    @Query("SELECT COUNT(*) FROM imagens")
    suspend fun totalImagens(): Long

    @Transaction
    suspend fun inserirPagina(linhas: List<EpcEntity>, estado: SyncEstado) {
        inserir(linhas)
        gravarEstado(estado)
    }
}

@Database(entities = [EpcEntity::class, ImagemEntity::class, SyncEstado::class], version = 1, exportSchema = false)
abstract class ColetorDb : RoomDatabase() {
    abstract fun epcs(): EpcDao

    companion object {
        fun abrir(context: Context): ColetorDb =
            Room.databaseBuilder(context.applicationContext, ColetorDb::class.java, "coletor.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
