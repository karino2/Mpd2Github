package karino2.livejournal.com.mpd2issue

/**
 * Created by _ on 2017/09/09.
 */

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter

import java.io.IOException

/**
 * Created by _ on 2017/05/24.
 */

data class Cell(
        @SerializedName("cell_type")
        val _cellType: String,
        @SerializedName("source")
        val _source: JsonElement,
        val executionCount: Int? = null,
        val metadata: JsonElement? = null,
        // always 1 element.
        val outputs: List<Output>? = null) {

    val source: String
            get() = jsonElementToString(_source)


    data class Output(val name: String = "",
                      // (name, _text) or _data
                      val outputType: String? = null,
                      @SerializedName("text")
                      val _text: JsonElement? = null,
                      @SerializedName("data")
                      val _data: Map<String, JsonElement>? = null,

                      val executionCount: Int? = null
    ) {


        val isImage: Boolean
            get() {
                if (_data == null)
                    return false
                for (key in _data!!.keys) {
                    if (key.startsWith("image/png") || key.startsWith("image/jpeg")) {
                        return true
                    }
                }
                return false
            }

        /*
        fun setData(newData: JsonObject) {
            val dataType = object : TypeToken<Map<String, JsonElement>>() {

            }.type
            _data = s_gson.fromJson<Map<String, JsonElement>>(newData, dataType)
        }
        */

        data class Base64Image(val key: String, val content: String)

        val base64Image : Base64Image?
            get() {
                for (key in _data!!.keys) {
                    if (key.startsWith("image/png") || key.startsWith("image/jpeg")) {
                        return Base64Image(key, jsonElementToString(_data!![key]))
                    }
                }
                return null

            }

        val imageAsBase64: String?
            get() = base64Image?.content

        val text : String
            get() = if (_data == null) jsonElementToString(_text) else jsonElementToString(_data!!["_text/plain"])

        fun appendResult(newcontents: String) {
            // in this case, output is cleared at first and _text must be JsonArray.
            val array = _text as JsonArray?
            array!!.add(newcontents)
        }

    }

    /*
    fun clearOutput() {
        outputs!!.clear()
        val newoutput = Output()
        newoutput.outputType = "stream"
        newoutput.name = "stdout"
        newoutput._text = JsonArray()
        outputs!!.add(newoutput)
    }
    */

    internal val output: Output?
        get() = if (outputs!!.isEmpty()) null else outputs!![0]

    enum class CellType {
        UNINITIALIZE,
        CODE,
        MARKDOWN
    }

    val cellType: CellType
        get() {
            return if ("code" == _cellType) {
                CellType.CODE
            } else if ("markdown" == _cellType) {
                CellType.MARKDOWN
            } else {
                CellType.UNINITIALIZE
            }
        }

    @Throws(IOException::class)
    fun toJson(gson: Gson, writer: JsonWriter) {
        if (CellType.MARKDOWN == cellType) {
            toJsonMarkdownCell(gson, writer)
        } else {
            toJsonCodeCell(gson, writer)
        }
    }

    internal val execCountForSave: Int?
        get() {
            if (executionCount == null)
                return null
            return if (executionCount === EXEC_COUNT_RUNNING) null else executionCount
        }

    @Throws(IOException::class)
    private fun toJsonCodeCell(gson: Gson, writer: JsonWriter) {
        writer.beginObject()
        writer.name("cell_type").value("code")
                .name("execution_count").value(execCountForSave)


        writeMetadata(gson, writer)

        writer.name("source")
                .value(source)

        writer.name("outputs")
                .beginArray()
                .jsonValue(gson.toJson(output))
                .endArray()

        writer.endObject()

    }

    @Throws(IOException::class)
    private fun toJsonMarkdownCell(gson: Gson, writer: JsonWriter) {
        //         {"metadata":{"collapsed":true},"cell_type":"markdown","source":"## Markdown cell\n\nHere is the test of markdown.\nNext line."},
        writer.beginObject()
                .name("cell_type").value("markdown")

        writeMetadata(gson, writer)

        writer.name("source")
                .beginArray().value(source).endArray()


        writer.endObject()

    }

    @Throws(IOException::class)
    private fun writeMetadata(gson: Gson, writer: JsonWriter) {
        writer.name("metadata")

        if (metadata == null) {
            writer.beginObject().endObject()
        } else {
            writer.jsonValue(gson.toJson(metadata))
        }
    }

    companion object {
        val EXEC_COUNT_RUNNING = -1

        internal var s_gson = Gson()


        internal fun jsonElementToString(obj: JsonElement?): String {
            if (obj == null)
                return ""
            if (obj.isJsonArray) {
                val sources = s_gson.fromJson(obj, List::class.java)
                return mergeAll(sources as List<String>)
            }
            return obj.asString

        }


        internal fun mergeAll(texts: List<String>): String {
            val buf = StringBuilder()
            for (source in texts) {
                buf.append(source)
                // should I handle return code here?
            }
            return buf.toString()
        }
    }


}
