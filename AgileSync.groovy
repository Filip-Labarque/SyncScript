import services.replication.PreparedHttpClient

class AgileSync {
    //ERRORS
    static def relationLevelError2 = { String msg, Throwable cause ->
        new IllegalStateException(msg, cause)
    }
    static def relationLevelError = { String msg ->
        new IllegalStateException(msg)
    }
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
        def getTotalFn = AgileSync.<P, Integer> fn { P p -> getItemsFn.apply(p).size() }
        scala.collection.Seq<P> resultSeq = paginateInternal(0, limit, AgileSync.<P>seq(), nextPageFn, getTotalFn)
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
        def gsp = InjectorGetter.getInjector().instanceOf(com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsPersistence.class)
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
                "key": story.urn as String
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
    static def sendRank(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, services.replication.PreparedHttpClient httpClient) {
        final def issueKey = new com.exalate.basic.domain.BasicIssueKey(issue.id as Long, issue.key)

        final def gs = generalSettings

        final def fieldsJson = getFieldsJson(httpClient).findAll { it.schema instanceof Map }
        final def rankCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-lexo-rank" }

        def getAllBoards = { String projectKey ->
            //noinspection GroovyAssignabilityCheck
            paginate(
                    50,
                    fn2 { Integer offset, Integer limit ->
                        def response
                        def queryParams = seq(
                                pair("startAt", offset as String),
                                pair("maxResults", limit as String)
                        )
                        if (projectKey != null) {
                            queryParams = seqPlus(queryParams, pair("projectKeyOrId", projectKey))
                        }
                        try {
                            response = await(await(httpClient.authenticate(
                                    none(String.class),
                                    httpClient
                                            .ws()
                                            .url(jiraCloudUrl+"/rest/agile/1.0/board")
                                            .withQueryString(queryParams)
                                            .withMethod("GET"),
                                    gs
                            )).get())
                        } catch (Exception e) {
                            throw issueLevelError2("Unable to get the boards, please contact Exalate Support: " + e.message, e)
                        }
                        if (response.status() != 200) {
                            throw issueLevelError("Can not get the boards (status "+ response.status() +"), please contact Exalate Support: "+ response.body())
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
        def getIssuesOnBoard = { Map<String, Object> board ->
            final def boardId = board.id as Long
            //noinspection GroovyAssignabilityCheck
            def issues = paginate(
                    50,
                    fn2 { Integer offset, Integer limit ->
                        def response
                        try {
                            response = await(await(httpClient.authenticate(
                                    none(String.class),
                                    httpClient
                                            .ws()
                                            .url(jiraCloudUrl+"/rest/agile/1.0/board/"+boardId+"/issue")
                                            .withQueryString(seq(
                                            pair("startAt", offset as String),
                                            pair("maxResults", limit as String),
                                            pair("fields", "id,key")
                                    ))
                                            .withMethod("GET"),
                                    gs
                            )).get())
                        } catch (Exception e) {
                            throw issueLevelError2("Unable to get the issues on board, please contact Exalate Support: " + e.message, e)
                        }
                        if (response.status() != 200) {
                            throw issueLevelError("Can not get the issues on board (status "+ response.status() +"), please contact Exalate Support: "+ response.body())
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
      "expand": "names,schema",
      "startAt": 0,
      "maxResults": 50,
      "total": 1,
      "issues": [
        {
          "expand": "",
          "id": "10001",
          "self": "http://www.example.com/jira/rest/agile/1.0/board/92/issue/10001",
          "key": "HSP-1",
          "fields": {}
        }
      ]
    }
*/
                        if (!(resultJson instanceof Map)) {
                            throw issueLevelError("Issues on board json has unrecognized structure, please contact Exalate Support: " + resultStr)
                        }
                        resultJson as Map<String, Object>
                    },
                    fn { Map<String, Object> page -> (page.issues as List<Map<String, Object>>) }
            )
            def issuekeys = issues.collect { i -> [
                    "id": i.id as Long,
                    "key": i.urn as String
            ]}
            issuekeys
        }
        def getBoards = { com.exalate.api.domain.IIssueKey anIssue ->
            def allBoards = getAllBoards(issue.project.key)
            def boardsAndIssuesForIssue = allBoards
                    .collect { board -> pair(board, getIssuesOnBoard(board)) }
                    .findAll { scala.Tuple2<Map<String, Object>, List<Map<String, Object>>> boardToIssuesTuple ->
                def issuesOnBoard = boardToIssuesTuple._2
                issuesOnBoard.any { i -> (anIssue.id as Long) == (i.id as Long) }
            }
            boardsAndIssuesForIssue.collect { scala.Tuple2<Map<String, Object>, List<Map<String, Object>>> boardToIssuesTuple ->
                def b = boardToIssuesTuple._1
                def issuesOnBoard = boardToIssuesTuple._2
                [
                        "id": b.id as Long,
                        "name": b.name as String,
                        "type": b.type as String,
                        "issues": issuesOnBoard.collect { i -> [
                                "id": i.id as Long,
                                "key": i.key as String
                        ]}
                ]
            }
        }
// ranking
        def getRankContext = {
            // get board for the current issue
            def boards = getBoards(issueKey)

            // get issues on board (ordered by rank)
            def rankContext = [
                    "validOn": new Date().time,
                    "boards": boards
            ]
            rankContext
        }


        if (issue.customFields[rankCfJson.schema.customId as String]?.value != null) {
            replica.customKeys."rankContext" = getRankContext()
        }
    }
    static def sendEpic(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, services.replication.PreparedHttpClient httpClient) {
        def issueKey = new com.exalate.basic.domain.BasicIssueKey(issue.id as Long, issue.key)
        def issueLevelError = { String msg ->
            new com.exalate.api.exception.IssueTrackerException(msg)
        }
        def issueLevelError2 = { String msg, Throwable e ->
            new com.exalate.api.exception.IssueTrackerException(msg, e)
        }
        final def gs = getGeneralSettings()

        final def fieldsJson = getFieldsJson(httpClient).findAll { it.schema instanceof Map }
        final def epicLinkCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-link" }
        final def epicNameCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-label" }
        final def epicColourCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-color" }
        final def epicStatusCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-status" }
        final def epicThemeCfJson = fieldsJson.findAll { it.schema.custom == "com.atlassian.jira.plugin.system.customfieldtypes:labels" }.find { it.name == "Epic/Theme" }

        def search = searchFn(httpClient)

        def getIssueByIdOrKey = { idOrKey ->
            def response
            try {
                //noinspection GroovyAssignabilityCheck
                response = await(await(httpClient.authenticate(
                        none(),
                        httpClient
                                .ws()
                                .url(jiraCloudUrl+"/rest/api/2/issue/"+idOrKey)
                                .withMethod("GET"),
                        gs
                )).get())
            } catch (Exception e) {
                throw issueLevelError2("Unable to get the issue "+ idOrKey +", please contact Exalate Support: " + e.message, e)
            }
            if (response.status() != 200) {
                throw issueLevelError("Can not get the issue "+ idOrKey +" (status "+ response.status() +"), please contact Exalate Support: "+ response.body())
            }
            def resultStr = response.body() as String
            def s = new groovy.json.JsonSlurper()
            def resultJson
            try {
                resultJson = s.parseText(resultStr)
            } catch (Exception e) {
                throw issueLevelError2("Can not parse the issue "+ idOrKey +" json, please contact Exalate Support: " + resultStr, e)
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
          "self": "http://www.example.com/jira/rest/agile/1.0/board/92/issue/10001",
          "key": "HSP-1",
          "fields": {}
        }
      ]
    }
*/
            if (!(resultJson instanceof Map)) {
                throw issueLevelError("Issue "+idOrKey+" json has unrecognized structure, please contact Exalate Support: " + resultStr)
            }
            resultJson as Map<String, Object>
        }

        def toRestIssueKeyInternal = { exIssueKey ->
            [
                    "id" : exIssueKey?.id,
                    "key": exIssueKey?.getURN(),
            ]
        }

        def toEpicContext = { epicIssueKey, storyIssueKeys ->
            [
                    "epic"   : toRestIssueKeyInternal(epicIssueKey),
                    "stories": storyIssueKeys
            ]
        }

        def getStories = { com.exalate.basic.domain.BasicIssueKey epicExIssueKey ->
            def epicLinkSearchClauseNames = epicLinkCfJson.clauseNames as List<String>
            final def epicLinkSearchClauseName = epicLinkSearchClauseNames[0]
            def jql = epicLinkSearchClauseName + " = " + epicExIssueKey.URN

            search(jql)
        }


        if (epicLinkCfJson != null && issue.customFields[epicLinkCfJson?.schema?.customId as String].value != null) {
            final def thisIssueJson = getIssueByIdOrKey(issueKey.id)
            def epicLinkKey = (thisIssueJson.fields[epicLinkCfJson.key as String]) as String
            if (epicLinkKey == null) {
                throw issueLevelError("Can not find the epic link ("+ epicLinkCfJson.key +") for issue `"+ issueKey.URN +"` ("+ issueKey.id +"), please contact Exalate Support: "+ thisIssueJson)
            }
            def epic = getIssueByIdOrKey(epicLinkKey)
            def epicLinkId = epic.id as Long
            replica.customKeys."Epic Link" = ["id": epicLinkId, "key": epicLinkKey]
            def exEpicIssueKey = new com.exalate.basic.domain.BasicIssueKey(epicLinkId, epicLinkKey)
            def stories = getStories(exEpicIssueKey)
            replica.customKeys."epicContext" = toEpicContext(exEpicIssueKey, stories)
        }

        if (epicNameCfJson != null && issue.customFields[epicNameCfJson?.schema?.customId as String]?.value != null) {
            def stories = getStories(issueKey)
            replica.customKeys."Epic Name" = issue.customFields[epicNameCfJson?.schema?.customId as String].value
            if(epicThemeCfJson != null) {
                replica.customFields."Epic/Theme" = issue.customFields[epicThemeCfJson?.schema?.customId as String]
            }
            if (issue.customFields[epicColourCfJson?.schema?.customId as String]?.value != null) {
                replica.customFields."Epic Colour" = issue.customFields[epicColourCfJson?.schema?.customId as String]
            }
            if (issue.customFields[epicStatusCfJson?.schema?.customId as String]?.value != null) {
                replica.customFields."Epic Status" = issue.customFields[epicStatusCfJson?.schema?.customId as String]
            }
            replica.customKeys."epicContext" = toEpicContext(issueKey, stories)
        }
    }

    // RECEIVE
    static def receiveEpicName(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, services.jcloud.hubobjects.NodeHelper nodeHelper, services.replication.PreparedHttpClient httpClient) {
        receiveEpicBeforeCreation(replica, issue, nodeHelper, httpClient)
    }
    static def receiveEpicBeforeCreation(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, services.jcloud.hubobjects.NodeHelper nodeHelper, services.replication.PreparedHttpClient httpClient) {
        def issueLevelError = { String msg ->
            new com.exalate.api.exception.IssueTrackerException(msg)
        }

        final def fieldsJson = getFieldsJson(httpClient).findAll { it.schema instanceof Map }
        final def epicNameCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-label" }
        final def epicColourCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-color" }
        final def epicStatusCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-status" }
        final def epicThemeCfJson = fieldsJson.findAll { it.schema.custom == "com.atlassian.jira.plugin.system.customfieldtypes:labels" }.find { it.name == "Epic/Theme" }

        if (replica.customKeys."Epic Name" != null) {
            def cf = issue.customFields[epicNameCfJson.schema.customId as String]
            if (cf == null) {
                throw issueLevelError("Can not find the `Epic Name` custom field by id `"+epicNameCfJson.schema.customId+"`, please contact Exalate Support")
            }
            cf.value = replica.customKeys."Epic Name"
            if (replica.customFields."Epic/Theme"?.value && epicThemeCfJson){
                def epicThemeCf = issue.customFields[epicThemeCfJson.schema.customId as String]
                epicThemeCf?.value = replica.customFields."Epic/Theme"?.value
            }
        }
        if (replica.customFields."Epic Colour"?.value) {
            def cf = issue.customFields[epicColourCfJson.schema.customId as String]
            if (cf == null) {
                throw issueLevelError("Can not find the `Epic Colour` custom field by id `"+epicColourCfJson.schema.customId+"`, please contact Exalate Support")
            }
            cf.value = replica.customFields."Epic Colour".value
        }
        if (replica.customFields."Epic Status"?.value) {
            def cf = issue.customFields[epicStatusCfJson.schema.customId as String]
            if (cf == null) {
                throw issueLevelError("Can not find the `Epic Status` custom field by id `"+epicStatusCfJson.schema.customId+"`, please contact Exalate Support")
            }
            def option = nodeHelper.getOption(issue, epicStatusCfJson.name as String, replica.customFields."Epic Status"?.value?.value as String)
            if (option) {
                cf.value = [["id":option.id as String]]
            }
        }
    }
    static def receiveEpicLinks(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, services.jcloud.hubobjects.NodeHelper nodeHelper, services.replication.PreparedHttpClient httpClient) {
        receiveEpicAfterCreation(replica, issue, nodeHelper, httpClient)
    }
    static def receiveEpicAfterCreation(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, services.jcloud.hubobjects.NodeHelper nodeHelper, services.replication.PreparedHttpClient httpClient) {
        def issueLevelError = { String msg ->
            new com.exalate.api.exception.IssueTrackerException(msg)
        }
        def issueLevelError2 = { String msg, Throwable e ->
            new com.exalate.api.exception.IssueTrackerException(msg, e)
        }
        final def gs = getGeneralSettings()

        final def fieldsJson = getFieldsJson(httpClient).findAll { it.schema instanceof Map }
        final def epicLinkCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-link" }

        def updateIssue = { com.exalate.basic.domain.BasicIssueKey issueKeyToUpdate, Map<String, Object> json ->
            //  PUT /rest/api/2/issue/{issueIdOrKey}
            /*
            {
              "fields": {
                "summary": "This is a shorthand for a set operation on the summary field",
                "customfield_10010": 1,
                "customfield_10000": "This is a shorthand for a set operation on a text custom field"
              }
            }
             */
            def jsonStr = groovy.json.JsonOutput.toJson(json)
            def response
            try {
                //noinspection GroovyAssignabilityCheck
                httpClient.put("/rest/api/2/issue/"+issueKeyToUpdate.id, jsonStr)

            } catch (Exception e) {
                throw issueLevelError2("Unable to update issue `"+ issueKeyToUpdate.URN +"`, please contact Exalate Support:  \n" +
                        "PUT "+jiraCloudUrl+"/rest/api/2/issue/"+issueKeyToUpdate.id+"\nBody: "+jsonStr+"\nError Message:"+ e.message, e)
            }
        }


//def jProject = pm.getProjectObj(project.id)

//def proxyAppUser = nserv.getProxyUser()
        def getLocalIssueKey = { remoteSuspectId, currentIssue ->
            if(replica.id.equals((remoteSuspectId as Long) as String)) {
                return new com.exalate.basic.domain.BasicIssueKey(currentIssue.id as Long, currentIssue.key)
            }
            nodeHelper.getLocalIssueKeyFromRemoteId(remoteSuspectId as Long)
        }


        def getLocalEpicIssueKey = { currentIssue ->
            def epicContext = replica.customKeys."epicContext"
            if (!(epicContext instanceof Map)) {
                return null
            }
            def remoteEpicId = epicContext.epic.id
            getLocalIssueKey(remoteEpicId, currentIssue)
        }

        if (issue.id == null) {
            throw new com.exalate.api.exception.IssueTrackerException(""" It seems, that the issue has not been created yet. Please change your create processor to create the issue and populate the `issue.id` property before using the `AgileSync.receiveEpicAfterCreation(...)` """.toString())
        }
        // try to link all the stories to the epic:
        def localEpicJissue = getLocalEpicIssueKey(issue)
        if (localEpicJissue != null) {
            replica
                    .customKeys
                    ."epicContext"
                    .stories
                    .collect { story -> getLocalIssueKey(story.id, issue) }
                    .findAll { it != null }
                    .each { localStory ->
//            log.debug("linking the localStory `"+localStory.key+"` to epic `"+ localEpicJissue.key +"` for the issue `"+ jIssue.key +"` for remote issue `"+ replica.key +"`")
                updateIssue(
                        new com.exalate.basic.domain.BasicIssueKey(localStory.id as Long, localStory.urn),
                        [
                                "fields" : [
                                        (epicLinkCfJson.key) : localEpicJissue.urn
                                ]
                        ]
                )
            }
        }
        def epicLinkCfIdStr = epicLinkCfJson?.schema?.customId as String
        def epicLinkCfValueInternal = issue.customFields[epicLinkCfIdStr].value
        issue.customFields.remove(epicLinkCfIdStr)
        if (replica.customKeys."epicContext" == null &&
                epicLinkCfValueInternal != null) {
            issue.customFields[epicLinkCfIdStr].value = null
        }
    }
    static def receiveRank(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, services.jcloud.hubobjects.NodeHelper nodeHelper, services.replication.PreparedHttpClient httpClient) {
        final def jIssue = issue
        if (issue.id == null) {
            throw new com.exalate.api.exception.IssueTrackerException(""" It seems, that the issue has not been created yet. Please change your create processor to create the issue and populate the `issue.id` property before using the `AgileSync.receiveRank(...)` """.toString())
        }
        def issueLevelError = { String msg ->
            new com.exalate.api.exception.IssueTrackerException(msg)
        }
        def issueLevelError2 = { String msg, Throwable e ->
            new com.exalate.api.exception.IssueTrackerException(msg, e)
        }

        
        def rank = { com.exalate.basic.domain.BasicIssueKey firstIssue, List<com.exalate.basic.domain.BasicIssueKey> rest ->
            final Map<String, Object> json = [
                    "rankAfterIssue": firstIssue.id as Long,
                    "issues": rest.collect { it.id as Long }
            ]
            final def jsonStr = groovy.json.JsonOutput.toJson(json)
            def response
            try {
                response = await(httpClient.thisJira(
                        "/rest/agile/1.0/issue/rank",
                        "PUT",
                        "application/json",
                        jsonStr
                ).execute())
            } catch (Exception e) {
                throw issueLevelError2(
                        "Unable to rank issues, please contact Exalate Support: " +
                                "\nRequest: PUT /rest/agile/1.0/issue/rank" +
                                "\nRequest Body: "+ jsonStr +
                                "\nError: " + e.message,
                        e
                )
            }
            if (response.status() != 204 && response.status() != 207) {
                throw issueLevelError("Can not rank issues (status "+ response.status() +"), please contact Exalate Support: " +
                        "\nRequest: PUT /rest/agile/1.0/issue/rank" +
                        "\nRequest Body: "+ jsonStr +
                        "\nResponse: "+ response.body()
                )
            }
        }
        def rankMultiple = { List<com.exalate.basic.domain.BasicIssueKey> issues ->
            if (issues.empty) {
                return
            }
            def partitions = issues.collate(50)
            partitions.inject(null as com.exalate.basic.domain.BasicIssueKey) { com.exalate.basic.domain.BasicIssueKey head, List<com.exalate.basic.domain.BasicIssueKey> partition ->
                def h = head ?: partition.head()
                def t = head != null ?
                        partition :
                        partition.tail()
                def nextH = partition.last()
                rank(h, t)
                nextH
            }
        }

        def getLocalIssueKey = { remoteSuspectId, currentIssue ->
            if(replica.id.equals((remoteSuspectId as Long) as String)) {
                return new com.exalate.basic.domain.BasicIssueKey(currentIssue.id as Long, currentIssue.key)
            }
            nodeHelper.getLocalIssueKeyFromRemoteId(remoteSuspectId as Long)
        }

        def rankAll = { Map<String, Object> rCtx ->
            if (rCtx == null) {
                return
            }
            // for each board:
            rCtx.boards?.each { b ->
//        def rankCfId = rankCfJson.schema.customId as Long
                def jIssues = b.issues
                        .collect { i -> getLocalIssueKey(i.id, jIssue) as com.exalate.basic.domain.BasicIssueKey }
                        .findAll { i -> i != null }
                if (!jIssues.empty) {
                    // take the first issue to rank, check if it is first, re-rank it
//            def firstIssue = jIssues.first()
//            rankFirst(localBoard, firstIssue, rankCfId)
//            def otherIssues = jIssues.tail()
//            rankAfter(localBoard, firstIssue, otherIssues, rankCfId)
                    rankMultiple(jIssues)
                }
            }
        }

        // rank all issues on the board
        rankAll(replica.customKeys."rankContext" as Map<String, Object>)
    }
    static def receiveSprints(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, services.jcloud.hubobjects.NodeHelper nodeHelper, services.replication.PreparedHttpClient httpClient) {
        receiveSprints(defaultOnNoBoardFound(), replica, issue, nodeHelper, httpClient)
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
            if (response.status() == 404) {
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
        def deleteSprint = { Long sprintId ->
//   DELETE /rest/agile/1.0/sprint/{sprintId}
            def response
            try {
                response = await(httpClient.thisJira(
                        "/rest/agile/1.0/sprint/"+sprintId,
                        "DELETE",
                        "application/json",
                        null
                ).execute())
            } catch (Exception e) {
                throw issueLevelError2(
                        "Unable to delete the sprint `"+ sprintId +"`, please contact Exalate Support: " +
                                "\nRequest: DELETE /rest/agile/1.0/sprint/"+sprintId +
                                "\nError: " + e.message,
                        e
                )
            }
            if (response.status() != 204) {
                throw issueLevelError("Can not move delete the sprint `"+ sprintId +"` (status "+ response.status() +"), please contact Exalate Support: " +
                        "\nRequest: DELETE /rest/agile/1.0/sprint/"+sprintId +
                        "\nResponse: "+ response.body()
                )
            }
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
        def updateSprintPartial = { Long sprintId, Map<String, Object> json ->
            /*
            POST /rest/agile/1.0/sprint/{sprintId}

        Sprints that are in a closed state cannot be updated.
        A sprint can be started by updating the state to ‘active’. This requires the sprint to be in the ‘future’ state and have a startDate and endDate set.
        A sprint can be completed by updating the state to ‘closed’. This action requires the sprint to be in the ‘active’ state. This sets the completeDate to the time of the request.
        Other changes to state are not allowed.
        The completeDate field cannot be updated manually.
             */
            final def jsonStr = groovy.json.JsonOutput.toJson(json)
            def response
            try {
                response = await(httpClient.thisJira(
                        "/rest/agile/1.0/sprint/"+sprintId,
                        "POST",
                        "application/json",
                        jsonStr
                ).execute())
            } catch (Exception e) {
                throw issueLevelError2(
                        "Unable to partially update sprint, please contact Exalate Support: " +
                                "\nRequest: POST /rest/agile/1.0/sprint/"+sprintId +
                                "\nRequest Body: "+ jsonStr +
                                "\nError: " + e.message,
                        e
                )
            }
            if (response.status() != 200) {
                throw issueLevelError("Can not partially update sprint (status "+ response.status() +"), please contact Exalate Support: " +
                        "\nRequest: POST /rest/agile/1.0/sprint/"+sprintId +
                        "\nRequest Body: "+ jsonStr +
                        "\nResponse: "+ response.body()
                )
            }

        }
        def startSprint = {  Long sprintId, Long startDate, Long endDate ->
            updateSprintPartial(sprintId, [
                    "startDate" : toSprintDateStr(startDate),
                    "endDate" : toSprintDateStr(endDate),
                    "state" : "active"
            ])
        }
        def completeSprint = { Long sprintId -> updateSprintPartial(sprintId, ["state":"closed"]) }
        def getLocalIssueKeyByRemote = { Long remoteIssueId -> { Long currentRemoteId, String currentRemoteKey ->
            if (remoteIssueId == currentRemoteId) {
                return localExIssueKey
            }
            def localIssueKey = issueInfoCache[remoteIssueId as Long]
            if(localIssueKey == null){
                localIssueKey = nodeHelper.getLocalIssueKeyFromRemoteId(remoteIssueId)
                issueInfoCache[remoteIssueId as Long] = localIssueKey
            }
            if (localIssueKey == null) {
                return null
            }
            return localIssueKey
        } }
        def search = searchFn(httpClient)
        def getIssuesInSprint = { Long sprintId ->
            def sprintClauseNames = sprintCfJson.clauseNames as List<String>
            def clauseName = sprintClauseNames[0]
            final def jql = clauseName + " = " + sprintId
            search(jql)
        }

        try {

            // apply sprints
/*
    1. iterate over sprints in context:
    1.0. check whether all issues are already there
    1.1. closed sprints: lookup,
        if not, delete, create again, put all synced issues, complete
    1.2. active sprint: lookup,
        if future
            1.2.1 put all synced issues
            1.2.2 activate
        if active
            1.2.3 check whether all issues are already there
                if there -> do nothing
                if not there ->
                    1.2.3.1 delete sprint
                    1.2.3.2 create a new one
                    1.2.3.3 put all issues into it
                    1.2.3.4 activate
        if closed
            1.2.4 throw an exception
        if none
            1.2.5 create sprint
            1.2.6 put all synced issues
            1.2.7 activate
    1.3 future sprint: lookup
        if future
            1.3.1 put all synced issues
            1.3.2 activate
        if active
            1.3.3 check whether all issues are already there
                if there -> do nothing
                if not there ->
                    1.3.3.1 delete sprint
                    1.3.3.2 create a new one
                    1.3.3.3 put all issues into it
                    1.3.3.4 activate
        if closed
            1.3.4 throw an exception
        if none
            1.3.5 create sprint
            1.3.6 put all synced issues
            1.3.7 activate
 */

            /*
                Compare local sprints with remote sprints:
                   * if there are local sprints which are not in the remote SprintContext, we handle get them and handle:
                         ** if the sprint is active or future - move issue into backlog
                         ** if a sprint is closed - do nothing
            */

            def projectKey = issue.project.key ?: issue.projectKey
            // apply sprints
/*
    1. iterate over sprints in context:
    1.0. check whether all issues are already there
    1.1. closed sprints: lookup,
        if closed, delete, create again, put all synced issues, start, complete
        if active, delete, create again, remove all unnecessary to backlog, put all synced issues, start, complete
        if future, remove all unnecessary to backlog, put all synced issues, start, complete
        if none, create, put all synced issues, start, complete
    1.2. active sprint: lookup,
        if future
            1.2.1 put all synced issues
            1.2.2 activate
        if active
            1.2.3 check whether all issues are already there
                if there -> do nothing
                if not there ->
                    1.2.3.1 delete sprint
                    1.2.3.2 create a new one
                    1.2.3.3 put all issues into it
                    1.2.3.4 activate
        if closed
            1.2.4 throw an exception
        if none
            1.2.5 create sprint
            1.2.6 put all synced issues
            1.2.7 activate
    1.3 future sprint: lookup
        if future
            1.3.1 put all synced issues
            1.3.2 activate
        if active
            1.3.3 check whether all issues are already there
                if there -> do nothing
                if not there ->
                    1.3.3.1 delete sprint
                    1.3.3.2 create a new one
                    1.3.3.3 put all issues into it
                    1.3.3.4 activate
        if closed
            1.3.4 throw an exception
        if none
            1.3.5 create sprint
            1.3.6 put all synced issues
            1.3.7 activate
 */

            /*
                Compare local sprints with remote sprints:
                   * if there are local sprints which are not in the remote SprintContext, we handle get them and handle:
                         ** if the sprint is active or future - move issue into backlog
                         ** if a sprint is closed - do nothing
            */

            def remoteSprintContext = replica.customKeys.sprintContext ?: []

            //Get all sprints in the local project
            def allLocalSprints = getLocalSprints(projectKey)
            def localSprints = (issue.customFields[sprintCfJson.schema.customId as String]?.value ?: []).collect { localSprint ->
                allLocalSprints.find { s -> (s.id as Long) == (localSprint.id as Long) }
            }.findAll { it != null }

            //FIXME: no lookup by id, while for sprint creation there is one
            //Get local sprints not in the remote Sprint Context
            def localSprintsNotInTheRemoteContext = localSprints.findAll { Map<String, Object> localSprint ->
                !remoteSprintContext.any { remoteSprint ->
                    def sprintJson = remoteSprint.sprint as Map<String, Object>;
                    def remoteSprintName = sprintJson.name as String;
                    localSprint.name as String == remoteSprintName
                }
            }

            //if such local sprints exist -> move an issue in the backlog if sprint status is "active" or "future"
            if (localSprintsNotInTheRemoteContext.any { Map<String, Object> localSprint ->
                //if the status of such local sprint is "active" or "future" - move the issue in the backlog and stop iterating
                localSprint.state == "active" || localSprint.state == "future"
            }) {
                moveToBacklog([localExIssueKey])
            }

            replica.customKeys.sprintContext?.each { Map<String, Object> ctx ->
                def remoteSprintInternal = ctx.sprint as Map<String, Object>;
                def localProjectKey = issue.project.key as String;
                def localSprint = getSprintByRemote(remoteSprintInternal.name as String, remoteSprintInternal.id as Long, localProjectKey);
                def localSprintId = localSprint?.id as Long;
                def issuesInSprint = ctx.issues as List<Map<String, Object>>;
                def syncedIssues = issuesInSprint
                        .collect { i -> getLocalIssueKeyByRemote(i.id as Long)(replica.id as Long, replica.key as String) }
                        .findAll()

                def board = getAllBoards(null, "scrum", localProjectKey).find()
                if (board == null) {
                    board = onNoBoardFound()
                    if (board == null) {
                        return
                    }
                }
                final def boardId = board.id as Long
                if (remoteSprintInternal.state == "closed") {
                    if (localSprint != null && localSprint.state == "closed") {
                        def inSprint = getIssuesInSprint(localSprintId).collect { i -> new com.exalate.basic.domain.BasicIssueKey(i.id as Long, i.key as String) }
                        def nonRemoteInSprint = inSprint - syncedIssues
                        def issuesToKeepInSprint = (syncedIssues + nonRemoteInSprint) as List

                        deleteSprint(localSprintId)
                        removeSprintIdMapping(localSprintId as String)

                        localSprint = createSprint(remoteSprintInternal, boardId)
                        localSprintId = localSprint.id as Long
                        addSprintIdMapping(localSprintId as String, remoteSprintInternal.id as String)

                        moveToSprintMultiple(localSprintId, issuesToKeepInSprint)
                        startSprint(localSprintId, remoteSprintInternal.startDateLong as Long, remoteSprintInternal.endDateLong as Long)
                        completeSprint(localSprintId)
                    } else if (localSprint != null && localSprint.state == "active") {
                        moveToSprintMultiple(localSprintId, syncedIssues)
                        completeSprint(localSprintId)
                    } else if (localSprint != null && localSprint.state == "future") {
                        moveToSprintMultiple(localSprintId, syncedIssues)
                        startSprint(localSprintId, remoteSprintInternal.startDateLong as Long, remoteSprintInternal.endDateLong as Long)
                        completeSprint(localSprintId)
                    } else if (localSprint == null) {

                        localSprint = createSprint(remoteSprintInternal, boardId)
                        localSprintId = localSprint.id as Long
                        addSprintIdMapping(localSprintId as String, remoteSprintInternal.id as String)

                        moveToSprintMultiple(localSprintId, syncedIssues)
                        startSprint(localSprintId, remoteSprintInternal.startDateLong as Long, remoteSprintInternal.endDateLong as Long)
                        completeSprint(localSprintId)
                    }
                } else if (remoteSprintInternal.state == "active") {
                    if (localSprint != null) {
                        if (localSprint.state == "closed") {
                            // do nothing with the sprint since this might be information about it's older version
                        } else if (localSprint.state == "active") {
                            moveToSprintMultiple(localSprintId, syncedIssues)
                        } else if (localSprint.state == "future") {
                            moveToSprintMultiple(localSprintId, syncedIssues)
                            startSprint(localSprintId, remoteSprintInternal.startDateLong as Long, remoteSprintInternal.endDateLong as Long)
                        }
                    } else {

                        localSprint = createSprint(remoteSprintInternal, boardId)
                        localSprintId = localSprint.id as Long
                        addSprintIdMapping(localSprintId as String, remoteSprintInternal.id as String)

                        moveToSprintMultiple(localSprintId, syncedIssues)
                        startSprint(localSprintId, remoteSprintInternal.startDateLong as Long, remoteSprintInternal.endDateLong as Long)
                    }
                } else if (remoteSprintInternal.state == "future") {
                    if (localSprint != null) {
                        if (localSprint.state == "closed") {
                            // do nothing with the sprint since this might be information about it's older version
                        } else if (localSprint.state == "active") {
                            // do nothing with the sprint since this might be information about it's older version
                        } else if (localSprint.state == "future") {
                            moveToSprintMultiple(localSprintId, syncedIssues)
                        }
                    } else {

                        localSprint = createSprint(remoteSprintInternal, boardId)
                        localSprintId = localSprint.id as Long
                        addSprintIdMapping(localSprintId as String, remoteSprintInternal.id as String)

                        moveToSprintMultiple(localSprintId, syncedIssues)
                    }
                }
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
