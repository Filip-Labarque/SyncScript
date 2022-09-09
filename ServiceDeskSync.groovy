import services.replication.PreparedHttpClient

class ServiceDeskSync {
    static def sendOrganizations(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, PreparedHttpClient httpClient) {
        if (!issue.customFields."Organizations"?.value || issue.customFields."Organizations".value.empty) {
            return
        }
        def allLocalOrg = getAllOrganizations(httpClient)
        replica.customKeys."Organizations" = issue.customFields."Organizations"?.value?.collect { orgId -> [ "name":allLocalOrg.find { it.id as Long == orgId as Long }?.name, "customers":[]] }
    }

    private static def getAllOrganizations(PreparedHttpClient httpClient) {
        def injector = InjectorGetter.getInjector()

        def issueLevelError = { String msg ->
            new com.exalate.api.exception.IssueTrackerException(msg)
        }
        def issueLevelError2 = { String msg, Throwable e ->
            new com.exalate.api.exception.IssueTrackerException(msg, e)
        }

        def fn = { Closure<?> closure ->
            new scala.runtime.AbstractFunction1<Object, Object>() {
                @Override
                Object apply(Object p) {
                    return closure.call(p)
                }
            }
        }
        def fn2 = { Closure<?> closure ->
            new scala.runtime.AbstractFunction2<Object, Object, Object>() {
                @Override
                Object apply(Object p1, Object p2) {
                    return closure.call(p1, p2)
                }
            }
        }
        def await = { scala.concurrent.Future<?> f -> scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
        def orNull = { scala.Option<?> opt -> opt.isDefined() ? opt.get() : null }
        def none = { scala.Option$.MODULE$.<?>empty() }
        def pair = { l, r -> scala.Tuple2$.MODULE$.<?, ?>apply(l, r) }
        def seq =  { ... ts ->
            def list = Arrays.asList(ts)
            def scalaBuffer = scala.collection.JavaConversions.asScalaBuffer(list)
            scalaBuffer.toSeq()
        }
        def seqPlus = { scala.collection.Seq<?> tsLeft, ... tsRight ->
            def list = Arrays.asList(tsRight)
            def scalaBuffer = scala.collection.JavaConversions.asScalaBuffer(list)
            scala.collection.Seq$.MODULE$
                    .newBuilder()
                    .$plus$plus$eq(tsLeft)
                    .$plus$plus$eq(scalaBuffer)
                    .result()
        }
        def _paginate2
        _paginate2 = { Integer offset, Integer limit, scala.collection.Seq<?> result, scala.runtime.AbstractFunction2<Integer, Integer, ?> nextPageFn, scala.runtime.AbstractFunction1<?, Integer> getTotalFn ->
            def page = nextPageFn.apply(offset, limit)
            def total = getTotalFn.apply(page)
            def last = total < limit
            def newResult = seqPlus(result, page)
            if (last) {
                newResult
            } else {
                _paginate2(offset + limit, limit, newResult, nextPageFn, getTotalFn)
            }
        }
        def paginate2 = { Integer limit, scala.runtime.AbstractFunction2<Integer, Integer, ?> nextPageFn, scala.runtime.AbstractFunction1<?, Integer> getTotalFn ->
            scala.collection.Seq<?> resultSeq = _paginate2(0, limit, seq(), nextPageFn, getTotalFn)
            scala.collection.JavaConversions.bufferAsJavaList(resultSeq.toBuffer())
        }

        def getGeneralSettings = {
            def gsp = InjectorGetter.getInjector().instanceOf(com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsPersistence.class)
            def gsOpt = await(gsp.get())
            def gs = orNull(gsOpt)
            gs
        }
        final def gs = getGeneralSettings()

        def removeTailingSlash = { String str -> str.trim().replace("/+\$","") }
        final def jiraCloudUrl = removeTailingSlash(gs.issueTrackerUrl)
        def getAllOrganizations = {
            //noinspection GroovyAssignabilityCheck
            def allOrganizationsResultJsons = paginate2(
                    50,
                    fn2 { Integer offset, Integer limit ->
                        def organizationsResponse
                        try {
                            organizationsResponse = await(await(httpClient.authenticate(
                                    none(),
                                    httpClient
                                            .ws()
                                            .url(jiraCloudUrl+"/rest/servicedeskapi/organization")
                                            .withQueryString(seq(
                                            pair("start", offset as String),
                                            pair("maxResults", limit as String)
                                    ))
                                            .withMethod("GET"),
                                    gs
                            )).get())
                        } catch (Exception e) {
                            throw issueLevelError2("Unable to get Organizations, please contact Exalate Support: " +
                                    "\nRequest: GET /rest/servicedeskapi/organization?startAt="+ offset +"&maxResults="+ limit +
                                    "\nError: " + e.message, e)
                        }
                        if (organizationsResponse.status() != 200) {
                            throw issueLevelError("Can not get Organizations (status "+ organizationsResponse.status() +"), please contact Exalate Support: " +
                                    "\nRequest: GET /rest/servicedeskapi/organization?&startAt="+ offset +"&maxResults="+ limit +
                                    "\nResponse: "+ organizationsResponse.body())
                        }
                        def result = organizationsResponse.body()
                        def s = new groovy.json.JsonSlurper()
                        def resultJson
                        try {
                            resultJson = s.parseText(result)
                        } catch (Exception e) {
                            throw issueLevelError2("Can not parse the Organizations json, please contact Exalate Support: " + result, e)
                        }

                        if (!(resultJson instanceof Map)) {
                            throw issueLevelError("Organizations json has unrecognized structure, please contact Exalate Support: " + result)
                        }
                        resultJson as Map<String, Object>
                    },
                    fn { Map<String, Object> page -> ((page.values as List<Map<String, Object>>).size()) }
            )
            allOrganizationsResultJsons
                    .collect { it.get("values") as List<Map<String, Object>> }
                    .flatten()
        }


        getAllOrganizations()
    }

    static def receiveOrganizations(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, PreparedHttpClient httpClient){
        if (!replica.customKeys."Organization Names" || replica.customKeys."Organization Names".empty) {
            return
        }
        def localOrgs = getAllOrganizations(httpClient)
        issue.customFields."Organizations".value = replica.customKeys."Organizations"
                .collect { it.name }
                .collect { remoteOrgName -> localOrgs.find { (it.name as String).equals(remoteOrgName) }?.id as Long }
                .findAll()
    }
}
