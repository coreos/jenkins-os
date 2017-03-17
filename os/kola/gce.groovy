#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        string(name: 'MANIFEST_REF',
               defaultValue: 'refs/tags/'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        choice(name: 'COREOS_OFFICIAL',
               choices: "0\n1"),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

node('gce') {  /* This needs "Read Write" on "Compute Engine" access scope.  */
    def config

    stage('Config') {
        configFileProvider([configFile(fileId: 'JOB_CONFIG', variable: 'JOB_CONFIG')]) {
            sh "cat ${env.JOB_CONFIG}"
            config = load("${env.JOB_CONFIG}")
        }
    }

    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector',
                         stable: false]])

        withEnv(["COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                 "MANIFEST_NAME=${params.MANIFEST_NAME}",
                 "MANIFEST_REF=${params.MANIFEST_REF}",
                 "MANIFEST_URL=${params.MANIFEST_URL}",
                 "DEV_BUILDS_ROOT=${config.DEV_BUILDS_ROOT()}",
                 "REL_BUILDS_ROOT=${config.REL_BUILDS_ROOT()}"]) {
            sh '''#!/bin/bash -ex

BOARD=amd64-usr

rm -rf *.tap manifests

short_ref="${MANIFEST_REF#refs/tags/}"
git clone --depth 1 --branch "${short_ref}" "${MANIFEST_URL}" manifests
source manifests/version.txt

if [[ "${COREOS_OFFICIAL}" -eq 1 ]]; then
  root="gs://${REL_BUILDS_ROOT}/stable"
else
  root="gs://${DEV_BUILDS_ROOT}"
fi

NAME="jenkins-${JOB_NAME##*/}-${BUILD_NUMBER}"

./bin/ore create-image \
    --board="${BOARD}" \
    --version="${COREOS_VERSION}" \
    --source-root="${root}/boards" \
    --service-auth=true \
    --family="${NAME}"

GCE_NAME="${NAME}-${COREOS_VERSION}"
GCE_NAME="${GCE_NAME//./-}"
GCE_NAME="${GCE_NAME//+/-}"

timeout --signal=SIGQUIT 30m ./bin/kola --tapfile="${JOB_NAME##*/}.tap" \
    --parallel=4 \
    --platform=gce \
    --gce-service-auth \
    --gce-image="${GCE_NAME}" \
    run
'''  /* Editor quote safety: ' */
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
    }
}
