import com.exalate.api.domain.connection.IConnection
import com.exalate.api.domain.request.ISyncRequest
import com.exalate.basic.domain.hubobject.v1.BasicHubIssue
import com.exalate.hubobject.jira.AttachmentHelper
import com.exalate.hubobject.jira.CommentHelper
import scala.collection.Seq
import services.jcloud.hubobjects.NodeHelper
import services.replication.PreparedHttpClient

/**
Usage:

ChangeProcessor.execute(
  replica,
  issue,
  previous,
  issueBeforeScript,
  issueKey,
  connection,
  traces,
  blobMetadataList,
  nodeHelper,
  commentHelper,
  attachmentHelper,
  workLogHelper,
  workflowHelper,
  serviceDeskHelper,
  log,
  httpClient,
  syncRequest
)
 * */
class ChangeProcessor {
    static execute(
            BasicHubIssue replica,
            BasicHubIssue issue,
            BasicHubIssue previous,
            BasicHubIssue issueBeforeScript,
            com.exalate.basic.domain.BasicIssueKey issueKey,
            IConnection connection,
            List<com.exalate.api.domain.twintrace.INonPersistentTrace> traces,
            Seq<com.exalate.api.domain.IBlobMetadata> blobMetadataList,
            NodeHelper nodeHelper,
            CommentHelper commentHelper,
            AttachmentHelper attachmentHelper,
            com.exalate.hubobject.jira.WorkLogHelper workLogHelper,
            com.exalate.hubobject.jira.WorkflowHelper workflowHelper,
            services.jcloud.hubobjects.ServiceDeskHelper serviceDeskHelper,
            log, //ch.qos.logback.classic.Logger log
            PreparedHttpClient httpClient,
            ISyncRequest syncRequest) {
        log.info("Debugging. Remote type: " + replica.type?.name)
        log.info("Debugging. Remote project key: " + replica.project?.key)
        log.info("Debugging. Local comments: " + issue.comments)
        log.info("Debugging. Remote type (from previous): " + previous.type?.name)

        if (replica.type?.name != previous.type?.name){
            issue.comments = commentHelper.addComment(
                    "This issue was moved on itrack.barco.com from issuetype ${previous.type?.name} to ${replica.type?.name}. Please move the issue manually to the new issuetype",
                    issue.comments)
        }
        if (replica.project?.name != previous.project?.name){
            issue.comments = commentHelper.addComment(
                    "This issue was moved on itrack.barco.com from project ${previous.project?.name} to ${replica.project?.name}. Please move the issue manually to the new project",
                    issue.comments)
        }
        issue.summary = replica.summary
        issue.description = replica.description
        issue.environment = replica.environment
        issue.securityLevel = replica.securityLevel

//        def desiredIssueType = replica.type?.name
//        issue.type = nodeHelper.getIssueType(desiredIssueType, issue.project)
//        issue.typeName     = issue.type?.name
//        if (issue.type == null) {
//            throw new com.exalate.api.exception.IssueTrackerException("""
//Can not find issue type `${desiredIssueType}` for the project `${issue.project?.key}`.
//Please check project settings or change the script""".toString()
//            )
//        }
        def desiredPriority = replica.priority.name
        def localPrio = nodeHelper.getPriority(desiredPriority)
        if (localPrio == null) {
//            throw new com.exalate.api.exception.IssueTrackerException("""
//Can not find priority `${desiredPriority}` for the project `${issue.project?.key}`.
//Please check project settings or change the script""".toString()
//            )
        } else {
            issue.priority = localPrio
        }

        issue.labels = replica.labels

        issue.due = replica.due

        if (replica.assignee) {
            issue.assignee = nodeHelper.getUserByEmail(replica.assignee?.email) ?: ({
                def defaultAssignee = new com.exalate.basic.domain.hubobject.v1.BasicHubUser()
                defaultAssignee.key = "-1"
                defaultAssignee
            })()
        } else {
            issue.assignee = null
        }

        VersionSync.receive(replica, issue, connection, nodeHelper, httpClient)
        ComponentSync.receive(replica, issue, connection, nodeHelper, httpClient)

        def js = new groovy.json.JsonSlurper()
        def fieldsJsonStr = new JiraClient(httpClient).http(
                "GET",
                "/rest/api/2/issue/${issue.key}/editmeta".toString(),
                null,
                null,
                ["Accept": ["application/json"]]
        )
        def fieldsJson = js.parseText(fieldsJsonStr)
        def getFieldJson = { String fieldName ->
            fieldsJson."fields".values().find { Map<String, Object> v -> v."name" == fieldName } as Map<String, Object>
        }
        def canBeSet = { String fieldName ->
            def fieldJson = getFieldJson(fieldName)
            fieldJson != null
        }
        def getExOption = { String fieldName, String optValue ->
            def fieldJson = getFieldJson(fieldName)
            fieldJson?."allowedValues"?.findAll { Map<String, Object> v ->
                v."value" == optValue
            }?.collect { Map<String, Object> v ->
                def exOption = new com.exalate.basic.domain.hubobject.v1.BasicHubOption()
                exOption.id = v."id" as Long
                exOption.value = v."value" as String
                exOption
            }?.find()
        }
        if (replica.customFields."Business Value"?.value && canBeSet("Business Value")) issue.customFields."Business Value"?.value = replica.customFields."Business Value"?.value
        if (replica.customFields."Flagged"?.value && canBeSet("Flagged")) issue.customFields."Flagged"?.value = replica.customFields."Flagged".value.collect { com.exalate.basic.domain.hubobject.v1.BasicHubOption v ->
            getExOption("Flagged", v.value)
        }?.findAll()
        if (replica.customFields."Severity"?.value && canBeSet("Severity")) issue.customFields."Severity"?.value = getExOption("Severity", replica.customFields."Severity"?.value?.value as String)
        if (replica.customFields."Start date"?.value && canBeSet("Start date")) issue.customFields."Start date"?.value = replica.customFields."Start date"?.value

        if (replica.customFields."Observed By"?.value && canBeSet("Observed By")) issue.customFields."Observed By"?.value = getExOption("Observed By", replica.customFields."Observed By"?.value?.value as String)
        if (replica.customFields."Observed During"?.value && canBeSet("Observed During")) issue.customFields."Observed During"?.value = getExOption("Observed During", replica.customFields."Observed During"?.value?.value as String)
        if (replica.customFields."Project Type"?.value && canBeSet("Project Type")) issue.customFields."Project Type"?.value = getExOption("Project Type", replica.customFields."Project Type"?.value?.value as String)
        //if (replica.customFields."Region"?.value && canBeSet("Region")) issue.customFields."Region"?.value = getExOption("Region", replica.customFields."Region"?.value?.value as String)
        if (replica.customFields."Status Detail"?.value && canBeSet("Status Detail")) issue.customFields."Status Detail"?.value = getExOption("Status Detail", replica.customFields."Status Detail"?.value?.value as String)
        if (replica.customFields."Customer Support ID"?.value && canBeSet("Customer Support ID")) issue.customFields."Customer Support ID"?.value = replica.customFields."Customer Support ID"?.value
        if (replica.customFields."Serial Number"?.value && canBeSet("Serial Number")) issue.customFields."Serial Number"?.value = replica.customFields."Serial Number"?.value


        AgileSync.receiveEpicBeforeCreation(replica, issue, nodeHelper, httpClient)

        AgileSync.receiveEpicAfterCreation(replica, issue, nodeHelper, httpClient)
        AgileSync.receiveRank(replica, issue, nodeHelper, httpClient)
        //Temporarily commented out sprint calls, issue https://support.idalko.com/servicedesk/customer/portal/8/EASE-3755
        //AgileSync.receiveSprints(AgileSync.skipOnNoBoardFound(), replica, issue, nodeHelper, httpClient)

        issue.attachments = attachmentHelper.mergeAttachments(issue, replica)
        replica.removedComments = []
        issue.comments = commentHelper.mergeComments(issue, replica)

        IssueLinkSync.receive(replica, issue, httpClient, nodeHelper)

        // RESOLUTION
        if (replica.resolution == null && issue.resolution != null) {
            // if the remote issue is not resolved, but the local issue is

            issue.resolution = null
        }
        if (replica.resolution != null && issue.resolution == null) {
            // the remote issue is resolved, but the local isn't - look up the correct resolution object.

            // use 'done' as resolution if the remote resolution is not found
            issue.resolution = nodeHelper.getResolution(replica.resolution.name) ?: nodeHelper.getResolution("Done")
        }

        if (replica.project.name == issue.project.name && replica.type.name == issue.type.name) {
            def desiredStatusName = ([:].find { k,_ -> k.equalsIgnoreCase(replica.status?.name) }?.value)
            if (true && desiredStatusName == null) {
                desiredStatusName = replica.status?.name
            }
            Status.receive()
        }
    }
}
