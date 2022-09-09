import com.exalate.api.domain.webhook.WebhookEntityType
import com.exalate.basic.domain.hubobject.v1.BasicHubIssue
import com.exalate.basic.domain.hubobject.v1.BasicHubUser
import com.exalate.basic.domain.hubobject.v1.BasicHubWorkLog
import com.exalate.replication.services.replication.impersonation.AuditLogService
import play.libs.Json
import services.jcloud.hubobjects.NodeHelper
import services.replication.PreparedHttpClient

import java.text.SimpleDateFormat

/**
Usage:
Add the snippet below to the end of your "Outgoing sync(data filter)":

TempoWorkLogSync.send(
    "OM72xP3i1IxEgUT4YR1dmAHNRXcOEw"  // replace OM72xP3i1IxEgUT4YR1dmAHNRXcOEw with the previously generated access token
)
--------------------------------
Add the snippet below to the end of your "Incoming sync for new issues(create processor)":


TempoWorkLogSync.receive(
    "OM72xP3i1IxEgUT4YR1dmAHNRXcOEw" // replace OM72xP3i1IxEgUT4YR1dmAHNRXcOEw with the previously generated access token
)

--------------------------------
Add the snippet below to the end of your "Incoming sync for existing issues(change processor)":

TempoWorkLogSync.receive(
  "OM72xP3i1IxEgUT4YR1dmAHNRXcOEw" // replace OM72xP3i1IxEgUT4YR1dmAHNRXcOEw with the previously generated access token
)
--------------------------------

 * */
class TempoWorkLogSync {
    static <T> T await (scala.concurrent.Future<T> f){ scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
    private static <T> T orNull(scala.Option<T> opt) { opt.isDefined() ? opt.get() : null }
    private static <T> scala.Option<T> none() { scala.Option$.MODULE$.<T>empty() }
    @SuppressWarnings("GroovyUnusedDeclaration")
    private static <T> scala.Option<T> none(Class<T> evidence) { scala.Option$.MODULE$.<T>empty() }
    private static <L, R> scala.Tuple2<L, R> pair(L l, R r) { scala.Tuple2$.MODULE$.<L, R>apply(l, r) }
    private static <T> scala.collection.Seq<T> seq(T ... ts) {
        def list = Arrays.asList(ts)
        def scalaBuffer = scala.collection.JavaConversions.asScalaBuffer(list)
        scalaBuffer.toSeq()
    }
    //replica = issue


    static send(String token, BasicHubIssue replica, BasicHubIssue issue, PreparedHttpClient httpClient, services.jcloud.hubobjects.NodeHelper nodeHelper) {
        def http = { String method, String path, Map<String, List<String>> queryParams, String body, Map<String, List<String>> headers ->

            def getGeneralSettings = {
                def gsp = InjectorGetter.getInjector().instanceOf(com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsPersistence.class)
                def gsOpt = await(gsp.get())
                def gs = orNull(gsOpt)
                gs
            }
            final def gs = getGeneralSettings()

            def removeTailingSlash = { String str -> str.trim().replace("/+\$", "") }
//            final def jiraCloudUrl = removeTailingSlash(gs.issueTrackerUrl)
            final def tempoRestApiUrl = "https://api.tempo.io/core/3"

            def parseQueryString = { String string ->
                string.split('&').collectEntries{ param ->
                    param.split('=', 2).collect{ URLDecoder.decode(it, 'UTF-8') }
                }
            }

            //Usage examples: https://gist.github.com/treyturner/4c0f609677cbab7cef9f
            def parseUri
            parseUri = { String uri ->
                def parsedUri
                try {
                    parsedUri = new URI(uri)
                    if (parsedUri.scheme == 'mailto') {
                        def schemeSpecificPartList = parsedUri.schemeSpecificPart.split('\\?', 2)
                        def tempMailMap = parseQueryString(schemeSpecificPartList[1])
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.mailMap = [
                                recipient: schemeSpecificPartList[0],
                                cc       : tempMailMap.find { //noinspection GrUnresolvedAccess
                                    it.key.toLowerCase() == 'cc' }.value,
                                bcc      : tempMailMap.find { //noinspection GrUnresolvedAccess
                                    it.key.toLowerCase() == 'bcc' }.value,
                                subject  : tempMailMap.find { //noinspection GrUnresolvedAccess
                                    it.key.toLowerCase() == 'subject' }.value,
                                body     : tempMailMap.find { //noinspection GrUnresolvedAccess
                                    it.key.toLowerCase() == 'body' }.value
                        ]
                    }
                    if (parsedUri.fragment?.contains('?')) { // handle both fragment and query string
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.rawQuery = parsedUri.rawFragment.split('\\?')[1]
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.query = parsedUri.fragment.split('\\?')[1]
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.rawFragment = parsedUri.rawFragment.split('\\?')[0]
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.fragment = parsedUri.fragment.split('\\?')[0]
                    }
                    if (parsedUri.rawQuery) {
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.queryMap = parseQueryString(parsedUri.rawQuery)
                    } else {
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.queryMap = null
                    }

                    //noinspection GrUnresolvedAccess
                    if (parsedUri.queryMap) {
                        //noinspection GrUnresolvedAccess
                        parsedUri.queryMap.keySet().each { key ->
                            def value = parsedUri.queryMap[key]
                            //noinspection GrUnresolvedAccess
                            if (value.startsWith('http') || value.startsWith('/')) {
                                parsedUri.queryMap[key] = parseUri(value)
                            }
                        }
                    }
                } catch (e) {
                    throw new com.exalate.api.exception.IssueTrackerException("Parsing of URI failed: $uri\n$e", e)
                }
                parsedUri
            }

            def unsanitizedUrl = tempoRestApiUrl + path
            def parsedUri = parseUri(unsanitizedUrl)

            //noinspection GrUnresolvedAccess
            def embeddedQueryParams = parsedUri.queryMap

            def allQueryParams = embeddedQueryParams instanceof Map ?
                    ({
                        def m = [:] as Map<String, List<String>>;
                        m.putAll(embeddedQueryParams as Map<String, List<String>>)
                        m.putAll(queryParams)
                    })()
                    : (queryParams ?: [:] as Map<String, List<String>>)

            def urlWithoutQueryParams = { String url ->
                URI uri = new URI(url)
                new URI(uri.getScheme(),
                        uri.getUserInfo(), uri.getHost(), uri.getPort(),
                        uri.getPath(),
                        null, // Ignore the query part of the input url
                        uri.getFragment()).toString()
            }
            def sanitizedUrl = urlWithoutQueryParams(unsanitizedUrl)

            def response
            try {
                def request = httpClient
                        .ws()
                        .url(sanitizedUrl)
                        .withMethod(method)

                if (headers != null && !headers.isEmpty()) {
                    def scalaHeaders = scala.collection.JavaConversions.asScalaBuffer(
                            headers.entrySet().inject([] as List<scala.Tuple2<String, String>>) { List<scala.Tuple2<String, String>> result, kv ->
                                kv.value.each { v -> result.add(pair(kv.key, v) as scala.Tuple2<String, String>) }
                                result
                            }
                    )
                    request = request.withHeaders(scalaHeaders)
                }

                if (!allQueryParams.isEmpty()) {
                    def scalaQueryParams = scala.collection.JavaConversions.asScalaBuffer(queryParams.entrySet().inject([] as List<scala.Tuple2<String, String>>) { List<scala.Tuple2<String, String>> result, kv ->
                        kv.value.each { v -> result.add(pair(kv.key, v) as scala.Tuple2<String, String>) }
                        result
                    })
                    request = request.withQueryString(scalaQueryParams)
                }

                if (body != null) {
                    def writable = play.api.http.Writeable$.MODULE$.wString(play.api.mvc.Codec.utf_8())
                    request = request.withBody(body, writable)
                }

                response = await(request.execute())


            } catch (Exception e) {
                throw new com.exalate.api.exception.IssueTrackerException("Unable to perform the request $method $path, please contact Exalate Support: ".toString() + e.message, e)
            }
            if (response.status() >= 300) {
                throw new com.exalate.api.exception.IssueTrackerException("Failed to perform the request $method $path (status ${response.status()}), please contact Exalate Support: ".toString() + response.body())
            }
            response.body() as String
        }

        def js = new groovy.json.JsonSlurper()

        def response = js.parseText(http(
                "GET",
                "/worklogs/issue/${issue.key}",
                null,
                null,
                [
//                            "Accept":["application/json"],
"Authorization":["Bearer ${token}".toString()],
                ]
        )) as Map<String, Object>;

        List<BasicHubWorkLog> result = new ArrayList<>()
        def listAdditionalParams = [:]

        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        response.get("results").collect{ Map<String, Object> responseItem ->
            BasicHubWorkLog basicHubWorkLog = new BasicHubWorkLog()
            /*"BasicHubWorkLog{" +
                    ", @remoteId=`" + remoteId + '`' +
                    ", @adjustmentMode=`" + adjustmentMode + '`' +
                    ", @updateAuthor=`" + updateAuthor + '`' +
                    ", @group=`" + group + "`" +
                    ", @role=`" + role + "`" +
                    '}';*/


            def tempoAuthor = responseItem.author as Map<String, String>;

            // author
            BasicHubUser author = nodeHelper.getUser(tempoAuthor.accountId as String)
            basicHubWorkLog.setAuthor(author)
            // create
            basicHubWorkLog.setCreated(formatter.parse(responseItem.createdAt as String))
            // comment
            basicHubWorkLog.setComment(responseItem.description as String)
            // id
            basicHubWorkLog.setId(responseItem.tempoWorklogId as Long)
            // startDate
            basicHubWorkLog.setStartDate(new SimpleDateFormat("yyyy-MM-dd").parse(responseItem.startDate as String))
            // timeSpent
            basicHubWorkLog.setTimeSpent(responseItem.timeSpentSeconds as Long)
            // updated
            basicHubWorkLog.setUpdated(formatter.parse(responseItem.updatedAt as String))

            result.add(basicHubWorkLog)

            def params = [
                    billableSeconds : responseItem.get("billableSeconds"),
                    startTime: responseItem.get("startTime"),
                    jiraWorklogId: responseItem.get("jiraWorklogId"),
                    attributes: responseItem.get("attributes"),
                    issue : responseItem.get("issue"),
                    remainingEstimateSeconds: issue.remainingEstimate,
            ]
            listAdditionalParams.put(responseItem.get("tempoWorklogId"), params)

        }
        replica.workLogs = result
        replica.customKeys."tempoWorklogParams" = listAdditionalParams

    }

    static send(String token){
        def context = com.exalate.replication.services.processor.ChangeIssueProcessor$.MODULE$.threadLocalContext.get()
        if (!context) {
            context = com.exalate.replication.services.processor.CreateIssueProcessor$.MODULE$.threadLocalContext.get()
        }
        def replica = context.replica
        def issue = context.issue
        def httpClient = context.httpClient
        def nodeHelper = context.nodeHelper
        send(token, replica, issue, httpClient, nodeHelper)
    }

    // issue = replica

    static receive(String token){
        def context = com.exalate.replication.services.processor.ChangeIssueProcessor$.MODULE$.threadLocalContext.get()
        if (!context) {
            context = com.exalate.replication.services.processor.CreateIssueProcessor$.MODULE$.threadLocalContext.get()
        }
        def replica = context.replica
        def issue = context.issue
        def connection = context.connection
        def issueBeforeScript = context.issueBeforeScript
        def traces = context.traces
        def blobMetadataList = context.blobMetadataList
        def syncRequest = context.syncRequest
        def httpClient = context.httpClient
        def nodeHelper = context.nodeHelper

        def projectKey = issue.project?.key ?: issue.projectKey
        def issueType = issue.type ?: ({ nodeHelper.getIssueType(issue.typeName) })()
        if (projectKey == null) {
            throw new com.exalate.api.exception.IssueTrackerException(""" Project key is not found. Please fill issue.projectKey or issue.project parameter in script """.toString())
        }
        if (issueType == null) {
            throw new com.exalate.api.exception.IssueTrackerException(""" Issue type is not found. Please fill issue.typeName or issue.type parameter in script """.toString())
        }

        CreateIssue.create(
                        true,
                        replica,
                        issue,
                        connection,
                        issueBeforeScript,
                        traces,
                        blobMetadataList,
                        httpClient,
                        syncRequest) {
           receive(token, replica, issue, httpClient, traces, nodeHelper)
         }
    }

    static receive(String token, BasicHubIssue replica, BasicHubIssue issue, PreparedHttpClient httpClient, traces, NodeHelper nodeHelper){
        receive(
                token,
                replica,
                issue,
                httpClient,
                traces,
                nodeHelper,
                { BasicHubWorkLog w ->
                    def getUser = { String key ->
                        def localAuthor = nodeHelper.getUser(key)
                        if (localAuthor == null) {
                            localAuthor = new BasicHubUser()
                            localAuthor.key = "557058:c020323a-70e4-4c07-9ccc-3ad89b1c02ec"
                        }
                        localAuthor
                    }
                    w.author = w.author ? getUser(w.author.key) : null
                    w.updateAuthor = w.updateAuthor ? getUser(w.updateAuthor.key) : null
                    w
                }
        )
    }
    static receive(String token, BasicHubIssue replica, BasicHubIssue issue, PreparedHttpClient httpClient, traces, NodeHelper nodeHelper, Closure<?> onWorklogFn){
        def http = { String method, String path, Map<String, List<String>> queryParams, String body, Map<String, List<String>> headers ->

            def orNull = { scala.Option<?> opt -> opt.isDefined() ? opt.get() : null }
            def pair = { l, r -> scala.Tuple2$.MODULE$.<?, ?>apply(l, r) }
            def none = { scala.Option$.MODULE$.<?> empty() }
            def getGeneralSettings = {
                def gsp = InjectorGetter.getInjector().instanceOf(com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsPersistence.class)
                def gsOpt = await(gsp.get())
                def gs = orNull(gsOpt)
                gs
            }
            final def gs = getGeneralSettings()

            def removeTailingSlash = { String str -> str.trim().replace("/+\$", "") }
//            final def jiraCloudUrl = removeTailingSlash(gs.issueTrackerUrl)
            final def tempoRestApiUrl = "https://api.tempo.io/core/3"

            def parseQueryString = { String string ->
                string.split('&').collectEntries{ param ->
                    param.split('=', 2).collect{ URLDecoder.decode(it, 'UTF-8') }
                }
            }

            //Usage examples: https://gist.github.com/treyturner/4c0f609677cbab7cef9f
            def parseUri
            parseUri = { String uri ->
                def parsedUri
                try {
                    parsedUri = new URI(uri)
                    if (parsedUri.scheme == 'mailto') {
                        def schemeSpecificPartList = parsedUri.schemeSpecificPart.split('\\?', 2)
                        def tempMailMap = parseQueryString(schemeSpecificPartList[1])
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.mailMap = [
                                recipient: schemeSpecificPartList[0],
                                cc       : tempMailMap.find { //noinspection GrUnresolvedAccess
                                    it.key.toLowerCase() == 'cc' }.value,
                                bcc      : tempMailMap.find { //noinspection GrUnresolvedAccess
                                    it.key.toLowerCase() == 'bcc' }.value,
                                subject  : tempMailMap.find { //noinspection GrUnresolvedAccess
                                    it.key.toLowerCase() == 'subject' }.value,
                                body     : tempMailMap.find { //noinspection GrUnresolvedAccess
                                    it.key.toLowerCase() == 'body' }.value
                        ]
                    }
                    if (parsedUri.fragment?.contains('?')) { // handle both fragment and query string
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.rawQuery = parsedUri.rawFragment.split('\\?')[1]
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.query = parsedUri.fragment.split('\\?')[1]
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.rawFragment = parsedUri.rawFragment.split('\\?')[0]
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.fragment = parsedUri.fragment.split('\\?')[0]
                    }
                    if (parsedUri.rawQuery) {
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.queryMap = parseQueryString(parsedUri.rawQuery)
                    } else {
                        //noinspection GrUnresolvedAccess
                        parsedUri.metaClass.queryMap = null
                    }

                    //noinspection GrUnresolvedAccess
                    if (parsedUri.queryMap) {
                        //noinspection GrUnresolvedAccess
                        parsedUri.queryMap.keySet().each { key ->
                            def value = parsedUri.queryMap[key]
                            //noinspection GrUnresolvedAccess
                            if (value.startsWith('http') || value.startsWith('/')) {
                                parsedUri.queryMap[key] = parseUri(value)
                            }
                        }
                    }
                } catch (e) {
                    throw new com.exalate.api.exception.IssueTrackerException("Parsing of URI failed: $uri\n$e", e)
                }
                parsedUri
            }

            def unsanitizedUrl = tempoRestApiUrl + path
            def parsedUri = parseUri(unsanitizedUrl)

            //noinspection GrUnresolvedAccess
            def embeddedQueryParams = parsedUri.queryMap

            def allQueryParams = embeddedQueryParams instanceof Map ?
                    ({
                        def m = [:] as Map<String, List<String>>;
                        m.putAll(embeddedQueryParams as Map<String, List<String>>)
                        m.putAll(queryParams)
                    })()
                    : (queryParams ?: [:] as Map<String, List<String>>)

            def urlWithoutQueryParams = { String url ->
                URI uri = new URI(url)
                new URI(uri.getScheme(),
                        uri.getUserInfo(), uri.getHost(), uri.getPort(),
                        uri.getPath(),
                        null, // Ignore the query part of the input url
                        uri.getFragment()).toString()
            }
            def sanitizedUrl = urlWithoutQueryParams(unsanitizedUrl)

            def response
            try {
                def request = httpClient
                        .ws()
                        .url(sanitizedUrl)
                        .withMethod(method)

                if (headers != null && !headers.isEmpty()) {
                    def scalaHeaders = scala.collection.JavaConversions.asScalaBuffer(
                            headers.entrySet().inject([] as List<scala.Tuple2<String, String>>) { List<scala.Tuple2<String, String>> result, kv ->
                                kv.value.each { v -> result.add(pair(kv.key, v) as scala.Tuple2<String, String>) }
                                result
                            }
                    )
                    request = request.withHeaders(scalaHeaders)
                }

                if (!allQueryParams.isEmpty()) {
                    def scalaQueryParams = scala.collection.JavaConversions.asScalaBuffer(queryParams.entrySet().inject([] as List<scala.Tuple2<String, String>>) { List<scala.Tuple2<String, String>> result, kv ->
                        kv.value.each { v -> result.add(pair(kv.key, v) as scala.Tuple2<String, String>) }
                        result
                    })
                    request = request.withQueryString(scalaQueryParams)
                }

                if (body != null) {
                    def writable = play.api.http.Writeable$.MODULE$.wString(play.api.mvc.Codec.utf_8())
                    request = request.withBody(body, writable)
                }

                response = await(request.execute())


            } catch (Exception e) {
                throw new com.exalate.api.exception.IssueTrackerException("Unable to perform the request $method $path, please contact Exalate Support: ".toString() + e.message, e)
            }
            if (response.status() >= 300) {
                throw new com.exalate.api.exception.IssueTrackerException("Failed to perform the request $method $path ${body ? "with body `$body`".toString() : ""}(status ${response.status()}), please contact Exalate Support: ".toString() + response.body())
            }
            response.body() as String
        }

        def gsp = InjectorGetter.getInjector().instanceOf(AuditLogService.class)

        def js = new groovy.json.JsonSlurper()
        def jo = new groovy.json.JsonOutput()

        def listAdditionalParams = replica.customKeys."tempoWorklogParams" as Map<String, Map<String, Object>>;

        // create worklog block
        replica.addedWorklogs.each{ BasicHubWorkLog worklog ->
            def transformedWorklog
            try {
                transformedWorklog = onWorklogFn(worklog)
            } catch (com.exalate.api.exception.IssueTrackerException ite) {
                throw ite
            } catch (Exception e) {
                throw new com.exalate.api.exception.IssueTrackerException(e)
            }
            if (transformedWorklog instanceof BasicHubWorkLog) {
                worklog = transformedWorklog as BasicHubWorkLog
            } else if (transformedWorklog == null) {
                return
            }

            def auditLogOpt = gsp.createAuditLog(scala.Option$.MODULE$.<String>apply(issue.id as String),
                    WebhookEntityType.WORKLOG_CREATED,
                    worklog.getAuthor().getKey()
            )

            def attributes = ((listAdditionalParams?.get(worklog.remoteId.toString())?.get("attributes") as Map<String, Object>)?.get("values") as List<Map<String, String>>)?.inject([]){
                List<Map<String, String>>result, Map<String, String> attribute ->
                    result.add(
                            [
                                    key: attribute.get("key"),
                                    value: attribute.get("value")
                            ]
                    )
                    result
            } ?: []

            def properties = [
                    issueKey : issue.key,
                    timeSpentSeconds : worklog.getTimeSpent(),
                    billableSeconds: listAdditionalParams?.get(worklog.remoteId.toString())?.get("billableSeconds") ?: worklog.getTimeSpent(),
                    startDate : new java.text.SimpleDateFormat("yyyy-MM-dd").format(worklog.startDate),
                    startTime : new java.text.SimpleDateFormat("hh:mm:ss").format(worklog.startDate) ?: listAdditionalParams?.get(worklog.remoteId.toString())?.get("startTime"),//strDateSplitted[1].split("\\.")[0],
                    description : worklog.getComment(),
                    authorAccountId : worklog.getAuthor().getKey(),
                    remainingEstimateSeconds:  replica.remainingEstimate ?: listAdditionalParams?.get(worklog.remoteId.toString())?.get("remainingEstimateSeconds"),
                    attributes : attributes
            ]
            def jsonTempoPost = jo.toJson(properties)


            def response = js.parseText(http(
                    "POST",
                    "/worklogs",
                    null,
                    jsonTempoPost,
                    [
                            "Authorization":["Bearer ${token}".toString()],
                            "Content-Type":["application/json"],
                    ]
            ))
            println(response)

//send request to get jira worklog by Jira_id (for change worklogs)
            gsp.updateAuditLog(scala.Option$.MODULE$.apply(auditLogOpt), issue.id as String, response["jiraWorklogId"] as String, Json.stringify(Json.toJson(response)))


            String localIdStr = response["tempoWorklogId"]
            String remoteIdStr = worklog.remoteId.toString()

            com.exalate.api.domain.twintrace.INonPersistentTrace trace = new com.exalate.basic.domain.BasicNonPersistentTrace()
                    .setLocalId(localIdStr)
                    .setRemoteId(remoteIdStr)
                    .setType(com.exalate.api.domain.twintrace.TraceType.WORKLOG)
                    .setAction(com.exalate.api.domain.twintrace.TraceAction.NONE)
                    .setToSynchronize(true)
            traces.add(trace)
        }


        //delete woklog block
//        replica.removedWorklogs.each{ worklog ->
//
////            def worklogId = worklog.getId()
//            http(
//                    "DELETE",
//                    "/worklogs/${worklog.getId()}", // remoteId
//                    null,
//                    null,
//                    [
//                            "Authorization":["Bearer ${token}".toString()],
//                    ]
//            )
//            println(traces)
//            //delete trace by remoteId
//
//            def toDelete = traces.find{ t -> t.getAction().name() == "REMOVE"}
//            if (toDelete != null) {
//                traces.remove(toDelete)
//            }
//
////            Iterator<com.exalate.basic.domain.BasicNonPersistentTrace> iterator = traces.iterator()
////            while (iterator.hasNext()) {
////                def trace = iterator.next()
////                if(trace["action"] == "REMOVE"){
////                    iterator.remove()
////                }
////            }
//
//            println(traces)
//        }


    }
}
