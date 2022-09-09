import com.exalate.basic.domain.hubobject.v1.BasicHubIssue

class DataFilterIssueUpdate {
    // SCALA HELPERS
    static <T> T await(scala.concurrent.Future<T> f) { scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
    static <T> T orNull(scala.Option<T> opt) { opt.isDefined() ? opt.get() : null }
    static <T> scala.collection.Seq<T> seq(T ... ts) {
        def list = Arrays.asList(ts)
        def scalaBuffer = scala.collection.JavaConversions.asScalaBuffer(list)
        scalaBuffer.toSeq()
    }

    static def update(
            BasicHubIssue issue,
            com.exalate.api.domain.connection.IConnection connection,
            com.exalate.api.domain.request.ISyncRequest syncRequest,
            services.replication.PreparedHttpClient httpClient) {
        def issueKey = new com.exalate.basic.domain.BasicIssueKey(issue.id as Long, issue.key)
        def injector = InjectorGetter.getInjector()
        def hos = injector.instanceOf(services.jcloud.hubobjects.JCloudHubObjectService.class)
        def hubPayloadOptFuture = hos.getHubPayloadFromTracker(issueKey)
        def hubPayloadOpt = await(hubPayloadOptFuture)
        if (hubPayloadOpt.empty) {
            throw new com.exalate.api.exception.IssueTrackerException("""Failed to update the issue `${issueKey}` from the data filter of connection `${connection.name}` - the issue can not be found anymore.""".toString())
        }
        def hubPayload = hubPayloadOpt.get()
        def issueBeforeUpdate = (BasicHubIssue)hubPayload.hubIssue
        UpdateIssue.update(
                issue,
                connection,
                syncRequest,
                issueKey,
                issueBeforeUpdate,
                [],
                seq(),
                httpClient
        )
    }
}
