/**
 * Created by serhiy on 2/13/19.
 */
class UpdateIssue {
    static scala.Tuple2<com.exalate.api.domain.twintrace.TraceType, scala.collection.Seq<com.exalate.api.domain.twintrace.ITrace>> update(com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue,
                  com.exalate.api.domain.connection.IConnection connection,
                  com.exalate.api.domain.request.ISyncRequest syncRequest,
                  com.exalate.basic.domain.BasicIssueKey localExIssueKey,
                  com.exalate.basic.domain.hubobject.v1.BasicHubIssue issueBeforeScript,
                  java.util.List<com.exalate.api.domain.twintrace.INonPersistentTrace> traces,
                  scala.collection.Seq<com.exalate.api.domain.IBlobMetadata> blobMetadataList,
                  services.replication.PreparedHttpClient httpClient) {
        afterCreate(
                blobMetadataList,
                localExIssueKey,
                issueBeforeScript,
                issue,
                connection,
                syncRequest,
                traces,
                httpClient
        )
    }

    private static scala.Tuple2<com.exalate.api.domain.twintrace.TraceType, scala.collection.Seq<com.exalate.api.domain.twintrace.ITrace>> afterCreate(scala.collection.Seq<com.exalate.api.domain.IBlobMetadata> blobMetadataList,
                                                                                                                                                       com.exalate.basic.domain.BasicIssueKey localExIssueKey,
                                                                                                                                                       com.exalate.basic.domain.hubobject.v1.BasicHubIssue issueBeforeScript,
                                                                                                                                                       com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue,
                                                                                                                                                       com.exalate.api.domain.connection.IConnection relation,
                                                                                                                                                       com.exalate.api.domain.request.ISyncRequest syncRequest,
                                                                                                                                                       java.util.List<com.exalate.api.domain.twintrace.INonPersistentTrace> traces, services.replication.PreparedHttpClient httpClient) {
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

    private static toFakeTrace(com.exalate.api.domain.twintrace.INonPersistentTrace nonPersistentTrace) {
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
}
