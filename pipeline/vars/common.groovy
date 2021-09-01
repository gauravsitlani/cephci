#!/usr/bin/env groovy
/*
    Common groovy methods that can be reused by the pipeline jobs.
*/

import org.jsoup.Jsoup

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

def yamlToMap(def yamlFile, def location="/ceph/cephci-jenkins/latest-rhceph-container-info") {
    /*
        Read the yaml file and returns a map object
    */
    def yamlFileExists = sh (returnStatus: true, script: "ls -l ${location}/${yamlFile}")
    if (yamlFileExists != 0) {
        println "File ${location}/${yamlFile} does not exist."
        return [:]
    }
    def props = readYaml file: "${location}/${yamlFile}"
    return props
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

def fetchMajorMinorOSVersion(def buildType){
    /*
        method accepts buildType as an input and
        Returns RH-CEPH major version, minor version and OS platform based on buildType
        different buildType supported: unsigned-compose, unsigned-container-image, cvp, signed-compose, signed-container-image

    */
    def cimsg = getCIMessageMap()
    def majorVer
    def minorVer
    def platform

    if (buildType == 'unsigned-compose' || buildType == 'unsigned-container-image') {
        majorVer = cimsg.compose_id.substring(7,8)
        minorVer = cimsg.compose_id.substring(9,10)
        platform = cimsg.compose_id.substring(11,17).toLowerCase()
    }
    if (buildType == 'cvp'){
        majorVer = cimsg.artifact.brew_build_target.substring(5,6)
        minorVer = cimsg.artifact.brew_build_target.substring(7,8)
        platform = cimsg.artifact.brew_build_target.substring(9,15).toLowerCase()
    }
    if (buildType == 'signed-compose'){
        majorVer = cimsg["compose-id"].substring(7,8)
        minorVer = cimsg["compose-id"].substring(9,10)
        platform = cimsg["compose-id"].substring(11,17).toLowerCase()
    }
    if (buildType == 'signed-container-image'){
        majorVer = cimsg.tag.name.substring(5,6)
        minorVer = cimsg.tag.name.substring(7,8)
        platform = cimsg.tag.name.substring(9,15).toLowerCase()
    }
    if (majorVer && minorVer && platform){
        return ["major_version":majorVer, "minor_version":minorVer, "platform":platform]
    }
    error "Required values are not obtained.."
}

def fetchCephVersion(def baseUrl){
    /*
        Fetches ceph version using compose base url
    */
    baseUrl += "/compose/Tools/x86_64/os/Packages/"
    println baseUrl
    def document = Jsoup.connect(baseUrl).get().toString()
    def cephVer = document.findAll(/"ceph-common-([\w.-]+)\.([\w.-]+)"/)[0].findAll(/([\d]+)\.([\d]+)\.([\d]+)\-([\d]+)/)
    println cephVer
    if (! cephVer){
        error "ceph version not found.."
    }
    return cephVer[0]
}

def setLock(def majorVer, def minorVer){
    /*
        create a lock file
    */
    def defaultFileDir = "/ceph/cephci-jenkins/latest-rhceph-container-info"
    def lockFile = "${defaultFileDir}/RHCEPH-${majorVer}.${minorVer}.lock"
    def lockFileExists = sh (returnStatus: true, script: "ls -l ${lockFile}")
    if (lockFileExists != 0) {
        println "RHCEPH-${majorVer}.${minorVer}.lock does not exist. creating it"
        sh(script: "touch ${lockFile}")
        return
    }
    def startTime = System.currentTimeMillis()
    while((System.currentTimeMillis()-startTime)<600000){
        lockFilePresent = sh (returnStatus: true, script: "ls -l ${lockFile}")
        if (lockFilePresent != 0) {
            sh(script: "touch ${lockFile}")
            return
            }
    }
    error "Lock file: RHCEPH-${majorVer}.${minorVer}.lock already exist.can not create lock file"
}

def unSetLock(def majorVer, def minorVer){
    /*
        Unset a lock file
    */
    def defaultFileDir = "/ceph/cephci-jenkins/latest-rhceph-container-info"
    def lockFile = "${defaultFileDir}/RHCEPH-${majorVer}.${minorVer}.lock"
    sh(script: "rm -f ${lockFile}")
}

def readFromReleaseFile(def majorVer, def minorVer, def lockFlag=true, def location="/ceph/cephci-jenkins/latest-rhceph-container-info"){
    /*
        Method to set lock and read content from the release yaml file.
    */
    def releaseFile = "RHCEPH-${majorVer}.${minorVer}.yaml"
    if (lockFlag){ 
        setLock(majorVer, minorVer)
    }
    def releaseContent = yamlToMap(releaseFile, location)
    println "content of release file is: ${releaseContent}"
    return releaseContent
}

def writeToReleaseFile(def majorVer, def minorVer, def releaseContent, def unsetLockFlag=true, def location="/ceph/cephci-jenkins/latest-rhceph-container-info"){
    /*
        Method write content from the release yaml file and unset the lock.
    */
    def releaseFile = "RHCEPH-${majorVer}.${minorVer}.yaml"
    writeYaml file: "${location}/${releaseFile}", data: releaseContent, overwrite: true
    if (unsetLockFlag){
        unSetLock(majorVer, minorVer)
    }
}

def compareCephVersion(def oldCephVer, def newCephVer){
    /*
        compares new and old ceph versions.
        returns 0 if equal
        returns 1 if new ceph version is greater than old ceph version
        returns -1 if new ceph version is lesser than old ceph version

        example for ceph version: 16.2.0-117, 14.2.11-190
    */

    if (newCephVer == oldCephVer){return 0}

    def oldVer = oldCephVer.split("\\.|-").collect { it.toInteger() }
    def newVer = newCephVer.split("\\.|-").collect { it.toInteger() }

    if (newVer[0] > oldVer[0]){return 1}
    else if (newVer[0] < oldVer[0]){return -1}

    if (newVer[1] > oldVer[1]){return 1}
    else if (newVer[1] < oldVer[1]){return -1}

    if (newVer[2] > oldVer[2]){return 1}
    else if (newVer[2] < oldVer[2]){return -1}

    if (newVer[3] > oldVer[3]){return 1}
    else if (newVer[3] < oldVer[3]){return -1}
}

def SendUMBMessage(def msgMap, def overrideTopic, def msgType){
    /*
        Trigger a UMB message.
    */
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

def sendEmail(def testResults, def artifactDetails, def tierLevel){
    /*
        Send an Email
        Arguments:
            testResults: map of the test suites and its status
                Example: testResults = [ "01_deploy": "PASS", "02_object": "PASS"]
            artifactDetails: Map of artifact details
                Example: artifactDetails = ["composes": ["rhe-7": "composeurl1",
                                                         "rhel-8": "composeurl2"],
                                            "product": "Redhat",
                                            "version": "RHCEPH-5.0",
                                            "ceph_version": "16.2.0-117",
                                            "repository": "reponame"]
            tierLevel:
                Example: Tier0, Tier1, CVP..
    */
    def status = "STABLE"
    def toList = "ckulal@redhat.com"
    def body = readFile(file: "pipeline/vars/emailable-report.html")

    body += "<body>"
    body += "<h2><u>Test Artifacts</u></h2>"
    body += "<table>"

    if (artifactDetails.product){body += "<tr><td>PRODUCT</td><td>${artifactDetails.product}</td></tr>"}
    if (artifactDetails.version){body += "<tr><td> VERSION </td><td>${artifactDetails.version}</td></tr>"}
    if (artifactDetails.ceph_version){body += "<tr><td> CEPH-VERSION </td><td>${artifactDetails.ceph_version}</td></tr>"}
    if (artifactDetails.composes){body += "<tr><td>COMPOSES</td><td>${artifactDetails.composes}</td></tr>"}
    if (artifactDetails.repository){body += "<tr><td> REPOSITORY </td><td>${artifactDetails.repository}</td></tr>"}

    body += "</table><br />"
    body += "<h2><u>Test Summary</u></h2>"
    body += "<p>Logs are available at ${env.BUILD_URL}</p>"
    body += "<table>"
    body += "<tr><th>Test Suite</th><th>Result</th></tr>"
    for (test in testResults) {
        body += "<tr><td>${test.key}</td><td>${test.value}</td></tr>"
    }
    body += "</table><br /></body></html>"
    if ('FAIL' in testResults.values()){
        toList = "ckulal@redhat.com"
        status = "UNSTABLE"}

    def subject = "${tierLevel} test report status of ${artifactDetails.version} is ${status}"

    emailext (
        mimeType: 'text/html',
        subject: "${subject}",
        body: "${body}",
        from: "cephci@redhat.com",
        to: "${toList}"
    )
}

def sendGChatNotification(def testResults, def tierLevel){
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
    def ciMsg = getCIMessageMap()
    def jobStatus = "STABLE"
    if ('FAIL' in testResults.values()){
        jobStatus = "UNSTABLE"}
    def msg= "Run for ${ciMsg.artifact.nvr}:${tierLevel} is ${jobStatus}.Log:${env.BUILD_URL}"
    googlechatnotification(url: "id:rhcephCIGChatRoom",
                           message: msg
                          )
}



// def sendEmail(def testResults){
//     /*
//         Send an Email
//     */
//     def ciMsg = getCIMessageMap()
//     def jobStatus = "STABLE"
//     def toList = "ckulal@redhat.com"
//     def version = ciMsg["artifact"]["nvr"].split("\\.|-")
//     def content = readFromReleaseFile(version[1], version[2], lockFlag=false)
//     def build_action = ciMsg["artifact"]["build_action"]
//     def body = readFile(file: "pipeline/vars/emailable-report.html")

//     body += "<h2><u>Test Artifacts</h2></u><table><tr><td> RHEL7_COMPOSE_URL </td><td>${content."${build_action}".composes["rhel-7"]}</td></tr><tr><td> RHEL8_COMPOSE_URL </td><td>${content."${build_action}".composes["rhel-8"]}</td></tr><td>PRODUCT</td><td>${ciMsg.artifact.name}</td></tr>"
//     body += "<tr><td> VERSION </td><td>${ciMsg.artifact.nvr}</td></tr>"
//     body += "<tr><td> CEPH-VERSION </td><td>${ciMsg.artifact.version}</td></tr>"
//     body += "<tr><td> REPOSITORY </td><td>${content."${build_action}".repository}</td></tr>"
//     body += "</table>"
//     body += "<body><u><h3>Test Summary</h3></u><br />"
//     body += "<p>Logs are available at ${env.BUILD_URL}</p><br />"
//     body += "<tr><th>Test Suite</th><th>Result</th>"
//     for (test in testResults) {
//         body += "<tr><td>${test.key}</td><td>${test.value}</td></tr>"
//         }
//     if ('FAIL' in testResults.values()){
//         toList = "ckulal@redhat.com"
//         jobStatus = "UNSTABLE"}
    
//     if (build_action == 'latest'){build_action = 'tier0'}
//     def subject = "Test report status of RH Ceph ${ciMsg.artifact.nvr}:${build_action} is ${jobStatus}"

//     emailext (
//         mimeType: 'text/html',
//         subject: "${subject}",
//         body: "${body}",
//         from: "cephci@redhat.com",
//         to: "${toList}"
//     )
// }

// def sendGChatNotification(def testResults){
//     /*
//         Send a GChat notification.
//         Plugin used:
//             googlechatnotification which allows to post build notifications to a Google Chat Messenger groups.
//             parameter:
//                 url: Mandatory String parameter.
//                      Single/multiple comma separated HTTP URLs or/and single/multiple comma separated Credential IDs.
//                 message: Mandatory String parameter.
//                          Notification message to be sent.
//     */
//     def ciMsg = getCIMessageMap()
//     def jobStatus = "STABLE"
//     if ('FAIL' in testResults.values()){
//         jobStatus = "UNSTABLE"}
//     def tier = ciMsg["artifact"]["build_action"]
//     if (tier == 'latest'){tier = 'tier0'}
//     def msg= "Testing: Run for ${ciMsg.artifact.nvr}:${tier} is ${jobStatus}.Log:${env.BUILD_URL}"
//     googlechatnotification(url: "id:rhcephCIGChatRoom",
//                            message: msg
//                           )
// }

return this;
