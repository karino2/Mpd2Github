package io.github.karino2.mpd2github

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.github.kittinunf.fuel.core.ResponseDeserializable
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPut
import com.github.kittinunf.fuel.rx.rx_response
import com.github.kittinunf.fuel.rx.rx_responseObject
import com.github.kittinunf.result.Result
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonWriter
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import karino2.livejournal.com.mpd2issue.Cell
import karino2.livejournal.com.mpd2issue.Note
import java.io.StringWriter

class LoginActivity : AppCompatActivity() {
    companion object {
        fun getAppPreferences(ctx : Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        fun getAccessTokenFromPreferences(prefs: SharedPreferences): String {
            return prefs.getString("access_token", "")
        }

    }

    val prefs : SharedPreferences by lazy { getAppPreferences(this) }

    fun showMessage(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

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

    fun Note.urlFnamePair() : Pair<String, String>{
        this.cells?.let {
            if (this.cells.isEmpty())
                throw IllegalArgumentException("No cells.")
            val first = this.cells[0]
            if (first.cellType != Cell.CellType.MARKDOWN)
                throw IllegalArgumentException("First cell is not markdown")
            val content = first.source
            val yamlMap = content.lines().map {
                it.split(":", limit=2)
            }.filter { it.size == 2 }
                    .map { Pair(it[0].trim(' '), it[1].trim(' ')) }
                    .toMap()

            val url = yamlMap["GithubUrl"] ?:  throw IllegalArgumentException("No GithubUrl entry in first cell")
            val fname = yamlMap["FileName"] ?:throw IllegalArgumentException("No FileName entry in first cell")

            return Pair(url, fname)
        }
        throw IllegalArgumentException("No cells.")


    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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



    fun checkTokenValidity(accessToken: String){
        val (repoUrl, _) = ipynbNote?.let { it.urlFnamePair() } ?: throw IllegalArgumentException("No ipynb found.")
        val (owner, repoName) = Uri.parse(repoUrl).let {it.pathSegments.let { Pair(it[it.size - 2], it.last()) }}

        val apiUrl = "https://api.github.com/repos/${owner}/${repoName}/contents/ref=MeatPieDay"

        apiUrl.httpGet()
                .header("Authorization" to "token ${accessToken}")
                .rx_response()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { (response, result) ->
                    if(response.statusCode == 200) {
                        afterLogin()
                    } else {
                        webView.loadUrl(authorizeUrl)
                    }

                }


    }

    fun checkValidTokenAndGotoTopIfValid() {
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

    fun arrayListOfContentParameter(fname: String) = arrayListOf(
            "path" to fname,
            "message" to "Put from Mpd2Github",
            "content" to readBase64(ipynbUri!!),
            "branch" to "MeatPieDay"
        )

    fun jsonBuilder(builder: JsonWriter.()->Unit) : String {
        val sw = StringWriter()
        val jw = JsonWriter(sw)
        jw.builder()
        return sw.toString()
    }


        fun afterLogin(){
        try {
            val (repoUrl, fname) = ipynbNote?.let { it.urlFnamePair() } ?: throw IllegalArgumentException("No ipynb found.")
            val (owner, repoName) = Uri.parse(repoUrl).let {it.pathSegments.let { Pair(it[it.size - 2], it.last()) }}

            val apiGetUrl = "https://api.github.com/repos/${owner}/${repoName}/contents/${fname}?ref=MeatPieDay"
            val apiUrl = "https://api.github.com/repos/${owner}/${repoName}/contents/${fname}"
            // val apiUrlPath = "https://api.github.com/repos/${owner}/${repoName}/contents/"

            apiGetUrl.httpGet()
                    .header("Authorization" to "token ${accessToken}")
                    .rx_responseObject(Content.Deserializer())
                    .subscribeOn(Schedulers.io())
                    .subscribe { (response, result) ->
                        val contParam = arrayListOfContentParameter(fname)

                        when(result) {
                            is Result.Success -> {
                                contParam.add("sha" to result.get().sha)
                            }
                        }

                        val json = jsonBuilder {
                            val obj = beginObject()
                            contParam.map { (k, v)-> obj.name(k).value(v) }
                        }

                        apiUrl.httpPut()
                                .body(json)
                                .header("Authorization" to "token ${accessToken}")
                                .header("Content-Type" to  "application/json")
                                .response { _, resp, res ->



                            AndroidSchedulers.mainThread().scheduleDirect{
                                val msg = when(resp.statusCode) {
                                    200, 201 -> "Done"
                                    else -> "Fail to post."
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


    data class AuthenticationJson(@SerializedName("access_token") val accessToken : String,
                                  @SerializedName("token_type") val tokenType : String,
                                  val scope: String
    ) {
        class Deserializer : ResponseDeserializable<AuthenticationJson> {
            override fun deserialize(content: String) = Gson().fromJson(content, AuthenticationJson::class.java)
        }
    }




    fun getAccessToken(code: String) {
        val url =
                "https://github.com/login/oauth/access_token?client_id=${getString(R.string.client_id)}&client_secret=${getString(R.string.client_secret)}&code=$code"

        url.httpGet()
                .header("Accept" to "application/json")
                .rx_responseObject(AuthenticationJson.Deserializer())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {(response, result) ->
                    val (authjson, error) = result

                    authjson?.let {
                        prefs.edit()
                                .putString("access_token", it.accessToken)
                                .commit()
                        afterLogin()
                    }
                }
    }



}
