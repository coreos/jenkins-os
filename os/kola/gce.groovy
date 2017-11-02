#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: '''Credentials given here must have permission \
to download release storage files, create compute images, and run instances''',
                    name: 'GS_RELEASE_CREDS',
                    required: true),
        string(name: 'GS_RELEASE_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where image files are downloaded'),
        text(name: 'TORCX_MANIFEST',
             defaultValue: '',
             description: 'Contents of the torcx manifest for kola tests'),
        string(name: 'VERSION',
               defaultValue: '',
               description: 'OS image version to use'),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

/* The kola step doesn't fail the job, so save the return code separately.  */
def rc = 0

node('amd64') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'torcx_manifest.json', text: params.TORCX_MANIFEST ?: ''

        withCredentials([
            file(credentialsId: params.GS_RELEASE_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
        ]) {
            withEnv(["BOARD=amd64-usr",
                     "COREOS_VERSION=${params.VERSION}",
                     "DOWNLOAD_ROOT=${params.GS_RELEASE_ROOT}"]) {
                rc = sh returnStatus: true, script: '''#!/bin/bash -ex

rm -rf *.tap _kola_temp*

NAME="jenkins-${JOB_NAME##*/}-${BUILD_NUMBER}"

bin/ore gcloud create-image \
    --board="${BOARD}" \
    --family="${NAME}" \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --source-root="${DOWNLOAD_ROOT}/boards" \
    --version="${COREOS_VERSION}"

GCE_NAME="${NAME//[+.]/-}-${COREOS_VERSION//[+.]/-}"

trap 'bin/ore gcloud delete-images \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    "${GCE_NAME}"' EXIT

timeout --signal=SIGQUIT 60m bin/kola run \
    --basename="${NAME}" \
    --gce-image="${GCE_NAME}" \
    --gce-json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --parallel=4 \
    --platform=gce \
    --tapfile="${JOB_NAME##*/}.tap" \
    --torcx-manifest=torcx_manifest.json
'''  /* Editor quote safety: ' */
            }
        }
    }

    stage('Post-build') {
        step([$class: 'TapPublisher',
              discardOldReports: false,
              enableSubtests: true,
              failIfNoResults: true,
              failedTestsMarkBuildAsFailure: true,
              flattenTapResult: false,
              includeCommentDiagnostics: true,
              outputTapToConsole: true,
              planRequired: true,
              showOnlyFailures: false,
              skipIfBuildNotOk: false,
              stripSingleParents: false,
              testResults: '*.tap',
              todoIsFailure: false,
              validateNumberOfTests: true,
              verbose: true])

        sh 'tar -cJf _kola_temp.tar.xz _kola_temp'
        archiveArtifacts '_kola_temp.tar.xz'
    }
}

/* Propagate the job status after publishing TAP results.  */
currentBuild.result = rc == 0 ? 'SUCCESS' : 'FAILURE'
