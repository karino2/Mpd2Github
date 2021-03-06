package io.github.karino2.mpd2github

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.github.kittinunf.fuel.core.Response
import com.github.kittinunf.fuel.core.ResponseDeserializable
import com.github.kittinunf.fuel.coroutines.awaitResponseResult
import com.github.kittinunf.fuel.coroutines.awaitStringResponseResult
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPut
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonWriter
import karino2.livejournal.com.mpd2issue.Cell
import karino2.livejournal.com.mpd2issue.Note
import kotlinx.coroutines.*
import java.io.StringWriter
import kotlin.coroutines.CoroutineContext

abstract class GithubPostBaseActivity : AppCompatActivity() , CoroutineScope {
    lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job


    companion object {
        fun getAppPreferences(ctx : Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        fun getAccessTokenFromPreferences(prefs: SharedPreferences): String {
            return prefs.getString("access_token", "")
        }

    }
    fun showMessage(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    val prefs : SharedPreferences by lazy { getAppPreferences(this) }

    val webView by lazy { findViewById(R.id.webview) as WebView }

    var ipynbUri : Uri? = null


    override fun onSaveInstanceState(outState: Bundle) {
        ipynbUri?.let {
            outState.putString("IPYNB_PATH", it.toString())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        savedInstanceState.getString("IPYNB_PATH")?.let {
            ipynbUri = Uri.parse(it)
        }
    }

    fun readNote(fileUri : Uri) : Note {
        val inputStream = contentResolver.openInputStream(fileUri)
        try {
            val note = Note.fromJson(inputStream)
            return note!!
        } finally {
            inputStream.close()
        }

    }

    val ipynbNote by lazy {
        ipynbUri?.let {
            readNote(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = Job()
        setContentView(R.layout.activity_login)

        intent?.let {
            when(it.action) {
                Intent.ACTION_SEND -> {
                    ipynbUri = intent.getParcelableExtra<Uri?>(Intent.EXTRA_STREAM)

                }
                else -> {
                    showMessage("Please use this app via SEND.")
                    return
                }
            }
        }

        with(webView.settings) {
            javaScriptEnabled = true
            blockNetworkImage = false
            loadsImagesAutomatically = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if(url?.startsWith(getString(R.string.redirect_uri)) == true) {
                    val code = Uri.parse(url).getQueryParameter("code")
                    code?.let {
                        getAccessToken(code)
                        return true
                    }
                }
                return super.shouldOverrideUrlLoading(view, url)
            }
        }

        checkValidTokenAndGotoTopIfValid()
    }

    val accessToken: String
        get() = getAccessTokenFromPreferences(prefs)

    val authorizeUrl: String
        get() =
            "https://github.com/login/oauth/authorize?client_id=${getString(R.string.client_id)}" +
                    "&scope=public_repo&redirect_uri=${getString(R.string.redirect_uri)}"


    abstract fun afterLogin()


    abstract val apiUrlForCheckTokenValidity : String

    fun getAccessToken(code: String) {
        val url =
                "https://github.com/login/oauth/access_token?client_id=${getString(R.string.client_id)}&client_secret=${getString(R.string.client_secret)}&code=$code"

        launch {
            val (request, response, result) = url.httpGet()
                    .header("Accept" to "application/json")
                    .awaitResponseResult(AuthenticationJson.Deserializer(), Dispatchers.IO)

            val (authjson, error) = result

            authjson?.let {
                prefs.edit()
                        .putString("access_token", it.accessToken)
                        .commit()
                afterLogin()
            }

        }
    }

    fun checkTokenValidity(accessToken: String){
        launch {
           val  (_, response, result) =
                   apiUrlForCheckTokenValidity.httpGet()
                           .header("Authorization" to "token ${accessToken}")
                           .awaitStringResponseResult(scope=Dispatchers.IO)
            if(response.statusCode == 200) {
                afterLogin()
            } else {
                webView.loadUrl(authorizeUrl)
            }
        }
    }

    // if this function return false, checkValidTokenAndGotoTopIfValid does nothing.
    // subclass must initiate this method after setup is done.
    open fun canStartCheckToken() = true

    fun checkValidTokenAndGotoTopIfValid() {
        if(!canStartCheckToken())
            return

        val accToken = accessToken
        if (accToken == "") {
            // not valid.
            webView.loadUrl(authorizeUrl)
            return
        }

        try {
            checkTokenValidity(accToken)
        }catch (e: IllegalArgumentException) {
            showMessage("Invalid ipynb. ${e.message}")
        }
    }

    fun readBase64(fileUri : Uri) : String {
        val inputStream = contentResolver.openInputStream(fileUri)
        try {
            return Base64.encodeToString(inputStream.readBytes(), Base64.DEFAULT)
        } finally {
            inputStream.close()
        }

    }

    data class Content(val content : String, val sha : String) {
        class Deserializer : ResponseDeserializable<Content> {
            override fun deserialize(content: String) = Gson().fromJson(content, Content::class.java)
        }
    }

    fun arrayListOfContentParameter(branchName: String, fname: String, base64Content: String) = arrayListOf(
            "path" to fname,
            "message" to "Put from Mpd2Github",
            "content" to base64Content,
            "branch" to branchName
    )

    fun jsonBuilder(builder: JsonWriter.()->Unit) : String {
        val sw = StringWriter()
        val jw = JsonWriter(sw)
        jw.builder()
        return sw.toString()
    }

    data class AuthenticationJson(@SerializedName("access_token") val accessToken : String,
                                  @SerializedName("token_type") val tokenType : String,
                                  val scope: String
    ) {
        class Deserializer : ResponseDeserializable<AuthenticationJson> {
            override fun deserialize(content: String) = Gson().fromJson(content, AuthenticationJson::class.java)
        }
    }

    fun putContentAndFinish(apiUrl: String, branchName: String, fname: String, base64Content: String) {
        launch {
            val resp = putContent(apiUrl, branchName, fname, base64Content)

            val msg = when (resp.statusCode) {
                200, 201 -> "Done"
                else -> "Fail to post."
            }
            showMessage(msg)
            finish()
        }
    }

    suspend fun putContent(apiUrl: String, branchName: String, fname: String, base64Content: String) : Response {
        val (request, response, result) = "$apiUrl?ref=$branchName".httpGet()
                .header("Authorization" to "token ${accessToken}")
                .awaitResponseResult(Content.Deserializer(), Dispatchers.IO)


        val contParam = arrayListOfContentParameter(branchName, fname, base64Content)

        result.fold(
                { cont -> contParam.add("sha" to cont.sha) },
                { _ /* err */ -> {} }
        )


        val json = jsonBuilder {
            val obj = beginObject()
            contParam.map { (k, v) -> obj.name(k).value(v) }
        }

        val (_, resp, _) = apiUrl.httpPut()
                .body(json)
                .header("Authorization" to "token ${accessToken}")
                .header("Content-Type" to "application/json")
                .awaitStringResponseResult(scope = Dispatchers.IO)
        return resp
    }

    fun Note.firstCellToYamlMap() : Map<String, String>? {
        this.cells?.let {
            if (this.cells.isEmpty())
                return null
            val first = this.cells[0]
            if (first.cellType != Cell.CellType.MARKDOWN)
                return null
            val content = first.source
            val yamlMap = content.lines().map {
                it.split(":", limit=2)
            }.filter { it.size == 2 }
                    .map { Pair(it[0].trim(' '), it[1].trim(' ')) }
                    .toMap()

            return yamlMap
        }
        return null
    }

}