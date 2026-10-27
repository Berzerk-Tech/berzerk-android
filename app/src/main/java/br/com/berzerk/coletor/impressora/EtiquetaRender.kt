package br.com.berzerk.coletor.impressora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import br.com.berzerk.coletor.R
import br.com.berzerk.coletor.core.Graph

/** Cabeça de impressão da B31: 600 px a 203 dpi. */
const val CABECA_PX = 600
private const val ALTURA_MAX_PX = 1200

/**
 * mm → px a 203 dpi. Aplica mínimos de 30 mm de largura e 50 mm de altura: as zonas da etiqueta são
 * ancoradas em cima e embaixo e colapsam abaixo disso.
 *
 * Conversão: arredondado pra cima a múltiplo de 8 (100 mm = 799,2 → 800); largura limitada à cabeça (≥ 75 mm vira 600 fixo) e altura a 1200 px. */
fun dimensoesPx(larguraMm: Int, alturaMm: Int): Pair<Int, Int> {
    fun px(mm: Int) = Math.ceil(mm * 203 / 25.4 / 8).toInt() * 8
    val larguraMm = larguraMm.coerceAtLeast(30)
    val alturaMm = alturaMm.coerceAtLeast(50)
    val l = if (larguraMm >= 75) CABECA_PX else px(larguraMm).coerceIn(8, CABECA_PX)
    val a = px(alturaMm).coerceIn(8, ALTURA_MAX_PX)
    return l to a
}

/** Etiqueta: tamanho gigante no alto, EAN e nome do produto (até 2 linhas) embaixo, tudo preto puro sobre branco. */
object EtiquetaRender {
    private val fonte: Typeface by lazy {
        ResourcesCompat.getFont(Graph.appContext, R.font.geist_bold) ?: Typeface.DEFAULT_BOLD
    }

    fun renderizar(tamanho: String, ean: String, nome: String, larguraPx: Int = 600, alturaPx: Int = 800): Bitmap {
        val bmp = Bitmap.createBitmap(larguraPx, alturaPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val cx = larguraPx / 2f
        // As zonas são as da etiqueta 600x800 contadas a partir de cima (tamanho) e de baixo (EAN, nome).
        val zonaTamanhoTopo = 40f
        val zonaTamanhoBase = alturaPx - 240f
        val baselineEan = alturaPx - 185f
        val zonaNomeTopo = alturaPx - 160f
        val zonaNomeBase = alturaPx - 10f

        fun tinta(px: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonte; color = Color.BLACK; textSize = px; textAlign = Paint.Align.CENTER
        }

        // Tamanho: 560 px de fonte, encolhe até caber na largura útil; glifo centrado na faixa.
        val larguraUtil = larguraPx - 60f
        val pTam = tinta(560f)
        while (pTam.textSize > 40f && pTam.measureText(tamanho) > larguraUtil) pTam.textSize -= 4f
        val caixa = Rect()
        pTam.getTextBounds(tamanho, 0, tamanho.length, caixa)
        val baselineTam = (zonaTamanhoTopo + zonaTamanhoBase) / 2f - (caixa.top + caixa.bottom) / 2f
        c.drawText(tamanho, cx, baselineTam, pTam)

        c.drawText(ean, cx, baselineEan, tinta(36f))

        // Nome: 56 px em até 2 linhas; se não couber sem cortar, reduz até 40 px (aí corta com reticências).
        val larguraNome = (larguraPx - 40).coerceAtLeast(8)
        var layout: StaticLayout? = null
        var px = 56f
        while (px >= 40f) {
            layout = montarNome(nome, tinta(px), larguraNome)
            if (layout.getEllipsisCount(layout.lineCount - 1) == 0) break
            px -= 2f
        }
        val l = layout ?: montarNome(nome, tinta(40f), larguraNome)
        c.save()
        c.translate(20f, zonaNomeTopo + ((zonaNomeBase - zonaNomeTopo) - l.height) / 2f)
        l.draw(c)
        c.restore()

        binarizar(bmp)
        return bmp
    }

    private fun montarNome(nome: String, paint: TextPaint, largura: Int): StaticLayout =
        StaticLayout.Builder.obtain(nome, 0, nome.length, paint, largura)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /** Sem cinza do antialias indo pra impressora: luminância < 160 vira preto, o resto branco. */
    private fun binarizar(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val lum = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
            px[i] = if (lum < 160) Color.BLACK else Color.WHITE
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Etiqueta de teste do ajuste: "M", EAN fictício e o tamanho do rolo no nome. */
    fun etiquetaDeTeste(larguraMm: Int, alturaMm: Int): Bitmap {
        val (l, a) = dimensoesPx(larguraMm, alturaMm)
        return renderizar("M", "7890000000000", "Etiqueta de teste $larguraMm×$alturaMm mm", l, a)
    }
}
