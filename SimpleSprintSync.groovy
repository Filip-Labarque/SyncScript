import com.exalate.api.domain.connection.IConnection
import com.exalate.api.domain.request.ISyncRequest
import com.exalate.api.exception.IssueTrackerException
import com.exalate.basic.domain.hubobject.v1.BasicHubIssue
import services.jcloud.hubobjects.NodeHelper
import services.replication.PreparedHttpClient

/**
Usage:
Add the snippet below to the end of your "Outgoing sync":

SimpleSprintSync.send()
--------------------------------
Add the snippet below to the end of your "Incoming sync":

SimpleSprintSync.receive()
--------------------------------
 * */
class SimpleSprintSync {
    //ERRORS
    static def issueLevelError(String msg) {
        new com.exalate.api.exception.IssueTrackerException(msg)
    }
    static def issueLevelError2(String msg, Throwable e) {
        new com.exalate.api.exception.IssueTrackerException(msg, e)
    }

    // SCALA HELPERS
    static <T> T await(scala.concurrent.Future<T> f) { scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
    static <T> T orNull(scala.Option<T> opt) { opt.isDefined() ? opt.get() : null }
    static <P1, R> scala.Function1<P1, R> fn(Closure<R> closure) {
        (scala.runtime.AbstractFunction1<P1, R>)new scala.runtime.AbstractFunction1() {
            @Override
            Object apply(Object p1) {
                return closure.call(p1)
            }
        }
    }
    static <P1, P2, R> scala.Function2<P1, P2, R> fn2(Closure<R> closure) {
        (scala.runtime.AbstractFunction2<P1, P2, R>)new scala.runtime.AbstractFunction2() {
            @Override
            Object apply(Object p1, Object p2) {
                return closure.call(p1, p2)
            }
        }
    }
    static <T> scala.Option<T> none() { scala.Option$.MODULE$.<T>empty() }
    @SuppressWarnings("GroovyUnusedDeclaration")
    static <T> scala.Option<T> none(Class<T> evidence) { scala.Option$.MODULE$.<T>empty() }
    static <T1, T2> scala.Tuple2<T1, T2> pair(T1 l, T2 r) { scala.Tuple2$.MODULE$.<T1, T2>apply(l, r) }
    static <T> scala.collection.Seq<T> seq(T ... ts) {
        def list = Arrays.asList(ts)
        def scalaBuffer = scala.collection.JavaConversions.asScalaBuffer(list)
        scalaBuffer.toSeq()
    }
    static <T> scala.collection.Seq<T> seqPlus (scala.collection.Seq<T> tsLeft, T ... tsRight) {
        def list = Arrays.asList(tsRight)
        def scalaBuffer = scala.collection.JavaConversions.asScalaBuffer(list)
        scala.collection.Seq$.MODULE$
                .newBuilder()
                .$plus$plus$eq(tsLeft)
                .$plus$plus$eq(scalaBuffer)
                .result()
    }
    static <P> scala.collection.Seq<P> paginateInternal(Integer offset, Integer limit, scala.collection.Seq<P> result, scala.Function2<Integer, Integer, P> nextPageFn, scala.Function1<P, Integer> getTotalFn) {
        def page = nextPageFn.apply(offset, limit)
        def total = getTotalFn.apply(page)
        def last = total < limit
        def newResult = seqPlus(result, page)
        if (last) {
            newResult
        } else {
            paginateInternal(offset + limit, limit, newResult, nextPageFn, getTotalFn)
        }
    }
    static <P, I> List<I> paginate(Integer limit, scala.Function2<Integer, Integer, P> nextPageFn, scala.Function1<P, List<I>> getItemsFn) {
        def getTotalFn = SimpleSprintSync.<P, Integer> fn { P p -> getItemsFn.apply(p).size() }
        scala.collection.Seq<P> resultSeq = paginateInternal(0, limit, SimpleSprintSync.<P>seq(), nextPageFn, getTotalFn)
        List<P> pages = scala.collection.JavaConversions.bufferAsJavaList(resultSeq.toBuffer())
        def items = pages
                .collect { P p -> getItemsFn.apply(p) }
                .flatten()
        items
    }

    // SERVICES AND EXALATE API
    static play.api.inject.Injector getInjector() {
        InjectorGetter.getInjector()
    }
    static def getGeneralSettings() {
        def gsp = injector.instanceOf(com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsPersistence.class)
        def gsOpt = await(gsp.get())
        def gs = orNull(gsOpt)
        gs
    }
    static String getJiraCloudUrl() {
        final def gs = getGeneralSettings()

        def removeTailingSlash = { String str -> str.trim().replace("/+\$", "") }
        final def jiraCloudUrl = removeTailingSlash(gs.issueTrackerUrl)
        jiraCloudUrl
    }

    // JIRA API
    static List<Map<String, Object>> getFieldsJson(PreparedHttpClient httpClient) {
        //"com.pyxis.greenhopper.jira:gh-epic-link"

        def fieldsResponse
        try {
            fieldsResponse = await(httpClient.thisJira("/rest/api/2/field", "GET", null, null).get())
        } catch (Exception e) {
            throw new IllegalStateException("Unable to get the fields json, please contact Exalate Support: " + e.message, e)
        }
        if (fieldsResponse.status() != 200) {
            throw new IllegalStateException("Can not get fields (status "+ fieldsResponse.status() +"), please contact Exalate Support: "+ fieldsResponse.body())
        }
        groovy.json.JsonSlurper s = new groovy.json.JsonSlurper()
        /*
        [..., {"id":"customfield_10990","key":"customfield_10990","name":"Epic Link","custom":true,"orderable":true,"navigable":true,"searchable":true,"clauseNames":["cf[10990]","Epic Link"],"schema":{"type":"any","custom":"com.pyxis.greenhopper.jira:gh-epic-link","customId":10990}, ...}]
         */
        def fieldsJson
        try {
            fieldsJson = s.parseText(fieldsResponse.body())
        } catch (Exception e) {
            throw new IllegalStateException("Can not parse fields json, please contact Exalate Support: " + fieldsResponse.body(), e)
        }
        if (!(fieldsJson instanceof List)) {
            throw new IllegalStateException("Fields json has unrecognized strucutre, please contact Exalate Support: " + fieldsResponse.body())
        }
        fieldsJson as List<Map<String, Object>>
    }
    static def searchFn(PreparedHttpClient httpClient) { return { String jql ->
        final def gs = generalSettings
        //noinspection GroovyAssignabilityCheck
        def foundIssues = paginate(
                50,
                fn2 { Integer offset, Integer limit ->
                    def searchResponse
                    try {
                        searchResponse = await(await(httpClient.authenticate(
                                none(),
                                httpClient
                                        .ws()
                                        .url(jiraCloudUrl+"/rest/api/2/search")
                                        .withQueryString(seq(
                                        pair("jql", jql),
                                        pair("startAt", offset as String),
                                        pair("maxResults", limit as String),
                                        pair("fields", "id,key")
                                ))
                                        .withMethod("GET"),
                                gs
                        )).get())
                    } catch (Exception e) {
                        throw issueLevelError2("Unable to search, please contact Exalate Support: " +
                                "\nRequest: GET /rest/api/2/search?jql="+ jql +"&startAt="+ offset +"&maxResults="+ limit +"&fields=id,key" +
                                "\nError: " + e.message, e)
                    }
                    if (searchResponse.status() != 200) {
                        throw issueLevelError("Can not search (status "+ searchResponse.status() +"), please contact Exalate Support: " +
                                "\nRequest: GET /rest/api/2/search?jql="+ jql +"&startAt="+ offset +"&maxResults="+ limit +"&fields=id,key"+
                                "\nResponse: "+ searchResponse.body())
                    }
                    def searchResult = searchResponse.body()
                    groovy.json.JsonSlurper s = new groovy.json.JsonSlurper()
                    def searchResultJson
                    try {
                        searchResultJson = s.parseText(searchResult)
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse the search json, please contact Exalate Support: " + searchResult, e)
                    }

                    /*
                    {
                      "expand": "names,schema",
                      "startAt": 0,
                      "maxResults": 50,
                      "total": 1,
                      "issues": [
                        {
                          "expand": "",
                          "id": "10001",
                          "self": "http://www.example.com/jira/rest/api/2/issue/10001",
                          "key": "HSP-1"
                        }
                      ],
                      "warningMessages": [
                        "The value 'splat' does not exist for the field 'Foo'."
                      ]
                    }
                    */
                    if (!(searchResultJson instanceof Map)) {
                        throw issueLevelError("Issue search json has unrecognized structure, please contact Exalate Support: " + searchResult)
                    }
                    searchResultJson as Map<String, Object>
                },
                fn { Map<String, Object> page ->
                    if (!(page.issues instanceof List)) {
                        throw issueLevelError("Issue Search json has unrecognized structure inside each page, please contact Exalate Support: " + page)
                    }
                    page.issues as List<Map<String, Object>>
                }
        )
        foundIssues.collect { story -> [
                "id": story.id as Long,
                "key": story.key as String
        ]}
    } }

    // Callbacks
    static Closure<Object> defaultOnNoBoardFound() { { String localProjectKey ->
        throw issueLevelError("Can not find a scrum board for project `$localProjectKey`".toString())
    } }
    static Closure<Object> skipOnNoBoardFound() { { String localProjectKey ->
//        LOG.info("Can not find a scrum board for project `$localProjectKey`".toString())
        null
    } }

    // SEND
    static def send() {
        def context = com.exalate.replication.services.processor.CreateReplicaProcessor$.MODULE$.threadLocalContext.get()
        BasicHubIssue replica = context.replica
        BasicHubIssue issue = context.issue
        PreparedHttpClient httpClient = context.httpClient
        sendSprints(replica, issue, httpClient)
    }

    static def sendSprints(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, services.replication.PreparedHttpClient httpClient) {
        final def iso8601DateFormat = new com.fasterxml.jackson.databind.util.ISO8601DateFormat()
        def parseDateIso8601 = { String dateStr ->
            iso8601DateFormat.parse(dateStr)
        }
        final def fieldsJson = getFieldsJson(httpClient).findAll { it.schema instanceof Map }
        final def sprintCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-sprint" }
        def search = searchFn(httpClient)


        final def gs = generalSettings


        def getSprintJson = { sprintId ->
            def response
            try {
                response = await(await(httpClient.authenticate(
                        none(),
                        httpClient
                                .ws()
                                .url(jiraCloudUrl+"/rest/agile/1.0/sprint/"+ sprintId)
                                .withMethod("GET"),
                        gs
                )).execute())
            } catch (Exception e) {
                throw issueLevelError2(
                        "Unable to get the sprint "+ sprintId +", please contact Exalate Support: " +
                                "\nRequest: GET /rest/agile/1.0/sprint/"+ sprintId +
                                "\nError: " + e.message,
                        e
                )
            }
            if (response.status() != 200) {
                throw issueLevelError(
                        "Can not get the sprint "+ sprintId +" (status "+ response.status() +"), please contact Exalate Support: "+
                                "\nRequest: GET /rest/agile/1.0/sprint/"+ sprintId +
                                "\nResponse: "+ response.body()
                )
            }
            def resultStr = response.body()
            groovy.json.JsonSlurper s = new groovy.json.JsonSlurper()
            def resultJson
            try {
                resultJson = s.parseText(resultStr)
            } catch (Exception e) {
                throw issueLevelError2("Can not parse the sprint "+ sprintId +" json, please contact Exalate Support: " + resultStr, e)
            }
            /*
            {
              "id": 37,
              "self": "http://www.example.com/jira/rest/agile/1.0/sprint/23",
              "state": "closed",
              "name": "sprint 1",
              "startDate": "2015-04-11T15:22:00.000+10:00",
              "endDate": "2015-04-20T01:22:00.000+10:00",
              "completeDate": "2015-04-20T11:04:00.000+10:00",
              "originBoardId": 5,
              "goal": "sprint 1 goal"
            }
             */
            if (!(resultJson instanceof Map)) {
                throw issueLevelError("Sprint "+sprintId+" json has unrecognized structure, please contact Exalate Support: " + resultStr)
            }
            def resultMap = resultJson as Map<String, Object>
            if (resultMap.startDate instanceof String) {
                resultMap.startDateLong = parseDateIso8601(resultMap.startDate as String).time
            }
            if (resultMap.endDate instanceof String) {
                resultMap.endDateLong = parseDateIso8601(resultMap.endDate as String).time
            }
            if (resultMap.completeDate instanceof String) {
                resultMap.completeDateLong = parseDateIso8601(resultMap.completeDate as String).time
            }
            resultMap
        }
        def getIssuesInSprint = { sprintId ->
            def sprintClauseNames = sprintCfJson.clauseNames as List<String>
            def clauseName = sprintClauseNames[0]
            final def jql = clauseName + " = " + sprintId
            search(jql)
        }
        def getSprintContext = { List<Long> sprintIds ->
            sprintIds.inject([]) { result, sprintId ->
                result += [
                        "sprint" : getSprintJson(sprintId),
                        "issues" : getIssuesInSprint(sprintId)
                ]
                result
            }
        }

        def sprintCfValue = issue.customFields[sprintCfJson?.schema?.customId as String]?.value
        if (sprintCfJson != null && sprintCfValue != null) {
            replica.customKeys."sprintContext" = getSprintContext((sprintCfValue as List<com.exalate.basic.domain.hubobject.v1.BasicHubSprint>).collect { s-> s.id })
        }
    }

    // RECEIVE
    static def receive() {
        receive(defaultOnNoBoardFound())
    }
    static def receiveSprints(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, services.jcloud.hubobjects.NodeHelper nodeHelper, services.replication.PreparedHttpClient httpClient) {
        receiveSprints(defaultOnNoBoardFound(), replica, issue, nodeHelper, httpClient)
    }
    static def receive(Closure<Object> onNoBoardFound) {
        def context = com.exalate.replication.services.processor.ChangeIssueProcessor$.MODULE$.threadLocalContext.get()
        if (!context) {
            context = com.exalate.replication.services.processor.CreateIssueProcessor$.MODULE$.threadLocalContext.get()
        }

        BasicHubIssue replica = context.replica
        BasicHubIssue issueBeforeScript = context.issueBeforeScript
        BasicHubIssue issue = context.issue
        NodeHelper nodeHelper = context.nodeHelper
        PreparedHttpClient httpClient = context.httpClient
        IConnection connection = context.connection
        List<com.exalate.api.domain.twintrace.INonPersistentTrace> traces = context.traces
        scala.collection.Seq<com.exalate.api.domain.IBlobMetadata> blobMetadataList = context.blobMetadataList
        ISyncRequest syncRequest = context.syncRequest

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
            receiveSprints(onNoBoardFound, replica, issue, nodeHelper, httpClient)
        }
    }
    static def receiveSprints(Closure<Object> onNoBoardFound, com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, services.jcloud.hubobjects.NodeHelper nodeHelper, services.replication.PreparedHttpClient httpClient) {
        def lookupSprintByName = true

        if (issue.id == null) {
            throw new com.exalate.api.exception.IssueTrackerException(""" It seems, that the issue has not been created yet. Please change your create processor to create the issue and populate the `issue.id` property before using the `AgileSync.receiveSprints(...)` """.toString())
        }
        def localExIssueKey = new com.exalate.basic.domain.BasicIssueKey(issue.id as Long, issue.key)
        final def gs = generalSettings
        final def iso8601DateFormat = new com.fasterxml.jackson.databind.util.ISO8601DateFormat()
        def parseDateIso8601 = { String dateStr ->
            iso8601DateFormat.parse(dateStr)
        }

        final def fieldsJson = getFieldsJson(httpClient).findAll { it.schema instanceof Map }
        final def sprintCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-sprint" }
        def getAppProperty = { String key ->
            def response
            try {
                response = await(
                        httpClient
                                .thisJira(
                                "/rest/atlassian-connect/1/addons/com.exalate.jiranode/properties/"+key,
                                "GET",
                                "application/json",
                                null
                        )
                                .execute()
                )
            } catch (Exception e) {
                throw issueLevelError2("Unable to get property `"+key+"`, please contact Exalate Support: " +
                        "\nRequest: GET /rest/atlassian-connect/1/addons/com.exalate.jiranode/properties/"+key+
                        "\nError: " + e.message, e)
            }
            if ((response.status() as Integer) == 404) {
                return null
            } else if (response.status() != 200) {
                throw issueLevelError("Can not get property `"+key+"` (status "+ response.status() +"), please contact Exalate Support: " +
                        "\nRequest: GET /rest/atlassian-connect/1/addons/com.exalate.jiranode/properties/"+key+
                        "\nResponse: "+ response.body())
            }
            def resultStr = response.body()
            def s = new groovy.json.JsonSlurper()
            def resultJson
            try {
                resultJson = s.parseText(resultStr)
            } catch (Exception e) {
                throw issueLevelError2("Can not parse the property json, please contact Exalate Support: " + resultStr, e)
            }
            /*
            {
              "key": "party-members",
              "value": {
                "party": {
                  "attendees": [
                    "antman",
                    "batman",
                    "catwoman",
                    "deadpool"
                  ],
                  "attendeeCount": 4
                }
              }
            }
            */
            if (!(resultJson instanceof Map)) {
                throw issueLevelError("App property `"+key+"` json has unrecognized structure, please contact Exalate Support: " + resultStr)
            }
            (resultJson as Map<String, Object>).value
        }

        def putAppProperty = { String key, value ->
            def response
            def jsonStr = null
            try {
                jsonStr = groovy.json.JsonOutput.toJson(value)
                response = await(
                        httpClient
                                .thisJira(
                                "/rest/atlassian-connect/1/addons/com.exalate.jiranode/properties/"+key,
                                "PUT",
                                "application/json",
                                jsonStr
                        )
                                .execute()
                )
            } catch (Exception e) {
                throw issueLevelError2("Unable to put property `"+key+"` with value `"+ value +"` (json=`"+ jsonStr +"`), please contact Exalate Support: " +
                        "\nRequest: PUT /rest/atlassian-connect/1/addons/com.exalate.jiranode/properties/"+key+
                        "\nBody: "+ jsonStr +
                        "\nError: " + e.message, e)
            }
            if (response.status() == 403) {
                throw issueLevelError("Can not put property `"+key+"` with value `"+ value +"` (json=`"+ jsonStr +"`) (status "+ response.status() +"), " +
                        "\nIt seems, storage for id mapping is exceeded need to migrate it to idalko filesystem." +
                        "\nPlease contact Exalate Support: " +
                        "\nRequest: PUT /rest/atlassian-connect/1/addons/com.exalate.jiranode/properties/"+key+
                        "\nBody: "+ jsonStr +
                        "\nResponse: "+ response.body())
            }
            if (response.status() != 200 && response.status() != 201 ) {
                throw issueLevelError("Can not put property `"+key+"` with value `"+ value +"` (json=`"+ jsonStr +"`) (status "+ response.status() +"), please contact Exalate Support: " +
                        "\nRequest: PUT /rest/atlassian-connect/1/addons/com.exalate.jiranode/properties/"+key+
                        "\nBody: "+ jsonStr +
                        "\nResponse: "+ response.body())
            }
            null
        }

        def deleteAppProperty = { String key ->
            def response
            try {
                response = await(
                        httpClient
                                .thisJira(
                                "/rest/atlassian-connect/1/addons/com.exalate.jiranode/properties/"+key,
                                "DELETE",
                                "application/json",
                                null
                        )
                                .execute()
                )
            } catch (Exception e) {
                throw issueLevelError2("Unable to delete property `"+key+"`, please contact Exalate Support: " +
                        "\nRequest: DELETE /rest/atlassian-connect/1/addons/com.exalate.jiranode/properties/"+key+
                        "\nError: " + e.message, e)
            }
            if (response.status() != 204) {
                throw issueLevelError("Can not delete property `"+key+"` (status "+ response.status() +"), please contact Exalate Support: " +
                        "\nRequest: DELETE /rest/atlassian-connect/1/addons/com.exalate.jiranode/properties/"+key+
                        "\nResponse: "+ response.body())
            }
            null
        }

        final def (addSprintIdMapping, removeSprintIdMapping, getSprintIdMapping) = ({
            final def sprintMappingAppPropKey = "sprintsync.v1.idmapping"
            return ([
                    { String localSprintId, String remoteSprintId ->
                        def idMapping = getAppProperty(sprintMappingAppPropKey)
                        idMapping = idMapping ?: [ (localSprintId) : remoteSprintId ]
                        putAppProperty(sprintMappingAppPropKey, idMapping)
                        idMapping
                    },
                    { String localSprintId ->
                        def idMapping = getAppProperty(sprintMappingAppPropKey)
                        idMapping = idMapping ?: [ : ]
                        putAppProperty(sprintMappingAppPropKey, idMapping)
                        idMapping
                    },
                    {
                        def idMapping = getAppProperty(sprintMappingAppPropKey)
                        idMapping ?: [ : ]
                    }
            ] as List<Closure<Map<String,String>>>)
        })()

        def issueInfoCache = [:]

        def getAllBoards = { String name, String type, String projectKey ->
            //noinspection GroovyAssignabilityCheck
            paginate(
                    50,
                    fn2 { Integer offset, Integer limit ->
                        def queryParams = seq(
                                pair("startAt", offset as String),
                                pair("maxResults", limit as String)
                        )
                        queryParams = projectKey == null ?
                                queryParams :
                                seqPlus(queryParams, pair("projectKeyOrId", projectKey))
                        queryParams = name == null ?
                                queryParams :
                                seqPlus(queryParams, pair("name", name))
                        queryParams = type == null ?
                                queryParams :
                                seqPlus(queryParams, pair("type", type))
                        def qParamsStr = scala.collection.JavaConversions
                                .bufferAsJavaList(
                                queryParams
                                        .tail()
                                        .tail()
                                        .<scala.Tuple2<String, String>>toBuffer()
                        )
                                .inject ("") { resultStr, qp ->
                            resultStr + "&"+ qp._1()+"="+qp._2()
                        }
                        def response
                        try {
                            response = await(await(httpClient.authenticate(
                                    none(),
                                    httpClient
                                            .ws()
                                            .url(jiraCloudUrl+"/rest/agile/1.0/board")
                                            .withQueryString(queryParams)
                                            .withMethod("GET"),
                                    gs
                            )).get())
                        } catch (Exception e) {
                            throw issueLevelError2(
                                    "Unable to get the boards, please contact Exalate Support: " +
                                            "\nRequest: GET /rest/agile/1.0/board?startAt="+ offset +"&maxResults="+limit + qParamsStr +
                                            "\nError: " + e.message
                                    , e
                            )
                        }
                        if (response.status() != 200) {
                            throw issueLevelError(
                                    "Can not get the boards (status "+ response.status() +"), please contact Exalate Support: " +
                                            "\nRequest: GET /rest/agile/1.0/board?startAt="+ offset +"&maxResults="+limit + qParamsStr+
                                            "\nResponse: "+ response.body()
                            )
                        }
                        def resultStr = response.body()
                        groovy.json.JsonSlurper s = new groovy.json.JsonSlurper()
                        def resultJson
                        try {
                            resultJson = s.parseText(resultStr)
                        } catch (Exception e) {
                            throw issueLevelError2("Can not parse the boards json, please contact Exalate Support: " + resultStr, e)
                        }

                        /*
            {
              "maxResults": 2,
              "startAt": 1,
              "total": 5,
              "isLast": false,
              "values": [
                {
                  "id": 84,
                  "self": "http://www.example.com/jira/rest/agile/1.0/board/84",
                  "name": "scrum board",
                  "type": "scrum"
                },
                {
                  "id": 92,
                  "self": "http://www.example.com/jira/rest/agile/1.0/board/92",
                  "name": "kanban board",
                  "type": "kanban"
                }
              ]
            }
                        */
                        if (!(resultJson instanceof Map)) {
                            throw issueLevelError("Boards json has unrecognized structure, please contact Exalate Support: " + resultStr)
                        }
                        resultJson as Map<String, Object>
                    },
                    fn { Map<String, Object> page -> (page.values as List<Map<String, Object>>) }
            )
        }
        def enrichSprint = { Map<String, Object> resultJson ->
            def resultMap = resultJson as Map<String, Object>
            if (resultMap.startDate instanceof String) {
                resultMap.startDateLong = parseDateIso8601(resultMap.startDate as String).time
            }
            if (resultMap.endDate instanceof String) {
                resultMap.endDateLong = parseDateIso8601(resultMap.endDate as String).time
            }
            if (resultMap.completeDate instanceof String) {
                resultMap.completeDateLong = parseDateIso8601(resultMap.completeDate as String).time
            }
            resultMap
        }
        def getSprintsOnBoard = { Long boardId ->
            //noinspection GroovyAssignabilityCheck
            def sprints = paginate(
                    50,
                    fn2 { Integer offset, Integer limit ->
                        def response
                        try {
                            response = await(await(httpClient.authenticate(
                                    none(),
                                    httpClient
                                            .ws()
                                            .url(jiraCloudUrl+"/rest/agile/1.0/board/"+boardId+"/sprint")
                                            .withQueryString(seq(
                                            pair("startAt", offset as String),
                                            pair("maxResults", limit as String)
                                    ))
                                            .withMethod("GET"),
                                    gs
                            )).execute())
                        } catch (Exception e) {
                            throw issueLevelError2(
                                    "Unable to get the sprints on board, please contact Exalate Support: " +
                                            "\nRequest: GET /rest/agile/1.0/board/"+boardId+"/sprint" +
                                            "\nError: " + e.message,
                                    e
                            )
                        }
                        if (response.status() != 200) {
                            throw issueLevelError(
                                    "Can not get the sprints on board (status "+ response.status() +"), please contact Exalate Support: "+
                                            "\nRequest: GET /rest/agile/1.0/board/"+boardId+"/sprint"+
                                            "\nResponse: "+ response.body()
                            )
                        }
                        def resultStr = response.body()
                        groovy.json.JsonSlurper s = new groovy.json.JsonSlurper()
                        def resultJson
                        try {
                            resultJson = s.parseText(resultStr)
                        } catch (Exception e) {
                            throw issueLevelError2("Can not parse the issues on board json, please contact Exalate Support: " + resultStr, e)
                        }
/*
{
  "maxResults": 2,
  "startAt": 1,
  "total": 5,
  "isLast": false,
  "values": [
    {
      "id": 37,
      "self": "http://www.example.com/jira/rest/agile/1.0/sprint/23",
      "state": "closed",
      "name": "sprint 1",
      "startDate": "2015-04-11T15:22:00.000+10:00",
      "endDate": "2015-04-20T01:22:00.000+10:00",
      "completeDate": "2015-04-20T11:04:00.000+10:00",
      "originBoardId": 5,
      "goal": "sprint 1 goal"
    },
    {
      "id": 72,
      "self": "http://www.example.com/jira/rest/agile/1.0/sprint/73",
      "state": "future",
      "name": "sprint 2",
      "goal": "sprint 2 goal"
    }
  ]
}
*/
                        if (!(resultJson instanceof Map)) {
                            throw issueLevelError("Issues on board json has unrecognized structure, please contact Exalate Support: " + resultStr)
                        }
                        resultJson as Map<String, Object>
                    },
                    fn { Map<String, Object> page -> (page.values as List<Map<String, Object>>) }
            )
            sprints.collect(enrichSprint)
        }
        def toSprintDateStr = { Long millis ->
            if (millis == null) {
                return null
            }
            iso8601DateFormat.format(new Date(millis))
        }
        def createSprint = { Map<String, Object>  remoteSprint, Long localBoardId ->
            /*
            {
              "name": "sprint 1",
              "startDate": "2015-04-11T15:22:00.000+10:00",
              "endDate": "2015-04-20T01:22:00.000+10:00",
              "originBoardId": 5,
              "goal": "sprint 1 goal"
            }
             */
            final Map<String, Object> json = [
                    "name": remoteSprint.name as String,
                    "startDate": toSprintDateStr(remoteSprint.startDateLong as Long),
                    "endDate": toSprintDateStr(remoteSprint.endDateLong as Long),
                    "originBoardId": localBoardId,
                    "goal": remoteSprint.goal as String
            ]
            final def jsonStr = groovy.json.JsonOutput.toJson(json)
            def response
            try {
                response = await(httpClient.thisJira(
                        "/rest/agile/1.0/sprint",
                        "POST",
                        "application/json",
                        jsonStr
                ).execute())
            } catch (Exception e) {
                throw issueLevelError2(
                        "Unable to rank issues, please contact Exalate Support: " +
                                "\nRequest: POST /rest/agile/1.0/sprint" +
                                "\nRequest Body: "+ jsonStr +
                                "\nError: " + e.message,
                        e
                )
            }
            if (response.status() != 201) {
                throw issueLevelError("Can not rank issues (status "+ response.status() +"), please contact Exalate Support: " +
                        "\nRequest: POST /rest/agile/1.0/sprint" +
                        "\nRequest Body: "+ jsonStr +
                        "\nResponse: "+ response.body()
                )
            }
            /*
            {
              "id": 37,
              "self": "http://www.example.com/jira/rest/agile/1.0/sprint/23",
              "state": "future",
              "name": "sprint 1",
              "startDate": "2015-04-11T15:22:00.000+10:00",
              "endDate": "2015-04-20T01:22:00.000+10:00",
              "originBoardId": 5,
              "goal": "sprint 1 goal"
            }
             */
            groovy.json.JsonSlurper s = new groovy.json.JsonSlurper()
            def sprintJsonStr = response.body()
            def sprintJson = s.parseText(sprintJsonStr) as Map<String, Object>
            sprintJson
        }


        def getLocalSprints = { localProjectKey ->
            def allBoards = getAllBoards(null, "scrum", localProjectKey)
            allBoards
                    .collect { b -> getSprintsOnBoard(b.id as Long) }
                    .flatten()
        }

        def getSprintByRemoteId = { List<Map<String, Object>> localSprints, Long remoteSprintId ->
            def sprintIdMapping = getSprintIdMapping()
            def localSprintIdOrNull = sprintIdMapping
                    .entrySet()
                    .find { kv -> kv.value == remoteSprintId as String }?.key
            if (localSprintIdOrNull == null) null
            else localSprints.find { s -> (s.id as String) == localSprintIdOrNull }
        }
        def getSprintByRemoteName = { List<Map<String, Object>> localSprints, String remoteSprintName ->
            if (lookupSprintByName) {
                return localSprints.find { s ->
                    s.name == remoteSprintName
                }
            }

            return null
        }

        def getSprintByRemote = { String remoteSprintName, Long remoteSprintId, String localProjectKey ->
            def localSprints = getLocalSprints(localProjectKey)

            def localSprintById = getSprintByRemoteId(localSprints, remoteSprintId)
            if (localSprintById != null) {
                return localSprintById
            }
            def localSprintByName = getSprintByRemoteName(localSprints, remoteSprintName)
            if (localSprintByName != null) {
                addSprintIdMapping(localSprintByName.id as String, remoteSprintId as String)
                return localSprintByName
            }

            // give up
            return null
        }

        def moveToSprint = { Long sprintId, List<com.exalate.basic.domain.BasicIssueKey> issues ->
            final Map<String, Object> json = [
                    "issues": issues.collect { i -> i.id }
            ]
            final def jsonStr = groovy.json.JsonOutput.toJson(json)
            def response
            try {
                response = await(httpClient.thisJira(
                        "/rest/agile/1.0/sprint/"+sprintId+"/issue",
                        "POST",
                        "application/json",
                        jsonStr
                ).execute())
            } catch (Exception e) {
                throw issueLevelError2(
                        "Unable to move issues to sprint, please contact Exalate Support: " +
                                "\nRequest: POST /rest/agile/1.0/sprint/"+sprintId+"/issue" +
                                "\nRequest Body: "+ jsonStr +
                                "\nError: " + e.message,
                        e
                )
            }
            if (response.status() != 204) {
                throw issueLevelError("Can not move issues to sprint (status "+ response.status() +"), please contact Exalate Support: " +
                        "\nRequest: POST /rest/agile/1.0/sprint/"+sprintId+"/issue" +
                        "\nRequest Body: "+ jsonStr +
                        "\nResponse: "+ response.body()
                )
            }
        }
        def moveToSprintMultiple = { Long sprintId, List<com.exalate.basic.domain.BasicIssueKey> issues ->
            if (issues.empty) {
                return
            }
            def partitions = issues.collate(50)
            partitions.each { is ->
                moveToSprint(sprintId, is)
            }
        }
        def moveToBacklog = { List<com.exalate.basic.domain.BasicIssueKey> issues ->
            final Map<String, Object> json = [
                    "issues": issues.collect { i -> i.id }
            ]
            final def jsonStr = groovy.json.JsonOutput.toJson(json)
            def response
            try {
                response = await(httpClient.thisJira(
                        "/rest/agile/1.0/backlog/issue",
                        "POST",
                        "application/json",
                        jsonStr
                ).execute())
            } catch (Exception e) {
                throw issueLevelError2(
                        "Unable to move issues to sprint, please contact Exalate Support: " +
                                "\nRequest: POST /rest/agile/1.0/backlog/issue" +
                                "\nRequest Body: "+ jsonStr +
                                "\nError: " + e.message,
                        e
                )
            }
            if (response.status() != 204) {
                throw issueLevelError("Can not move issues to sprint (status "+ response.status() +"), please contact Exalate Support: " +
                        "\nRequest: POST /rest/agile/1.0/backlog/issue" +
                        "\nRequest Body: "+ jsonStr +
                        "\nResponse: "+ response.body()
                )
            }
        }
        def moveToBacklogMultiple = { List<com.exalate.basic.domain.BasicIssueKey> issues ->
            if (issues.empty) {
                return
            }
            def partitions = issues.collate(50)
            partitions.each(moveToBacklog)
        }
        def getLocalIssueKeyByRemote = { Long remoteIssueId -> { Long currentRemoteId, String currentRemoteKey ->
            if (remoteIssueId == currentRemoteId) {
                return localExIssueKey
            }
            def localIssue = issueInfoCache[remoteIssueId as Long]
            if(localIssue == null){
                localIssue = nodeHelper.getLocalIssueFromRemoteId(remoteIssueId)
                issueInfoCache[remoteIssueId as Long] = localIssue
            }
            if (localIssue == null) {
                return null
            }
            new com.exalate.basic.domain.BasicIssueKey(localIssue.id as Long, localIssue.key)
        } }
        def search = searchFn(httpClient)
//        def getIssuesInSprint = { Long sprintId ->
//            def sprintClauseNames = sprintCfJson.clauseNames as List<String>
//            def clauseName = sprintClauseNames[0]
//            final def jql = clauseName + " = " + sprintId
//            search(jql)
//        }

        try {

            // apply sprints
/*
    1. iterate over sprints in context:
1.0. check whether sprint by name is present
    if local is closed, ignore
    if local is active, put current issue into it
    if local is future, put current issue into it
    if local is none, create, put current issue into it
 */


            def projectKey = issue.project.key ?: issue.projectKey
            def firstBoard = getAllBoards(null, "scrum", projectKey)?.find()
            def localBoardId = firstBoard?.id as Long

            def remoteSprintContext = replica.customKeys.sprintContext ?: []

            //Get all sprints in the local project
            def allLocalSprints = getLocalSprints(projectKey)
            def localSprints = (issue.customFields[sprintCfJson.schema.customId as String]?.value ?: []).collect { localSprint ->
                allLocalSprints.find { s -> (s.id as Long) == (localSprint.id as Long) }
            }

            remoteSprintContext?.each { Map<String, Object> ctx ->
                def remoteSprint = ctx.sprint as Map<String, Object>;

                def localSprint = getSprintByRemote(remoteSprint.name as String, remoteSprint.id as Long, projectKey)

//                log.debug("Got localSprint: `" + localSprint + "` for issue `${issue.key}`")

                def localSprintId = localSprint?.id as Long

                if (localSprint == null && !"CLOSED".equalsIgnoreCase(remoteSprint.state as String)) {
                    localSprint = createSprint(remoteSprint, localBoardId)
                    localSprintId = localSprint?.id as Long
                }

                def alreadyHasSprint = localSprints.any { sprint -> (sprint.id as Long) == localSprintId }

//                    log.debug("Checking if we can move the issue to the sprint: `" + localSprintId + "` alreadyHasSprint=`${alreadyHasSprint}` for issue `${issue.key}`")
                if (localSprintId != null && !"CLOSED".equalsIgnoreCase(localSprint.state) && !alreadyHasSprint) {
                    moveToSprintMultiple(localSprintId, [localExIssueKey])
//                        log.debug("Issues in sprint `${localSprintId}` after move : ${search("sprint = ${localSprintId}")}")
                }
            }

            //if such local sprints exist -> move an issue in the backlog if sprint status is "active" or "future"
            if (!remoteSprintContext?.any { Map<String, Object> ctx ->
                Map<String, Object> remoteSprint = ctx.sprint as Map<String, Object>;
                //if the status of such local sprint is "active" or "future" - move the issue in the backlog and stop iterating
                !"CLOSED".equalsIgnoreCase(remoteSprint.state as String)
            }) {
                moveToBacklog([localExIssueKey])
            }
            issue.customFields.remove(sprintCfJson.schema.customId as String)
//   log.debug("The Change Processor is executed: the issue `"+ jIssue.key +"` for remote issue `"+ replica.key +"` is synced: "+ durationStr)
        } catch (com.exalate.api.exception.IssueTrackerException ite) {
            throw ite
        } catch (Exception e) {
            throw new com.exalate.api.exception.IssueTrackerException(e)
        }
    }
}
