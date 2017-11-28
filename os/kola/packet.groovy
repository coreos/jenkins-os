#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        choice(name: 'BOARD',
               choices: "amd64-usr\narm64-usr",
               description: 'Target board to build'),
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
                    defaultValue: '',
                    description: 'Credential ID for SSH Git clone URLs',
                    name: 'BUILDS_CLONE_CREDS',
                    required: false),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: 'Credentials for signing GCS download URLs',
                    name: 'DOWNLOAD_CREDS',
                    required: true),
        string(name: 'DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where image files are downloaded'),
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
        text(name: 'TORCX_MANIFEST',
             defaultValue: '',
             description: 'Contents of the torcx manifest for kola tests'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: 'Credentials to upload iPXE scripts',
                    name: 'UPLOAD_CREDS',
                    required: true),
        string(name: 'UPLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where development files are uploaded'),
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

node('coreos && amd64 && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'torcx_manifest.json', text: params.TORCX_MANIFEST ?: ''
        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        sshagent(credentials: [params.BUILDS_CLONE_CREDS], ignoreMissing: true) {
            withCredentials([
                file(credentialsId: params.DOWNLOAD_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
                string(credentialsId: params.PACKET_CREDS, variable: 'PACKET_API_KEY'),
                file(credentialsId: params.UPLOAD_CREDS, variable: 'UPLOAD_CREDS'),
            ]) {
                withEnv(["BOARD=${params.BOARD}",
                         "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}",
                         "MANIFEST_NAME=${params.MANIFEST_NAME}",
                         "MANIFEST_TAG=${params.MANIFEST_TAG}",
                         "MANIFEST_URL=${params.MANIFEST_URL}",
                         "PACKET_PROJECT=${params.PACKET_PROJECT}",
                         "UPLOAD_ROOT=${params.UPLOAD_ROOT}"]) {
                    rc = sh returnStatus: true, script: '''#!/bin/bash -ex

rm -rf *.tap _kola_temp* url.txt

# JOB_NAME will not fit within the character limit
NAME="jenkins-${BUILD_NUMBER}"

# Set up GPG for verifying tags.
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap 'rm -rf "${GNUPGHOME}" credentials.json' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

bin/cork update \
    --create --downgrade-replace --verify --verify-signature --verbose \
    --manifest-branch "refs/tags/${MANIFEST_TAG}" \
    --manifest-name "${MANIFEST_NAME}" \
    --manifest-url "${MANIFEST_URL}"
source .repo/manifests/version.txt

timeout=3h

set -o pipefail
ln -f "${GOOGLE_APPLICATION_CREDENTIALS}" credentials.json
bin/cork enter --experimental -- gsutil signurl -d "${timeout}" \
    /mnt/host/source/credentials.json \
    "${DOWNLOAD_ROOT}/boards/${BOARD}/${COREOS_VERSION}/coreos_production_packet_image.bin.bz2" |
sed -n 's,^.*https://,https://,p' > url.txt

timeout --signal=SIGQUIT "${timeout}" bin/kola run \
    --basename="${NAME}" \
    --board="${BOARD}" \
    --gce-json-key="${UPLOAD_CREDS}" \
    --packet-api-key="${PACKET_API_KEY}" \
    --packet-facility=ewr1 \
    --packet-image-url="$(<url.txt)" \
    --packet-project="${PACKET_PROJECT}" \
    --packet-storage-url="${UPLOAD_ROOT}/mantle/packet" \
    --parallel=4 \
    --platform=packet \
    --tapfile="${JOB_NAME##*/}.tap" \
    --torcx-manifest=torcx_manifest.json | tee test.output
'''  /* Editor quote safety: ' */

                message = sh returnStdout: true, script: '''awk 'BEGIN{line=""} /--- FAIL: (.*) \\([0-9\\.]+s\\)/{if(line!=""){print line};flag=1;print $0;next}/([:space:]*[-=]{3})|((PASS)|(FAIL), output in)/{flag=0}flag {line=$0} END{print line}' < test.output'''
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

if (currentBuild.result == 'FAILURE')
    slackSend color: 'bad',
              message: "```Kola: Packet-$BOARD Failure:\n$message```"
