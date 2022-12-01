import com.exalate.basic.domain.hubobject.v1.BasicHubIssue

/**
Usage:

Add this snippet below to the end of your "Outgoing sync(data filter)":

Status.send()
--------------------------------

Add the snippet below to the end of your "Incoming sync":

// If you have status are not the same on both sides:
Status.receive(["Done": "Resolved", "In Progress": "In Action"])

 //If you wish to go to a particular status:
 //Status.transitionTo("Done")
--------------------------------
 * */
class Status {
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
    private static <T> T await(scala.concurrent.Future<T> f) {
        scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf())
    }

    private static <T> T orNull(scala.Option<T> opt) { opt.isDefined() ? opt.get() : null }

    private static <T> scala.Option<T> none() { scala.Option$.MODULE$.<T> empty() }

    @SuppressWarnings("GroovyUnusedDeclaration")
    private static <T> scala.Option<T> none(Class<T> evidence) { scala.Option$.MODULE$.<T> empty() }

    private static <L, R> scala.Tuple2<L, R> pair(L l, R r) { scala.Tuple2$.MODULE$.<L, R> apply(l, r) }

    private static <T> scala.collection.Seq<T> seq(T... ts) {
        def list = Arrays.asList(ts)
        def scalaBuffer = scala.collection.JavaConversions.asScalaBuffer(list)
        scalaBuffer.toSeq()
    }

    // SERVICES AND EXALATE API
    private static play.api.inject.Injector getInjector() {
        InjectorGetter.getInjector()
    }

    /**
     * Due to changes on Exalate's API from 5.3 to 5.4 we need to consider that IJCloudGeneralSettingsRepository might have
     * a different classname such as IJCloudGneeralSettingsPersistence, so we will load the class dinamycally and catching an exception if Exalate is running
     * 5.3 or lower version
     */
    private static def getGeneralSettings() {
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

    private static String getJiraCloudUrl() {
        final def gs = getGeneralSettings()

        def removeTailingSlash = { String str -> str.trim().replace("/+\$", "") }
        final def jiraCloudUrl = removeTailingSlash(gs.issueTrackerUrl)
        jiraCloudUrl
    }


    static Closure<BasicHubIssue> defaultOnNoStatusFound = { String desiredStatusName, Map<String, Object> wf, BasicHubIssue issue ->
        throw new com.exalate.api.exception.IssueTrackerException(
                "Can not find status `" + desiredStatusName + "` " +
                        "in workflow `" + wf.name + ". " +
                        "Please review whether `" + wf.name + "` is the correct workflow for the issue `" + issue.key + "`."
        )
    }

    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue send() {
        def context = com.exalate.replication.services.processor.CreateReplicaProcessor$.MODULE$.threadLocalContext.get()
        def replica = context.replica
        def issue = context.issue
        replica.status = issue.status
        replica
    }


    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receiveStatus(Closure<BasicHubIssue> onNoStatusFoundFn,
                                                                             boolean useRemoteStatusByDefault,
                                                                             Map<String, String> workflowMapping,
                                                                             Map<String, Object> projectWorkflowSchemeMapping,
                                                                             Map<Object, String> workflowSchemeMapping,
                                                                             Map<String, String> workflowDetailsMapping,
                                                                             BasicHubIssue replica,
                                                                             BasicHubIssue issue,
                                                                             nodeHelper,
                                                                             httpClient,
                                                                             boolean onlyDirectTransitions) {
        try {
            //FIXME: What if I have a status that is not found on transition
            def desiredStatusName = (workflowMapping.find { k, _ -> k.equalsIgnoreCase(replica.status?.name) }?.value)
            if (useRemoteStatusByDefault && desiredStatusName == null) {
                desiredStatusName = replica.status?.name
            }
            transitionTo(onNoStatusFoundFn, desiredStatusName, projectWorkflowSchemeMapping, workflowSchemeMapping, workflowDetailsMapping, replica, issue, nodeHelper, httpClient, onlyDirectTransitions)
        } catch (com.exalate.api.exception.IssueTrackerException ite) {
            throw ite
        } catch (Exception e) {
            throw new com.exalate.api.exception.IssueTrackerException(e)
        }
        issue
    }

    /*
    This is the receive medefaultOnNoStatusFoundthod with the simplified API.
     */


    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receive(boolean useRemoteStatusByDefault,
                                                                       Map<String, String> workflowMapping,
                                                                       Map<String, Object> projectWorkflowSchemeMapping,
                                                                       Map<Object, String> workflowSchemeMapping,
                                                                       Map<String, String> workflowDetailsMapping,
                                                                       boolean onlyDirectTransitions) {

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
        def firstSync = context.firstSync


        if (firstSync) {
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

                receiveStatus(
                        defaultOnNoStatusFound,
                        useRemoteStatusByDefault,
                        workflowMapping,
                        projectWorkflowSchemeMapping,
                        workflowSchemeMapping,
                        workflowDetailsMapping,
                        replica,
                        issue,
                        nodeHelper,
                        httpClient,
                        onlyDirectTransitions
                )
                return null
            }
        } else {
            receiveStatus(
                    defaultOnNoStatusFound,
                    useRemoteStatusByDefault,
                    workflowMapping,
                    projectWorkflowSchemeMapping,
                    workflowSchemeMapping,
                    workflowDetailsMapping,
                    replica,
                    issue,
                    nodeHelper,
                    httpClient,
                    onlyDirectTransitions
            )
        }


        issue
    }

    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receive(boolean useRemoteStatusByDefault, Map<String, String> workflowMapping) {
        Status.receive(useRemoteStatusByDefault, workflowMapping, [:], [:], [:], false)
    }

    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receiveOnlyDirectTransitions(boolean useRemoteStatusByDefault, Map<String, String> workflowMapping) {
        Status.receive(useRemoteStatusByDefault, workflowMapping, [:], [:], [:], true)
    }

    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receive(Map<String, String> workflowMapping) {
        Status.receive(true, workflowMapping, [:], [:], [:], false)
    }

    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receive() {
        Status.receive(true, [:], [:], [:], [:], false)
    }

    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receiveOnlyDirectTransitions() {
        Status.receive(true, [:], [:], [:], [:], true)
    }



    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue transitionTo(String statusName,
                                                                            Map<String, Object> projectWorkflowSchemeMapping,
                                                                            Map<Object, String> workflowSchemeMapping,
                                                                            Map<String, String> workflowDetailsMapping,
                                                                            boolean onlyDirectTransitions = false) {
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

            Status.transitionTo(
                    defaultOnNoStatusFound,
                    statusName,
                    projectWorkflowSchemeMapping,
                    workflowSchemeMapping,
                    workflowDetailsMapping,
                    replica, issue, nodeHelper, httpClient, onlyDirectTransitions)
            return null
        }

        issue
    }

    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue transitionTo(String statusName){
        Status.transitionTo(statusName, [:], [:], [:], false)
    }

    static transitionTo(Closure<BasicHubIssue> onNoStatusFoundFn,
                        String desiredStatusName,
                        Map<String, Object> projectWorkflowSchemeMapping,
                        Map<Object, String> workflowSchemeMapping,
                        Map<String, String> workflowDetailsMapping,
                        BasicHubIssue replica, BasicHubIssue issue, nodeHelper, httpClient, boolean onlyDirectTransitions) {
        def username = "user"
        def password = "pass"
        ({
            try {
                def jIssue = issue
                if (issue.id == null) {
                    throw new com.exalate.api.exception.IssueTrackerException(""" It seems, that the issue has not been created yet. Please change your create processor to create the issue and populate the `issue.id` property before using the `StatusSync.receiveStatus(...)` """.toString())
                }
                def localExIssueKey = new com.exalate.basic.domain.BasicIssueKey(issue.id as Long, issue.key)

                final def gs = generalSettings

                def getWorkflowSchemeIdForProject = { String projectKey, String currentStatus, String targetStatus ->
                    def response
                    try {
                        //noinspection GroovyAssignabilityCheck
                        response = await(httpClient
                                .ws()
                                .url(jiraCloudUrl + "/rest/api/2/workflowscheme/project/" + projectKey)
                                .withAuth(username, password, play.api.libs.ws.WSAuthScheme.BASIC$.MODULE$)
                                .withMethod("GET")
                                .execute()
                        )
                    } catch (Exception e) {
                        throw issueLevelError2(
                                "Unable to get workflow scheme for project `" + projectKey + "` on behalf of user `" + username + "` using a private REST API, please contact Exalate Support: " +
                                        "\nRequest: GET " + jiraCloudUrl + "/rest/projectconfig/1/workflowscheme/" + projectKey +
                                        "\nAuth: " + username + ":" + password +
                                        "\nError: " + e.message,
                                e
                        )
                    }
                    if (401 == response.status()) {
                        throw issueLevelError("It looks you need to perform more than one transition to get from current status `" + currentStatus + "` to target status `" + targetStatus + "`. " +
                                "Unfortunately in order for exalate to calculate the right transition path to do that, more configuration will be required on your script. " +
                                "Check https://docs.idalko.com/exalate/display/ED/Status+synchronization+on+Jira+cloud+with+multiple+transitions+on+a+single+sync for more details")
//                        throw issueLevelError("Can not get workflow scheme for project `"+ projectKey +"`. " +
//                                "\nKnown projects / workflow schemes: `"+ projectWorkflowSchemeMapping +"`" +
//                                "\nPlease contact Exalate Support. " +
//                                "\nAlso failed to get it through REST API: "+
//                                "\nRequest: GET " + jiraCloudUrl + "/rest/projectconfig/1/workflowscheme/" + projectKey +
//                                "\nAuth: " + username +
//                                "\nResponse: " + response.body()
//                        )
                    }
                    if (200 != response.status()) {
                        throw issueLevelError(
                                "Failed to get workflow scheme for project `" + projectKey + "` on behalf of user `" + username + "` using a private REST API (status " + response.status() + "), please contact Exalate Support: " +
                                        "\nRequest: GET " + jiraCloudUrl + "/rest/projectconfig/1/workflowscheme/" + projectKey +
                                        "\nAuth: " + username + ":" + password +
                                        "\nResponse: " + response.body()
                        )
                    }
                    def s = new groovy.json.JsonSlurper()

                    def json
                    try {
                        json = s.parseText(response.body())
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse workflow scheme json from private REST API, please contact Exalate Support: " + response.body(), e)
                    }
                    if (!(json instanceof Map<String, Object>)) {
                        throw issueLevelError("Workflow scheme json from private REST API has unrecognized structure, please contact Exalate Support: " + response.body())
                    }
                    (json as Map<String, Object>).id as Long
                }

                def getWorkflowScheme = { String projectId, String currentStatus, String targetStatus ->
                    def response
                    try {
                        //noinspection GrUnresolvedAccess
                        response = await(
                            await(
                                httpClient.authenticate(
                                        none(),
                                        httpClient
                                            .ws()
                                            .url(jiraCloudUrl + "/rest/api/2/workflowscheme/project")
                                        .withQueryString(seq(pair("projectId", projectId)))
                                            .withMethod("GET"),
                                        generalSettings
                                )
                            ).execute()
                        )
                    } catch (Exception e) {
                        throw issueLevelError2(
                                "Unable to get workflow scheme by id `" + workflowSchemeId + "`, please contact Exalate Support: " +
                                        "\nRequest: GET " + jiraCloudUrl + "/rest/api/2/workflowscheme/" + workflowSchemeId +
                                        "\nAuth: " + username + ":" + password +
                                        "\nError: " + e.message,
                                e
                        )
                    }
                    if (response.status() == 401) {
                        throw issueLevelError("It looks you need to perform more than one transition to get from current status `" + currentStatus + "` to target status `" + targetStatus + "`. " +
                                "Unfortunately in order for exalate to calculate the right transition path to do that, more configuration will be required on your script. " +
                                "Check https://docs.idalko.com/exalate/display/ED/Status+synchronization+on+Jira+cloud+with+multiple+transitions+on+a+single+sync for more details")
//                            throw issueLevelError("Can not get details for workflow scheme by id `"+ workflowSchemeId +"`. " +
//                                    "\nKnown projects / workflow schemes: `"+ workflowSchemeMapping +"`" +
//                                    "\nPlease contact Exalate Support."+
//                                    "\nAlso failed to get it through REST API: "+
//                                    "\nRequest: GET " + jiraCloudUrl + "/rest/api/2/workflowscheme/" + workflowSchemeId +
//                                    "\nAuth: " + username +
//                                    "\nResponse: " + response.body()
//                            )
                    }
                    if (response.status() != 200) {
                        throw issueLevelError(
                                "Failed to get workflow scheme for project by id `" + projectId + "` (status " + response.status() + "), please contact Exalate Support: " +
                                        "\nRequest: GET " + jiraCloudUrl + "/rest/api/2/workflowscheme/project?projectId=" + projectId +
                                        "\nResponse: " + response.body()
                        )
                    }
                    def jsonStrInternal = response.body()


                    def s = new groovy.json.JsonSlurper()
                    def json
                    try {
                        json = s.parseText(jsonStrInternal)
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse workflow scheme json, please contact Exalate Support: " + jsonStrInternal, e)
                    }
                    if (!(json instanceof Map<String, Object>)) {
                        throw issueLevelError("Workflow scheme json has unrecognized structure, please contact Exalate Support: " + jsonStrInternal)
                    }
                    if (!(((json as Map<String, Object>).values instanceof List<Map<String, Object>>))) {
                        throw issueLevelError("Workflow scheme mappings json has unrecognized structure (it does not have values element), please contact Exalate Support: " + jsonStrInternal)
                    }
                    if (((((json as Map<String, Object>).values as List<Map<String, Object>>).empty))) {
                        throw issueLevelError("No Workflow scheme found for project `${projectId}`, please contact Exalate Support: " + jsonStrInternal)
                    }
                    if (!(((json as Map<String, Object>).values.find() instanceof Map<String, Object>))) {
                        throw issueLevelError("Workflow scheme mappings json has unrecognized structure (the values element does not hold objects), please contact Exalate Support: " + jsonStrInternal)
                    }
                    if (!((((json as Map<String, Object>).values.find() as Map<String, Object>).workflowScheme instanceof Map<String, Object>))) {
                        throw issueLevelError("Workflow scheme mappings json has unrecognized structure (the values element does not hold objects), please contact Exalate Support: " + jsonStrInternal)
                    }
                    if (!(((((json as Map<String, Object>).values.find() as Map<String, Object>).workflowScheme as Map<String, Object>).issueTypeMappings instanceof Map<String, String>))) {
                        throw issueLevelError("Workflow scheme mappings json has unrecognized structure (the values \\ workflowScheme \\ issueTypeMappings element does not hold objects), please contact Exalate Support: " + jsonStrInternal)
                    }
                    if (!(((((json as Map<String, Object>).values.find() as Map<String, Object>).workflowScheme as Map<String, Object>).defaultWorkflow instanceof String))) {
                        throw issueLevelError("Workflow scheme mappings json has unrecognized structure (the values \\ workflowScheme \\ defaultWorkflow element is not a string), please contact Exalate Support: " + jsonStrInternal)
                    }
                    ((json as Map<String, Object>).values.find() as Map<String, Object>).workflowScheme as Map<String, Object>;
                }

                def getWorkflowName = { String projectId, String issueTypeId, String currentStatus, String targetStatus ->
                    def workflowSchemeJson = getWorkflowScheme(projectId, currentStatus, targetStatus)

                    def wfName = workflowSchemeJson.issueTypeMappings[issueTypeId] ?: workflowSchemeJson.defaultWorkflow
                    wfName as String
                }

                def getWorkflowDetailsForProject = { String workflowName, String projectKey ->
                    def response
                    try {
                        response = await(
                            await(
                                httpClient.authenticate(
                                    none(),
                                    httpClient
                                        .ws()
                                        .url(jiraCloudUrl + "/rest/api/2/workflow/search")
                                        .withQueryString(seq(
                                            pair("workflowName", workflowName),
                                            pair("expand", "transitions,statuses,transitions.rules")
                                        ))
                                        .withMethod("GET"),
                                    generalSettings
                                )
                            ).execute()
                        )
                    } catch (Exception e) {
                        throw issueLevelError2(
                                "Unable to get workflow details for name `" + workflowName + "` for project `" + projectKey + "` on behalf of user `" + username + "`, please contact Exalate Support: " +
                                        "\nRequest: GET " + jiraCloudUrl + "/rest/api/2/workflow/search?workflowName=" + workflowName +
                                        "\nError: " + e.message,
                                e
                        )
                    }
                    if (401 == response.status()) {
                        throw issueLevelError("Can not get project to workflow details mapping for workflow name`" + workflowName + "`. " +
                                "\nKnown workflow name to project to details mapping: `" + workflowDetailsMapping + "`" +
                                "\nPlease contact Exalate Support." +
                                "\nAlso failed to get it through REST API: " +
                                "\nRequest: GET " + jiraCloudUrl + "/rest/api/2/workflow/search?workflowName=" + workflowName +
                                "\nResponse: " + response.body()
                        )
                    }
                    if (200 != response.status()) {
                        throw issueLevelError(
                                "Failed to get workflow details for name `" + workflowName + "` for project `" + projectKey + "` on behalf of user `" + username + "` (status " + response.status() + "), please contact Exalate Support: " +
                                        "\nRequest: GET " + jiraCloudUrl + "/rest/api/2/workflow/search?workflowName=" + workflowName +
                                        "\nResponse: " + response.body()
                        )
                    }
                    def jsonStr = response.body()

                    def s = new groovy.json.JsonSlurper()

                    def json
                    try {
                        json = s.parseText(jsonStr)
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse workflow details json, please contact Exalate Support: " + jsonStr, e)
                    }
                    if (!(json instanceof Map<String, Object>)) {
                        throw issueLevelError("Workflow details json has unrecognized structure, please contact Exalate Support: " + jsonStr)
                    }
                    if (!((json as Map<String, Object>).values instanceof List<Map<String, Object>>)) {
                        throw issueLevelError("Workflow details json has unrecognized structure, please contact Exalate Support: " + jsonStr)
                    }
                    if (((json as Map<String, Object>).values as List<Map<String, Object>>).empty) {
                        throw issueLevelError("No workflow details found for workflow `${workflowName}`, please contact Exalate Support: " + jsonStr)
                    }
                    if (!(((json as Map<String, Object>).values).every {it instanceof Map<String, Object>})) {
                        throw issueLevelError("Workflow details values json has unrecognized structure (each value is not an object), please contact Exalate Support: " + jsonStr)
                    }
                    if (!(((json as Map<String, Object>).values).every {it.id instanceof Map<String, Object>})) {
                        throw issueLevelError("Workflow details values id json has unrecognized structure (each value id is not an object), please contact Exalate Support: " + jsonStr)
                    }
                    if (!(((json as Map<String, Object>).values).every {it.id.name instanceof String})) {
                        throw issueLevelError("Workflow details values id json has unrecognized structure (each value id's name is not a String), please contact Exalate Support: " + jsonStr)
                    }
                    def workflowByName = ((json as Map<String, Object>).values).find { it.id.name == workflowName }
                    if (workflowByName == null) {
                        throw issueLevelError("No workflow details found in json for workflow `${workflowName}`, please contact Exalate Support: " + jsonStr)
                    }
                    if (!(workflowByName instanceof Map<String, Object>)) {
                        throw issueLevelError("Workflow details sources json has unrecognized structure, please contact Exalate Support: " + jsonStr)
                    }
                    /*
{
  "id": {
    "name": "SCRUM Workflow"
  },
  "description": "A workflow used for Software projects in the SCRUM methodology",
  "transitions": [
    {
      "id": "5",
      "name": "In Progress",
      "description": "Start working on the issue.",
      "from": [
        "10",
        "13"
      ],
      "to": "14",
      "type": "directed",
      "screen": {
        "id": "10000"
      },
      "rules": {
        "conditions": [
          {
            "type": "PermissionCondition",
            "configuration": {
              "permissionKey": "WORK_ON_ISSUES"
            }
          }
        ],
        "validators": [
          {
            "type": "FieldRequiredValidator",
            "configuration": {
              "ignoreContext": true,
              "errorMessage": "A custom error message",
              "fields": [
                "description",
                "assignee"
              ]
            }
          }
        ],
        "postFunctions": [
          {
            "type": "UpdateIssueStatusFunction"
          },
          {
            "type": "GenerateChangeHistoryFunction"
          },
          {
            "type": "FireIssueEventFunction"
          }
        ]
      }
    }
  ],
  "statuses": [
    {
      "id": "3",
      "name": "In Progress",
      "properties": {
        "issueEditable": false
      }
    }
  ]
}
                     */
                    workflowByName as Map<String, Object>
                }


                def getTransitionsForIssue = { String issueKeyStr ->
                    def response
                    try {
                        response = await(await(httpClient.authenticate(
                                none(),
                                httpClient
                                        .ws()
                                        .url(jiraCloudUrl + "/rest/api/2/issue/" + issueKeyStr + "/transitions")
                                        .withQueryString(seq(pair("skipRemoteOnlyCondition", "true"), pair("expand", "transitions.fields")))
                                        .withMethod("GET"),
                                gs
                        )).get())
                    } catch (Exception e) {
                        throw issueLevelError2("Unable to get transitions for issue, please contact Exalate Support: " +
                                "\nRequest: GET /rest/api/2/issue/" + issueKeyStr + "/transitions" +
                                "\nError: " + e.message, e)
                    }
                    if (response.status() != 200) {
                        throw issueLevelError("Can not get transitions for issue (status " + response.status() + "), please contact Exalate Support: " +
                                "\nRequest: GET /rest/api/2/issue/" + issueKeyStr + "/transitions" +
                                "\nResponse: " + response.body())
                    }
                    def resultStr = response.body()
                    def s = new groovy.json.JsonSlurper()
                    def json
                    try {
                        json = s.parseText(resultStr)
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse the get transitions for issue json, please contact Exalate Support: " + resultStr, e)
                    }
                    if (!(json instanceof Map)) {
                        throw issueLevelError("Get transitions for issue json has unrecognized structure, please contact Exalate Support: " + resultStr)
                    }
                    if (!(json.transitions instanceof List)) {
                        throw issueLevelError("Get transitions for issue .transitions json has unrecognized structure, please contact Exalate Support: " + resultStr)
                    }
                    json as Map<String, Object>
                }

                def getDirectTransitionsViaIssue = { String issueKeyStr ->
                    def tsResponse = getTransitionsForIssue(issueKeyStr)
                    def ts = tsResponse.transitions as List<Map<String, Object>>;
                    def result = ts
                            .collect { t ->
                        [
                                "id"    : t.id as String,
                                "name"  : t.name as String,
                                "to"    : [
                                        "id"  : t.to.id as String,
                                        "name": t.to.name as String
                                ],
                                "global": t.isGlobal,
                                "fields": t.fields
                        ]
                    }
                    // throw issueLevelError("Result: " + result)
                    return result
                }
                def getWorkflow = { String workflowName, String projectKey, String issueKeyStr ->
                    def json = getWorkflowDetailsForProject(workflowName, projectKey);
//                    def sources = (json as Map<String, Object>).sources as List<Map<String, Object>>;
//                    def transitions = sources
//                            .collect { src ->
//                        if (!(src.fromStatus instanceof Map<String, Object>)) {
//                            throw issueLevelError("Workflow details sources `" + src + "` from status `" + src.fromStatus + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
//                        }
//                        def fromStatus = src.fromStatus as Map<String, Object>
//                        if (!(fromStatus.id instanceof String)) {
//                            throw issueLevelError("Workflow details sources `" + src + "` from status `" + fromStatus + "` id `" + fromStatus.id + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
//                        }
//                        if (!(fromStatus.name instanceof String)) {
//                            throw issueLevelError("Workflow details sources `" + src + "` from status `" + fromStatus + "` name `" + fromStatus.name + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
//                        }
//                        if (!(src.targets instanceof List)) {
//                            throw issueLevelError("Workflow details sources `" + src + "` targets `" + src.targets + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
//                        }
//                        def targets = src.targets as List<Map<String, Object>>;
//                        def ts = targets.collect { target ->
//                            if (!(target instanceof Map<String, Object>)) {
//                                throw issueLevelError("Workflow details sources `" + src + "` target `" + target + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
//                            }
//                            if (!(target.toStatus instanceof Map<String, Object>)) {
//                                throw issueLevelError("Workflow details sources `" + src + "` target `" + target + "` toStatus `" + target.toStatus + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
//                            }
//                            def toStatus = target.toStatus as Map<String, Object>;
//                            if (!(toStatus.id instanceof String)) {
//                                throw issueLevelError("Workflow details sources `" + src + "` target `" + target + "` toStatus `" + toStatus + "` id `" + toStatus.id + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
//                            }
//                            if (!(toStatus.name instanceof String)) {
//                                throw issueLevelError("Workflow details sources `" + src + "` target `" + target + "` toStatus `" + toStatus + "` name `" + toStatus.name + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
//                            }
//                            if (!(target.transitionName instanceof String)) {
//                                throw issueLevelError("Workflow details sources `" + src + "` target `" + target + "` transitionName `" + target.transitionName + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
//                            }
//                            [
//                                    "name"  : target.transitionName,
//                                    "from"  : [
//                                            "id"  : fromStatus.id,
//                                            "name": fromStatus.name,
//                                    ],
//                                    "to"    : [
//                                            "id"  : toStatus.id,
//                                            "name": toStatus.name,
//                                    ],
//                                    "global": false
//                            ]
//                        }
//
//
//                        ts
//                    }

                    Map<String, String> statusIdToName = json."statuses".inject([:] as Map<String, String>) { Map<String, String> result, Map<String, Object> status ->
                        result[status."id" as String] = (status."name" as String)
                        result
                    } as Map<String, String>;
                    def addValidators = { Map<String, Object> jiraTransition, Map<String, Object> exaTransition ->
                        def validators = jiraTransition."rules"?."validators" as List<Map<String, Object>>;
                        def vs = validators?.collect { v ->
                            if (v."type" == "FieldRequiredValidator" && v."fields" instanceof List) {
                                def fs = v."fields" as List<String>;
                                ["type":"REQUIRED_FIELD_VALIDATOR", "fields":fs]
                            } else {
                                null
                            }
                        }?.findAll()
                        if (vs) {
                            exaTransition."validators" = vs
                        }
                        exaTransition
                    }
                    def transitionsFiltered = json."transitions".findAll{!(it."from".empty && it."to" == "")}
                    def transitions = transitionsFiltered.inject([] as List<Map<String, Object>>) { List<Map<String, Object>>result, Map<String, Object> transition ->
                        def ts
                        if (transition."from".empty && transition."type" == "global") {
                            def t = [
                              "name"  : transition.name as String,
                              "to"    : [
                                      "id"  : transition."to" as String,
                                      "name": statusIdToName[transition."to" as String],
                              ],
                              "global": true
                            ]
                            addValidators(transition, t)
                            ts = [t]
                        } else {
                            ts = transition."from".collect { String from ->
                                def t = [
                                        "name"  : transition.name as String,
                                        "from"  : [
                                                "id"  : from,
                                                "name": statusIdToName[from] as String,
                                        ],
                                        "to"    : [
                                                "id"  : transition."to" as String,
                                                "name": statusIdToName[transition."to" as String],
                                        ],
                                        "fields": transition?.rules?.validators?.collect{it.configuration?.fields}?.flatten().findAll{it != null} ?: [],
                                        "global": false
                                ]
//                                addValidators(transition, t)
                                t
                            }
                        }
                        result += ts
                        result
                    }
                    //FIXME: why do we get gloal transitions in separte method if transitions already should contain the global transitions
                    def allTransitions = transitions.sort{ a, b -> (a."global" && b."global")? 0 : a."global" ? -1 : 1 }
                    [
                            "name"       : workflowName,
                            "transitions": allTransitions,
                            "steps"      : allTransitions.inject([]) { List<Map<String, Object>> result, t ->
                                def stepsToAddToResult = t.global ? [t.to] : [t.from, t.to]
                                stepsToAddToResult.inject(result) { r, s ->
                                    def curStep = r.find { step -> step.id == s.id }
                                    if (curStep != null) {
                                        // the step is already in the result steps list
                                        if (t?.from?.id == s.id && !curStep.transitions?.any { it.name == t.name }) {
                                            // if the transition is from this step
                                            curStep.transitions += t
                                        }
                                    } else {
                                        // the step is not in the result steps list
                                        r += [
                                                "id"         : s.id,
                                                "name"       : s.name,
                                                "transitions": t?.from?.id == s.id ? [t] : []
                                        ]
                                    }
                                    r
                                }
                            }
                    ]
                }
                def transition = { com.exalate.basic.domain.BasicIssueKey zissueKey ->
                    { Map<String, Object> t, String transitionExecUserKey, Map<String, Object> fields ->
                        if (t.id == null) {
                            def currentlyAvailableTransitions = getTransitionsForIssue(zissueKey.URN).transitions as List<Map<String, Object>>;
                            def zTransition = currentlyAvailableTransitions.find { Map<String, Object> aTransition ->
                                aTransition.name as String == t.name as String &&
                                        aTransition.to?.id as Long == t.to.id as Long
                            }
                            if (zTransition == null) {
                                throw issueLevelError("Failing to transition because the transition found by algorithm `" + t.name + "` is no longer available for current step `" + t.from?.name + "` (" + t.from?.id + ")  transition data: " + t + ". Please contact Exalate Support" +
                                        "\nAvailable transitions:" + currentlyAvailableTransitions)
                            }
                            t.id = zTransition.id
                        }

                        def fieldsUpdate = [:]
                        if(t.fields){

                           //TODO: This approach only check for transition custom fields but won't set system fields. Support system fields
                           def requiredCustomFields = issue.customFields.findAll{cf ->
                               t.fields.any{
                                  /*
                                  We might have different format of the required fields depending if this is a first transition or any subsequent transition
                                  This is happening because we are using 2 different REST APIs to get transition information
                                  WARNING: there is a limitation to get required fields for the first transition as they only are detected if there is a screen for them
                                  */
                                  if (it instanceof String){
                                     it == "customfield_" + cf.value.id
                                  }else {
                                    it.value.schema.customId != null && it.value.schema.customId == cf.value.id
                                  }
                               }
                           }

                           fieldsUpdate = requiredCustomFields.collect{cf ->
                                def cfId = "customfield_" + cf.value.id
                                if(cf.value.value == null) return [:]
                                //TODO: Have a better check of other types
                                def stringV
                                if (cf.value.value instanceof String && cf.value.type == com.exalate.api.domain.hubobject.v1_2.HubCustomFieldType.OPTION) {
                                    stringV = cf.value.value
                                    return [(cfId): ["value": stringV]]
                                }
                                if(cf.value.type == com.exalate.api.domain.hubobject.v1_2.HubCustomFieldType.NUMERIC){
                                  stringV = cf.value.value
                                } else if(cf.value.value instanceof com.exalate.basic.domain.hubobject.v1.BasicHubOption) {
                                  stringV = cf.value.value.value
                                  return [(cfId): ["value": stringV]]
                                } else {
                                  stringV = cf.value.value.toString()
                                }
                                return [(cfId): stringV]

                            }.collectEntries()

                           if(t.fields.any{
                           if(it instanceof String){
                              return it == "assignee"
                           }else {
                              it.getKey() == "assignee"
                           }}){
                              if(issue.assignee){
                                fieldsUpdate = fieldsUpdate += ["assignee": ["id": issue.assignee?.key]]
                              }
                           }

                        }
                        def json = [
                                "fields": fieldsUpdate,
                                "transition": [
                                        "id": t.id
                                ],
                        ] as Map<String, Object>
                        if (fields != null) {
                            json.fields = fields
                        }
                        def jsonStr = groovy.json.JsonOutput.toJson(json)
                        def response
                        try {
                            //noinspection GroovyAssignabilityCheck
                            httpClient.post("/rest/api/2/issue/"+ zissueKey.URN + "/transitions", jsonStr)
                        } catch (Exception e) {
                            throw issueLevelError2("Unable to transition issue `" + zissueKey.URN + "`, please contact Exalate Support:  \n" +
                                    "POST " + jiraCloudUrl + "/rest/api/2/issue/" + zissueKey.URN + "/transitions\nBody: " + jsonStr + "\nError Message:" + e.message, e)
                        }
                    }
                }
                def getTransitions = { String statusName, Map<String, Object> wf ->
                    def step = wf.steps.find { s -> s.name.equalsIgnoreCase(statusName) }
                    if (step == null) {
                        throw issueLevelError("Can not find step `" + statusName + "`. Available steps: `" + wf.steps.collect { s -> s.name + " (" + s.id + ")" } + "`. Create it or review the transitioning script.")
                    }
                    step.transitions
                }
                def getGlobalTransitions = { Map<String, Object> wf ->
                    wf
                            .transitions
                            .findAll { t -> t.global }
                }
                def getAllTransitionsFromStatus = { String statusName, List<String> visitedStates, Map<String, Object> wf ->
                    (getTransitions(statusName, wf) + getGlobalTransitions(wf))
                            .findAll { t -> !visitedStates.any { it.equalsIgnoreCase(t.to.name) } }
                }
                def getTransitionPathInternal
                getTransitionPathInternal = { transitionWithParent ->
                    if (!transitionWithParent) return null;
                    if (transitionWithParent.parentTransition == null) {
                        return [transitionWithParent.transition]
                    }
                    getTransitionPathInternal(transitionWithParent.parentTransition) + [transitionWithParent.transition]
                }
                def getPathsFromAtoBInternal = { String currentStatusName, String targetStatusName, Map<String, Object> wf ->
                    if (currentStatusName.equalsIgnoreCase(targetStatusName)) {
                        return null
                    }
                    def q = [] as Queue<Map<String, Object>>;
                    def visitedStates = [] as List<String>;
                    // We first check if there is a direct transition that would take us to the desired status
                    def allFirstTransitions = getAllTransitionsFromStatus(currentStatusName, visitedStates, wf) as List<Map<String, Object>>;
                    for (aTransition in allFirstTransitions) {
                        def nextStatus = aTransition.to.name as String
                        def transitionWithParent = ["transition": aTransition, "parentTransition": null]
                        if (nextStatus.equalsIgnoreCase(targetStatusName)) {
                            return transitionWithParent
                        }
                        if (aTransition.from) {
                            visitedStates.add(aTransition.from.name as String)
                        }
                        q.add(transitionWithParent)
                    }
                    //solution -> every state has a from transition property
                    while (!q.isEmpty()) {
                        def transitionWithParent = q.remove()
                        def nextStatus = transitionWithParent.transition.to.name as String
                        def childTransitions = getAllTransitionsFromStatus(nextStatus, visitedStates, wf) as List<Map<String, Object>>;
                        for (childTransition in childTransitions) {
                            def nextChildStatus = childTransition.to.name as String
                            if (targetStatusName.equalsIgnoreCase(nextChildStatus)) {
                                return ["transition": childTransition, "parentTransition": transitionWithParent]
                            }
                            q.add(["transition": childTransition, "parentTransition": transitionWithParent])
                        }
                        visitedStates.add(nextStatus)
                    }
                }
                def getEditIssueMeta = { String issueKeyStr ->
//    GET /rest/api/2/issue/{issueIdOrKey}/editmeta
                    def response
                    try {
                        //noinspection GrUnresolvedAccess
                        response = await(await(httpClient.authenticate(
                                none(),
                                httpClient
                                        .ws()
                                        .url(jiraCloudUrl + "/rest/api/2/issue/" + issueKeyStr + "/editmeta")
                                        .withMethod("GET"),
                                gs
                        )).get())
                    } catch (Exception e) {
                        throw issueLevelError2("Unable to get edit meta for issue, please contact Exalate Support: " +
                                "\nRequest: GET /rest/api/2/issue/" + issueKeyStr + "/editmeta" +
                                "\nError: " + e.message, e)
                    }
                    if (response.status() != 200) {
                        throw issueLevelError("Can not get edit meta for issue (status " + response.status() + "), please contact Exalate Support: " +
                                "\nRequest: GET /rest/api/2/issue/" + issueKeyStr + "/editmeta" +
                                "\nResponse: " + response.body())
                    }
                    def resultStr = response.body()
                    def s = new groovy.json.JsonSlurper()
                    def json
                    try {
                        json = s.parseText(resultStr)
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse the get edit meta for issue json, please contact Exalate Support: " + resultStr, e)
                    }

                    /*
            {"fields":{
                "summary":{"required":true,"schema":{"type":"string","system":"summary"},"name":"Summary","key":"summary","operations":["set"]},
                "issuetype":{"required":true,"schema":{"type":"issuetype","system":"issuetype"},"name":"Issue Type","key":"issuetype","operations":[],"allowedValues":[{"self":"https://tsleft.atlassian.net/rest/api/2/issuetype/10002","id":"10002","description":"A task that needs to be done.","iconUrl":"https://tsleft.atlassian.net/secure/viewavatar?size=xsmall&avatarId=10318&avatarType=issuetype","name":"Task","subtask":false,"avatarId":10318},{"self":"https://tsleft.atlassian.net/rest/api/2/issuetype/10001","id":"10001","description":"Created by Jira Agile - do not edit or delete. Issue type for a user story.","iconUrl":"https://tsleft.atlassian.net/secure/viewavatar?size=xsmall&avatarId=10315&avatarType=issuetype","name":"Story","subtask":false,"avatarId":10315},{"self":"https://tsleft.atlassian.net/rest/api/2/issuetype/10004","id":"10004","description":"A problem which impairs or prevents the functions of the product.","iconUrl":"https://tsleft.atlassian.net/secure/viewavatar?size=xsmall&avatarId=10303&avatarType=issuetype","name":"Bug","subtask":false,"avatarId":10303},{"self":"https://tsleft.atlassian.net/rest/api/2/issuetype/10000","id":"10000","description":"Created by Jira Software - do not edit or delete. Issue type for a big user story that needs to be broken down.","iconUrl":"https://tsleft.atlassian.net/images/icons/issuetypes/epic.svg","name":"Epic","subtask":false}]},
                "components":{"required":false,"schema":{"type":"array","items":"component","system":"components"},"name":"Component/s","key":"components","operations":["add","set","remove"],"allowedValues":[]},
                "description":{"required":false,"schema":{"type":"string","system":"description"},"name":"Description","key":"description","operations":["set"]},
                "customfield_10064":{"required":false,"schema":{"type":"string","custom":"com.atlassian.jira.plugin.system.customfieldtypes:textfield","customId":10064},"name":"Remote Issue ID","key":"customfield_10064","operations":["set"]},
                "customfield_10010":{"required":false,"schema":{"type":"array","items":"string","custom":"com.pyxis.greenhopper.jira:gh-sprint","customId":10010},"name":"Sprint","key":"customfield_10010","operations":["set"]},
                "fixVersions":{"required":false,"schema":{"type":"array","items":"version","system":"fixVersions"},"name":"Fix Version/s","key":"fixVersions","operations":["set","add","remove"],"allowedValues":[]},
                "priority":{"required":false,"schema":{"type":"priority","system":"priority"},"name":"Priority","key":"priority","operations":["set"],"allowedValues":[{"self":"https://tsleft.atlassian.net/rest/api/2/priority/1","iconUrl":"https://tsleft.atlassian.net/images/icons/priorities/highest.svg","name":"Highest","id":"1"},{"self":"https://tsleft.atlassian.net/rest/api/2/priority/2","iconUrl":"https://tsleft.atlassian.net/images/icons/priorities/high.svg","name":"High","id":"2"},{"self":"https://tsleft.atlassian.net/rest/api/2/priority/3","iconUrl":"https://tsleft.atlassian.net/images/icons/priorities/medium.svg","name":"Medium","id":"3"},{"self":"https://tsleft.atlassian.net/rest/api/2/priority/4","iconUrl":"https://tsleft.atlassian.net/images/icons/priorities/low.svg","name":"Low","id":"4"},{"self":"https://tsleft.atlassian.net/rest/api/2/priority/5","iconUrl":"https://tsleft.atlassian.net/images/icons/priorities/lowest.svg","name":"Lowest","id":"5"}]},
                "labels":{"required":false,"schema":{"type":"array","items":"string","system":"labels"},"name":"Labels","key":"labels","autoCompleteUrl":"https://tsleft.atlassian.net/rest/api/1.0/labels/suggest?query=","operations":["add","set","remove"]},
                "customfield_10008":{"required":false,"schema":{"type":"any","custom":"com.pyxis.greenhopper.jira:gh-epic-link","customId":10008},"name":"Epic Link","key":"customfield_10008","operations":["set"]},
                "attachment":{"required":false,"schema":{"type":"array","items":"attachment","system":"attachment"},"name":"Attachment","key":"attachment","operations":[]},
                "issuelinks":{"required":false,"schema":{"type":"array","items":"issuelinks","system":"issuelinks"},"name":"Linked Issues","key":"issuelinks","autoCompleteUrl":"https://tsleft.atlassian.net/rest/api/2/issue/picker?currentProjectId=&showSubTaskParent=true&showSubTasks=true&currentIssueKey=LPROJ-9&query=","operations":["add"]},
                "comment":{"required":false,"schema":{"type":"comments-page","system":"comment"},"name":"Comment","key":"comment","operations":["add","edit","remove"]},
                "assignee":{"required":false,"schema":{"type":"user","system":"assignee"},"name":"Assignee","key":"assignee","autoCompleteUrl":"https://tsleft.atlassian.net/rest/api/latest/user/assignable/search?issueKey=LPROJ-9&username=","operations":["set"]},
                "resolution":{"required":false,"schema":{"type":"resolution","system":"resolution"},"name":"Resolution","key":"resolution","operations":["set"],"allowedValues":[{"self":"https://tsleft.atlassian.net/rest/api/2/resolution/10000","name":"Done","id":"10000"},{"self":"https://tsleft.atlassian.net/rest/api/2/resolution/10001","name":"Won't Do","id":"10001"},{"self":"https://tsleft.atlassian.net/rest/api/2/resolution/10002","name":"Duplicate","id":"10002"},{"self":"https://tsleft.atlassian.net/rest/api/2/resolution/10003","name":"Cannot Reproduce","id":"10003"}]}
            }}
                */
                    if (!(json instanceof Map)) {
                        throw issueLevelError("Get edit meta for issue json has unrecognized structure, please contact Exalate Support: " + resultStr)
                    }
                    if (!(json.fields instanceof Map)) {
                        throw issueLevelError("Get edit meta for issue `.fields` json has unrecognized structure, please contact Exalate Support: " + resultStr)
                    }
                    json as Map<String, Object>
                }

                // applying the resolution
                if (
                issue.resolution?.name != replica.resolution?.name &&
                        (getEditIssueMeta(jIssue.key).fields as Map<String, Object>)
                                .values()
                                .any { meta ->
                            meta.key == "resolution" &&
                                    meta.schema?.system == "resolution"
                        }
                ) {
                    if (replica.resolution?.name == null) {
                        issue.resolution = null
                    } else {
                        def resolution = nodeHelper.getResolution(replica.resolution?.name)
                        if (resolution == null) {
                            throw issueLevelError("Could not find resolution `" + replica.resolution?.name + "`. Please create one and resolve the error or contact Exalate Supprt.")
                        }
                        issue.resolution = resolution
                    }
                }
                if (desiredStatusName != null && !jIssue.status.name.equalsIgnoreCase(desiredStatusName)) {
                    //TODO: add transition validators from workflow, currently we only get field required if there is a screen for them
                    def directTrans = getDirectTransitionsViaIssue(issue.key)
                    directTrans = directTrans.sort{ a, b -> (a."global" && b."global")? 0 : a."global" ? -1 : 1 }
                    def directTransToDesiredStatus = directTrans.find { t -> desiredStatusName.equalsIgnoreCase(t?.to?.name) }
                    if (directTransToDesiredStatus != null) {
                        transition(localExIssueKey)(directTransToDesiredStatus as Map<String, Object>, null, null as Map<String, Object>)
                        def newStatusHub = new com.exalate.basic.domain.hubobject.v1.status.BasicHubStatus()
                        newStatusHub.name = desiredStatusName
                        issue.status = newStatusHub
                        return
                    }
                    if(onlyDirectTransitions){
                        return
                    }
                    def wfName = getWorkflowName(issue.project.id as String, issue.type.id, jIssue.status.name, desiredStatusName)
                    def wf = getWorkflow(wfName, issue.project.key, jIssue.key)
                    if (!wf.steps?.any { step -> desiredStatusName.equalsIgnoreCase(step?.name) }) {
                        def onStatusFoundResult = onNoStatusFoundFn(desiredStatusName, wf, jIssue)
                        if (onStatusFoundResult == null) {
                            // don't try to sync status, since we've indicated that we don't want to change anything
                            return
                        }
                    }
                    def shortestPath = getTransitionPathInternal(getPathsFromAtoBInternal(jIssue.status.name, desiredStatusName, wf))
                    if (shortestPath == null) {
                        throw issueLevelError(
                                "Can not find path from `" + jIssue.status.name + "` to `" + desiredStatusName + "` " +
                                        "in workflow `" + wf.name + ". " +
                                        "Please review whether `" + wf.name + "` is the correct workflow for the issue `" + jIssue.key + "`."
                        )
                    }
                    shortestPath.each { t ->
                        transition(localExIssueKey)(t as Map<String, Object>, null, null as Map<String, Object>)
                    }

                    def newStatusHub = new com.exalate.basic.domain.hubobject.v1.status.BasicHubStatus()
                    newStatusHub.name = desiredStatusName
                    issue.status =newStatusHub
                }
            } catch (com.exalate.api.exception.IssueTrackerException ite) {
                throw ite
            } catch (Exception e) {
                throw new com.exalate.api.exception.IssueTrackerException(e)
            }
        })()
        issue
    }

}