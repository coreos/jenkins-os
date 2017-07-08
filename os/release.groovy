#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        choice(name: 'CHANNEL',
               choices: "alpha\nbeta\nstable",
               description: 'Which release channel to use'),
        string(name: 'VERSION',
               defaultValue: '',
               description: 'Which OS version to release'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'd919f31a-22a4-4272-a5d2-67b6d9555209',
                    description: 'AWS credentials list for AMI creation and releasing',
                    name: 'AWS_RELEASE_CREDS',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '7ab88376-e794-4128-b644-41c83c89e76d',
                    description: 'JSON credentials file for all Azure clouds used by plume',
                    name: 'AZURE_CREDS',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '34003159-3f1c-48bb-8791-96c0042ceb84',
                    description: 'JSON credentials file for the GCE releases service account',
                    name: 'GCE_CREDS',
                    required: true),
    ])
])

node('amd64') {
    stage('Release') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        withCredentials([
            file(credentialsId: params.AWS_RELEASE_CREDS, variable: 'AWS_CREDENTIALS'),
            file(credentialsId: params.AZURE_CREDS, variable: 'AZURE_CREDENTIALS'),
            file(credentialsId: params.GCE_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
        ]) {
            withEnv(["CHANNEL=${params.CHANNEL}",
                     "VERSION=${params.VERSION}"]) {
                sh '''#!/bin/bash -ex
case "${CHANNEL}" in
    stable)
        boards=( amd64-usr )
        ;;
    *)
        boards=( amd64-usr arm64-usr )
        ;;
esac

for board in "${boards[@]}"
do
        bin/plume release \
            --debug \
            --aws-credentials="${AWS_CREDENTIALS}" \
            --azure-profile="${AZURE_CREDENTIALS}" \
            --gce-json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
            --board="${board}" \
            --channel="${CHANNEL}" \
            --version="${VERSION}"
done
'''  /* Editor quote safety: ' */
            }
        }
    }
}
