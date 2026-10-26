package com.nxdump

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.nxdump.core.Dumper
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

/** Writes extracted files into a SAF tree, inside a fresh uniquely-named top folder. */
class SafSink(ctx: Context, private val tree: Uri, wantedName: String) : Dumper.Sink {
    private val resolver = ctx.contentResolver
    private val dirs = HashMap<String, Uri>()
    val topName: String

    init {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var name = wantedName
        var n = 2
        while (findChild(root, name) != null) name = "$wantedName ($n)".also { n++ }
        topName = name
        dirs[""] = create(root, DocumentsContract.Document.MIME_TYPE_DIR, name)
    }

    private fun findChild(parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }

    private fun create(parent: Uri, mime: String, name: String): Uri =
        DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("Cannot create \"$name\" in the output folder")

    private fun dir(path: String): Uri {
        dirs[path]?.let { return it }
        val parent = dir(path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        val u = findChild(parent, name) ?: create(parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
        dirs[path] = u
        return u
    }

    override fun open(relativePath: String): OutputStream {
        val d = dir(relativePath.substringBeforeLast('/', ""))
        val f = create(d, "application/octet-stream", relativePath.substringAfterLast('/'))
        val os = resolver.openOutputStream(f, "w") ?: throw IOException("Cannot write $relativePath")
        return BufferedOutputStream(os, 1 shl 20)
    }
}
