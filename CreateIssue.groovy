import com.exalate.api.domain.connection.IConnection
import com.exalate.api.domain.twintrace.INonPersistentTrace
import com.exalate.basic.domain.BasicIssueKey

class CreateIssue {
    static create(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica,
                  com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue,
                  com.exalate.api.domain.connection.IConnection connection,
                  com.exalate.basic.domain.hubobject.v1.BasicHubIssue issueBeforeScript,
                  List<com.exalate.api.domain.twintrace.INonPersistentTrace> traces,
                  scala.collection.Seq<com.exalate.api.domain.IBlobMetadata> blobMetadataList,
                  httpClient,
                  com.exalate.api.domain.request.ISyncRequest syncRequest,
                  Closure<?> whenCreatedFn){
        create(false, replica, issue, connection, issueBeforeScript, traces, blobMetadataList, httpClient, syncRequest, whenCreatedFn)
    }

    static create(
            boolean reuseIssue,
            com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica,
            com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue,
            com.exalate.api.domain.connection.IConnection connection,
            com.exalate.basic.domain.hubobject.v1.BasicHubIssue issueBeforeScript,
            List<com.exalate.api.domain.twintrace.INonPersistentTrace> traces,
            scala.collection.Seq<com.exalate.api.domain.IBlobMetadata> blobMetadataList,
            httpClient,
            com.exalate.api.domain.request.ISyncRequest syncRequest,
            Closure<?> whenCreatedFn) {


        def context = com.exalate.replication.services.processor.ChangeIssueProcessor$.MODULE$.threadLocalContext.get()
        if (!context) {
            context = com.exalate.replication.services.processor.CreateIssueProcessor$.MODULE$.threadLocalContext.get()
        }
        if (!context) {
            context = com.exalate.replication.services.processor.CreateReplicaProcessor$.MODULE$.threadLocalContext.get()
        }

        def nodeHelper = context.nodeHelper
        def projectKey = issue.project?.key ?: issue.projectKey
        def issueType = issue.type ?: ({ nodeHelper.getIssueType(issue.typeName) })()
        if (projectKey == null) {
            throw new com.exalate.api.exception.IssueTrackerException(""" Project key is not found. Please fill issue.projectKey or issue.project parameter in script """.toString())
        }
        if (issueType == null) {
            throw new com.exalate.api.exception.IssueTrackerException(""" Issue type is not found. Please fill issue.typeName or issue.type parameter in script """.toString())
        }

        if(reuseIssue && issue.id != null){
            def await = { scala.concurrent.Future<?> f -> scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }

            def orNull = { scala.Option<?> opt -> opt.isDefined() ? opt.get() : null }
            final def hoh = InjectorGetter.getInjector().instanceOf(com.exalate.replication.services.api.issuetracker.hubobject.ITrackerHubObjectService.class)
            def existingExIssueKey = orNull(await(hoh.getLocalKeyById(issue.id, connection))) as com.exalate.basic.domain.BasicIssueKey
            if(existingExIssueKey != null){
                issue = fixStatusAndProjectAndType(issue, existingExIssueKey, connection)
                whenCreatedFn()
                return issue;
            }
        }

        def localExIssueKey = doCreate(
                issue,
                connection,
                issueBeforeScript,
                httpClient,
                syncRequest,
                reuseIssue
        )
        try {

            issue = fixStatusAndProjectAndType(issue, localExIssueKey, connection)

            whenCreatedFn()

            return afterCreate(blobMetadataList, localExIssueKey, issueBeforeScript, issue, connection, syncRequest, traces, httpClient)
        } catch (com.exalate.api.exception.IssueTrackerException ite) {
            deleteIssue(ite, localExIssueKey, reuseIssue, httpClient)
            throw ite
        } catch (Exception e) {
            deleteIssue(e, localExIssueKey, reuseIssue, httpClient)
            throw e
        }
    }

    private static com.exalate.basic.domain.hubobject.v1.BasicHubIssue fixStatusAndProjectAndType(com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, BasicIssueKey exIssueKey, IConnection connection) {

        def hos = InjectorGetter.getInjector().instanceOf(com.exalate.replication.services.api.issuetracker.hubobject.ITrackerHubObjectService.class)
        def exIssueOptFuture = hos.getHubPayloadFromTracker(exIssueKey, connection)
        def await = { scala.concurrent.Future<?> f -> scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
        def orNull = { scala.Option<?> opt -> opt.isDefined() ? opt.get() : null }
        def exIssueOpt = await(exIssueOptFuture)
        def exIssue = orNull(exIssueOpt)?.hubIssue as com.exalate.basic.domain.hubobject.v1.BasicHubIssue
        issue.status = exIssue.status
        issue.project = exIssue.project
        issue.type = exIssue.type
        issue
    }

    private static void deleteIssue(Exception ite, BasicIssueKey localExIssueKey, boolean reuseIssue, httpClient) {
        if (reuseIssue) {
            return
        }

        def await = { scala.concurrent.Future<?> f -> scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
        def orNull = { scala.Option<?> opt -> opt.isDefined() ? opt.get() : null }
        def none = { scala.Option$.MODULE$.<?> empty() }
        def getGeneralSettings = {
            def gsp = InjectorGetter.getInjector().instanceOf(com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsPersistence.class)
            def gsOpt = await(gsp.get())
            def gs = orNull(gsOpt)
            gs
        }
        final def gs = getGeneralSettings()

        def removeTailingSlash = { String str -> str.trim().replace("/+\$", "") }
        final def jiraCloudUrl = removeTailingSlash(gs.issueTrackerUrl)

        def deleteIssueFn = { com.exalate.api.domain.IIssueKey exIssueKey ->
            def response
            try {
                response = await(await(httpClient.authenticate(
                        none(),
                        httpClient
                                .ws()
                                .url(jiraCloudUrl + "/rest/api/2/issue/" + exIssueKey.id)
                                .withMethod("DELETE"),
                        gs
                )).execute())
            } catch (Exception e) {
                throw new com.exalate.api.exception.IssueTrackerException("Unable to delete issue `" + exIssueKey.URN + "`, please contact Exalate Support: " + e.message, e)
            }
            if (response.status() != 204) {
                throw new com.exalate.api.exception.IssueTrackerException("Can not delete issue `" + exIssueKey.URN + "` (status " + response.status() + "), please contact Exalate Support: " + response.body())
            }
        }
        try {
            deleteIssueFn(localExIssueKey)
        } catch (Exception ignore) {
            throw new com.exalate.api.exception.IssueTrackerException(ite.message + "\n also failed to delete the issue, created as a side-effect `" + localExIssueKey.URN + "`, please delete it!", ite)
        }
    }

    private static toFakeTrace(INonPersistentTrace nonPersistentTrace) {
        new com.exalate.basic.domain.twintrace.Trace(
                -1,
                nonPersistentTrace.type,
                nonPersistentTrace.localId,
                nonPersistentTrace.remoteId,
                nonPersistentTrace.action,
                nonPersistentTrace.isToSynchronize(),
                nonPersistentTrace.requestId,
                nonPersistentTrace.twinTraceId,
                nonPersistentTrace.eventId,
        )
    }

    private static scala.Tuple2<com.exalate.api.domain.twintrace.TraceType, scala.collection.Seq<com.exalate.api.domain.twintrace.ITrace>> afterCreate(scala.collection.Seq<com.exalate.api.domain.IBlobMetadata> blobMetadataList,
                                                  BasicIssueKey localExIssueKey,
                                                  com.exalate.basic.domain.hubobject.v1.BasicHubIssue issueBeforeScript,
                                                  com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue,
                                                  com.exalate.api.domain.connection.IConnection relation,
                                                  com.exalate.api.domain.request.ISyncRequest syncRequest,
                                                  java.util.List<com.exalate.api.domain.twintrace.INonPersistentTrace> traces, httpClient) {
        def indexFakeTraces = { List<com.exalate.api.domain.twintrace.INonPersistentTrace> traceList ->
            Map<com.exalate.api.domain.twintrace.TraceType, scala.collection.Seq<com.exalate.api.domain.twintrace.ITrace>> fakeTraces = new HashMap<>()
            for (com.exalate.api.domain.twintrace.INonPersistentTrace trace : traceList) {
                com.exalate.api.domain.twintrace.TraceType traceType = trace.getType()
                scala.collection.Seq<com.exalate.api.domain.twintrace.ITrace> tracesOrNull = fakeTraces.get(traceType)
                com.exalate.api.domain.twintrace.ITrace fakeTrace = toFakeTrace(trace)
                def builder = scala.collection.Seq$.MODULE$.<com.exalate.api.domain.twintrace.ITrace> newBuilder()
                builder = (tracesOrNull == null) ?
                        builder :
                        builder.$plus$plus$eq(tracesOrNull)
                builder = builder.$plus$eq(fakeTrace)
                fakeTraces.put(traceType, builder.result())
            }
            scala.collection.Seq<scala.Tuple2<com.exalate.api.domain.twintrace.TraceType, scala.collection.Seq<com.exalate.api.domain.twintrace.ITrace>>> typeToTraceTupleSeq = scala.collection.JavaConversions.<com.exalate.api.domain.twintrace.TraceType, scala.collection.Seq<com.exalate.api.domain.twintrace.ITrace>> mapAsScalaMap(fakeTraces).toSeq()
            return scala.collection.immutable.Map$.MODULE$.<com.exalate.api.domain.twintrace.TraceType, scala.collection.Seq<com.exalate.api.domain.twintrace.ITrace>> apply(typeToTraceTupleSeq) as scala.collection.immutable.Map<com.exalate.api.domain.twintrace.TraceType, scala.collection.Seq<com.exalate.api.domain.twintrace.ITrace>>
        }

        def fn = { Closure<?> closure ->
            new scala.runtime.AbstractFunction1<Object, Object>() {
                @Override
                Object apply(Object p) {
                    return closure.call(p)
                }
            }
        }
        def await = { scala.concurrent.Future<?> f -> scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
        def pair = { l, r -> scala.Tuple2$.MODULE$.<?, ?> apply(l, r) }

        final
        def hoh = InjectorGetter.getInjector().instanceOf(com.exalate.replication.services.api.issuetracker.hubobject.ITrackerHubObjectService.class)
        final
        def ps = InjectorGetter.getInjector().instanceOf(com.exalate.api.hubobject.v1.IHubIssueProcessorPreparationService.class)

        //finally update all
        def fakeTraces = indexFakeTraces(traces)
        def javaFakeTraces = scala.collection.JavaConversions.mapAsJavaMap(
                fakeTraces.mapValues(fn { scala.collection.Seq<com.exalate.api.domain.twintrace.ITrace> ts ->
                    scala.collection.JavaConversions.bufferAsJavaList(ts.toBuffer())
                })
        )
        def preparedIssue = ps.prepareLocalHubIssueViaPrevious(issueBeforeScript, issue, javaFakeTraces)
        //@Nonnull com.exalate.api.domain.IIssueKey issueKey, @Nonnull IHubIssueReplica hubIssueAfterScripts, @Nullable String proxyUser, @Nonnull IHubIssueReplica hubIssueBeforeScripts, @Nonnull Map<com.exalate.api.domain.twintrace.TraceType, List<com.exalate.api.domain.twintrace.ITrace>> traces, @Nonnull List<IBlobMetadata> blobMetadataList, IRelation relation
        def blobMetadataSeq = blobMetadataList
        def resultTraces = await(hoh.updateIssue(issueBeforeScript, preparedIssue, syncRequest, localExIssueKey, blobMetadataSeq, fakeTraces))
        traces.clear()
        traces.addAll(scala.collection.JavaConversions.bufferAsJavaList(resultTraces.toBuffer()))
        return pair(localExIssueKey, scala.collection.JavaConversions.asScalaBuffer(traces))
    }

    private static com.exalate.basic.domain.BasicIssueKey doCreate(
            com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue,
            com.exalate.api.domain.connection.IConnection relation,
            com.exalate.basic.domain.hubobject.v1.BasicHubIssue issueBeforeScript,
             httpClient,
            com.exalate.api.domain.request.ISyncRequest syncRequest,
            boolean reuseIssue = false
            ) {

        def issueTypeNameInternal = issue.type?.name ?: issue.typeName
        def projectKeyInternal = issue.project?.key ?: issue.projectKey
        def priorityNameInternal = issue.priority?.name

        def await = { scala.concurrent.Future<?> f -> scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
        def orNull = { scala.Option<?> opt -> opt.isDefined() ? opt.get() : null }

        final def hoh = InjectorGetter.getInjector().instanceOf(com.exalate.replication.services.api.issuetracker.hubobject.ITrackerHubObjectService.class)

        final def _blobMetadataList = syncRequest.blobMetadataList
        def _traces = syncRequest.traces.collect { itrace -> new com.exalate.basic.domain.BasicNonPersistentTrace().from(itrace) }

        def getExIssue = { com.exalate.api.domain.IIssueKey exIssueKey ->
            if (exIssueKey == null) {
                return null
            }
            orNull(await(hoh.getHubPayloadFromTracker(exIssueKey)))?.hubIssue as com.exalate.basic.domain.hubobject.v1.BasicHubIssue
        }

        def deleteIssue = { com.exalate.api.domain.IIssueKey exIssueKey ->
            def response
            try {
                response = await(
                        httpClient
                                .thisJira("/rest/api/2/issue/" + exIssueKey.id, "DELETE", "application/json", null)
                                .execute()
                )
            } catch (Exception ex) {
                throw new com.exalate.api.exception.IssueTrackerException("Unable to delete issue `" + exIssueKey.URN + "`, please contact Exalate Support: " + ex.message, ex)
            }
            if (response.status() != 204) {
                throw new com.exalate.api.exception.IssueTrackerException("Can not delete issue `" + exIssueKey.URN + "` (status " + response.status() + "), please contact Exalate Support: " + response.body())
            }
        }

//log.info("Executing the create processor for remote issue `"+ replica.key +"`")
        com.exalate.basic.domain.BasicIssueKey _localExIssueKey
        try {
            if (issue.id != null) {
                // handle issue key sync

                def existingExIssueKey = orNull(await(hoh.getLocalKeyById(issue.id, relation))) as com.exalate.basic.domain.BasicIssueKey
                def existingExIssue = getExIssue(existingExIssueKey)
                if (existingExIssue != null) {
                    if (reuseIssue) {
                        if (existingExIssue.type.name != issueTypeNameInternal) {
                            throw new com.exalate.api.exception.IssueTrackerException(
                                    "Exalate failed to reuse existing issue `${existingExIssue.key}` because it has a different type `${existingExIssue.type.name}` while expected `$issueTypeNameInternal` please, move the issue ${existingExIssue.key} to type `${issueTypeNameInternal}` and then resolve the error.".toString()
                            )
                        }

                        _localExIssueKey = existingExIssueKey
                    } else {
                        throw new com.exalate.api.exception.IssueTrackerException(
                                "Exalate can not create an issue with key `${issue.key}` because there is already an issue with such key. Please review whether you'd like to delete that existing issue and resolve this error or .".toString()
                        )
                    }
                } else {
                    def jIssueAndTraces = await(hoh.createIssue(issueBeforeScript, issue, relation, scala.collection.JavaConversions.asScalaBuffer(_blobMetadataList)))
                    def anExIssueKey = jIssueAndTraces._1() as com.exalate.basic.domain.BasicIssueKey
                    if (anExIssueKey.URN != issue.key) {
                        if (!reuseIssue) {
                            deleteIssue(anExIssueKey)
                        }
                        throw new com.exalate.api.exception.IssueTrackerException(
                                "Exalate can not create an issue with key `${issue.key}` because Jira assigned a different issue number `${anExIssueKey.URN}` please: ".toString() +
                                        "(1). create a file with .csv extention (for example `${issue.key}_import.csv`) with the following lines (remove the ` quotes):\n".toString() +
                                        "line 1: `Key,Priority,Summary,Type`\n".toString() +
                                        "line 2: `${issue.key},${priorityNameInternal},${issue.summary},$issueTypeNameInternal`\n".toString() +
                                        "(2). navigate to Settings (administration) > System > Import and Export > External system import\n".toString() +
                                        "(3). upload the .csv file you've created earlier\n".toString() +
                                        "(4). choose project `$projectKeyInternal`\n".toString() +
                                        "(5). choose columns `Issue Key`, `Priority`, `Summary` and `Issue Type`\n".toString() +
                                        "(6). submit the import action\n".toString() +
                                        "(7). resolve the error".toString()
                        )
                    }

                    _localExIssueKey = anExIssueKey
                    def tsInternal = scala.collection.JavaConversions.bufferAsJavaList(jIssueAndTraces._2().toBuffer()) as List<com.exalate.basic.domain.BasicNonPersistentTrace> ?: []
                    _traces.clear()
                    _traces.addAll(tsInternal)
                }
            } else {
                def jIssueAndTraces = await(hoh.createIssue(issueBeforeScript, issue, syncRequest, scala.collection.JavaConversions.asScalaBuffer(_blobMetadataList)))
                _localExIssueKey = jIssueAndTraces._1() as com.exalate.basic.domain.BasicIssueKey
                def tsInternal = scala.collection.JavaConversions.bufferAsJavaList(jIssueAndTraces._2().toBuffer()) as List<com.exalate.basic.domain.BasicNonPersistentTrace> ?: []
                _traces.clear()
                _traces.addAll(tsInternal)
            }
            issue.id = _localExIssueKey.id as String
            issue.key = _localExIssueKey.URN
            return _localExIssueKey
        } catch (com.exalate.api.exception.IssueTrackerException ite) {
            throw ite
        } catch (Exception e) {
            throw new com.exalate.api.exception.IssueTrackerException(e)
        }
    }
}
