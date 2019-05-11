package com.nsfl.tweetgrader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import twitter4j.Paging
import twitter4j.Status
import twitter4j.TwitterFactory
import twitter4j.conf.ConfigurationBuilder
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

private const val TWEETS_HTML = "<!doctypehtml><link href=https://cdn.datatables.net/1.10.19/css/jquery.dataTables.min.css rel=stylesheet><link href=https://cdn.datatables.net/select/1.3.0/css/select.dataTables.min.css rel=stylesheet><link href=https://cdn.datatables.net/buttons/1.5.6/css/buttons.dataTables.min.css rel=stylesheet><script src=https://code.jquery.com/jquery-3.3.1.js></script><script src=https://cdn.datatables.net/1.10.19/js/jquery.dataTables.min.js></script><script src=https://cdn.datatables.net/select/1.3.0/js/dataTables.select.min.js></script><script src=https://cdn.datatables.net/buttons/1.5.6/js/dataTables.buttons.min.js></script><script class=init>var dataSet = [%s]; \$(document).ready(function() { var table = \$('#table').DataTable({ paging: false, order: [ [0, \"asc\"] ], data: dataSet, columns: [ { title: 'Username' }, { title: 'Account' }, { title: 'Tweet' }, { title: 'Date' }, { title: 'Content' }, { title: 'Likes' }, { title: 'Retweets' }, { title: '' }], columnDefs: [ { orderable: false, className: 'select-checkbox', targets: 7 } ], select: { style: 'multi', selector: 'td:last-child' }, dom: 'Bfrtip', buttons: [ 'selectAll', 'selectNone' , { text: 'Generate payouts', action: function () { var indexList = \"\"; table.rows({ selected: true }).every(function(rowIndex) { indexList += rowIndex + ','; }); window.location.assign('/payouts?queryIndex=%s&tweetIndexList=' + indexList); } } ] }) });</script><style>div{padding-left:5%%;padding-right:5%%;padding-top:.5%%;padding-bottom:.5%%}</style><div><table class=\"cell-border\"id=table width=100%%></table></div>"

@RestController
@SpringBootApplication
class TweetGraderApplication {

    private val inputDateFormat = SimpleDateFormat("yyyy-MM-dd")
    private val outputDateFormat = SimpleDateFormat("MM/dd/yyyy")
    private val queryList = ArrayList<Query>()
    private val twitter = TwitterFactory(
            ConfigurationBuilder()
                    .setOAuthConsumerKey("9Si0uS3oOhLVoR9MkUEsrIzde")
                    .setOAuthConsumerSecret("wr1VbvXOBrH4htanXga3HowSGzAuSjfvwZxjFLh2cDZYoNkTCx")
                    .setOAuthAccessToken("1103849475737440261-3sqnrU1JBNiCatCBt1B7yp0fpvfDtp")
                    .setOAuthAccessTokenSecret("M4spF7vq8hz9GeiRfONfpM3xvSqdO8FlkczCHXiYqgjZw")
                    .build()
    ).instance

    @RequestMapping("/")
    fun getIndex(): String {
        return "<form action=/tweets>Post URL<br><input name=postUrl><br><br>Start Date (Sunday)<br><input name=startDate type=date><br><br><input type=submit></form>"
    }

    @RequestMapping("/tweets")
    fun getTweets(@RequestParam startDate: String, @RequestParam postUrl: String): String {

        val documentList = ArrayList<Document>()

        val firstDocument = Jsoup.connect(postUrl).get()
        val firstDocumentBody = firstDocument.body().toString()
        documentList.add(firstDocument)

        val pageCount = try {
            val startIndex = firstDocumentBody.indexOf("Pages:</a>")
            val endIndex = firstDocumentBody.indexOf(")", startIndex)
            firstDocumentBody.substring(startIndex, endIndex)
                    .replace(Pattern.compile("[^0-9.]").toRegex(), "")
                    .toInt()
        } catch (exception: Exception) {
            1
        }

        for (i in 1..(pageCount - 1)) {
            documentList.add(
                    Jsoup.connect(
                            "$postUrl&st=${i * 14}"
                    ).get()
            )
        }

        val userMap = HashMap<String, String>()

        documentList.forEach {
            it.body().getElementsByClass("post-normal").forEach { element ->
                val username = element.getElementsByClass("normalname").text()
                val content = element.getElementsByClass("postcolor").toString()
                if (content.contains("twitter.com/")) {
                    var completed = false
                    var index = content.indexOf("twitter.com/") + 12
                    var twitter = ""
                    while (!completed) {
                        val char = content[index]
                        if (char != '"' && char != '<' && char != '/' && char != '?' && char != ' ') {
                            twitter += char
                        } else {
                            completed = true
                        }
                        index++
                        if (index == content.length) {
                            completed = true
                        }
                    }
                    userMap[username] = twitter
                }
            }
        }

        val start = Calendar.getInstance()
        start.timeInMillis = inputDateFormat.parse(startDate).time
        start.set(Calendar.HOUR_OF_DAY, 0)
        start.set(Calendar.MINUTE, 0)
        start.set(Calendar.SECOND, 0)

        val end = Calendar.getInstance()
        end.timeInMillis = inputDateFormat.parse(startDate).time
        end.set(Calendar.HOUR_OF_DAY, 23)
        end.set(Calendar.MINUTE, 59)
        end.set(Calendar.SECOND, 59)
        end.add(Calendar.DAY_OF_YEAR, 6)

        val tweetList = ArrayList<Tweet>()

        userMap.entries.forEach { entry ->

            val username = entry.key
            val twitterHandle = entry.value

            val statusList = try {
                twitter.timelines().getUserTimeline(twitterHandle, Paging(1, 200)).map { it }.filter {
                    it.createdAt.time > start.timeInMillis && it.createdAt.time < end.timeInMillis
                }
            } catch (exception: Exception) {
                arrayListOf<Status>()
            }

            if (statusList.isEmpty()) {
                tweetList.add(Tweet(username, twitterHandle, null))
            } else {
                tweetList.addAll(statusList.map { Tweet(username, twitterHandle, it) })
            }
        }

        queryList.add(Query(start.timeInMillis, end.timeInMillis, tweetList))

        return TWEETS_HTML.format(
                tweetList.joinToString(",") { tweet ->
                    if (tweet.status == null) {
                        "['${tweet.username.replace("'", "\\'")}', '', '', ''," +
                                "'<font color=\"red\"><b>Could not parse this user’s tweets</b></font>.', '0', '0', '']"
                    } else {
                        "['${tweet.username.replace("'", "\\'")}', " +
                                "'<a href=\"https://www.twitter.com/${tweet.twitterHandle}\">Link</a>', " +
                                "'<a href=\"https://www.twitter.com/${tweet.twitterHandle}/status/${tweet.status.id}\">Link</a>', " +
                                "'${outputDateFormat.format(tweet.status.createdAt.time)}', " +
                                "'${tweet.status.text.replace("'", "\\'").replace("\n", " ")}', " +
                                "'${tweet.status.favoriteCount}', " +
                                "'${tweet.status.retweetCount}', " +
                                "'']"
                    }
                },
                queryList.lastIndex
        )
    }

    @RequestMapping("/payouts")
    fun getPayouts(@RequestParam queryIndex: Int, @RequestParam tweetIndexList: String): String {

        val query = queryList[queryIndex]

        val userMap = HashMap<String, ArrayList<Status>>()
        tweetIndexList.split(",").forEach { index ->
            index.toIntOrNull()?.let {
                val tweet = query.tweetList[it]
                if (tweet.status != null) {
                    if (userMap.containsKey(tweet.username)) {
                        userMap[tweet.username]?.add(tweet.status)
                    } else {
                        userMap[tweet.username] = arrayListOf(tweet.status)
                    }
                }
            }
        }

        val payoutList = ArrayList<Payout>()
        userMap.entries.forEach { entry ->

            var payableTweetCount = 0
            if (entry.value.any { checkDayOfWeek(it.createdAt, Calendar.SUNDAY) }) payableTweetCount++
            if (entry.value.any { checkDayOfWeek(it.createdAt, Calendar.MONDAY) }) payableTweetCount++
            if (entry.value.any { checkDayOfWeek(it.createdAt, Calendar.TUESDAY) }) payableTweetCount++
            if (entry.value.any { checkDayOfWeek(it.createdAt, Calendar.WEDNESDAY) }) payableTweetCount++
            if (entry.value.any { checkDayOfWeek(it.createdAt, Calendar.THURSDAY) }) payableTweetCount++
            if (entry.value.any { checkDayOfWeek(it.createdAt, Calendar.FRIDAY) }) payableTweetCount++
            if (entry.value.any { checkDayOfWeek(it.createdAt, Calendar.SATURDAY) }) payableTweetCount++

            val totalRetweetLikeCount = entry.value.filter { !it.isRetweet }.sumBy { status ->
                status.retweetCount + status.favoriteCount
            }

            val retweetedAccounts = entry.value.filter { it.isRetweet }.joinToString { status ->
                status.retweetedStatus.user.screenName
            }

            val likedAccounts = try {
                val twitterHandle = entry.value.first().user.screenName
                twitter.favorites().getFavorites(twitterHandle, Paging(1, 200)).map { it }.filter {
                    it.createdAt.time > query.startTime && it.createdAt.time < query.endTime
                }.joinToString { it.user.screenName }
            } catch (exception: Exception) {
                ""
            }

            payoutList.add(
                    Payout(
                            entry.key,
                            payableTweetCount,
                            totalRetweetLikeCount,
                            retweetedAccounts,
                            likedAccounts
                    )
            )
        }

        val dateRange = "${outputDateFormat.format(query.startTime)} - ${outputDateFormat.format(query.endTime)}"

        val perTweetPayout = payoutList.joinToString("<br>") { payout ->
            payout.username + " " +
                    if (payout.payableTweetCount > 3) {
                        600000 + ((payout.payableTweetCount - 3) * 50000)
                    } else {
                        payout.payableTweetCount * 200000
                    }
        }

        val retweetLikeCountPayout = payoutList
                .sortedByDescending { it.totalRetweetLikeCount }
                .joinToString("<br>") { payout ->
                    payout.username + " " + payout.totalRetweetLikeCount
                }

        val retweetLikeEligibility = payoutList
                .sortedByDescending { it.totalRetweetLikeCount }
                .joinToString("<br>") { payout ->
                    payout.username +
                            " <b>Retweeted Accounts:</b> " + payout.retweetedAccounts +
                            " <b>Liked Accounts:</b> " + payout.likedAccounts
                }

        return "$dateRange<br><br>$perTweetPayout<br><br>$retweetLikeCountPayout<br><br>$retweetLikeEligibility"
    }

    private fun checkDayOfWeek(date: Date, dayOfWeek: Int): Boolean {
        return Calendar.getInstance()
                .also { it.time = date }
                .get(Calendar.DAY_OF_WEEK) == dayOfWeek
    }

    class Query(
            val startTime: Long,
            val endTime: Long,
            val tweetList: ArrayList<Tweet>
    )

    class Tweet(
            val username: String,
            val twitterHandle: String,
            val status: Status?
    )

    class Payout(
            val username: String,
            val payableTweetCount: Int,
            val totalRetweetLikeCount: Int,
            val retweetedAccounts: String,
            val likedAccounts: String
    )
}

fun main(args: Array<String>) {
    runApplication<TweetGraderApplication>(*args)
}