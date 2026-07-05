package com.privacycamera.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import java.io.FileOutputStream
import java.io.IOException

/**
 * Renders a single already-masked-and-watermarked [bitmap] as a one-page PDF, writing it
 * directly into the [ParcelFileDescriptor] the print framework provides in [onWrite] —
 * never to app-private storage (P4: no temp files). [bitmap] is recycled in [onFinish]
 * regardless of outcome, since this adapter owns it for exactly one print job.
 */
class SubmissionPrintAdapter(
    private val context: Context,
    private var bitmap: Bitmap?,
    private val jobName: String
) : PrintDocumentAdapter() {

    private var pdfDocument: PrintedPdfDocument? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        pdfDocument = PrintedPdfDocument(context, newAttributes)
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(1)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        val doc = pdfDocument
        val bmp = bitmap
        if (doc == null || bmp == null) {
            callback.onWriteFailed(null)
            return
        }
        val page = doc.startPage(0)
        val canvas = page.canvas
        val scale = minOf(
            canvas.width.toFloat() / bmp.width,
            canvas.height.toFloat() / bmp.height
        )
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (canvas.width - bmp.width * scale) / 2f,
                (canvas.height - bmp.height * scale) / 2f
            )
        }
        canvas.drawBitmap(bmp, matrix, null)
        doc.finishPage(page)
        try {
            FileOutputStream(destination.fileDescriptor).use { doc.writeTo(it) }
        } catch (e: IOException) {
            callback.onWriteFailed(e.message)
            return
        } finally {
            doc.close()
            pdfDocument = null
        }
        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
    }

    override fun onFinish() {
        bitmap?.recycle()
        bitmap = null
    }
}
