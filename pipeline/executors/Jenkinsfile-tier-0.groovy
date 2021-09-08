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

// def buildArtifactsDetails() {
//     /* Return artifacts details using release content */
//     return [
//         "composes": releaseContent[buildPhase]["composes"],
//         "product": "Red Hat Ceph Storage",
//         "version": ciMap["artifact"]["nvr"],
//         "ceph_version": releaseContent[buildPhase]["ceph-version"],
//         "container_image": releaseContent[buildPhase]["repository"]
//     ]
// }

node(nodeName) {

    timeout(unit: "MINUTES", time: 30) {
        stage('Install Prereq') {
            checkout([
                $class: 'GitSCM',
                branches: [[name: 'refs/remotes/origin/testing_generic']],
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
        def emailTo = "ceph-qe@redhat.com"

        if ( ! ("FAIL" in testResults.values()) ) {
            releaseContent = sharedLib.readFromReleaseFile(majorVersion, minorVersion)
            println "release content2 is :"
            println releaseContent
            if (releaseContent[tierLevel]){
                releaseContent[tierLevel]["composes"] = releaseContent[buildPhase]["composes"]
                releaseContent[tierLevel]["last-run"] = releaseContent[tierLevel]["ceph-version"]
                releaseContent[tierLevel]["ceph-version"] = releaseContent[buildPhase]["ceph-version"]
                if (releaseContent[buildPhase]["repository"]){releaseContent[tierLevel]["repository"] = releaseContent[buildPhase]["repository"]}
            }
            
            else{
                println "else part"
                def updateContent = [
                    "${tierLevel}": [
                        "ceph-version": releaseContent[buildPhase]["ceph-version"],
                        "composes": releaseContent[buildPhase]["composes"]]]
                releaseContent += updateContent
                println "release content3 is :"
                println releaseContent
                println "tier content is:"
                println releaseContent[tierLevel]

                if (releaseContent[buildPhase]["repository"]){
                    println "if repo part"
                    def repo = ["repository": releaseContent[buildPhase]["repository"]]
                    releaseContent["${tierLevel}"] += repo
                }
            }
            
            sharedLib.writeToReleaseFile(majorVersion, minorVersion, releaseContent)            
            
        }
//         def artifactData = [
//             "composes": releaseContent[buildPhase]["composes"],
//             "product": "Red Hat Ceph Storage",
//             "version": ciMap["artifact"]["nvr"],
//             "ceph_version": releaseContent[buildPhase]["ceph-version"],
//             "container_image": releaseContent[buildPhase]["repository"]]
        
//         sharedLib.sendGChatNotification(testResults, tierLevel)
        sharedLib.sendEmail(testResults, sharedLib.buildArtifactsDetails(releaseContent,ciMap,buildPhase), tierLevel)
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
