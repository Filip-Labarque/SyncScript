import com.exalate.api.domain.connection.IConnection
import com.exalate.api.domain.request.ISyncRequest
import com.exalate.basic.domain.hubobject.v1.BasicHubIssue
import com.exalate.hubobject.jira.AttachmentHelper
import com.exalate.hubobject.jira.CommentHelper
import services.jcloud.hubobjects.NodeHelper
import services.replication.PreparedHttpClient

/**
Usage:

DataFilter.execute(
  replica,
  issue,
  issueKey,
  relation, // connection
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
class DataFilter {
    static execute(
            BasicHubIssue replica,
            BasicHubIssue issue,
            com.exalate.basic.domain.BasicIssueKey issueKey,
            IConnection relation, // connection
            NodeHelper nodeHelper,
            CommentHelper commentHelper,
            AttachmentHelper attachmentHelper,
            com.exalate.hubobject.jira.WorkLogHelper workLogHelper,
            com.exalate.hubobject.jira.WorkflowHelper workflowHelper,
            services.jcloud.hubobjects.ServiceDeskHelper serviceDeskHelper,
            log, //ch.qos.logback.classic.Logger log
            PreparedHttpClient httpClient,
            ISyncRequest syncRequest) {
        IConnection connection = relation

        replica.id            = issue.id
        replica.key            = issue.key
        replica.type           = issue.type
        replica.assignee       = issue.assignee
        replica.reporter       = issue.reporter
        replica.summary        = issue.summary
        replica.description    = issue.description
        replica.environment = issue.environment
        replica.labels         = issue.labels
        replica.comments       = issue.comments
        replica.parentId       = issue.parentId
        replica.priority       = issue.priority
        replica.attachments    = issue.attachments
        replica.project        = issue.project
        replica.securityLevel = issue.securityLevel
        replica.project.components = []
        replica.project.versions = [] as Set

        ComponentSync.send(replica, issue, connection)
        Status.send()
        replica.resolution     = issue.resolution
        VersionSync.send(replica, issue, connection)

        replica.customFields."Business Value" = issue.customFields."Business Value"
        replica.customFields."Flagged" = issue.customFields."Flagged"
        replica.customFields."Severity" = issue.customFields."Severity"
        replica.customFields."Observed By" = issue.customFields."Observed By"
        replica.customFields."Observed During" = issue.customFields."Observed During"
        replica.customFields."Start date" = issue.customFields."Start date"
        replica.customFields."Project Type" = issue.customFields."Project Type"
        //replica.customFields."Region" = issue.customFields."Region"
        replica.customFields."Status Detail" = issue.customFields."Status detail"
        replica.customFields."Scope Definition" = issue.customFields."Scope Definition"
        replica.customFields."Customer Support ID" = issue.customFields."Customer Support ID"
        replica.customFields."Serial Number" = issue.customFields."Serial Number"
        replica.customFields."Story Points" = issue.customFields."Story Points"


        //replica.issueLinks = issue.issueLinks
        IssueLinkSync.send(replica, issue, httpClient)

        ServiceDeskSync.sendOrganizations(replica, issue, httpClient)
        AgileSync.sendEpic(replica, issue, httpClient)
        AgileSync.sendRank(replica, issue, httpClient)
        //Temporarily commented out sprint calls, issue https://support.idalko.com/servicedesk/customer/portal/8/EASE-3755
        //AgileSync.sendSprints(replica, issue, httpClient)

        SubTaskSync.send(replica, issue, connection, httpClient)
        // if there's no value on "Original Issue" yet (it is the original issue)
        /*if (issue.customFields."Original Issue".value == null || "".equals(issue.customFields."Original Issue".value)) {
            issue.customFields.clear() // I want Exalate to update only one custom field - Original Issue
            issue.customFields."Original Issue".value = GetIssueUrl.getLocal(issue.key)
            DataFilterIssueUpdate.update(issue, connection, httpClient)
        }*/

        /*if (issue.customFields."Original Issue".value == null || "".equals(issue.customFields."Original Issue".value)) {
            def localUrl = GetIssueUrl.getLocal(issue.key)
            def client = new JiraClient(httpClient)
            client.http(
                    "PUT", // method
                    "/rest/api/3/issue/${issue.id}", // path
                    ["overrideScreenSecurity":["true"]], // queryParams
                    groovy.json.JsonOutput.toJson(["fields":[("customfield_${issue.customFields."Original Issue".id}".toString()): localUrl]]), // body
                    ["Content-Type":["application/json"]] // headers
            )
            issue.customFields.clear() // I want Exalate to update only one custom field - Original Issue
        }*/


    }
}
