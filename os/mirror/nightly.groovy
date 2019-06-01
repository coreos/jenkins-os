#!groovy

properties([
    buildDiscarder(logRotator(artifactDaysToKeepStr: '3',
                              artifactNumToKeepStr: '3',
                              daysToKeepStr: '30',
                              numToKeepStr: '50')),

    parameters([
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: 'Credentials to upload distfiles to GCS',
                    name: 'GCS_CREDS',
                    required: true),
    ]),

    pipelineTriggers([cron('H 8 * * *')])
])

node('coreos && amd64 && sudo') {
    stage('Build') {
        copyArtifacts fingerprintArtifacts: true,
                      projectName: '/mantle/master-builder',
                      selector: lastSuccessful()

        withCredentials([
            file(credentialsId: params.GCS_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
        ]) {
            sh '''#!/bin/bash -ex
bin/cork update --create --verbose --force-sync
bin/cork enter --bind-gpg-agent=false -- \
    /mnt/host/source/src/scripts/update_distfiles --download --upload coreos portage-stable
'''
        }
    }
}
