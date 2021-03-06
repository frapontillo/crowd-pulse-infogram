/*
 * Copyright 2015 Francesco Pontillo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.frapontillo.pulse.crowd.infogram

import com.github.frapontillo.pulse.crowd.data.entity.Message
import com.github.frapontillo.pulse.crowd.infogram.rest.*
import com.github.frapontillo.pulse.spi.IPlugin
import com.github.frapontillo.pulse.util.ConfigUtil
import com.github.frapontillo.pulse.util.PulseLogger
import com.google.gson.GsonBuilder
import net.infogram.api.InfogramAPI
import rx.Observable
import rx.Subscriber
import rx.observers.SafeSubscriber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.text.Regex

/**
 * A plugin for generating Infogr.am graphs about the results of the pipeline processing.
 *
 * @author Francesco Pontillo
 */
public class InfogramPlugin : IPlugin<Message, Message, InfogramConfig>() {

    val PLUGIN_NAME = "infogram"
    var props: Properties? = null
    val logger = PulseLogger.getLogger(InfogramPlugin::class.java)
    var gson = GsonBuilder().create()

    override fun getName(): String? {
        return PLUGIN_NAME
    }

    override fun getNewParameter(): InfogramConfig? {
        return InfogramConfig()
    }

    override fun getOperator(parameters: InfogramConfig?): Observable.Operator<Message, Message>? {
        return InfoGraphOperator(parameters)
    }

    /**
     * Operator implementation that counts, for each element going through it, tags, categories
     * and lemmas, eventually discarding the ones that are marked as stop words.
     * When completed, it generates infograms and saves them on disk.
     */
    private inner class InfoGraphOperator : Observable.Operator<Message, Message> {
        var infogramApi: InfogramAPI
        var parameters: InfogramConfig?

        constructor(parameters: InfogramConfig?) {
            this.parameters = parameters
            if (props == null) {
                props = ConfigUtil.getPropertyFile(InfogramPlugin::class.java, "infogram.properties")
            }
            val API_KEY: String = props!!.getProperty("infogram.apikey")
            val API_SECRET: String = props!!.getProperty("infogram.secret")
            infogramApi = InfogramAPI(API_KEY, API_SECRET)
        }

        override fun call(t: Subscriber<in Message>?): Subscriber<in Message>? {
            return object : SafeSubscriber<Message>(t) {
                var tagMap: MutableMap<String, Double> = HashMap()
                var categoryMap: MutableMap<String, Double> = HashMap()
                var lemmaMap: MutableMap<String, Double> = HashMap()
                var tagCount: Long = 0
                var categoryCount: Long = 0
                var lemmaCount: Long = 0

                override fun onNext(message: Message) {
                    // make calculations for tags and categories
                    if (message.tags != null) {
                        var categories: MutableList<String> = ArrayList()
                        for (tag in message.tags) {
                            // exclude stop word tags
                            if (tag.isStopWord) {
                                continue
                            }
                            tagCount += 1
                            var key = tag.text
                            val value: Double = tagMap[key] ?: 0.0
                            tagMap.put(key, value + 1)
                            if (tag.categories != null) {
                                // exclude stop word categories
                                categories.addAll(tag.categories
                                        .filter { !it.isStopWord }
                                        .map { it.text })
                            }
                        }
                        categoryCount += categories.size
                        for (cat in categories) {
                            val value: Double = categoryMap[cat] ?: 0.0
                            categoryMap.put(cat, value + 1)
                        }
                    }

                    // make calculations for lemmas
                    if (message.tokens != null) {
                        for (tok in message.tokens) {
                            // exclude stop word tokens
                            if (tok.isStopWord || tok.lemma == null) {
                                continue
                            }
                            lemmaCount += 1
                            val value: Double = lemmaMap[tok.lemma] ?: 0.0
                            lemmaMap.put(tok.lemma, value + 1)
                        }
                    }
                    t?.onNext(message)
                }

                override fun onError(e: Throwable) {
                    e.printStackTrace()
                    t?.onError(e)
                }

                override fun onCompleted() {
                    // generate infogram
                    val tagChart = buildChart(arrayOf(tagMap), arrayOf("Tags"))
                    val categoryChart = buildChart(arrayOf(categoryMap), arrayOf("Categories"))
                    val lemmaChart = buildChart(arrayOf(lemmaMap), arrayOf("Lemmas"))

                    // save the charts
                    val tagRes = post(
                            infogramApi, arrayOf(tagChart), "Crowd Pulse Tags", true, "public")
                    val categoryRes = post(
                            infogramApi, arrayOf(categoryChart), "Crowd Pulse Categories", true, "public")
                    val lemmaRes = post(
                            infogramApi, arrayOf(lemmaChart), "Crowd Pulse Lemmas", true, "public")

                    // get the resulting images
                    val tagImage = getPNG(infogramApi, tagRes?.id)
                    val categoryImage = getPNG(infogramApi, categoryRes?.id)
                    val lemmaImage = getPNG(infogramApi, lemmaRes?.id)

                    // save the images on the file system
                    writePNGs(parameters?.path, Pair(tagImage, "tags"),
                            Pair(categoryImage, "categories"), Pair(lemmaImage, "lemmas"))

                    t?.onCompleted()
                }

                /**
                 * Build an {@link InfogramChart} of type "word cloud" according to the raw values
                 * contained in the maps object.
                 *
                 * @param maps          An {@link Array} (each for every word cloud to generate) of
                 *                      {@link MutableMap}, where the {@link String} key is the
                 *                      word and the {@link Double} value the number of occurrences.
                 * @param sheetsNames   The names to give to each word cloud.
                 *
                 * @return An {@link InfogramChart} object ready to be sent to the Infogr.am API.
                 */
                fun buildChart(maps: Array<MutableMap<String, Double>>, sheetsNames: Array<String>):
                        InfogramChart {
                    var chart = InfogramChart("chart", "wordcloud")
                    val QTY = "#"
                    val MAX_SHEET_SIZE = 100;

                    var sheets: Array<InfogramChartSheet?> = arrayOfNulls(maps.size)

                    // for each word cloud to generate
                    for (i in 0..(maps.size - 1)) {
                        // init the sheet
                        sheets[i] = InfogramChartSheet()
                        if (maps.size > 1) {
                            sheets[i]!!.header = arrayOf(sheetsNames[i], QTY)
                        }
                        var sheetList: MutableList<InfogramChartSheetRow> = arrayListOf()
                        val map = maps[i]
                        // for each word, add it as a row of the chart
                        for ((key, value) in map) {
                            val row = arrayOf(key, value)
                            sheetList.add(InfogramChartSheetRow(row))
                        }
                        // order the list of words and select only the highest ones
                        // (so that the cloud isn't too big)
                        sheetList.sortByDescending { it.data!![1] as Double }
                        var orderedSheetList = sheetList.take(MAX_SHEET_SIZE)
                        sheets[i]?.rows = orderedSheetList.toTypedArray()
                    }

                    chart.data = InfogramChartData(sheets)
                    return chart;
                }
            }
        }
    }

    /**
     * Create and infograph on infogr.am by providing all of the needed values.
     *
     * @param infogram      The {@link InfogramAPI} service to use.
     * @param content       An array of {@link InfogramChart}s to display.
     * @param title         The title of the infograph.
     * @param publish       {@code true} if the infograph must be public, {@code false} otherwise.
     * @param publishMode   The publish mode, as specified by the Infogr.am API.
     * @param copyright     The copyright to set into the image.
     * @param width         The width of the image.
     * @param themeId       The ID of the Infogr.am theme to use (defaults to 45).
     * @param password      An optional password for the infograph.
     *
     * @return An {@link InfogramResponse} containing the outcome of the request.
     */
    fun post(infogram: InfogramAPI, content: Array<InfogramChart>, title: String? = null,
             publish: Boolean? = null, publishMode: String? = null, copyright: String? = null,
             width: Double? = null, themeId: Int? = 45, password: String? = null)
            : InfogramResponse? {

        var chartContent: String = GsonBuilder().create().toJson(content)
        val parameters: MutableMap<String, String?> = HashMap()
        parameters.set("content", chartContent)
        if (themeId != null) {
            parameters.set("theme_id", themeId.toString())
        }
        if (!title.isNullOrEmpty()) {
            parameters.set("title", title)
        }
        if (publish != null) {
            parameters.set("publish", publish.toString())
        }
        if (!publishMode.isNullOrEmpty()) {
            parameters.set("publish_mode", publishMode)
        }
        if (!password.isNullOrEmpty()) {
            parameters.set("password", password)
        }
        if (width != null) {
            parameters.set("width", width.toString())
        }
        if (!copyright.isNullOrEmpty()) {
            parameters.set("copyright", copyright)
        }

        val res = infogram.sendRequest("POST", "infographics", parameters)
        val id = res.headers["X-Infogram-Id"]?.firstOrNull()
        if (res.httpStatusCode == 201) {
            logger.info("Created infogram at $id.")
            var reader = BufferedReader(InputStreamReader(res.responseBody))
            return gson.fromJson(reader, InfogramResponse::class.java);
        }
        logger.error("Couldn't create infogram at $id.\n" +
                "Error ${res.httpStatusCode}:\n" +
                "${res.httpStatusMessage}")
        return null
    }

    /**
     * Retrieve an infograph as an array of bytes (PNG) by its ID on Infogr.am.
     *
     * @param infogram  The {@link InfogramAPI} service.
     * @param id        The ID of the infograph to retrieve.
     *
     * @return An array of bytes representing the infograph as a PNG.
     */
    fun getPNG(infogram: InfogramAPI, id: String?): ByteArray? {
        val res = infogram.sendRequest("GET", "infographics/$id", mapOf(Pair("format", "png")))
        if (res.httpStatusCode == 200) {
            logger.info("Fetched infogram PNG at $id.")
            return res.responseBody.readBytes()
        }
        logger.error("Couldn't get infogram PNG at: $id.\n" +
                "Error: ${res.httpStatusCode}:\n" +
                "${res.httpStatusMessage}")
        return null;
    }

    /**
     * Write some elements into a path using the given filenames in the files object.
     *
     * @param path  The path to save files to.
     * @param files A {@link Pair} of {@link ByteArray} (the PNG bytes) and {@link String}
     *              (the file name).
     *
     * @return An {@link Array} of {@link String}s containing the saved file paths.
     */
    fun writePNGs(path: String?, vararg files: Pair<ByteArray?, String>): Array<String?> {
        // get a valid directory and replace ~ with the user dir
        val directory = (path ?: System.getProperty("java.io.tmpdir"))
                .replaceFirst(Regex("^~"), System.getProperty("user.home"));
        var resolved = Paths.get(directory, "crowd-pulse-infogram");
        Files.createDirectories(resolved);

        var date = ZonedDateTime.now()
                .truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_INSTANT)
                .replace(Regex(":"), "-")

        // for every file, save it if it's not empty
        val pngs: Array<String?> = arrayOfNulls(files.size)
        for (f in files.indices) {
            var file = files[f]
            if (file.first == null) {
                continue
            }
            var filePath = resolved.resolve("$date-${file.second}.png")
            try {
                Files.write(filePath, file.first, StandardOpenOption.CREATE_NEW)
                logger.info("Infogram written at path: $filePath.")
                pngs[f] = filePath.toString()
            } catch (e: IOException) {
                logger.error("Couldn't write infogram at path: $filePath.")
            }
        }
        return pngs
    }

}