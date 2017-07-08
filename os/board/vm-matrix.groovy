#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'AWS_REGION',
               defaultValue: 'us-west-2',
               description: 'AWS region to use for AMIs and testing'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '1bb768fc-940d-4a95-95d0-27c1153e7fa0',
                    description: 'AWS credentials list for AMI creation and releasing',
                    name: 'AWS_RELEASE_CREDS',
                    required: true),
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl',
                    defaultValue: '6d37d17c-503e-4596-9a9b-1ab4373955a9',
                    description: 'Credentials with permissions required by "kola run --platform=aws"',
                    name: 'AWS_TEST_CREDS',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '7ab88376-e794-4128-b644-41c83c89e76d',
                    description: 'JSON credentials file for all Azure clouds used by plume',
                    name: 'AZURE_CREDS',
                    required: true),
        choice(name: 'BOARD',
               choices: "amd64-usr\narm64-usr",
               description: 'Target board to build'),
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
                    defaultValue: '',
                    description: 'Credential ID for SSH Git clone URLs',
                    name: 'BUILDS_CLONE_CREDS',
                    required: false),
        choice(name: 'COREOS_OFFICIAL',
               choices: "0\n1"),
        text(name: 'FORMAT_LIST',
             defaultValue: 'pxe qemu_uefi',
             description: 'Space-separated list of VM image formats to build'),
        string(name: 'GROUP',
               defaultValue: 'developer',
               description: 'Which release group owns this build'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: '''Credentials ID for a JSON file passed as \
the GOOGLE_APPLICATION_CREDENTIALS value for downloading development files from \
the Google Storage URL, requires read permission''',
                    name: 'GS_DEVEL_CREDS',
                    required: true),
        string(name: 'GS_DEVEL_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where development files are uploaded'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: '''Credentials ID for a JSON file passed as \
the GOOGLE_APPLICATION_CREDENTIALS value for uploading release files to the \
Google Storage URL, requires write permission''',
                    name: 'GS_RELEASE_CREDS',
                    required: true),
        string(name: 'GS_RELEASE_DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are downloaded'),
        string(name: 'GS_RELEASE_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are uploaded'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        string(name: 'MANIFEST_TAG',
               defaultValue: ''),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl',
                    defaultValue: 'd67b5bde-d138-487a-9da3-0f5f5f157310',
                    description: 'Credentials to run hosts in PACKET_PROJECT',
                    name: 'PACKET_CREDS',
                    required: true),
        string(name: 'PACKET_PROJECT',
               defaultValue: '9da29e12-d97c-4d6e-b5aa-72174390d57a',
               description: 'The Packet project ID to run test machines'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'buildbot-official.EF4B4ED9.subkey.gpg',
                    description: 'Credential ID for a GPG private key file',
                    name: 'SIGNING_CREDS',
                    required: true),
        string(name: 'SIGNING_USER',
               defaultValue: 'buildbot@coreos.com',
               description: 'E-mail address to identify the GPG key'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

/* Define downstream testing/prerelease builds for specific formats.  */
def downstreams = [
    'ami_vmdk': { if (params.BOARD == 'amd64-usr')
        build job: '../prerelease/aws', wait: false, parameters: [
            string(name: 'AWS_REGION', value: params.AWS_REGION),
            credentials(name: 'AWS_RELEASE_CREDS', value: params.AWS_RELEASE_CREDS),
            credentials(name: 'AWS_TEST_CREDS', value: params.AWS_TEST_CREDS),
            credentials(name: 'DOWNLOAD_CREDS', value: params.GS_RELEASE_CREDS),
            string(name: 'GROUP', value: params.GROUP),
            text(name: 'VERIFY_KEYRING', value: params.VERIFY_KEYRING),
            string(name: 'VERSION', value: it.version),
            string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
        ]
    },
    'azure': { if (params.BOARD == 'amd64-usr' && params.COREOS_OFFICIAL == '1')
        build job: '../prerelease/azure', wait: false, parameters: [
            credentials(name: 'AZURE_CREDS', value: params.AZURE_CREDS),
            credentials(name: 'DOWNLOAD_CREDS', value: params.GS_RELEASE_CREDS),
            string(name: 'GROUP', value: params.GROUP),
            text(name: 'VERIFY_KEYRING', value: params.VERIFY_KEYRING),
            string(name: 'VERSION', value: it.version),
            string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
        ]
    },
    'gce': { if (params.BOARD == 'amd64-usr')
        build job: '../kola/gce', wait: false, parameters: [
            credentials(name: 'GS_RELEASE_CREDS', value: params.GS_RELEASE_CREDS),
            string(name: 'GS_RELEASE_ROOT', value: params.GS_RELEASE_ROOT),
            string(name: 'VERSION', value: it.version),
            string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
        ]
    },
    'packet': { if (params.BOARD == 'amd64-usr')
        build job: '../kola/packet', wait: false, parameters: [
            credentials(name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS),
            credentials(name: 'DOWNLOAD_CREDS', value: params.GS_RELEASE_CREDS),
            string(name: 'DOWNLOAD_ROOT', value: params.GS_RELEASE_ROOT),
            string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
            string(name: 'MANIFEST_TAG', value: params.MANIFEST_TAG),
            string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
            credentials(name: 'PACKET_CREDS', value: params.PACKET_CREDS),
            string(name: 'PACKET_PROJECT', value: params.PACKET_PROJECT),
            credentials(name: 'UPLOAD_CREDS', value: params.GS_DEVEL_CREDS),
            string(name: 'UPLOAD_ROOT', value: params.GS_DEVEL_ROOT),
            text(name: 'VERIFY_KEYRING', value: params.VERIFY_KEYRING),
            string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
        ]
    },
    'qemu_uefi': {
        build job: '../kola/qemu_uefi', wait: false, parameters: [
            string(name: 'BOARD', value: params.BOARD),
            credentials(name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS),
            credentials(name: 'DOWNLOAD_CREDS', value: params.GS_RELEASE_CREDS),
            string(name: 'DOWNLOAD_ROOT', value: params.GS_RELEASE_ROOT),
            string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
            string(name: 'MANIFEST_TAG', value: params.MANIFEST_TAG),
            string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
            text(name: 'VERIFY_KEYRING', value: params.VERIFY_KEYRING),
            string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
        ]
    }
]

/* Construct a matrix of build variation closures.  */
def matrix_map = [:]

/* Force this as an ArrayList for serializability, or Jenkins explodes.  */
ArrayList<String> format_list = params.FORMAT_LIST.split()

for (format in format_list) {
    def FORMAT = format  /* This MUST use fresh variables per iteration.  */

    matrix_map[FORMAT] = {
        def version = ''

        node('coreos && amd64 && sudo') {
            step([$class: 'CopyArtifact',
                  fingerprintArtifacts: true,
                  projectName: '/mantle/master-builder',
                  selector: [$class: 'StatusBuildSelector', stable: false]])

            writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

            sshagent(credentials: [params.BUILDS_CLONE_CREDS], ignoreMissing: true) {
                withCredentials([
                    file(credentialsId: params.GS_DEVEL_CREDS, variable: 'GS_DEVEL_CREDS'),
                    file(credentialsId: params.GS_RELEASE_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
                    file(credentialsId: params.SIGNING_CREDS, variable: 'GPG_SECRET_KEY_FILE'),
                ]) {
                    withEnv(["BOARD=${params.BOARD}",
                             "COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                             "DOWNLOAD_ROOT=${params.GS_RELEASE_DOWNLOAD_ROOT}",
                             "FORMAT=${FORMAT}",
                             "GS_DEVEL_ROOT=${params.GS_DEVEL_ROOT}",
                             "MANIFEST_NAME=${params.MANIFEST_NAME}",
                             "MANIFEST_TAG=${params.MANIFEST_TAG}",
                             "MANIFEST_URL=${params.MANIFEST_URL}",
                             "SIGNING_USER=${params.SIGNING_USER}",
                             "UPLOAD_ROOT=${params.GS_RELEASE_ROOT}"]) {
                        sh '''#!/bin/bash -ex

# The build may not be started without a tag value.
[ -n "${MANIFEST_TAG}" ]

# Set up GPG for verifying tags.
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap 'rm -rf "${GNUPGHOME}"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

bin/cork update \
    --create --downgrade-replace --verify --verify-signature --verbose \
    --manifest-branch "refs/tags/${MANIFEST_TAG}" \
    --manifest-name "${MANIFEST_NAME}" \
    --manifest-url "${MANIFEST_URL}"

# Run branch-specific build commands from the scripts repository.
. src/scripts/jenkins/vm.sh
'''
                    }
                }
            }

            version = sh(script: "sed -n 's/^COREOS_VERSION=//p' .repo/manifests/version.txt",
                         returnStdout: true).trim()

            fingerprint "chroot/build/${params.BOARD}/var/lib/portage/pkgs/*/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2,tmp/*"
            dir('tmp') {
                deleteDir()
            }
        }

        /* Spawn a downstream job for formats that require it.  */
        if (FORMAT in downstreams)
            downstreams[FORMAT](version: version)
    }
}

stage('Build') {
    if (true) {  /* Limit the parallel builds to avoid scheduling failures.  */
        def parallel_max = 2
        /* Make this ugly for serializability again.  */
        ArrayList<Closure> vm_builds = matrix_map.values()
        matrix_map = [:]
        for (int j = 0; j < parallel_max; j++) {
            def MOD = j  /* This MUST use fresh variables per iteration.  */
            matrix_map["vm_${MOD}"] = {
                for (int i = MOD; i < vm_builds.size(); i += parallel_max) {
                    vm_builds[i]()
                }
            }
        }
    }

    matrix_map.failFast = true
    parallel matrix_map
}
