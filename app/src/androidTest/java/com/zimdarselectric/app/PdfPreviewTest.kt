package com.zimdarselectric.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zimdarselectric.app.data.Catalog
import com.zimdarselectric.app.data.Customer
import com.zimdarselectric.app.data.JobType
import com.zimdarselectric.app.data.Material
import com.zimdarselectric.app.data.Quote
import com.zimdarselectric.app.data.QuoteLine
import com.zimdarselectric.app.data.QuotePdf
import com.zimdarselectric.app.data.Settings
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Makes sample PDFs and saves each page as a PNG so they can be looked at. */
@RunWith(AndroidJUnit4::class)
class PdfPreviewTest {
    @Test fun makePreviews() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), "preview").apply { deleteRecursively(); mkdirs() }
        val s = Settings(phone = "(315) 555-0100", email = "jeff@example.com", license = "", insurer = "")
        val cust = Customer(id = 1, name = "Pat Sample", phone = "(315) 555-0199", address = "12 Main St, Ilion, NY 13357")
        val picks = Catalog.items.filterIndexed { i, _ -> i % 13 == 0 }.take(38)
        val mats = picks.mapIndexed { i, c -> Material(id = i + 1L, name = c.name, spec = c.spec, unit = c.unit, location = if (i % 3 == 0) "Shop" else "Truck") }
        val lines = picks.mapIndexed { i, c -> QuoteLine(id = i + 1L, quoteId = 7, materialId = i + 1L, name = c.name, unit = c.unit, qty = (i % 5 + 1) * if (c.unit == "ft") 25.0 else 1.0, unitCost = 1.5 + i * 0.73) }

        // A fake finger signature
        val sigBmp = Bitmap.createBitmap(500, 140, Bitmap.Config.ARGB_8888)
        Canvas(sigBmp).drawPath(Path().apply { moveTo(10f, 100f); cubicTo(80f, 0f, 120f, 140f, 200f, 60f); cubicTo(260f, 10f, 300f, 130f, 380f, 50f); lineTo(490f, 80f) },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 5f; style = Paint.Style.STROKE })
        val sigFile = File(out, "sig.png").apply { outputStream().use { sigBmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }

        val q = Quote(id = 7, number = "Q-0007", title = "Kitchen circuits + basement lighting", customerId = 1, customerName = "Pat Sample",
            site = "12 Main St, Ilion, NY 13357", jobType = JobType.INSTALL, scheduled = "Thu Nov 5 – Fri Nov 6", laborHours = 9.5,
            notes = "Run two new 20A circuits from the panel to the kitchen counters and install GFCI protection.\nReplace basement lights with LED and add a switch at the stairs.")
        val signed = q.copy(signedAt = System.currentTimeMillis(), signerName = "Pat Sample", signerIp = "203.0.113.5", signaturePath = sigFile.path)
        val short = q.copy(number = "Q-0008", jobType = JobType.REPAIR)

        listOf(
            "customer" to QuotePdf.customerCopy(ctx, q, lines, cust, s),
            "signed" to QuotePdf.customerCopy(ctx, signed, lines, cust, s),
            "job" to QuotePdf.jobCopy(ctx, signed, lines, mats, cust, s),
            "short-repair" to QuotePdf.customerCopy(ctx, short, lines.take(3), cust, s),
            "st124" to com.zimdarselectric.app.data.St124Pdf.make(ctx, signed.copy(site = "110 State Street, Prospect, NY 13435"),
                cust.copy(address = "12 Main St, Ilion, NY 13357"), s.copy(ownerSignaturePath = sigFile.path, address = "6221 State Route 26, Rome, NY 13440"),
                "Pat Sample", sigFile, "owner", "not", System.currentTimeMillis()),
            "both-photos" to QuotePdf.customerCopy(ctx, signed.copy(number = "Q-0009", jobType = JobType.BOTH, repairHours = 2.0,
                repairNotes = "Replace the light and junction box at the top of the stairway."),
                lines.take(4).mapIndexed { i, l -> l.copy(repair = i % 2 == 1) }, cust, s,
                listOf(com.zimdarselectric.app.data.JobPhoto(quoteId = 7, path = sigFile.path, caption = "Before · panel", onCustomerCopy = true),
                    com.zimdarselectric.app.data.JobPhoto(quoteId = 7, path = sigFile.path, caption = "After", onCustomerCopy = true))),
        ).forEach { (name, pdf) ->
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    for (i in 0 until r.pageCount) r.openPage(i).use { p ->
                        val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        File(out, "$name-p${i + 1}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        }
    }
}
