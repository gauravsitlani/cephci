/*
    Pipeline script for executing Tier 0 test suites for RH Ceph Storage.
*/
// Global variables section

def nodeName = "centos-7"
def testStages = [:]
def testResults = [:]
def releaseContent
def buildPhase
def ciMap
def sharedLib
def majorVersion
def minorVersion
def tierLevel

def buildArtifactsDetails() {
    /* Return artifacts details using release content */
    return [
        "composes": releaseContent[buildPhase]["composes"],
        "product": "Red Hat Ceph Storage",
        "version": ciMap["artifact"]["nvr"],
        "ceph_version": releaseContent[buildPhase]["ceph-version"],
        "container_image": releaseContent[buildPhase]["repository"]
    ]
}

node(nodeName) {

    timeout(unit: "MINUTES", time: 30) {
        stage('Install Prereq') {
            checkout([
                $class: 'GitSCM',
                branches: [[name: 'refs/remotes/origin/testing_executor']],
                doGenerateSubmoduleConfigurations: false,
                extensions: [[
                    $class: 'SubmoduleOption',
                    disableSubmodules: false,
                    parentCredentials: false,
                    recursiveSubmodules: true,
                    reference: '',
                    trackingSubmodules: false
                ]],
                submoduleCfg: [],
                userRemoteConfigs: [[
                    url: 'https://github.com/red-hat-storage/cephci.git'
                ]]
            ])

            // prepare the node for executing test suites
            sharedLib = load("${env.WORKSPACE}/pipeline/vars/lib.groovy")
            sharedLib.prepareNode()
        }
    }

    stage('Prepare-Stages') {
        /* Prepare pipeline stages using RHCEPH version */
        ciMap = sharedLib.getCIMessageMap()
        buildPhase = ciMap["artifact"]["phase"]
        def rhcsVersion = sharedLib.getRHCSVersionFromArtifactsNvr()
        majorVersion = rhcsVersion["major_version"]
        minorVersion = rhcsVersion["minor_version"]
        
        println "versions:"
        println majorVersion
        println minorVersion

        /*
           Read the release yaml contents to get contents,
           before other listener/Executo Jobs updates it.
        */
        releaseContent = sharedLib.readFromReleaseFile(majorVersion, minorVersion, lockFlag=false)
        testStages = sharedLib.fetchStages(buildPhase, buildPhase, testResults)
    }

    parallel testStages

    stage('Publish Results') {
        /* Publish results through E-mail and Google Chat */
        def buildPhaseValue = buildPhase.split("-")
        def tierValue = buildPhaseValue[1].toInteger()+1
        tierLevel = buildPhaseValue[0]+"-"+tierValue

        if ( ! (sharedLib.failStatus in testResults.values()) ) {
            releaseContent = sharedLib.readFromReleaseFile(majorVersion, minorVersion)
            releaseContent[tierLevel]["composes"] = releaseContent[buildPhase]["composes"]
            releaseContent[tierLevel]["last-run"] = releaseContent[buildPhase]["ceph-version"]
            sharedLib.writeToReleaseFile(majorVersion, minorVersion, releaseContent)
        }
//         sharedLib.sendGChatNotification(testResults, buildPhase)
        sharedLib.sendEMail(testResults, buildArtifactsDetails(), buildPhase)
    }

    stage('Publish UMB') {
        /* send UMB message */

        def artifactsMap = [
            "artifact": [
                "type": "product-build-${buildPhase}",
                "name": "Red Hat Ceph Storage",
                "version": ciMap["artifact"]["version"],
                "nvr": ciMap["artifact"]["nvr"],
                "phase": tierLevel,
            ],
            "contact": [
                "name": "Downstream Ceph QE",
                "email": "ceph-qe@redhat.com",
            ],
            "pipeline": [
                "name": "rhceph-tier-x",
            ],
            "test-run": [
                "type": buildPhase,
                "result": currentBuild.currentResult,
                "url": env.BUILD_URL,
                "log": "${env.BUILD_URL}/console",
            ]
        ]
        def msgType = buildPhaseValue[0]+buildPhaseValue[1]+"testingdone"
        def msgContent = writeJSON returnText: true, json: artifactsMap
        println "${msgContent}"

        sharedLib.SendUMBMessage(
            artifactsMap,
            "VirtualTopic.qe.ci.rhcephqe.product-build.test.complete",
            msgType,
        )
    }

}
