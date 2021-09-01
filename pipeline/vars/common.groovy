#!/usr/bin/env groovy
/*
    Common groovy methods that can be reused by the pipeline jobs.
*/


import org.jsoup.Jsoup

def yamlToMap(def yamlFile, def location="/ceph/cephci-jenkins/latest-rhceph-container-info") {
    /*
        Read the JSON file and returns a map object
    */
    def yamlfileExists = sh (returnStatus: true, script: "ls -l ${location}/${yamlFile}")
    if (yamlfileExists != 0) {
        println "File ${location}/${yamlFile} does not exist."
        return [:]
    }
    def props = readYaml file: "${location}/${yamlFile}"
    return props
}

def SendUMBMessageTest(def msgMap, def overrideTopic, def msgType){
    def msgContent = writeJSON returnText: true, json: msgMap
    def msgProperties = """ PRODUCT = Red Hat Ceph Storage
        TOOL = cephci
    """

    sendCIMessage ([
        providerName: 'Red Hat UMB',
        overrides: [topic: "${overrideTopic}"],
        messageContent: "${msgContent}",
        messageProperties: msgProperties,
        messageType: msgType,
        failOnError: true
    ])

}

def sendEmailNew(def testResults){
    def ciMsg = getCIMessageMap()
    println ciMsg
//     def jobStatus = "STABLE"
//     def version = ${ciMsg.artifact.nvr}.split("\\.|-")
//     def content = readFromReleaseFile(version[0], version[1], lockFlag=false)
//     def build_action = ciMsg["artifact"]["build_action"]
//     content[build_action]["repository"]

//     def body = readFile(file: "pipeline/vars/emailable-report.html")
//     body += "<h2><u>Test Artifacts</h2></u><table><tr><td> COMPOSE_URL </td><td>${ciMsg["build"]["compose-url"]}</td></tr><td>PRODUCT</td><td> ${ciMsg.artifact.name}</td></tr>"
//     body += "<tr><td> VERSION </td><td>${ciMsg.artifact.nvr}</td></tr>"
//     body += "<tr><td> CEPH-VERSION </td><td>${ciMsg.artifact.version}</td></tr>"
//     body += "<tr><td> REPOSITORY </td><td>${content[build_action]["repository"]}</td></tr>"

//     body += "</table>"
//     body += "<body><u><h3>Test Summary</h3></u><br />"
//     def toList = "ckulal@redhat.com"
//     body += "<tr><th>Test Suite</th><th>Result</th>"
//     for (test in testResults) {
//         body += "<tr><td>${test.key}</td><td>${test.value}</td></tr>"
//         }
//     if ('FAIL' in testResults.values()){
//         jobStatus = "UNSTABLE"}

//     def subject = "Test report status of RH Ceph ${ciMsg.artifact.nvr} is ${jobStatus}"

//     emailext (
//         mimeType: 'text/html',
//         subject: "${subject}",
//         body: "${body}",
//         from: "cephci@redhat.com",
//         to: "${toList}"
//     )
}

def prepareNode() {
    /*
        Installs the required packages needed by the Jenkins node to
        run and execute the cephci test suites.
    */
    withCredentials([
        usernamePassword(
            credentialsId: 'psi-ceph-jenkins',
            usernameVariable: 'OSPUSER',
            passwordVariable: 'OSPCRED'
        )
    ]) {
        def ospMap = [
            "globals": [
                "openstack-credentials": [
                    "username": OSPUSER,
                    "password": OSPCRED,
                    "auth-url": "https://rhos-d.infra.prod.upshift.rdu2.redhat.com:13000",
                    "auth-version": "3.x_password",
                    "tenant-name": "ceph-jenkins",
                    "service-region": "regionOne",
                    "domain": "redhat.com",
                    "tenant-domain-id": "62cf1b5ec006489db99e2b0ebfb55f57"
                ]
            ]
        ]
        writeYaml file: "${env.HOME}/osp-cred-ci-2.yaml", data: ospMap, overwrite: true
    }

    sh (script: "bash ${env.WORKSPACE}/pipeline/vars/node_bootstrap.bash")
}

def fetchMajorMinorOSVersion(def build_type){
    /*
        method accepts build_type as an input and
        Returns RH-CEPH major version, minor version and OS platform based on build_type
        different build_type supported: compose, osbs, cvp, rc-compose, rc-osbs

    */
    def cimsg = getCIMessageMap()
    def major_ver
    def minor_ver
    def platform

    if (build_type == 'compose' || build_type == 'osbs') {
        major_ver = cimsg.compose_id.substring(7,8)
        minor_ver = cimsg.compose_id.substring(9,10)
        platform = cimsg.compose_id.substring(11,17).toLowerCase()
    }
    if (build_type == 'cvp'){
        major_ver = cimsg.artifact.brew_build_target.substring(5,6)
        minor_ver = cimsg.artifact.brew_build_target.substring(7,8)
        platform = cimsg.artifact.brew_build_target.substring(9,15).toLowerCase()
    }
    if (build_type == 'rc-compose'){
        major_ver = cimsg.compose-id.substring(7,8)
        minor_ver = cimsg.compose-id.substring(9,10)
        platform = cimsg.compose-id.substring(11,17).toLowerCase()
    }
    if (build_type == 'rc-osbs'){
        major_ver = cimsg.tag.name.substring(5,6)
        minor_ver = cimsg.tag.name.substring(7,8)
        platform = cimsg.tag.name.substring(9,15).toLowerCase()
    }
    if (major_ver && minor_ver && platform){
        return ["major_version":major_ver, "minor_version":minor_ver, "platform":platform]
    }
    else{
        error "Required value is not obtained.."
    }
}

def fetchCephVersion(def base_url){
    base_url += "/compose/Tools/x86_64/os/Packages/"
    println base_url
    def document = Jsoup.connect(base_url).get().toString()
    def ceph_ver = document.findAll(/"ceph-common-([\w.-]+)\.([\w.-]+)"/)[0].findAll(/([\d]+)\.([\d]+)\.([\d]+)\-([\d]+)/)
    println ceph_ver
    if (! ceph_ver){
        error "ceph version not found.."
    }
    return ceph_ver[0]
}

def setLock(def major_ver, def minor_ver){
    /*
        create a lock file
    */
    def defaultFileDir = "/ceph/cephci-jenkins/latest-rhceph-container-info"
    def lock_file = "${defaultFileDir}/RHCEPH-${major_ver}.${minor_ver}.lock"
    def lockFileExists = sh (returnStatus: true, script: "ls -l ${lock_file}")
    if (lockFileExists != 0) {
        println "RHCEPH-${major_ver}.${minor_ver}.lock does not exist. creating it"
        sh(script: "touch ${lock_file}")
        return
    }
    def startTime = System.currentTimeMillis()
    while((System.currentTimeMillis()-startTime)<600000){
        lockFilePresent = sh (returnStatus: true, script: "ls -l ${lock_file}")
        if (lockFilePresent != 0) {
            sh(script: "touch ${lock_file}")
            return
            }
    }
    error "Lock file: RHCEPH-${major_ver}.${minor_ver}.lock already exist.can not create lock file"
}

def unSetLock(def major_ver, def minor_ver){
    def defaultFileDir = "/ceph/cephci-jenkins/latest-rhceph-container-info"
    def lock_file = "${defaultFileDir}/RHCEPH-${major_ver}.${minor_ver}.lock"
    sh(script: "rm -f ${lock_file}")
}

def readFromReleaseFile(def majorVer, def minorVer, def location="/ceph/cephci-jenkins/latest-rhceph-container-info", def lockFlag=True){
    /*
        Method to set lock and read content from the release yaml file.
    */
    def releaseFile = "RHCEPH-${majorVer}.${minorVer}.yaml"
    if (lockFlag){ setLock(majorVer, minorVer)}
    def releaseContent = yamlToMap(releaseFile, location)
    println "content of release file is: ${releaseContent}"
    return releaseContent
}

def writeToReleaseFile(def majorVer, def minorVer, def releaseContent, def location="/ceph/cephci-jenkins/latest-rhceph-container-info", def unsetLockFlag=True){
    /*
        Method write content from the release yaml file and unset the lock.
    */
    def releaseFile = "RHCEPH-${majorVer}.${minorVer}.yaml"
    writeYaml file: "${location}/${releaseFile}", data: releaseContent, overwrite: true
    if (unsetLockFlag){unSetLock(majorVer, minorVer)}


def getCvpVariable() {
    /*
        Returns the cvp variable after processing the CI message
    */
    def ciMessage = getCIMessageMap()
    def cvp = ciMessage.CVP ?: false
    return cvp
}

def getCLIArgsFromMessage() {
    /*
        Returns the arguments required for CLI after processing the CI message
    */
    // Processing CI_MESSAGE parameter, it can be empty
    def cmd = ""
    def jsonCIMsg = getCIMessageMap()

    if (! getCvpVariable()) {
        env.composeId = jsonCIMsg.compose_id

        // Don't use Elvis operator
        if (! env.composeUrl ) {
            env.composeUrl = jsonCIMsg.compose_url
        }

        // Don't use Elvis operator
        if (! env.rhcephVersion ) {
            // get rhbuild value from RHCEPH-5.0-RHEL-8.yyyymmdd.ci.x
            env.rhcephVersion = env.composeId.substring(7,17).toLowerCase()
        }

        cmd += " --rhs-ceph-repo ${env.composeUrl}"

        if (!env.containerized || (env.containerized && "${env.containerized}" == "true")) {
            def (dockerDTR, dockerImage1, dockerImage2Tag) = (jsonCIMsg.repository).split('/')
            def (dockerImage2, dockerTag) = dockerImage2Tag.split(':')
            def dockerImage = "${dockerImage1}/${dockerImage2}"
            env.repository = jsonCIMsg.repository
            cmd += " --docker-registry ${dockerDTR}"
            cmd += " --docker-image ${dockerImage}"
            cmd += " --docker-tag ${dockerTag}"
            if ("${dockerDTR}".indexOf('registry-proxy') >= 0) {
                cmd += " --insecure-registry"
            }
        }
    } else {
        env.buildTarget = jsonCIMsg.artifact.brew_build_target

        // Don't use Elvis operator
        if (! env.rhcephVersion ) {
            // get rhbuild value from ceph-4.2-rhel-8-containers-candidate
            env.rhcephVersion = env.buildTarget.substring(5,15).toLowerCase()
        }

        def dockerRegistry = jsonCIMsg.artifact.registry_url
        def dockerImage = jsonCIMsg.artifact.namespace + "/" + jsonCIMsg.artifact.name
        def dockerTag = jsonCIMsg.artifact.image_tag
        cmd += " --docker-registry ${dockerRegistry}"
        cmd += " --docker-image ${dockerImage}"
        cmd += " --docker-tag ${dockerTag}"
    }

    cmd += " --ignore-latest-container"

    if (! env.rhcephVersion ) {
        error "Unable to determine the value for CLI option --rhbuild value"
    }

    cmd += " --rhbuild ${env.rhcephVersion}"

    return cmd
}

def cleanUp(def instanceName) {
   /*
       Destroys the created instances and volumes with the given instanceName created in
       RHOS-D ring.
   */
   try {
        cleanup_cmd = "PYTHONUNBUFFERED=1 ${env.WORKSPACE}/.venv/bin/python"
        cleanup_cmd += " run.py --cleanup ${instanceName}"
        cleanup_cmd += " --osp-cred ${env.HOME}/osp-cred-ci-2.yaml"
        cleanup_cmd += " --log-level debug"

        sh(script: "${cleanup_cmd}")
    } catch(Exception ex) {
        println "WARNING: Encountered an error during cleanup."
        println "Please manually verify the test artifacts are removed."
    }
}

def executeTest(def cmd, def instanceName) {
   /*
        Executes the cephci suite using the CLI input given
   */
    def rc = 0
    catchError (message: 'STAGE_FAILED', buildResult: 'FAILURE', stageResult: 'FAILURE') {
        try {
            sh(script: "PYTHONUNBUFFERED=1 ${cmd}")
        } catch(Exception err) {
            rc = 1
            println err.getMessage()
            error "Encountered an error"
        } finally {
            cleanUp(instanceName)
        }
    }

    return rc
}

def runTestSuite() {
    /*
        Execute the test suite by processing CI_MESSAGE for container
        information along with the environment variables. The required
        variables are
            - sutVMConf
            - sutConf
            - testSuite
            - addnArgs
    */
    println "Begin Test Suite execution"

    // generate random instance name
    def randString = sh(
                        script: "cat /dev/urandom | tr -cd 'a-f0-9' | head -c 5",
                        returnStdout: true
                     ).trim()
    def instanceName = "psi${randString}"

    // Build the CLI options
    def cmd = "${env.WORKSPACE}/.venv/bin/python run.py"
    cmd += " --osp-cred ${env.HOME}/osp-cred-ci-2.yaml"
    cmd += " --report-portal"
    cmd += " --instances-name ${instanceName}"

    // Append test suite specific
    cmd += " --global-conf ${env.sutConf}"
    cmd += " --suite ${env.testSuite}"
    cmd += " --inventory ${env.sutVMConf}"

    // Processing CI_MESSAGE parameter, it can be empty
    cmd += getCLIArgsFromMessage()

    // Check for additional arguments
    def addnArgFlag = "${env.addnArgs}" ?: ""
    if (addnArgFlag?.trim()) {
        cmd += " ${env.addnArgs}"
    }

    // test suite execution
    echo cmd
    return executeTest(cmd, instanceName)
}

def fetchEmailBodyAndReceiver(def test_results, def isStage) {
    /*
        Return the Email body with the the test results in a tabular form.
    */
    def to_list = "ceph-qe-list@redhat.com"
    def jobStatus = "STABLE"
    def failureCount = 0
    def body = "<table>"

    if(isStage) {
        body += "<tr><th>Test Suite</th><th>Result</th>"
        for (test in test_results) {
            def res = "PASS"
            if (test.value != 0) {
                res = "FAIL"
                failureCount += 1
            } 

            body += "<tr><td>${test.key}</td><td>${res}</td></tr>"
        }
    }
    else {
        body += "<tr><th>Jenkins Job Name</th><th>Result</th>"
        for (test in test_results) {
            if (test.value != "SUCCESS") {
                failureCount += 1
            }

            body += "<tr><td>${test.key}</td><td>${test.value}</td></tr>"
        }
    }

    body +="</table> </body> </html>"

    if (failureCount > 0) {
        to_list = "cephci@redhat.com"
        jobStatus = 'UNSTABLE'
    }
    return ["to_list" : to_list, "jobStatus" : jobStatus, "body" : body]
}

def sendGChatNotification(def tier){
    /*
        Send a GChat notification.
        Plugin used:
            googlechatnotification which allows to post build notifications to a Google Chat Messenger groups.
            parameter:
                url: Mandatory String parameter.
                     Single/multiple comma separated HTTP URLs or/and single/multiple comma separated Credential IDs.
                message: Mandatory String parameter.
                         Notification message to be sent.
    */

    currentBuild.result = currentBuild.currentResult
    def msg= "Run for ${env.composeId}:${tier} is ${currentBuild.result}. log:${env.BUILD_URL}"
    googlechatnotification(url: "id:rhcephCIGChatRoom",
                           message: msg
                          )
}

def sendEMail(def subjectPrefix, def test_results, def isStage=true) {
    /*
        Send an email notification.
    */
    def versionFileExists = sh(
        returnStatus: true, script: "ls -l version_info.json"
    )
    def version_info = [:]
    def body = readFile(file: "pipeline/vars/emailable-report.html")
    body += "<h2><u>Test Artifacts</h2></u><table><tr><td> COMPOSE_URL </td><td>${env.composeUrl}</td></tr><td>COMPOSE_ID</td><td> ${env.composeId}</td></tr>"
    body += "<tr><td> REPOSITORY </td><td>${env.repository}</td></tr>"
    if (versionFileExists == 0) {
        version_info = jsonToMap("version_info.json")
        for (def key in version_info.keySet()) {
            body += "<tr><td> ${key} </td><td> ${version_info[key]}</td></tr>"
        }
    }
    body += "</table>"
    body += "<body><u><h3>Test Summary</h3></u><br />"
    if (getCvpVariable()) {
        def ciMsg = getCIMessageMap()
        body += "<p>CVP Image : ${ciMsg.artifact.nvr}</p><br />"
    }
    body += "<p>Logs are available at ${env.BUILD_URL}</p><br />"
    def params = fetchEmailBodyAndReceiver(test_results, isStage)
    body += params["body"]

    def to_list = params["to_list"]
    def jobStatus = params["jobStatus"]
    def rh_ceph_version = env.rhcephVersion.substring(0,3)

    def subject = "Test report status of RH Ceph ${rh_ceph_version} for ${subjectPrefix} is ${jobStatus}"
    if (getCvpVariable()) {
        subject = "${subjectPrefix} test execution is ${jobStatus}."
    }

    emailext (
        mimeType: 'text/html',
        subject: "${subject}",
        body: "${body}",
        from: "cephci@redhat.com",
        to: "${to_list}"
    )
}

def sendUMBMessage(def msgType) {
    /*
        Trigger a UMB message for successful tier completion
    */
    def msgMap = [
        "BUILD_URL" : env.BUILD_URL,
        "CI_STATUS" : "PASS",
        "COMPOSE_ID" : env.composeId,
        "COMPOSE_URL" : env.composeUrl,
        "PRODUCT" : "Red Hat Ceph Storage",
        "REPOSITORY" : env.repository,
        "TOOL" : "cephci"
    ]

    def msgContent = writeJSON returnText: true, json: msgMap

    def msgProperties = """ BUILD_URL = ${env.BUILD_URL}
        CI_STATUS = PASS
        COMPOSE_ID = ${env.composeId}
        COMPOSE_URL = ${env.composeUrl}
        PRODUCT = Red Hat Ceph Storage
        REPOSITORY = ${env.repository}
        TOOL = cephci
    """

    sendCIMessage ([
        providerName: 'Red Hat UMB',
        overrides: [topic: 'VirtualTopic.qe.ci.jenkins'],
        messageContent: "${msgContent}",
        messageProperties: msgProperties,
        messageType: msgType,
        failOnError: true
    ])
}

def jsonToMap(def jsonFile) {
    /*
        Read the JSON file and returns a map object
    */
    def props = readJSON file: jsonFile
    return props
}

def fetchTier1Compose() {
    /*
       Fetch the compose to be tested for tier1, if there is one
    */
    def defaultFileDir = "/ceph/cephci-jenkins/latest-rhceph-container-info"

    def tier0Json = "${defaultFileDir}/RHCEPH-${env.rhcephVersion}-tier0.json"
    def tier1Json = "${defaultFileDir}/RHCEPH-${env.rhcephVersion}-tier1.json"

    def tier0FileExists = sh (returnStatus: true, script: "ls -l ${tier0Json}")
    if (tier0FileExists != 0) {
        println "RHCEPH-${env.rhcephVersion}-tier0.json does not exist."
        return null
    }
    def tier0Compose = jsonToMap(tier0Json)
    def tier0ComposeString = writeJSON returnText: true, json: tier0Compose

    def tier1FileExists = sh (returnStatus: true, script: "ls -l ${tier1Json}")
    if (tier1FileExists != 0) {
        return tier0ComposeString
    }
    def tier1Compose = jsonToMap(tier1Json)

    if (tier0Compose.compose_id != tier1Compose.latest.compose_id) {
        return tier0ComposeString
    }

    println "There are no new stable build."
    return null
}

def postCompose(def data, def jsonFile) {
    /*
        Writes the given data to provided file under the default latest compose folder.
    */
    def defaultFileDir = "/ceph/cephci-jenkins/latest-rhceph-container-info"
    def target = "${defaultFileDir}/${jsonFile}"

    sh(script: """ echo '${data}' > ${target} """)
}

def postLatestCompose(def onlyLatest=false) {
    /*
        Store the latest compose in ressi for QE usage.
    */
    def ciMsgFlag = "${params.CI_MESSAGE}" ?: ""
    if (! ciMsgFlag?.trim()) {
        println "Missing required information hence not posting compose details."
        return
    }

    def latestJson = "latest-RHCEPH-${env.rhcephVersion}.json"
    postCompose("${params.CI_MESSAGE}", latestJson)

    if (onlyLatest) {
        return
    }

    def tier0Json = "RHCEPH-${env.rhcephVersion}-tier0.json"
    postCompose("${params.CI_MESSAGE}", tier0Json)
}

def postTierCompose(def testResults, def composeInfo, def tier) {
    /*
        Store the stable build details for the Tier in ressi.
    */
    def defaultFileDir = "/ceph/cephci-jenkins/latest-rhceph-container-info"

    def composeMap = readJSON text: composeInfo
    def composeData = [ "latest": composeMap, "pass": composeMap ]

    def tierJson = "${defaultFileDir}/RHCEPH-${env.rhcephVersion}-${tier}.json"

    if ( "FAILURE" in testResults.values() || "ABORTED" in testResults.values() ) {
        def tierJsonFileExists = sh (returnStatus: true, script: "ls -l ${tierJson}" )

        if ( tierJsonFileExists == 0) {
            def tierCompose = jsonToMap(tierJson)
            composeData.pass = tierCompose.pass
        } else {
            composeData.pass = [:]
        }
    }

    writeJSON file: tierJson, json: composeData
}

def fetchComposeInfo(def composeInfo) {
    /*
        Return a map after processing the passed information.
    */
    def composeMap = readJSON text: "${composeInfo}"

    return [
        "composeId" : composeMap.compose_id,
        "composeUrl" : composeMap.compose_url,
        "repository" : composeMap.repository
    ]
}

def getCIMessageMap() {
    /*
        Return the CI_MESSAGE map
    */
    def ciMessage = "${params.CI_MESSAGE}" ?: ""
    if (! ciMessage?.trim() ) {
        return [:]
    }
    def compose = readJSON text: "${params.CI_MESSAGE}"
    return compose
}

def getCephVersion(){
    def compose = getCIMessageMap()
    print compose
    }

def getRHCSVersion() {
    /*
        Returns the RHCEPH version from the compose ID in CI_MESSAGE.
    */
    def compose = getCIMessageMap()
    def ver
    if ( getCvpVariable() ) {
        ver = compose.artifact.brew_build_target.substring(5,8).toLowerCase()
    } else {
        ver = compose.compose_id.substring(7,10).toLowerCase()
    }
    return ver
}

def getRepository() {
    /*
        Return the repository details from CI_MESSAGE
    */
    def compose = getCIMessageMap()
    def repo = compose.repository

    return repo
}

def getRHBuild(def osVersion) {
    /*
        Return the rhbuild value based on the given OS version.
    */
    def rhcsVersion = getRHCSVersion()
    def build = "${rhcsVersion}-${osVersion}"

    return build
}

def getPlatformComposeMap(def osVersion, def tier=null) {
    /*
        Return the Map of the given platform's latest json content.
    */
    def defaultFileDir = "/ceph/cephci-jenkins/latest-rhceph-container-info"
    def rhBuild = getRHBuild(osVersion)
    def composeInfo = ""
    def jsonFile = ""
    if(tier == "tier1") {
        jsonFile = "${defaultFileDir}/RHCEPH-${rhBuild}-tier0.json"
    }
    else
    {
        jsonFile = "${defaultFileDir}/latest-RHCEPH-${rhBuild}.json"
    }

    composeInfo = jsonToMap(jsonFile)
    if(! composeInfo) {
        error "Unable to retrieve the latest build information."
    }
    return composeInfo
}

def getBaseUrl(def osVersion, def tier=null) {
    /*
        Return the compose url for the current RHCS build. The osVersion determines the
        platform for which the URL needs to be retrieved.
    */
    def url
    def composeMap = readJSON text: "${params.CI_MESSAGE}"
    if( composeMap.compose_urls?.trim() ) {
        // When RC pipeline is used compose_urls is present
        url = composeMap.compose_urls.find( {
            x -> x.compose_id.contains(osVersion.toUpperCase())
        } ).compose_url

        return url
    }
    def compose = getPlatformComposeMap(osVersion, tier)
    url = compose.compose_url

    return url
}

def getComposeId(def osVersion) {
    /*
        Retrieve the composeId details from the latest compose file for the given osVer.
    */
    def compose = getPlatformComposeMap(osVersion)
    def composeId = compose.compose_id

    return composeId
}

def fetchRCJsonFile(def rhcsVersion){
    /*
        Process and retrieve the Compose information
    */
    def defaultFileDir = "/ceph/cephci-jenkins/latest-rhceph-container-info"
    def rcJsonFile = "RHCEPH-${rhcsVersion}-RC.json"

    def rcFileExists = sh(
        returnStatus: true, script: "ls -l ${defaultFileDir}/${rcJsonFile}"
    )
    def rcMapExisting = null

    if (rcFileExists == 0) {
        rcMapExisting = jsonToMap("${defaultFileDir}/${rcJsonFile}")
    }

    def rcJsonDetails = [
        "fileExists" : rcFileExists,
        "rcMapExisting" : rcMapExisting,
        "fileName" : rcJsonFile
    ]

    return rcJsonDetails
}

def postRCRPMCompose() {
    /*
        Create/append the RC compose information.
    */
    def composeMap = getCIMessageMap()

    def rhcsVersion = composeMap["compose-id"].substring(7,10).toLowerCase()
    def rcJsonDetails = fetchRCJsonFile(rhcsVersion)
    def osVersion = composeMap["compose-id"].substring(11,17)
    def compose_path = composeMap["compose-path"].replace("/mnt/redhat/", "")

    def compose_url = "http://download-node-02.eng.bos.redhat.com/${compose_path}".toString()

    def jsonContent
    if (rcJsonDetails.fileExists == 0 && composeMap["compose-label"] == rcJsonDetails.rcMapExisting.compose_label) {
        def compose_urls = [
            "compose_id" : composeMap["compose-id"],
            "compose_url" : compose_url
        ]
        rcJsonDetails.rcMapExisting.compose_urls.add(compose_urls)
        jsonContent = writeJSON returnText: true, json: rcJsonDetails.rcMapExisting

        postCompose(jsonContent.trim(), rcJsonDetails.fileName)
        return
    }
    def rcMap = [
        "compose_label" : composeMap["compose-label"],
        "compose_urls" : [
            [
                "compose_id" : composeMap["compose-id"],
                "compose_url" : compose_url
            ]
        ]
    ]
    jsonContent = writeJSON returnText: true, json: rcMap
    postCompose(jsonContent.trim(), rcJsonDetails.fileName)
}

def postRCContCompose() {
    /*
        Retrieve the container details from the repository.
    */
    def composeMap = readJSON text: "${params.CI_MESSAGE}"
    def rhcsVersion = composeMap.tag.name.substring(5,8)
    def rcJsonDetails = fetchRCJsonFile(rhcsVersion)
    if(rcJsonDetails.fileExists != 0){
        error "Missing RC Json File."
    }
    def repoDetails = composeMap.build.extra.image
    def containerImage = repoDetails.index.pull.find({x -> !(x.contains("sha"))})
    def repoUrl = repoDetails.yum_repourls.find({x -> x.contains("RHCEPH")})

    def composeId = repoUrl.split("/").find({x -> x.contains("RHCEPH-${rhcsVersion}")})
    def compose_ids = rcJsonDetails.rcMapExisting.compose_urls.collect{it.compose_id}
    if(!(composeId in compose_ids)){
        error "Compose ID mismatch"
    }
    def compose_url = rcJsonDetails.rcMapExisting.compose_urls.find(
        { x -> x.compose_id == composeId }
    ).compose_url

    rcJsonDetails.rcMapExisting.repository = containerImage
    rcJsonDetails.rcMapExisting.compose_id = composeId
    rcJsonDetails.rcMapExisting.compose_url = compose_url

    def jsonContent = writeJSON returnText: true, json: rcJsonDetails.rcMapExisting
    postCompose(jsonContent.trim(), rcJsonDetails.fileName)

    return jsonContent
}

def postRCCompose(def jobKey){
    /*
        Store the RC compose in ressi for QE usage.
    */
    def ciMsgFlag = "${params.CI_MESSAGE}" ?: ""
    if (! ciMsgFlag?.trim()) {
        println "Missing required information hence not posting compose details."
        return
    }

    if (jobKey == "rpm") {
        echo jobKey
        postRCRPMCompose()
        return
    }
    if (jobKey == "container") {
        def jsonContent = postRCContCompose()
        return jsonContent
    }

}

return this;
