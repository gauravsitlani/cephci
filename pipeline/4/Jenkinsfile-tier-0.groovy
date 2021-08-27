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
        def result = sharedLib.fetchMajorMinorOSVersion("compose")
        sharedLib.unSetLock(result.major_version, result.minor_version)
        def location="/ceph/cephci-jenkins/latest-rhceph-container-info"
        def releaseFile = "RHCEPH-4.3.yaml"
        sh (script: "touch -f ${location}/${releaseFile}")
//         def content = ['something': 'my datas',
//                     'size': 3,
//                     'isEmpty': false]
//         sharedLib.WriteToReleaseFile('4', '3', content)
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
