package io.github.karino2.mpd2github

import android.net.Uri
import karino2.livejournal.com.mpd2issue.Note


class Mpd2EbookActivity : GithubPostBaseActivity() {

    fun Note.urlFnamePair() : Pair<String, String>{
        val yamlMap = this.firstCellToYamlMap() ?: throw IllegalArgumentException("No metadata cell at top")
        val url = yamlMap["GithubUrl"] ?:  throw IllegalArgumentException("No GithubUrl entry in first cell")
        val fname = yamlMap["FileName"] ?:throw IllegalArgumentException("No FileName entry in first cell")

        return Pair(url, fname)
    }

    override val apiUrlForCheckTokenValidity : String
    get() {
        val (repoUrl, _) = ipynbNote?.let { it.urlFnamePair() } ?: throw IllegalArgumentException("No ipynb found.")
        val (owner, repoName) = Uri.parse(repoUrl).let {it.pathSegments.let { Pair(it[it.size - 2], it.last()) }}

        return "https://api.github.com/repos/${owner}/${repoName}/contents/ref=MeatPieDay"
    }

    override fun afterLogin(){
        try {
            val (repoUrl, fname) = ipynbNote?.let { it.urlFnamePair() } ?: throw IllegalArgumentException("No ipynb found.")
            val (owner, repoName) = Uri.parse(repoUrl).let {it.pathSegments.let { Pair(it[it.size - 2], it.last()) }}

            val apiUrl = "https://api.github.com/repos/${owner}/${repoName}/contents/${fname}"

            val base64Content = readBase64(ipynbUri!!)

            putContentAndFinish(apiUrl, "MeatPieDay", fname, base64Content)

        }catch(e: IllegalArgumentException){
            showMessage("Invalid ipynb. ${e.message}")
        }
    }
}
