
class IssueLinkSync {
    static def send(replica, issue, httpClient) {
        def ws = new JiraClient(httpClient)

        def js = new groovy.json.JsonSlurper()
        def issueJson = js.parseText(ws.http(
                "GET",
                "/rest/api/2/issue/${issue.id}",
                null,
                null,
                null
        ))

        /*
    [
        {
            "id": "10196",
            "inwardIssue": {
                "fields": {
                    "issuetype": {
                        "avatarId": 10318.0,
                        "description": "A task that needs to be done.",
                        "iconUrl": "https://exalatedevtest.atlassian.net/secure/viewavatar?size=xsmall&avatarId=10318&avatarType=issuetype",
                        "id": "10000",
                        "name": "Task",
                        "self": "https://exalatedevtest.atlassian.net/rest/api/2/issuetype/10000",
                        "subtask": false
                    },
                    "priority": {
                        "iconUrl": "https://exalatedevtest.atlassian.net/images/icons/priorities/medium.svg",
                        "id": "3",
                        "name": "Medium",
                        "self": "https://exalatedevtest.atlassian.net/rest/api/2/priority/3"
                    },
                    "status": {
                        "description": "",
                        "iconUrl": "https://exalatedevtest.atlassian.net/images/icons/status_generic.gif",
                        "id": "10000",
                        "name": "To Do",
                        "self": "https://exalatedevtest.atlassian.net/rest/api/2/status/10000",
                        "statusCategory": {
                            "colorName": "blue-gray",
                            "id": 2.0,
                            "key": "new",
                            "name": "To Do",
                            "self": "https://exalatedevtest.atlassian.net/rest/api/2/statuscategory/2"
                        }
                    },
                    "summary": "Test issue link sync #2"
                },
                "id": "11990",
                "key": "BKTES-324",
                "self": "https://exalatedevtest.atlassian.net/rest/api/2/issue/11990"
            },
            "self": "https://exalatedevtest.atlassian.net/rest/api/2/issueLink/10196",
            "type": {
                "id": "10003",
                "inward": "relates to",
                "name": "Relates",
                "outward": "relates to",
                "self": "https://exalatedevtest.atlassian.net/rest/api/2/issueLinkType/10003"
            }
        }
    ]
        */
        replica.issueLinks = issueJson?.fields?.issuelinks?.collect { Map<String, Object> issueLinkJson ->
            def isInward = issueLinkJson.inwardIssue != null
            def otherIssueId = (isInward ? issueLinkJson.inwardIssue?.id : issueLinkJson.outwardIssue?.id) as Long
            def linkName = (isInward ? issueLinkJson.type.inward : issueLinkJson.type.outward) as String
            def linkTypeName = issueLinkJson.type.name as String
            new com.exalate.basic.domain.hubobject.v1.BasicHubIssueLink(
                    //this.otherIssueId
                    otherIssueId,
                    //this.linkName
                    linkName,
                    //this.linkTypeName
                    linkTypeName,
                    //this.linkType
                    com.exalate.basic.domain.hubobject.v1.BasicHubIssueLink.IssueLinkType.ISSUE,
                    //this.url
                    null,
                    !isInward

            )
        } ?: []
    }
    static def receive(replica, issue, httpClient, nodeHelper) {
        def ws = new JiraClient(httpClient)

        def js = new groovy.json.JsonSlurper()
        def jo = new groovy.json.JsonOutput()

        def issueJson = js.parseText(ws.http(
                "GET",
                "/rest/api/2/issue/${issue.id}",
                null,
                null,
                null
        ))
        def localIssueLinks = issueJson.fields.issuelinks


        // get all issue link types:
        def localLinkTypes = js.parseText(ws.http(
                //GET /rest/api/2/issueLinkType
                "GET",
                "/rest/api/2/issueLinkType",
                null, null, null
        )).issueLinkTypes as List<Map<String, Object>>;

        // set the links to whatever issues we can find at this point
        def linksToBe = replica.issueLinks?.collect { issueLink ->
            def remoteIssueId = issueLink.otherIssueId as Long

            def localIssueKey = nodeHelper.getLocalIssueKeyFromRemoteId(remoteIssueId)
            if (!localIssueKey) {
                return null
            }
            else {
                def remoteLinkTypeName = issueLink.linkTypeName as String
                def localLinkType = localLinkTypes.find { remoteLinkTypeName.equals(it.name) }
                if (localLinkType == null) {
                    throw new com.exalate.api.exception.IssueTrackerException("Failed to create link for remote link `${remoteLinkTypeName} : ${replica.key} - ${issueLink.otherIssueId}`. There is no link type `${remoteLinkTypeName}`. Known link types: ${localLinkTypes.collect{it.name}}".toString())
                }
                if (localLinkType.inward != issueLink.linkName && localLinkType.outward != issueLink.linkName) {
                    throw new com.exalate.api.exception.IssueTrackerException("Can not create link `${issueLink.linkName}` of type `${remoteLinkTypeName}` since inward links of this type are called `${localLinkType.inward}` and outward: `${localLinkType.outward}`".toString())
                }
                def isInward = localLinkType.inward == issueLink.linkName

                def body = [
                        "type": localLinkType
                ]
                def localIssueRefJson = ["id": localIssueKey.id, "key": localIssueKey.urn]
                if (isInward) {
                    body.inwardIssue = localIssueRefJson
                } else {
                    body.outwardIssue = localIssueRefJson
                }
                return body
            }
        }?.findAll()

        // remove all links
//    http(
//            "PUT",
//            "/rest/api/2/issue/${issue.id}",
//            [
//                    "overrideScreenSecurity":["true"],
//                    "overrideEditableFlag":["true"]
//            ],
//            jo.toJson(
//                    [
//                            "update": [
//                                    "issuelinks": [
//                                            [
//                                                    "set" : []
//                                            ]
//                                    ]
//                            ]
//                    ]
//            ),
//            ["Content-Type":["application/json"]]
//    )
        localIssueLinks.each { Map<String, Object> issueLink ->
            //DELETE /rest/api/2/issueLink/{linkId}
            ws.http(
                    "DELETE",
                    "/rest/api/2/issueLink/${issueLink.id}".toString(),
                    null,
                    null,
                    null
            )
        }

        // create all the links to be
        linksToBe.each { body ->
            httpClient.put("/rest/api/2/issue/${issue.id}", """{"update": {"issuelinks": [{"add": ${jo.toJson(body)}}]}}""")
//             ws.http(
//                     "PUT",
//                     "/rest/api/2/issue/${issue.id}",
//                     [
//                             "overrideScreenSecurity":["true"],
//                             "overrideEditableFlag":["true"]
//                     ],
//                     jo.toJson(
//                             [
//                                     "update": [
//                                             "issuelinks": [
//                                                     [
//                                                             "add" : body
//                                                     ]
//                                             ]
//                                     ]
//                             ]
//                     ),
//                     ["Content-Type":["application/json"]]
//             )
        }
    }
}
