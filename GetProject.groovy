import com.exalate.basic.domain.hubobject.v1.BasicHubProject
import services.replication.PreparedHttpClient

class GetProject {
    // Errors
    static def issueLevelError(String msg) {
        new com.exalate.api.exception.IssueTrackerException(msg)
    }
    static def issueLevelError2(String msg, Throwable e) {
        new com.exalate.api.exception.IssueTrackerException(msg, e)
    }

    // Scala Helpers
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

    static BasicHubProject byKey(String key, PreparedHttpClient httpClient) {
        def projectJsons = searchProjects(httpClient, key)
        def projectJson = projectJsons.find { projectJson -> key.equals(projectJson.key) }
        if (!projectJson) {
            return null
        }
        toExProject(projectJson)
    }

    static BasicHubProject byName(String name, PreparedHttpClient httpClient) {
        def projectJsons = searchProjects(httpClient, name)
        def projectJson = projectJsons.find { projectJson -> name.equals(projectJson.name) }
        if (!projectJson) {
            return null
        }
        def exProject = new BasicHubProject()
        exProject.id = projectJson.id as Long
        exProject.key = projectJson.key as String
        exProject.name = projectJson.name as String
        //... fill in others only if necessary
        exProject
    }

    private static java.util.List<java.util.Map<java.lang.String, java.lang.Object>> searchProjects(services.replication.PreparedHttpClient httpClient, java.lang.String query) {
        final def ws = new JiraClient(httpClient)
        def projectJsons = paginate(
                50,
                fn2 { Integer offset, Integer limit ->
                    def searchResult
                    try {
                        searchResult = ws.http(
                                "GET",
                                "/rest/api/2/project/search",
                                [
                                        "query"     : [query],
                                        "startAt"   : [offset as String],
                                        "maxResults": [limit as String],
                                        "expand"    : ["description,lead,issueTypes,projectKeys"],
                                ],
                                null,
                                [:]
                        )
                    } catch (Exception e) {
                        throw issueLevelError2("Unable to search for project, please contact Exalate Support: " +
                                "\nRequest: GET /rest/api/2/project/search?query=" + query + "&startAt=" + offset + "&maxResults=" + limit + "&expand=description,lead,issueTypes,projectKeys" +
                                "\nError: " + e.message, e)
                    }
                    groovy.json.JsonSlurper s = new groovy.json.JsonSlurper()
                    def searchResultJson
                    try {
                        searchResultJson = s.parseText(searchResult)
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse the project search json, please contact Exalate Support: " + searchResult, e)
                    }

                    /*
                    {
                      ...
                      "maxResults": 2,
                      "startAt": 0,
                      "total": 7,
                      "isLast": false,
                      "values": [
                        {
                          ...
                          "id": "10000",
                          "key": "EX",
                          "name": "Example",
                          "avatarUrls": {
                            ...
                          },
                          "projectCategory": {
                            ...
                            "id": "10000",
                            "name": "FIRST",
                            "description": "First Project Category"
                          },
                          "simplified": false,
                          "style": "classic"
                        },
                        {
                          ...
                          "id": "10001",
                          "key": "ABC",
                          "name": "Alphabetical",
                          "avatarUrls": {
                            ...
                          },
                          "projectCategory": {
                            ...
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
                    if (!(searchResultJson instanceof Map)) {
                        throw issueLevelError("Project search json has unrecognized structure, please contact Exalate Support: " + searchResult)
                    }
                    searchResultJson as Map<String, Object>
                },
                fn { Map<String, Object> page ->
                    if (!(page.values instanceof List)) {
                        throw issueLevelError("Project Search json has unrecognized structure inside each page, please contact Exalate Support: " + page)
                    }
                    page.values as List<Map<String, Object>>
                }
        )
        projectJsons
    }

    private static BasicHubProject toExProject(java.util.Map<java.lang.String, java.lang.Object> projectJson) {
        def exProject = new BasicHubProject()
        exProject.id = projectJson.id as Long
        exProject.key = projectJson.key as String
        exProject.name = projectJson.name as String
        //... fill in others only if necessary
        exProject
    }
}
