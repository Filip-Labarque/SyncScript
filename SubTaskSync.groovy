import com.exalate.api.domain.connection.IConnection
import services.jcloud.hubobjects.NodeHelper
import services.replication.PreparedHttpClient

class SubTaskSync {
    // SCALA HELPERS
    private static <T> T await(scala.concurrent.Future<T> f) { scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
    private static <T> List<T> toList(scala.collection.Seq<T> xs) { scala.collection.JavaConverters$.MODULE$.bufferAsJavaListConverter(xs.toBuffer()).asJava() as List }
    static def send(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, IConnection connection, PreparedHttpClient httpClient) {
        final def nserv = InjectorGetter.getInjector().instanceOf(com.exalate.replication.services.api.issuetracker.hubobject.ITrackerHubObjectService.class)
        def issueLevelError = { String msg ->
            new com.exalate.api.exception.IssueTrackerException(msg)
        }
        def issueLevelError2 = { String msg, Throwable e ->
            new com.exalate.api.exception.IssueTrackerException(msg, e)
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
        def paginateInternal
        paginateInternal = { Integer offset, Integer limit, scala.collection.Seq<?> result, scala.runtime.AbstractFunction2<Integer, Integer, ?> nextPageFn, scala.runtime.AbstractFunction1<?, Integer> getTotalFn ->
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
        def paginate = { Integer limit, scala.runtime.AbstractFunction2<Integer, Integer, ?> nextPageFn, scala.runtime.AbstractFunction1<?, Integer> getTotalFn ->
            scala.collection.Seq<?> resultSeq = paginateInternal(0, limit, seq(), nextPageFn, getTotalFn)
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


        def getIssuesSubTasksByIdOrKey = { idOrKey ->
            def response
            try {
                //noinspection GroovyAssignabilityCheck
                response = await(await(httpClient.authenticate(
                        none(),
                        httpClient
                                .ws()
                                .url(jiraCloudUrl+"/rest/api/2/issue/"+idOrKey)
                                .withQueryString(seq(pair("fields", "subtasks")))
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
  "expand": "renderedFields,names,schema,operations,editmeta,changelog,versionedRepresentations",
  "id": "11620",
  "key": "TEST-21",
  "fields": {
    "subtasks": [
      {
        "id": "11621",
        "key": "TEST-22",
        "fields": {
          "summary": "Sub-Task #1.1",
          "status": {
            "name": "To Do",
            "id": "10000",
            "statusCategory": {
                ...
            }
          },
          "priority": {
            "name": "Medium",
            "id": "3"
          },
          "issuetype": {
            "id": "10102",
            "name": "Sub-task",
            "subtask": true,
            ...
          }
        }
      }
    ]
  }
}
*/
            if (!(resultJson instanceof Map)) {
                throw issueLevelError("Issue "+idOrKey+" json has unrecognized structure, please contact Exalate Support: " + resultStr)
            }
            resultJson as Map<String, Object>
        }



        replica.parentId = issue.parentId
        if (issue.parentId != null) {
            def localKeyFuture = nserv.getLocalKey(issue.parentId, connection)
            def localKeyOpt = await(localKeyFuture)
            def localKey = orNull(localKeyOpt)
            def localIssueKey = localKey?.URN
            replica.customKeys."parentContext" = ["key": localIssueKey]
            replica.customKeys."subTaskContext" = [
                    "parent" : [
                            "id" : issue.parentId as Long,
                            "key" : localIssueKey
                    ],
                    "children" : getIssuesSubTasksByIdOrKey(issue.parentId).fields.subtasks.collect { Map<String, Object> subTaskJissue -> [ "id" : subTaskJissue.id as Long, "key" : subTaskJissue.key ]}
            ]
        } else {
            def jIssue = getIssuesSubTasksByIdOrKey(issue.id)
            if (jIssue?.fields?.subtasks && !jIssue.fields.subtasks.empty) {
                replica.customKeys."subTaskContext" = [
                        "parent" : [
                                "id" : issue.id as Long,
                                "key" : issue.key
                        ],
                        "children" : jIssue.fields.subtasks.collect { Map<String, Object> subTaskJissue -> [ "id" : subTaskJissue.id as Long, "key" : subTaskJissue.key ]}
                ]
            }
        }
    }

    static def receiveBeforeCreation(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, NodeHelper nodeHelper) {
        def issueLevelError = { String msg ->
            new com.exalate.api.exception.IssueTrackerException(msg)
        }

        if (replica.parentId != null) {
            def parentIssue = nodeHelper.getLocalIssueKeyFromRemoteId(replica.parentId as Long)
            if (parentIssue == null) {
                throw issueLevelError(
                        "#SubTaskSync#receive: Can not find parent issue `"+replica.customKeys."parentContext"?.key+"` ("+ replica.parentId +"). " +
                                "This issue can not be created until the parent issue is successfully synchronized. " +
                                "Resolve this error whenever the parent issue `"+replica.customKeys."parentContext"?.key+"` ("+ replica.parentId +") is synced.")
            }
            def parentIssueId = parentIssue.id as Long
            issue.parentId = parentIssueId as String
        }
    }

    static def receiveAfterCreation(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, IConnection connection, NodeHelper nodeHelper) {
//         if (replica.customKeys.subTaskContext?.children && !replica.customKeys.subTaskContext.children.empty) {
//             final def injector = play.api.Play$.MODULE$.current().injector()
//             def errorRepo = injector.instanceOf(com.exalate.api.persistence.error.IErrorRepository.class)
//             def errorServ = injector.instanceOf(com.exalate.replication.services.api.error.IErrorService.class)
//
//             replica.customKeys.subTaskContext.children.each { Map<String, Object> remoteSubTask ->
//                 def remoteSubTaskId = remoteSubTask.id
//
//                 def errors = toList(await(errorRepo.findAllBlockers(
//                         scala.Option.empty(),
//                         connection.ID ? scala.Some.apply(connection.ID) : scala.Option.empty(),
//                         connection.remoteInstance?.ID ? scala.Some.apply(connection.remoteInstance?.ID) : scala.Option.empty(),
//                         scala.Option.empty(),
//                         remoteSubTaskId ? scala.Some.apply(remoteSubTaskId as String) : scala.Option.empty()
//                 )))
//                 errors.findAll { error -> error.rootCauseDetails?.contains("#SubTaskSync#receive:") }.each { error -> errorServ.resolveErrorAndRescheduleSync(error.id) }
//             }
//         }
    }
}
