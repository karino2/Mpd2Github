package io.github.karino2.mpd2github

import android.app.Dialog
import android.net.Uri
import android.support.v7.app.AlertDialog
import android.widget.EditText
import com.google.gson.Gson
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonWriter
import io.reactivex.android.schedulers.AndroidSchedulers
import karino2.livejournal.com.mpd2issue.Cell
import karino2.livejournal.com.mpd2issue.Note
import java.io.File
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class Mpd2BlogActivity : GithubPostBaseActivity() {

    companion object {
        const val URL_INPUT_DIALOG_ID = 1
    }

    // 2017-09-10-17.md

    // 2017-09-10-123456
    val generatedId by lazy {
        val tsf = SimpleDateFormat("yyyy-MM-dd-HHmmss")
        tsf.format(Date())
    }


    val blogOwnerRepo : String?
    get() = prefs.getString("blog_owner_repo", null)

    fun saveOwnerRepo(repo : String) = prefs.edit()
            .putString("blog_owner_repo", repo)
            .commit()


    override fun canStartCheckToken(): Boolean {
        blogOwnerRepo?.let { return true }

        showDialog(URL_INPUT_DIALOG_ID)
        return false
    }

    override fun onCreateDialog(id: Int): Dialog {
        when(id) {
            URL_INPUT_DIALOG_ID-> {
                val builder = AlertDialog.Builder(this)
                val et = EditText(this)
                builder.setTitle("Enter blog owner/reponame")
                builder.setMessage("Ex: karino2/karino2.github.io")
                builder.setView(et)

                builder.setPositiveButton("Save") { di, bid ->
                    saveOwnerRepo(et.text.toString())
                    AndroidSchedulers.mainThread().scheduleDirect {
                        // notification here.
                        checkValidTokenAndGotoTopIfValid()
                    }
                }
                builder.setNegativeButton("Cancel") { di, bid->
                    finish()
                }
                return builder.show()
            }
        }
        return super.onCreateDialog(id)
    }

    fun Note.postId() : String? {
        return firstCellToYamlMap()?.let {
            it["PostId"]
        }

    }

    var idGenerated = false

    val postId : String
        get() {
            return ipynbNote?.postId() ?: generatedId
        }

    override val apiUrlForCheckTokenValidity : String
        get() {
            return "https://api.github.com/repos/${blogOwnerRepo!!}/contents/ref=MeatPieDay"
        }

    fun Uri.toName() = File(this.path).name
    fun String?.baseName() : String? {
        if(this == null)
            return null
        val pos = this.lastIndexOf(".")
        return if(pos == -1) null else this.substring(0, pos)
    }


    /*
        {"cell_type":"markdown",
"metadata": {},
"source":"PostId:2018-08-18-123456\nTitle:This is TITLE!"]
}
     */
    fun writeFirstCell(writer : JsonWriter) {
        val title = ipynbUri?.toName().baseName()!!

        writer.beginObject()

        writer.name("cell_type").value("markdown")
        writer.name("metadata")
                .beginObject().endObject()
        writer.name("source")
        writer.value(
"""PostId: $postId
Title:$title""")

        writer.endObject()
    }



    fun noteToBase64(note: Note): String {
        return android.util.Base64.encodeToString(noteToContentJson(note).toByteArray(), android.util.Base64.DEFAULT)
    }

    fun noteToContentJson(note: Note) : String {
        val sw = StringWriter()
        val writer = JsonWriter(sw)
        val gson = Note.gson

        writer.beginObject()
        writer.name("cells")
        writer.beginArray()

        writeFirstCell(writer)

        val cells = note.postId()?.let { note.cells!!.drop(1) } ?: note.cells!!
        cells.forEach {
            it.toJson(gson , writer)
        }

        writer.endArray()

        writer.name("metadata") // begin metaata:
        Streams.write(note.metadata, writer)


        writer.name("nbformat").value(4)
        writer.name("nbformat_minor").value(0)
        writer.endObject()
        writer.close()

        return sw.toString()
    }


    override fun afterLogin() {
        try {
            val fname = "$postId.ipynb"

            val  apiUrl = "https://api.github.com/repos/${blogOwnerRepo!!}/contents/ipynb/$fname"
            val base64Content = ipynbNote?.let{ noteToBase64(it) } ?: throw IllegalArgumentException("No ipynb found.")

            putContent(apiUrl, "MeatPieDay", fname, base64Content)
        }catch(e: IllegalArgumentException){
            showMessage("Invalid ipynb. ${e.message}")
        }
    }
}