import services.replication.PreparedHttpClient

class VersionSync {
    static send(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, com.exalate.api.domain.connection.IConnection connection) {
        replica.fixVersions = issue.fixVersions
        replica.affectedVersions = issue.affectedVersions
        ((com.exalate.basic.domain.hubobject.v1.BasicHubProject)replica.project).versions = ([] as Set)
    }

    static receive(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue, com.exalate.api.domain.connection.IConnection connection, services.jcloud.hubobjects.NodeHelper nodeHelper, PreparedHttpClient httpClient) {
        issue.affectedVersions = receive(replica.affectedVersions, issue.project?.key ?: issue.projectKey, connection, nodeHelper, httpClient)
        issue.fixVersions = receive(replica.fixVersions, issue.project?.key ?: issue.projectKey, connection, nodeHelper, httpClient)
        issue
    }

    static <V extends com.exalate.api.domain.hubobject.v1_2.IHubVersion> Set<com.exalate.basic.domain.hubobject.v1.BasicHubVersion> receive(Set<V> remoteVersions, String localProjectKey, com.exalate.api.domain.connection.IConnection connection, services.jcloud.hubobjects.NodeHelper nodeHelper, PreparedHttpClient httpClient) {
        receive(true, remoteVersions, localProjectKey, connection, nodeHelper, httpClient)
    }
    static <V extends com.exalate.api.domain.hubobject.v1_2.IHubVersion> Set<com.exalate.basic.domain.hubobject.v1.BasicHubVersion> receive(boolean matchByName, Set<V> remoteVersions, String localProjectKey, com.exalate.api.domain.connection.IConnection connection, services.jcloud.hubobjects.NodeHelper nodeHelper, PreparedHttpClient httpClient) {
        try {
            def wc = new JiraClient(httpClient)
            def localExProject = nodeHelper.getProject(localProjectKey)
            if (localExProject == null) {
                throw new com.exalate.api.exception.IssueTrackerException("Contact Exalate Support - for some reason the project `$localProjectKey` is not found during remoteVersion sync for remote remoteVersions `${remoteVersions.collect { remoteVersion -> ["name":remoteVersion.name, "id":remoteVersion.id, "projectKey":remoteVersion.projectKey] } }`".toString())
            }


            // SAAB-16 for now just create / update versions for remote versions (don't update, since this could result in bulk issue update)
            remoteVersions.collect { remoteVersion ->
                try {
                    def js = new groovy.json.JsonSlurper()
                    def jo = new groovy.json.JsonOutput()


                    def createVersion = {
                        def postBody = jo.toJson(
                                [
                                        "description": remoteVersion.description,
                                        "name": remoteVersion.name,
                                        "archived": remoteVersion.archived,
                                        "released": remoteVersion.released,
                                        "releaseDate": remoteVersion.releaseDate ? new java.text.SimpleDateFormat("yyyy-MM-dd").format(remoteVersion.releaseDate) : null,
                                        "projectId": localExProject.id as Long
                                ]
                        )
                        httpClient.post("/rest/api/2/version", postBody)
                        // Log sucessful creation of select list value

                        //                    nodeHelper.nodeHubObjectConverter.getHubVersion(localJiraV)
                        nodeHelper.getVersion(remoteVersion.name, localExProject) as com.exalate.basic.domain.hubobject.v1.BasicHubVersion
                    }
                    def updateVersion = { localJiraV ->
                        def isNameDiff = remoteVersion.name != localJiraV.name
                        def isDescriptionDiff = remoteVersion.description != localJiraV.description
                        def isStartDateDiff = remoteVersion.startDate != localJiraV.startDate
                        def isReleasedDiff = remoteVersion.released != localJiraV.released
                        def isReleaseDateDiff = remoteVersion.releaseDate != ({ String date ->
                            if (date == null) { return null }
                            def format = new java.text.SimpleDateFormat("yyyy-MM-dd")
                            format.parse(date)
                        })(localJiraV.releaseDate)
                        def isArchivedDiff = remoteVersion.archived != localJiraV.archived
                        if (!(isNameDiff || isDescriptionDiff || isStartDateDiff || isReleasedDiff || isReleaseDateDiff || isArchivedDiff)) {
                            // don't update the version
                            return
                        }
                        def versionUpdateBody = jo.toJson(
                                [
                                        "description": remoteVersion.description,
                                        "name": remoteVersion.name,
                                        "archived": remoteVersion.archived,
                                        "released": remoteVersion.released,
                                        "releaseDate": remoteVersion.releaseDate ? new java.text.SimpleDateFormat("yyyy-MM-dd").format(remoteVersion.releaseDate) : null,
                                        "projectId": localExProject.id as Long
                                ]
                        )
                        httpClient.put("/rest/api/2/version/${localJiraV.id}".toString(), versionUpdateBody)
                        // Log sucessful update of Verion
                    }

                    def getVersion = { versionId ->
                        js.parseText(wc.http(
                                "GET",
                                "/rest/api/2/version/$versionId".toString(),
                                [:],
                                null,
                                [
                                        "Accept" : ["application/json"]
                                ]
                        ))
                    }

                    if (matchByName) {
                        def localVersionId = nodeHelper.getVersion(remoteVersion.name, nodeHelper.getProject(localProjectKey))?.id
                        if (localVersionId != null) {
                            def localJiraV = getVersion(localVersionId)
                            if (localJiraV) {
                                updateVersion(localJiraV)
                            } else {
                                createVersion()
                            }
                        } else {
                            createVersion()
                        }
                    } else {
                        createVersion()
                    }

                    nodeHelper.getVersion(remoteVersion.name, nodeHelper.getProject(localProjectKey)) as com.exalate.basic.domain.hubobject.v1.BasicHubVersion
                } catch (com.exalate.api.exception.IssueTrackerException ite) {
                    throw ite
                } catch (Exception e) {
                    throw new com.exalate.api.exception.IssueTrackerException("Contact Exalate Support - failed to receive for remote remoteVersions `${["name":remoteVersion.name, "id":remoteVersion.id, "projectKey":remoteVersion.projectKey] }` for local `$localProjectKey`".toString(), e)
                }
            } as Set
        } catch (com.exalate.api.exception.IssueTrackerException ite) {
            throw ite
        } catch (Exception e) {
            throw new com.exalate.api.exception.IssueTrackerException("Contact Exalate Support - failed to receive for remote remoteVersions `${remoteVersions.collect { remoteVersion -> ["name":remoteVersion.name, "id":remoteVersion.id, "projectKey":remoteVersion.projectKey] } }` for local `$localProjectKey`".toString(), e)
        }
    }
}
