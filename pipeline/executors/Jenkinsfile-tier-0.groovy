/*
    Pipeline script for executing Tier 0 test suites for RH Ceph Storage.
*/
// Global variables section

def nodeName = "centos-7"
def tierLevel = "tier-0"
def testStages = [:]
def testResults = [:]
def releaseContent
def buildPhase
def ciMap
def sharedLib
def majorVersion
def minorVersion


node(nodeName) {

    timeout(unit: "MINUTES", time: 30) {
        stage('Install prereq') {
            checkout([
                $class: 'GitSCM',
                branches: [[name: 'refs/remotes/origin/testing_generic']],
                doGenerateSubmoduleConfigurations: false,
                extensions: [[
                    $class: 'CloneOption',
                    shallow: true,
                    noTags: false,
                    reference: '',
                    depth: 0
                ]],
                submoduleCfg: [],
                userRemoteConfigs: [[
                    url: 'https://github.com/red-hat-storage/cephci.git'
                ]]
            ])

            // prepare the node
            sharedLib = load("${env.WORKSPACE}/pipeline/vars/lib.groovy")
            sharedLib.prepareNode()
        }
    }

    stage('Prepare-Stages') {
        /* Prepare pipeline stages using RHCEPH version */
        ciMap = sharedLib.getCIMessageMap()
        buildPhase = ciMap["artifact"]["build_action"]
        def rhcsVersion = sharedLib.getRHCSVersionFromArtifactsNvr()
        majorVersion = rhcsVersion["major_version"]
        minorVersion = rhcsVersion["minor_version"]

        /* 
           Read the release yaml contents to get contents,
           before other listener/Executo Jobs updates it.
        */
        sharedLib.unSetLock(majorVersion, minorVersion)
        releaseContent = sharedLib.readFromReleaseFile(majorVersion, minorVersion, lockFlag=false)
        println "release content1 is :"
        println releaseContent
        testStages = sharedLib.fetchStages(buildPhase, tierLevel, testResults)
    }

    parallel testStages

    stage('Publish Results') {
        /* Publish results through E-mail and Google Chat */

        if ( ! ("FAIL" in testResults.values()) ) {
            latestContent = sharedLib.readFromReleaseFile(majorVersion, minorVersion)
            if (latestContent.containsKey(tierLevel)){
                latestContent[tierLevel] = releaseContent[buildPhase]
            }
            else {
                println "else part"
                def updateContent = ["${tierLevel}": releaseContent[buildPhase]]
                println "update content is:"
                println updateContent
                latestContent += updateContent
            }
            println "latestContent is:"
            println latestContent
            sharedLib.writeToReleaseFile(majorVersion, minorVersion, latestContent)
        }
        
//         sharedLib.sendGChatNotification(testResults, tierLevel)
        sharedLib.sendEmail(testResults, sharedLib.buildArtifactsDetails(releaseContent,ciMap,buildPhase), tierLevel.capitalize())
    }

    stage('Publish UMB') {
        /* send UMB message */
        def buildState = buildPhase

        if ( buildPhase == "latest" ) {
            buildState = "tier-1"
        }

        def artifactsMap = [
            "artifact": [
                "type": "product-build-${buildPhase}",
                "name": "Red Hat Ceph Storage",
                "version": ciMap["artifact"]["version"],
                "nvr": ciMap["artifact"]["nvr"],
                "phase": buildState,
            ],
            "contact": [
                "name": "Downstream Ceph QE",
                "email": "ceph-qe@redhat.com",
            ],
            "pipeline": [
                "name": "rhceph-tier-0",
            ],
            "test-run": [
                "type": tierLevel,
                "result": currentBuild.currentResult,
                "url": env.BUILD_URL,
                "log": "${env.BUILD_URL}/console",
            ]
        ]

        def msgContent = writeJSON returnText: true, json: artifactsMap
        println "${msgContent}"

        sharedLib.SendUMBMessage(
            artifactsMap,
            "VirtualTopic.qe.ci.rhcephqe.product-build.test.complete",
            "Tier0TestingDone",
        )
    }

}
