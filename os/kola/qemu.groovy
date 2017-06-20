#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
         defaultValue: '',
         description: 'Credential ID for SSH Git clone URLs',
         name: 'BUILDS_CLONE_CREDS',
         required: false],
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
         description: '''Credentials ID for a JSON file passed as the \
GOOGLE_APPLICATION_CREDENTIALS value for downloading release files from the \
Google Storage URL, requires read permission''',
         name: 'DOWNLOAD_CREDS',
         required: true],
        string(name: 'DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where image files are downloaded'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
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

/* The kola step doesn't fail the job, so save the return code separately.  */
def rc = 0

node('amd64 && kvm && sudo') {
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
                 credentialsId: params.DOWNLOAD_CREDS,
                 variable: 'GOOGLE_APPLICATION_CREDENTIALS']
            ]) {
                withEnv(["BOARD=amd64-usr",
                         "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}",
                         "MANIFEST_NAME=${params.MANIFEST_NAME}",
                         "MANIFEST_TAG=${params.MANIFEST_TAG}",
                         "MANIFEST_URL=${params.MANIFEST_URL}"]) {
                    rc = sh returnStatus: true, script: '''#!/bin/bash -ex

sudo rm -rf *.tap src/scripts/_kola_temp tmp _kola_temp*

enter() {
  bin/cork enter --experimental -- "$@"
}

# set up GPG for verifying tags
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap "rm -rf '${GNUPGHOME}'" EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

bin/cork update --create --downgrade-replace --verify --verify-signature --verbose \
                --manifest-branch "refs/tags/${MANIFEST_TAG}" \
                --manifest-name "${MANIFEST_NAME}" \
                --manifest-url "${MANIFEST_URL}"
source .repo/manifests/version.txt

[ -s verify.asc ] && verify_key=--verify-key=verify.asc || verify_key=

mkdir -p tmp
bin/cork download-image \
    --cache-dir=tmp \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --platform=qemu \
    --root="${DOWNLOAD_ROOT}/boards/${BOARD}/${COREOS_VERSION}" \
    --verify=true $verify_key
enter lbunzip2 -k -f /mnt/host/source/tmp/coreos_production_image.bin.bz2

# copy all of the latest mantle binaries into the chroot
sudo cp -t chroot/usr/lib/kola/arm64 bin/arm64/*
sudo cp -t chroot/usr/lib/kola/amd64 bin/amd64/*
sudo cp -t chroot/usr/bin bin/[b-z]*

enter sudo timeout --signal=SIGQUIT 60m kola run \
    --board="${BOARD}" \
    --parallel=2 \
    --platform=qemu \
    --qemu-bios=bios-256k.bin \
    --qemu-image=/mnt/host/source/tmp/coreos_production_image.bin \
    --tapfile="/mnt/host/source/${JOB_NAME##*/}.tap"

sudo rm -rf tmp

if [[ "${COREOS_BUILD_ID}" == *-master-* ]]; then
  enter gsutil cp "${DOWNLOAD_ROOT}/boards/${BOARD}/${COREOS_VERSION}/version.txt" \
                  "${DOWNLOAD_ROOT}/boards/${BOARD}/current-master/version.txt"
fi
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

        sh 'tar -C src/scripts -cJf _kola_temp.tar.xz _kola_temp'
        archiveArtifacts '_kola_temp.tar.xz'
    }
}

/* Propagate the job status after publishing TAP results.  */
currentBuild.result = rc == 0 ? 'SUCCESS' : 'FAILURE'
