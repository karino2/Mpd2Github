package io.github.karino2.mpd2github

import android.app.Dialog
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonWriter
import io.reactivex.android.schedulers.AndroidSchedulers
import karino2.livejournal.com.mpd2issue.Note
import kotlinx.coroutines.*
import java.io.File
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class Mpd2BlogActivity : GithubPostBaseActivity() {

    companion object {
        const val URL_INPUT_DIALOG_ID = 1
        const val NOTIFICATION_ID = 2
    }


    private fun showPostIdNotification() {
        val builder = NotificationCompat.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Ipynb2Blog")
                .setContentText("PostId:$postId")


        val intentCopy = Intent(this, CopyIdReceiver::class.java)
        intentCopy.putExtra("postid", "PostId:$postId")
        val piCopy = PendingIntent.getBroadcast(this, 1,intentCopy,  PendingIntent.FLAG_UPDATE_CURRENT)
        builder.setContentIntent(piCopy)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, builder.build())

    }


    // 2017-09-10-123456
    val generatedId by lazy {
        val tsf = SimpleDateFormat("yyyy-MM-dd-HHmmss")
        AndroidSchedulers.mainThread().scheduleDirect {
            showPostIdNotification()
        }
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

    val postId : String
        get() {
            return ipynbNote?.postId() ?: generatedId
        }

    override val apiUrlForCheckTokenValidity : String
        get() {
            return "https://api.github.com/repos/${blogOwnerRepo!!}/contents/?ref=MeatPieDay"
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
                .beginArray()
                .value(
"""PostId: $postId
Title:$title""")
                .endArray()

        writer.endObject()
    }


    val chunkSize = 700*1024

    fun noteToBase64(note: Note): List<String> {
        val bytesArray = noteToContentJson(note).toByteArray()
        val chunkNum = 1+ (bytesArray.size/chunkSize)
        return (0 until chunkNum).map {idx ->
            val begin = idx*chunkSize
            val end = Math.min(bytesArray.size-1, (idx+1)*chunkSize)
            bytesArray.slice(begin..end)
        }.map {
            android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.DEFAULT)
        }
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
            val note = ipynbNote ?: throw IllegalArgumentException("No ipynb found.")

            launch {

                val base64Contents = withContext(Dispatchers.IO) { noteToBase64(note) }

                if(base64Contents.size == 1) {
                    val fname = "$postId.ipynb"
                    val  apiUrl = "https://api.github.com/repos/${blogOwnerRepo!!}/contents/ipynb/$fname"
                    putContentAndFinish(apiUrl, "MeatPieDay", fname, base64Contents[0])
                } else {
                    launch {
                        val  apiBaseUrl = "https://api.github.com/repos/${blogOwnerRepo!!}/contents/ipynb/split/$postId"
                        val results = base64Contents.withIndex().map { (idx, content) ->
                            val fname = "%04d.txt".format(idx)
                            val apiUrl = "$apiBaseUrl/$fname"
                            async {
                                // Too short request cause error.
                                delay(idx*5000L)
                                putContent(apiUrl, "MeatPieDay", fname, content)
                            }
                        }

                        val resps = results.awaitAll()
                        // resps.forEach { Log.d("Mpd2Blog", "resp statusCode = ${it.statusCode}") }

                        val msg = if(resps.all{it.statusCode in 200..201}) {
                            "Done(${base64Contents.size})"
                        }else {
                            "Fail to post(${base64Contents.size})"
                        }
                        showMessage(msg)
                        finish()
                    }

                }

            }

        }catch(e: IllegalArgumentException){
            showMessage("Invalid ipynb. ${e.message}")
        }
    }
}