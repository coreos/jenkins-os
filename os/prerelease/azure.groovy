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
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
         description: '''Credentials given here must have permission to \
download release storage files''',
         name: 'DOWNLOAD_CREDS',
         required: true],
        string(name: 'GROUP',
               defaultValue: 'developer',
               description: 'Which release group owns this build'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'VERSION',
               defaultValue: '',
               description: 'OS image version to use'),
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
                     "COREOS_VERSION=${params.VERSION}"]) {
                    sh '''#!/bin/bash -ex

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
