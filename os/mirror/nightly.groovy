#!groovy

properties([
    buildDiscarder(logRotator(artifactDaysToKeepStr: '3',
                              artifactNumToKeepStr: '3',
                              daysToKeepStr: '30',
                              numToKeepStr: '50')),

    pipelineTriggers([cron('H 8 * * *')])
])

node('coreos && amd64 && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'TriggeredBuildSelector',
                         allowUpstreamDependencies: true,
                         fallbackToLastSuccessful: true,
                         upstreamFilterStrategy: 'UseGlobalSetting']])

        withCredentials([
            [$class: 'FileBinding',
             credentialsId: 'jenkins-coreos-systems-write-5df31bf86df3.json',
             variable: 'GOOGLE_APPLICATION_CREDENTIALS']
        ]) {
            sh '''#!/bin/bash -ex

script() {
  local script="/mnt/host/source/src/scripts/${1}"; shift
  ./bin/cork enter --experimental -- "${script}" "$@"
}

./bin/cork update --create --verbose
script update_distfiles --download --upload coreos portage-stable
'''  /* Editor quote safety: ' */
        }
    }

    stage('Post-build') {
        fingerprint 'chroot/var/lib/portage/pkgs/*/*.tbz2'
    }
}
