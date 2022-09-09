import com.exalate.api.domain.connection.IConnection
import com.exalate.api.domain.request.ISyncRequest
import com.exalate.basic.domain.hubobject.v1.BasicHubIssue
import com.exalate.basic.domain.hubobject.v1.BasicHubOption
import com.exalate.hubobject.jira.AttachmentHelper
import com.exalate.hubobject.jira.CommentHelper
import services.jcloud.hubobjects.NodeHelper
import services.replication.PreparedHttpClient

/**
Usage:

CreateProcessor.execute(
  replica,
  issue,
  issueBeforeScript,
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
class CreateProcessor {
    static execute(
            BasicHubIssue replica,
            BasicHubIssue issue,
            BasicHubIssue issueBeforeScript,
            IConnection connection,
            List<com.exalate.api.domain.twintrace.INonPersistentTrace> traces,
            scala.collection.Seq<com.exalate.api.domain.IBlobMetadata> blobMetadataList,
            NodeHelper nodeHelper,
            CommentHelper commentHelper,
            AttachmentHelper attachmentHelper,
            com.exalate.hubobject.jira.WorkLogHelper workLogHelper,
            com.exalate.hubobject.jira.WorkflowHelper workflowHelper,
            services.jcloud.hubobjects.ServiceDeskHelper serviceDeskHelper,
            log, //ch.qos.logback.classic.Logger log
            PreparedHttpClient httpClient,
            ISyncRequest syncRequest) {

        issue.summary = replica.summary
        issue.description = replica.description
        issue.environment = replica.environment
        issue.securityLevel = replica.securityLevel

        def desiredProjectName = replica.project?.name
        issue.project = GetProject.byName(desiredProjectName, httpClient)
        issue.projectKey = issue.project?.key
        if (issue.project == null) {
            throw new com.exalate.api.exception.IssueTrackerException("""
Can not find project `${desiredProjectName}`. 
Please create it or change the script""".toString()
            )
        }

        def issueTypeMapping = [
//                "Defect" : "Bug",
//                "Request" : "Improvement",
        ]
        def desiredIssueType = issueTypeMapping[replica.type?.name] ?: replica.type?.name
        issue.type = nodeHelper.getIssueType(desiredIssueType)
        issue.typeName     = issue.type?.name
        if (issue.type == null) {
            throw new com.exalate.api.exception.IssueTrackerException("""
Can not find issue type `${desiredIssueType}` for the project `${issue.project?.key}`.
Please check project settings or change the script""".toString()
            )
        }
        def desiredPriority = replica.priority.name
        issue.priority = nodeHelper.getPriority(desiredPriority)
        if (issue.priority == null) {
            throw new com.exalate.api.exception.IssueTrackerException("""
Can not find priority `${desiredPriority}` for the project `${issue.project?.key}`.
Please check project settings or change the script""".toString()
            )
        }

        issue.labels = replica.labels

        issue.due = replica.due

        VersionSync.receive(replica, issue, connection, nodeHelper, httpClient)
        ComponentSync.receive(replica, issue, connection, nodeHelper, httpClient)

        def js = new groovy.json.JsonSlurper()
        def fieldsJson = js.parseText(new JiraClient(httpClient).http(
                "GET",
                "/rest/api/2/issue/createmeta",
                [
                        "projectKeys":[issue.projectKey],
                        "issuetypeNames":[issue.typeName],
                        "expand":["projects.issuetypes.fields"]
                ],
                null,
                ["Accept":["application/json"]]
        ))
        def canBeSet = { String fieldName ->
            def projectJson = fieldsJson."projects".find()
            if (!projectJson) return false
            def issueTypeJson = projectJson."issuetypes".find { it."name" == issue.typeName }
            if (!issueTypeJson) return false
            def fieldJson = issueTypeJson."fields".find { _, Map<String, Object> v -> v."name" == fieldName }
            fieldJson != null
        }
        if (replica.customFields."Business Value"?.value && canBeSet("Business Value")) issue.customFields."Business Value"?.value = replica.customFields."Business Value"?.value
        if (replica.customFields."Flagged"?.value && canBeSet("Flagged")) issue.customFields."Flagged"?.value = replica.customFields."Flagged".value.collect { BasicHubOption v ->
            nodeHelper.getOption(issue, "Flagged", v.value)
        }
        if (replica.customFields."Severity"?.value && canBeSet("Severity")) issue.customFields."Severity"?.value = nodeHelper.getOption(issue, "Severity", replica.customFields."Severity"?.value?.value as String)
        if (replica.customFields."Start date"?.value && canBeSet("Start date")) issue.customFields."Start date"?.value = replica.customFields."Start date"?.value
        if (replica.customFields."Observed By"?.value && canBeSet("Observed By")) issue.customFields."Observed By"?.value = nodeHelper.getOption(issue, "Observed By", replica.customFields."Observed By"?.value?.value as String)
        if (replica.customFields."Observed During"?.value && canBeSet("Observed During")) issue.customFields."Observed During"?.value = nodeHelper.getOption(issue, "Observed During", replica.customFields."Observed During"?.value?.value as String)
        if (replica.customFields."Project Type"?.value && canBeSet("Project Type")) issue.customFields."Project Type"?.value = nodeHelper.getOption(issue, "Project Type", replica.customFields."Project Type"?.value?.value as String)
        if (replica.customFields."Region"?.value && canBeSet("Region")) issue.customFields."Region"?.value = nodeHelper.getOption(issue, "Region", replica.customFields."Region"?.value?.value as String)
        if (replica.customFields."Status Detail"?.value && canBeSet("Status detail")) issue.customFields."Status Detail"?.value = nodeHelper.getOption(issue, "Status detail", replica.customFields."Status Detail"?.value?.value as String)
        if (replica.customFields."Scope Definition"?.value && canBeSet("Scope Definition")) issue.customFields."Scope Definition"?.value = nodeHelper.getOption(issue, "Scope Definition", replica.customFields."Scope Definition"?.value?.value as String)
        if (replica.customFields."Customer Support ID"?.value && canBeSet("Customer Support ID")) issue.customFields."Customer Support ID"?.value = nodeHelper.getOption(issue, "Customer Support ID", replica.customFields."Customer Support ID"?.value?.value as String)
        if (replica.customFields."Serial Number"?.value && canBeSet("Serial Number")) issue.customFields."Serial Number"?.value = nodeHelper.getOption(issue, "Serial Number", replica.customFields."Serial Number"?.value?.value as String)

        /*issue.customFields."Original Issue".value = GetIssueUrl.getRemote(replica.key, connection)*/
        issue.customFields?."Original Issue"?.value = "https://itrack.barco.com/browse/"+replica.key


        SubTaskSync.receiveBeforeCreation(replica, issue, nodeHelper)
        AgileSync.receiveEpicBeforeCreation(replica, issue, nodeHelper, httpClient)

        if (replica.assignee) {
            issue.assignee = nodeHelper.getUserByEmail(replica.assignee?.email) ?: ({
                def defaultAssignee = new com.exalate.basic.domain.hubobject.v1.BasicHubUser()
                defaultAssignee.key = "-1"
                defaultAssignee
            })()
        }

        return CreateIssue.create(
                replica,
                issue,
                connection,
                issueBeforeScript,
                traces,
                blobMetadataList,
                httpClient,
                syncRequest) {

            SubTaskSync.receiveAfterCreation(replica, issue, connection, nodeHelper)
            AgileSync.receiveEpicAfterCreation(replica, issue, nodeHelper, httpClient)
            AgileSync.receiveRank(replica, issue, nodeHelper, httpClient)
            //Temporarily commented out sprint calls, issue https://support.idalko.com/servicedesk/customer/portal/8/EASE-3755
            //AgileSync.receiveSprints(AgileSync.skipOnNoBoardFound(), replica, issue, nodeHelper, httpClient)

            issue.attachments = attachmentHelper.mergeAttachments(issue, replica)
            issue.comments = commentHelper.mergeComments(issue, replica)

            if (replica.assignee == null) {
                issueBeforeScript.assignee = new com.exalate.basic.domain.hubobject.v1.BasicHubUser()
                issue.assignee = null
            }

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

            Status.receive()
        }
    }
}

