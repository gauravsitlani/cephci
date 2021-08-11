/*
    Pipeline script for executing Tier 1 object test suites for RH Ceph 4.x
*/
// Global variables section

def nodeName = "centos-7"
def cephVersion = "nautilus"
def sharedLib
def defaultRHEL7BaseUrl
def defaultRHEL7Build

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
    stage('Set RHEL7 vars') {
    // Gather the RHEL 7 latest compose information
        defaultRHEL7Build = sharedLib.getRHBuild("rhel-7")
        defaultRHEL7BaseUrl = sharedLib.getBaseUrl("rhel-7", "tier1")}

    stage('Multi-site-secondary-to-primary') {
        withEnv([
            "sutVMConf=conf/inventory/rhel-8.4-server-x86_64.yaml",
            "sutConf=conf/${cephVersion}/rgw/tier_1_rgw_multisite.yaml",
            "testSuite=suites/${cephVersion}/rgw/tier_1_rgw_multisite_secondary_to_primary.yaml",
            "addnArgs=--post-results --log-level DEBUG"
        ]) {
            sharedLib.runTestSuite()
        }
    }

}
