import services.replication.PreparedHttpClient

class ComponentSync {
    // SCALA HELPERS
    private static <T> T await(scala.concurrent.Future<T> f) { scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
    private static <T> List<T> toList(scala.collection.Seq<T> xs) { scala.collection.JavaConverters$.MODULE$.bufferAsJavaListConverter(xs.toBuffer()).asJava() as List }
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

        // SERVICES AND EXALATE API
    static def issueLevelError(String msg) {
        new com.exalate.api.exception.IssueTrackerException(msg)
    }
    static def issueLevelError2(String msg, Throwable e) {
        new com.exalate.api.exception.IssueTrackerException(msg, e)
    }
    static play.api.inject.Injector getInjector() {
        InjectorGetter.getInjector()
    }
    /**
     * Due to changes on Exalate's API from 5.3 to 5.4 we need to consider that IJCloudGeneralSettingsRepository might have
     * a different classname such as IJCloudGneeralSettingsPersistence, so we will load the class dinamycally and catching an exception if Exalate is running
     * 5.3 or lower version
     */
    static def getGeneralSettings() {
        def classLoader = this.getClassLoader()
        def gsp
        try {
            gsp = getInjector().instanceOf(classLoader.loadClass("com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsRepository"))
        } catch(ClassNotFoundException exception) {
            gsp = getInjector().instanceOf(classLoader.loadClass("com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsPersistence"))
        }
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

    private static <P, I, R> List<R> _paginate(Long offset, Integer limit, Closure<P> getPageFn, Closure<List<I>> toItems, Closure<R> usePageFn, List<R> results) {
        def page = getPageFn(offset, limit)
        def items = toItems(page)
        def resultsToAdd = usePageFn(items)
        def newResults = ((results + resultsToAdd) as List<R>)
        if (items.size() < limit) {
            return newResults
        }
        _paginate(((offset+limit) as Long), limit, getPageFn, toItems, usePageFn, newResults)
    }
    static <P, I, R> List<R> paginate(Closure<P> getPageFn, Closure<List<I>> toItems, Closure<R> usePageFn) {
        _paginate(0L, 50, getPageFn, toItems, usePageFn, [] as List<R>)
    }

    static send(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, com.exalate.api.domain.connection.IConnection connection) {
        send(false, replica, issue, connection, null)
    }
    static send(boolean watchComponents, com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, com.exalate.api.domain.connection.IConnection connection, PreparedHttpClient httpClient) {
        if (watchComponents) {
            ComponentSync.watchComponents(httpClient)
        }
        replica.components = issue.components
        ((com.exalate.basic.domain.hubobject.v1.BasicHubProject)replica.project).components = []
    }
    static def searchAndExecute(PreparedHttpClient httpClient) { return { String jql, Closure<?> executeFn ->
        final def gs = generalSettings
        //noinspection GroovyAssignabilityCheck
        paginate(
                { Integer offset, Integer limit ->
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
                { Map<String, Object> page ->
                    if (!(page.issues instanceof List)) {
                        throw issueLevelError("Issue Search json has unrecognized structure inside each page, please contact Exalate Support: " + page)
                    }
                    page.issues as List<Map<String, Object>>
                },
                executeFn
        )
    } }

    private static watchComponents(PreparedHttpClient httpClient) {
        def ws = new JiraClient(httpClient)
        def js = new groovy.json.JsonSlurper()
        def injector = InjectorGetter.getInjector()
        def sdp = injector.instanceOf(this.getClassLoader().loadClass("com.exalate.api.persistence.scriptdata.IJScriptDataPersistence"))
        def ttRepo = injector.instanceOf(com.exalate.api.persistence.twintrace.ITwinTraceRepository.class)

        /**
         * Due to changes on Exalate's API from 5.3 to 5.4 we need to consider that ITrackerHubObjectService might be on
         * different packages, so we will load the class dinamycally and catching an exception if Exalate is running
         * 5.3 or lower version
         */
        def hos
        def classLoader = this.getClassLoader
        try {
            hos = InjectorGetter.getInjector.instanceOf(classLoader.loadClass("com.exalate.generic.services.api.ITrackerHubObjectService"))
        } catch(ClassNotFoundException exception) {
            hos = InjectorGetter.getInjector.instanceOf(classLoader.loadClass("com.exalate.replication.services.api.issuetracker.hubobject.ITrackerHubObjectService"))
        }
        def ess = injector.instanceOf(com.exalate.replication.services.api.replication.IEventSchedulerService.class)

        paginate(
                { Long offset, Integer limit ->
                    def pageResultStr = ws.http(
                            "GET",
                            "/rest/api/3/project/search",
                            ["startAt" : [offset], "maxResults": [limit]],
                            null,
                            [:] as Map<String, List<String>>
                    )
                    js.parseText(pageResultStr)
                },
                { json ->
                    json.values as List<Map<String, Object>>;
                },
                { List<Map<String, Object>> projects ->

                    projects.collect { project ->
                        paginate(
                                { Long offset, Integer limit ->
                                    def pageResultStr = ws.http(
                                            "GET",
                                            "/rest/api/3/project/${project.id}/component".toString(),
                                            ["startAt" : [offset], "maxResults": [limit]],
                                            null,
                                            [:] as Map<String, List<String>>
                                    )
                                    js.parseText(pageResultStr)
                                },
                                { json ->
                                    json.values as List<Map<String, Object>>;
                                },
                                { List<Map<String, Object>> components ->

                                    components.each { component ->
                                        def sdPropKey = "projects.${project.id}.components.${component.id}".toString()
                                        def componentJson = groovy.json.JsonOutput.toJson(component)
                                        //get from DB,
                                        def prop = sdp.getGlobal(sdPropKey)
                                        if (!prop) {
                                            sdp.upsertGlobal(sdPropKey, componentJson)
                                        } else {
                                            //compare if any different
                                            if (prop.value != componentJson) {
                                                //if they are:
                                                //store to DB
                                                sdp.upsertGlobal(sdPropKey, componentJson)
                                                //search for any issues under sync using the component, trigger sync for each
                                                searchAndExecute(httpClient)("project = \"${project.id}\" component = \"${component.id}\"") { List<Map<String, Object>> issuesWithComponent ->
                                                    issuesWithComponent.each { issueWithComponent ->
                                                        def exIssueKey = new com.exalate.basic.domain.BasicIssueKey(issueWithComponent.id as String, issueWithComponent.key as String)
                                                        def tts = ttRepo.findByLocalIssueKey(exIssueKey)
                                                        tts.each { tt ->
                                                            def hubIssue = orNull(await(hos.getHubPayloadFromTracker(exIssueKey)))
                                                            ess.schedulePairOrSyncEvent(tt.connection, exIssueKey, hubIssue, scala.Some$.MODULE$.apply(tt))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                        )
                    }


//                    items.collect {
//                        [
//                                "id" : it.id,
//                                "key" : it.key
//                        ]
//                    }
                }
        )

        // GET /rest/api/3/project/search?startAt=$offset&maxResults=$limit
        /*
{
"self": "http://your-domain.atlassian.net/rest/api/3/project/paginated?startAt=0&maxResults=2",
"nextPage": "http://your-domain.atlassian.net/rest/api/3/project/paginated?startAt=2&maxResults=2",
"maxResults": 2,
"startAt": 0,
"total": 7,
"isLast": false,
"values": [
{
  ...,
  "id": "10000",
  "key": "EX",
  "name": "Example",
  "avatarUrls": {
    ...
  },
  "projectCategory": {
    ...,
    "id": "10000",
    "name": "FIRST",
    "description": "First Project Category"
  },
  "simplified": false,
  "style": "classic"
},
{
  ...,
  "id": "10001",
  "key": "ABC",
  "name": "Alphabetical",
  "avatarUrls": {
    ...
  },
  "projectCategory": {
    ...,
    "id": "10000",
    "name": "FIRST",
    "description": "First Project Category"
  },
  "simplified": false,
  "style": "classic"
}
]
}
         */
        // GET /rest/api/3/project/{projectIdOrKey}/component?startAt=$offset&maxResults=$limit
        /*
{
"self": "http://your-domain.atlassian.net/rest/api/3/project/PR/component?startAt=0&maxResults=2",
"nextPage": "http://your-domain.atlassian.net/rest/api/3/project/PR/component?startAt=2&maxResults=2",
"maxResults": 2,
"startAt": 0,
"total": 7,
"isLast": false,
"values": [
{
  "componentBean": {
    "self": "http://your-domain.atlassian.net/rest/api/3/component/10000",
    "id": "10000",
    "name": "Component 1",
    "description": "This is a Jira component",
    "lead": {
      "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "key": "mia",
      "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "name": "mia",
      "avatarUrls": {
        "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
        "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
        "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
        "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
      },
      "displayName": "Mia Krystof",
      "active": false
    },
    "assigneeType": "PROJECT_LEAD",
    "assignee": {
      "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "key": "mia",
      "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "name": "mia",
      "avatarUrls": {
        "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
        "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
        "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
        "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
      },
      "displayName": "Mia Krystof",
      "active": false
    },
    "realAssigneeType": "PROJECT_LEAD",
    "realAssignee": {
      "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "key": "mia",
      "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "name": "mia",
      "avatarUrls": {
        "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
        "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
        "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
        "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
      },
      "displayName": "Mia Krystof",
      "active": false
    },
    "isAssigneeTypeValid": false,
    "project": "HSP",
    "projectId": 10000
  },
  "issueCount": 1,
  "description": "This is a Jira component",
  "self": "http://your-domain.atlassian.net/rest/api/3/component/10000",
  "project": "HSP",
  "projectId": 10000,
  "assignee": {
    "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "key": "mia",
    "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "name": "mia",
    "avatarUrls": {
      "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
      "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
      "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
      "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
    },
    "displayName": "Mia Krystof",
    "active": false
  },
  "lead": {
    "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "key": "mia",
    "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "name": "mia",
    "avatarUrls": {
      "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
      "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
      "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
      "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
    },
    "displayName": "Mia Krystof",
    "active": false
  },
  "assigneeType": "PROJECT_LEAD",
  "realAssigneeType": "PROJECT_LEAD",
  "realAssignee": {
    "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "key": "mia",
    "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "name": "mia",
    "avatarUrls": {
      "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
      "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
      "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
      "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
    },
    "displayName": "Mia Krystof",
    "active": false
  },
  "isAssigneeTypeValid": false,
  "name": "Component 1",
  "id": "10000"
},
{
  "componentBean": {
    "self": "http://your-domain.atlassian.net/rest/api/3/component/10000",
    "id": "10050",
    "name": "PXA",
    "description": "This is a another Jira component",
    "lead": {
      "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "key": "mia",
      "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "name": "mia",
      "avatarUrls": {
        "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
        "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
        "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
        "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
      },
      "displayName": "Mia Krystof",
      "active": false
    },
    "assigneeType": "PROJECT_LEAD",
    "assignee": {
      "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "key": "mia",
      "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "name": "mia",
      "avatarUrls": {
        "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
        "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
        "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
        "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
      },
      "displayName": "Mia Krystof",
      "active": false
    },
    "realAssigneeType": "PROJECT_LEAD",
    "realAssignee": {
      "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "key": "mia",
      "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
      "name": "mia",
      "avatarUrls": {
        "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
        "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
        "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
        "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
      },
      "displayName": "Mia Krystof",
      "active": false
    },
    "isAssigneeTypeValid": false,
    "project": "PROJECTKEY",
    "projectId": 10000
  },
  "issueCount": 5,
  "description": "This is a another Jira component",
  "self": "http://your-domain.atlassian.net/rest/api/3/component/10000",
  "project": "PROJECTKEY",
  "projectId": 10000,
  "assignee": {
    "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "key": "mia",
    "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "name": "mia",
    "avatarUrls": {
      "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
      "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
      "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
      "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
    },
    "displayName": "Mia Krystof",
    "active": false
  },
  "lead": {
    "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "key": "mia",
    "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "name": "mia",
    "avatarUrls": {
      "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
      "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
      "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
      "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
    },
    "displayName": "Mia Krystof",
    "active": false
  },
  "assigneeType": "PROJECT_LEAD",
  "realAssigneeType": "PROJECT_LEAD",
  "realAssignee": {
    "self": "http://your-domain.atlassian.net/rest/api/3/user?accountId=99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "key": "mia",
    "accountId": "99:27935d01-92a7-4687-8272-a9b8d3b2ae2e",
    "name": "mia",
    "avatarUrls": {
      "48x48": "http://your-domain.atlassian.net/secure/useravatar?size=large&ownerId=mia",
      "24x24": "http://your-domain.atlassian.net/secure/useravatar?size=small&ownerId=mia",
      "16x16": "http://your-domain.atlassian.net/secure/useravatar?size=xsmall&ownerId=mia",
      "32x32": "http://your-domain.atlassian.net/secure/useravatar?size=medium&ownerId=mia"
    },
    "displayName": "Mia Krystof",
    "active": false
  },
  "isAssigneeTypeValid": false,
  "name": "PXA",
  "id": "10050"
}
]
}
         */
    }

    static receive(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, com.exalate.api.domain.connection.IConnection connection, services.jcloud.hubobjects.NodeHelper nodeHelper, PreparedHttpClient httpClient) {
        issue.components = receive(replica.components, issue.project?.key ?: issue.projectKey, connection, nodeHelper, httpClient)
        issue
    }

    static <C extends com.exalate.api.domain.hubobject.v1_2.IHubComponent> List<com.exalate.basic.domain.hubobject.v1.BasicHubComponent> receive(List<C> remoteComponents, String localProjectKey, com.exalate.api.domain.connection.IConnection connection, services.jcloud.hubobjects.NodeHelper nodeHelper, PreparedHttpClient httpClient) {
        receive(true, remoteComponents, localProjectKey, connection, nodeHelper, httpClient)
    }
    static <C extends com.exalate.api.domain.hubobject.v1_2.IHubComponent> List<com.exalate.basic.domain.hubobject.v1.BasicHubComponent> receive(boolean matchByName, List<C> remoteComponents, String localProjectKey, com.exalate.api.domain.connection.IConnection connection, services.jcloud.hubobjects.NodeHelper nodeHelper, PreparedHttpClient httpClient) {
        try {
            def wc = new JiraClient(httpClient)
            def localExProject = nodeHelper.getProject(localProjectKey)
            if (localExProject == null) {
                throw new com.exalate.api.exception.IssueTrackerException("Contact Exalate Support - for some reason the project `$localProjectKey` is not found during component sync for remote remoteComponents `${remoteComponents.collect { remoteComponent -> ["name":remoteComponent.name, "id":remoteComponent.id, "projectKey":remoteComponent.projectKey] } }`".toString())
            }

            remoteComponents.collect { remoteComponent ->
                try {
                    def js = new groovy.json.JsonSlurper()
                    def jo = new groovy.json.JsonOutput()

                    def createComponent = {
                        def postBody = jo.toJson(
                               [
                                       "description": remoteComponent.description,
                                       "name": remoteComponent.name,
                                       "leadAccountId": nodeHelper.getUserByEmail(remoteComponent.lead?.email)?.key,
                                       "assigneeType": remoteComponent.assigneeType ? remoteComponent.assigneeType.name() : com.exalate.api.domain.hubobject.v1_2.HubAssigneeType.PROJECT_DEFAULT.name(),
                                       "project": localExProject.key
                               ]
                       )
                        httpClient.post( "/rest/api/2/component", postBody)
                        nodeHelper.getComponent(remoteComponent.name, localExProject) as com.exalate.basic.domain.hubobject.v1.BasicHubComponent
                    }
                    def updateComponent = { localJiraC ->
                        def isNameDiff = remoteComponent.name != localJiraC.name
                        def isDescriptionDiff = remoteComponent.description != localJiraC.description
                        def isLeadAccountIdDiff = remoteComponent.leadKey != localJiraC.lead?.accountId
//                        def isAssigneeTypeDiff = remoteComponent.assigneeType != localJiraC.assigneeType
                        if (!(isNameDiff || isDescriptionDiff || isLeadAccountIdDiff)) {
                            // don't update the component
                            return
                        }
                        def putBody = jo.toJson(
                                  [
                                          "description": remoteComponent.description,
                                          "name": remoteComponent.name,
                                          "leadAccountId": nodeHelper.getUserByEmail(remoteComponent.lead?.email)?.key,
                                          "assigneeType": remoteComponent.assigneeType ? remoteComponent.assigneeType.name() : com.exalate.api.domain.hubobject.v1_2.HubAssigneeType.PROJECT_DEFAULT.name(),
                                          "project": localExProject.key
                                  ]
                          )
                        httpClient.put(  "/rest/api/2/component/${localJiraC.id}".toString(), putBody)
                    }

                    def getComponent = { componentId ->
                        js.parseText(wc.http(
                                "GET",
                                "/rest/api/2/component/$componentId".toString(),
                                [:],
                                null,
                                [
                                        "Accept" : ["application/json"]
                                ]
                        ))
                    }

                    if (matchByName) {
                        def localComponentId = nodeHelper.getComponent(remoteComponent.name, nodeHelper.getProject(localProjectKey))?.id
                        if (localComponentId != null) {
                            def localJiraC = getComponent(localComponentId)
                            if (localJiraC) {
                                updateComponent(localJiraC)
                            } else {
                                createComponent()
                            }
                        } else {
                            createComponent()
                        }
                    } else {
                        createComponent()
                    }

                    nodeHelper.getComponent(remoteComponent.name, nodeHelper.getProject(localProjectKey)) as com.exalate.basic.domain.hubobject.v1.BasicHubComponent
                } catch (com.exalate.api.exception.IssueTrackerException ite) {
                    throw ite
                } catch (Exception e) {
                    throw new com.exalate.api.exception.IssueTrackerException("Contact Exalate Support - failed to receive for remote remoteComponents `${["name":remoteComponent.name, "id":remoteComponent.id, "projectKey":remoteComponent.projectKey] }` for local `$localProjectKey`".toString(), e)
                }
            }
        } catch (com.exalate.api.exception.IssueTrackerException ite) {
            throw ite
        } catch (Exception e) {
            throw new com.exalate.api.exception.IssueTrackerException("Contact Exalate Support - failed to receive for remote remoteComponents `${remoteComponents.collect { remoteComponent -> ["name":remoteComponent.name, "id":remoteComponent.id, "projectKey":remoteComponent.projectKey] } }` for local `$localProjectKey`".toString(), e)
        }
    }
}
