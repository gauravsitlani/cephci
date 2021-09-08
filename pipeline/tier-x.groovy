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
def postTierLevel
def msgType


node(nodeName) {

    timeout(unit: "MINUTES", time: 30) {
        stage('Install prereq') {
            checkout([
                $class: 'GitSCM',
                branches: [[name: 'refs/remotes/origin/testing_executor']],
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
        buildPhase = ciMap["artifact"]["phase"]
        def rhcsVersion = sharedLib.getRHCSVersionFromArtifactsNvr()
        majorVersion = rhcsVersion["major_version"]
        minorVersion = rhcsVersion["minor_version"]

        /*
           Read the release yaml contents to get contents,
           before other listener/Executor Jobs updates it.
        */
        releaseContent = sharedLib.readFromReleaseFile(majorVersion, minorVersion, lockFlag=false)
        testStages = sharedLib.fetchStages(buildPhase, buildPhase, testResults)
    }

    parallel testStages
    
    stage('Publish Results') {
        /* Publish results through E-mail and Google Chat */
        def buildPhaseValue = buildPhase.split("-")
        def postTierValue = buildPhaseValue[1].toInteger()+1
        postTierLevel = buildPhaseValue[0]+"-"+postTierValue
        def preTierValue = buildPhaseValue[1].toInteger()-1
        def preTierLevel = buildPhaseValue[0]+"-"+preTierValue
        println "buildphase is:"
        println buildPhase
        println "pretierlevel is:"
        println preTierLevel
        println "posttierlevel is:"
        println postTierLevel

        if ( ! ("FAIL" in testResults.values()) ) {
            def latestContent = sharedLib.readFromReleaseFile(majorVersion, minorVersion)
            if (releaseContent.containsKey(preTierLevel)){
                if (latestContent.containsKey(buildPhase)){
                    latestContent[buildPhase] = releaseContent[preTierLevel]
                }
                else {
                    def updateContent = ["${buildPhase}": releaseContent[preTierLevel]]
                    latestContent += updateContent
                }
            }
            else{
                sharedLib.unSetLock(majorVersion, minorVersion)
                error "No data found for pre tier level: ${preTierLevel}"
            }
            
            sharedLib.writeToReleaseFile(majorVersion, minorVersion, latestContent)
            println "latest content is:"
            println latestContent
        }
        

//         sharedLib.sendGChatNotification(testResults, buildPhase.capitalize())
        sharedLib.sendEmail(testResults, sharedLib.buildArtifactsDetails(releaseContent,ciMap,preTierValue), buildPhase.capitalize())
    }

    stage('Publish UMB') {
        /* send UMB message */

        def artifactsMap = [
            "artifact": [
                "type": "product-build-${buildPhase}",
                "name": "Red Hat Ceph Storage",
                "version": ciMap["artifact"]["version"],
                "nvr": ciMap["artifact"]["nvr"],
                "phase": postTierLevel,
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
        if (buildPhase == "tier-2"){msgType = "Tier2ValidationTestingDone"}
        else {msgType = buildPhaseValue[0].capitalize()+buildPhaseValue[1]+"TestingDone"}
        def msgContent = writeJSON returnText: true, json: artifactsMap
        println "${msgContent}"

        sharedLib.SendUMBMessage(
            artifactsMap,
            "VirtualTopic.qe.ci.rhcephqe.product-build.test.complete",
            msgType,
        )
    }

}
