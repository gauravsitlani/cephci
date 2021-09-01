/*
    Pipeline script for executing Tier 0 test suites for RH Ceph 4.x
*/
// Global variables section

def nodeName = "centos-7"
def cephVersion = "nautilus"
def sharedLib
def test_results = [:]
def defaultRHEL7BaseUrl
def defaultRHEL7Build
def cvp

def rpmStages = ['deployRpmRhel7': {
                    stage('RHEL7 RPM') {
                        withEnv([
                            "osVersion=RHEL-7",
                            "sutVMConf=conf/inventory/rhel-7.9-server-x86_64.yaml",
                            "sutConf=conf/${cephVersion}/ansible/tier_0_deploy.yaml",
                            "testSuite=suites/${cephVersion}/ansible/tier_0_deploy_rpm_ceph.yaml",
                            "containerized=false",
                            "addnArgs=--post-results --log-level DEBUG",
                            "composeUrl=${defaultRHEL7BaseUrl}",
                            "rhcephVersion=${defaultRHEL7Build}"
                        ]) {
                            rc = sharedLib.runTestSuite()
                            test_results["RHCEPH baremetal based deployment on RHEL-7"] = rc
                        }
                    }
                 },
                 'deployRpmRhel8': {
                    stage('RHEL8 RPM') {
                        withEnv([
                            "osVersion=RHEL-8",
                            "sutVMConf=conf/inventory/rhel-8.4-server-x86_64.yaml",
                            "sutConf=conf/${cephVersion}/ansible/tier_0_deploy.yaml",
                            "testSuite=suites/${cephVersion}/ansible/tier_0_deploy_rpm_ceph.yaml",
                            "containerized=false",
                            "addnArgs=--post-results --log-level DEBUG"
                        ]) {
                            rc = sharedLib.runTestSuite()
                            test_results["RHCEPH baremetal based deployment on RHEL-8"] = rc
                        }
                    }
                 }]

def containerStages = ['deployContainerRhel7': {
                        stage('RHEL7 Container') {
                            withEnv([
                                "osVersion=RHEL-7",
                                "sutVMConf=conf/inventory/rhel-7.9-server-x86_64.yaml",
                                "sutConf=conf/${cephVersion}/ansible/tier_0_deploy.yaml",
                                "testSuite=suites/${cephVersion}/ansible/tier_0_deploy_containerized_ceph.yaml",
                                "addnArgs=--post-results --log-level DEBUG",
                                "composeUrl=${defaultRHEL7BaseUrl}",
                                "rhcephVersion=${defaultRHEL7Build}"
                            ]) {
                                rc = sharedLib.runTestSuite()
                                test_results["RHCEPH image based deployment on RHEL-7"] = rc
                            }
                        }
                    },
                    'deployContainerRhel8': {
                        stage('RHEL8 Container') {
                            withEnv([
                                "osVersion=RHEL-8",
                                "sutVMConf=conf/inventory/rhel-8.4-server-x86_64.yaml",
                                "sutConf=conf/${cephVersion}/ansible/tier_0_deploy.yaml",
                                "testSuite=suites/${cephVersion}/ansible/tier_0_deploy_containerized_ceph.yaml",
                                "addnArgs=--post-results --log-level DEBUG"
                            ]) {
                                rc = sharedLib.runTestSuite()
                                test_results["RHCEPH image based deployment on RHEL-8"] = rc
                            }
                        }
                    }]

def functionalityStages = [ 'object': {
                            stage('Object suite') {
                                withEnv([
                                    "osVersion=RHEL-8",
                                    "sutVMConf=conf/inventory/rhel-8.4-server-x86_64-medlarge.yaml",
                                    "sutConf=conf/${cephVersion}/rgw/tier_0_rgw.yaml",
                                    "testSuite=suites/${cephVersion}/rgw/tier_0_rgw.yaml",
                                    "containerized=false",
                                    "addnArgs=--post-results --log-level DEBUG"
                                ]) {
                                    rc = sharedLib.runTestSuite()
                                    test_results["Tier-0 RGW verification"] = rc
                                }
                            }
                        }]

// Pipeline script entry point

node(nodeName) {

    timeout(unit: "MINUTES", time: 30) {
        stage('Install prereq') {
            if (env.WORKSPACE) {
                println env.WORKSPACE
                deleteDir()
            }
            checkout([
                $class: 'GitSCM',
                branches: [[name: 'refs/remotes/origin/testing_4x_jen']],
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
            sharedLib = load("${env.WORKSPACE}/pipeline/vars/common.groovy")
            sharedLib.prepareNode()
        }
    }
    
    stage('Testing') {
//         def result = sharedLib.fetchMajorMinorOSVersion("compose")
//         sharedLib.unSetLock('4', '3')
//         def content = ['latest':['ceph-version':'14.2.122-190', 	
//                        'repository':'registry-proxy.engineering.redhat.com/rh-osbs/rhceph:ceph-4.2-rhel-8-containers-candidate-21853-20210824055854']]
//         sharedLib.WriteToReleaseFile('4', '3', content)
//         def value1 = sharedLib.fetchCephVersion("http://download.eng.bos.redhat.com/rhel-8/composes/auto/ceph-4.2-rhel-8/RHCEPH-4.2-RHEL-8-20210824.ci.1")
//         println value1
//         def msgMap = [
//         "BUILD_URL" : env.BUILD_URL,
//         "CI_STATUS" : "PASS",
//         "COMPOSE_ID" : env.composeId,
//         "COMPOSE_URL" : env.composeUrl,
//         "PRODUCT" : "Red Hat Ceph Storage",
//         "REPOSITORY" : env.repository,
//         "TOOL" : "cephci"
//     ]
//         def topic = "VirtualTopic.qe.ci.rhcephqe.product-build.promote.complete"
//         sharedLib.SendUMBMessageTest(msgMap, topic, "Tier0TestingDone")
        def testResults = [ "01_deploy": "PASS", "02_object": "FAIL"]
//         sharedLib.sendEmailNew(testResults)
        sharedLib.sendGChatNotification(testResults)
    }

//     stage('Set CVP Variable') {
//         cvp = sharedLib.getCvpVariable()
//     }

//     stage('Set RHEL7 vars') {
//         // Gather the RHEL 7 latest compose information
//         defaultRHEL7Build = sharedLib.getRHBuild("rhel-7")
//         defaultRHEL7BaseUrl = sharedLib.getBaseUrl("rhel-7")
//     }
//     timeout(unit: "HOURS", time: 2) {
//         parallel functionalityStages
//     }

//     stage('Publish Results') {
        
//         sharedLib.sendGChatNotification("Tier-0")
//     }

}
