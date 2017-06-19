#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: '7ab88376-e794-4128-b644-41c83c89e76d',
         description: 'JSON credentials file for all Azure clouds used by plume',
         name: 'AZURE_CREDS',
         required: true],
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
         defaultValue: '',
         description: 'Credential ID for SSH Git clone URLs',
         name: 'BUILDS_CLONE_CREDS',
         required: false],
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
         description: '''Credentials given here must have permission to \
download release storage files''',
         name: 'DOWNLOAD_CREDS',
         required: true],
        string(name: 'GROUP',
               defaultValue: 'developer',
               description: 'Which release group owns this build'),
        string(name: 'MANIFEST_TAG',
               defaultValue: ''),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

node('amd64') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        sshagent(credentials: [params.BUILDS_CLONE_CREDS],
                 ignoreMissing: true) {
            withCredentials([
                [$class: 'FileBinding',
                 credentialsId: params.AZURE_CREDS,
                 variable: 'AZURE_CREDENTIALS'],
                [$class: 'FileBinding',
                 credentialsId: params.DOWNLOAD_CREDS,
                 variable: 'GOOGLE_APPLICATION_CREDENTIALS']
            ]) {
                withEnv(["BOARD=amd64-usr",
                         "CHANNEL=${params.GROUP}",
                         "MANIFEST_TAG=${params.MANIFEST_TAG}",
                         "MANIFEST_URL=${params.MANIFEST_URL}"]) {
                    sh '''#!/bin/bash -ex

rm -rf manifests

# set up GPG for verifying tags
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap 'rm -rf "${GNUPGHOME}"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

git clone --depth=1 --branch="${MANIFEST_TAG}" "${MANIFEST_URL}" manifests
git -C manifests tag -v "${MANIFEST_TAG}"
source manifests/version.txt

[ -s verify.asc ] && verify_key=--verify-key=verify.asc || verify_key=

bin/plume pre-release \
    --debug \
    --platform=azure \
    --azure-profile="${AZURE_CREDENTIALS}" \
    --gce-json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --board="${BOARD}" \
    --channel="${CHANNEL}" \
    --version="${COREOS_VERSION}" \
    $verify_key
'''  /* Editor quote safety: ' */
                }
            }
        }
    }
}
