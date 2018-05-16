package karino2.livejournal.com.mpd2issue

/**
 * Created by _ on 2017/09/09.
 */


import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Date

/**
 * Created by _ on 2017/05/24.
 */


/*
{"cells":
[{"cell_type":"markdown","metadata":{},"source":["Hello. This is test."]}],
"metadata":{"kernelspec":{"display_name":"Python 2","language":"python","name":"python2"},
    "lanbuage_info":{"codemirror_mode":
                        {"name":"ipython","version":2},
                        "file_extension":".py","mimetype":"text/x-python",
                        "name":"python","nbconvert_exporter":"python","pygments_lexer":"ipython2","version":"2.7.11"
                    }
            },
"nbformat":4,"nbformat_minor":0}
 */

data class Note(val cells: List<Cell>? = null,
                val metadata: JsonElement? = null
) {

    companion object {

        fun fromJson(buf: String): Note? {
            val gson = gson
            return gson.fromJson(buf, Note::class.java)
        }

        val gson : Gson by lazy {
            GsonBuilder()
                    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                    .create()

        }

        @Throws(IOException::class)
        fun fromJson(inputStream: InputStream): Note? {
            // JsonReader reader = new JsonReader(new InputStreamReader(inputStream, "UTF-8"));
            val json = readAll(inputStream)
            return fromJson(json)
        }

        @Throws(IOException::class)
        private fun readAll(inputStream: InputStream): String {
            val br = BufferedReader(InputStreamReader(inputStream))
            val buf = StringBuffer()

            var line: String?
            line = br.readLine()
            while (line != null) {
                buf.append(line)
                line = br.readLine()
            }
            br.close()
            return buf.toString()
        }
    }
}
