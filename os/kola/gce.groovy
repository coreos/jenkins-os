#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        string(name: 'MANIFEST_TAG',
               defaultValue: ''),
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
download release storage files, create compute images, and run instances''',
         name: 'GS_RELEASE_CREDS',
         required: true],
        string(name: 'GS_RELEASE_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where image files are downloaded'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
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

        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        sshagent(credentials: [params.BUILDS_CLONE_CREDS],
                 ignoreMissing: true) {
            withCredentials([
                [$class: 'FileBinding',
                 credentialsId: params.GS_RELEASE_CREDS,
                 variable: 'GOOGLE_APPLICATION_CREDENTIALS']
            ]) {
                withEnv(["BOARD=amd64-usr",
                         "DOWNLOAD_ROOT=${params.GS_RELEASE_ROOT}",
                         "MANIFEST_TAG=${params.MANIFEST_TAG}",
                         "MANIFEST_URL=${params.MANIFEST_URL}"]) {
                    rc = sh returnStatus: true, script: '''#!/bin/bash -ex

sudo rm -rf *.tap manifests _kola_temp*

# set up GPG for verifying tags
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap "rm -rf '${GNUPGHOME}'" EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

git clone --depth=1 --branch="${MANIFEST_TAG}" "${MANIFEST_URL}" manifests
git -C manifests tag -v "${MANIFEST_TAG}"
source manifests/version.txt

NAME="jenkins-${JOB_NAME##*/}-${BUILD_NUMBER}"

bin/ore create-image \
    --board="${BOARD}" \
    --family="${NAME}" \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --source-root="${DOWNLOAD_ROOT}/boards" \
    --version="${COREOS_VERSION}"

GCE_NAME="${NAME//[+.]/-}-${COREOS_VERSION//[+.]/-}"

timeout --signal=SIGQUIT 30m bin/kola run \
    --gce-image="${GCE_NAME}" \
    --gce-json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --parallel=4 \
    --platform=gce \
    --tapfile="${JOB_NAME##*/}.tap"
'''  /* Editor quote safety: ' */
                }
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
