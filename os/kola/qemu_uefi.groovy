#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        choice(name: 'BOARD',
               choices: "amd64-usr\narm64-usr",
               description: 'Target board to build'),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        string(name: 'MANIFEST_REF',
               defaultValue: 'refs/tags/'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        string(name: 'BUILDS_CLONE_CREDS',
               defaultValue: '',
               description: 'Credential ID for SSH Git clone URLs'),
        string(name: 'DOWNLOAD_CREDS',
               defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
               description: '''Credentials ID for a JSON file passed as the \
GOOGLE_APPLICATION_CREDENTIALS value for uploading image files from the \
Google Storage URL, requires read permission'''),
        string(name: 'DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where image files are downloaded'),
        text(name: 'SIGNING_VERIFY',
             defaultValue: '',
             description: '''Public key to verify signed files, or blank to \
use the built-in buildbot public key'''),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

/* The kola step doesn't fail the job, so save the return code separately.  */
def rc = 0

node('amd64 && kvm') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'verify.gpg.pub', text: params.SIGNING_VERIFY ?: ''

        sshagent(credentials: [params.BUILDS_CLONE_CREDS],
                 ignoreMissing: true) {
            withCredentials([
                [$class: 'FileBinding',
                 credentialsId: params.DOWNLOAD_CREDS,
                 variable: 'GOOGLE_APPLICATION_CREDENTIALS']
            ]) {
                withEnv(["BOARD=${params.BOARD}",
                         "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}",
                         "MANIFEST_NAME=${params.MANIFEST_NAME}",
                         "MANIFEST_REF=${params.MANIFEST_REF}",
                         "MANIFEST_URL=${params.MANIFEST_URL}"]) {
                    rc = sh returnStatus: true, script: '''#!/bin/bash -ex

sudo rm -rf src/scripts/_kola_temp tmp _kola_temp*

enter() {
  bin/cork enter --experimental -- "$@"
}

script() {
  local script="/mnt/host/source/src/scripts/${1}"; shift
  enter "${script}" "$@"
}

./bin/cork update --create --downgrade-replace --verify --verbose \
                  --manifest-url "${MANIFEST_URL}" \
                  --manifest-branch "${MANIFEST_REF}" \
                  --manifest-name "${MANIFEST_NAME}"
source .repo/manifests/version.txt

[ -s verify.gpg.pub ] && verify_key=--verify-key=verify.gpg.pub || verify_key=

mkdir -p tmp
bin/cork download-image \
    --cache-dir=tmp \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --platform=qemu_uefi \
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
    --qemu-bios=/mnt/host/source/tmp/coreos_production_qemu_uefi_efi_code.fd \
    --qemu-image=/mnt/host/source/tmp/coreos_production_image.bin \
    --tapfile="/mnt/host/source/tmp/${JOB_NAME##*/}.tap"
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
              testResults: 'tmp/*.tap',
              todoIsFailure: false,
              validateNumberOfTests: true,
              verbose: true])

        sh 'tar -C src/scripts -cJf _kola_temp.tar.xz _kola_temp'
        archiveArtifacts '_kola_temp.tar.xz'
    }
}

/* Propagate the job status after publishing TAP results.  */
currentBuild.result = rc == 0 ? 'SUCCESS' : 'FAILURE'
