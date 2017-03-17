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
        choice(name: 'COREOS_OFFICIAL',
               choices: "0\n1"),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

node('amd64 && kvm') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector',
                         stable: false]])

        withCredentials([
            [$class: 'FileBinding',
             credentialsId: 'GOOGLE_APPLICATION_CREDENTIALS',
             variable: 'GOOGLE_APPLICATION_CREDENTIALS']
        ]) {
            withEnv(["BOARD=${params.BOARD}",
                     "COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                     "MANIFEST_NAME=${params.MANIFEST_NAME}",
                     "MANIFEST_REF=${params.MANIFEST_REF}",
                     "MANIFEST_URL=${params.MANIFEST_URL}"]) {
                sh '''#!/bin/bash -ex

# clean up old test results
rm -f tmp/*.tap

# build may not be started without a ref value
[[ -n "${MANIFEST_REF#refs/tags/}" ]]

enter() {
  ./bin/cork enter --experimental -- "$@"
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

if [[ "${COREOS_OFFICIAL}" -eq 1 ]]; then
  root="gs://builds.release.core-os.net/stable"
else
  root="gs://builds.developer.core-os.net"
fi

mkdir -p tmp
./bin/cork download-image --root="${root}/boards/${BOARD}/${COREOS_VERSION}" \
                          --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
                          --cache-dir=./tmp \
                          --platform=qemu
enter lbunzip2 -k -f /mnt/host/source/tmp/coreos_production_image.bin.bz2

bios=bios-256k.bin
if [[ "${BOARD}" == arm64* ]]; then
  script setup_board --board=${BOARD} \
                     --getbinpkgver="${COREOS_VERSION}" \
                     --regen_configs_only
  enter "emerge-${BOARD}" --nodeps -qugKN sys-firmware/edk2-armvirt
  bios="/build/${BOARD}/usr/share/edk2-armvirt/bios.bin"
fi

# copy all of the latest mantle binaries into the chroot
sudo cp -t chroot/usr/lib/kola/arm64 bin/arm64/*
sudo cp -t chroot/usr/lib/kola/amd64 bin/amd64/*
sudo cp -t chroot/usr/bin bin/[b-z]*

enter sudo timeout --signal=SIGQUIT 60m kola run --board="${BOARD}" \
                     --parallel=2 \
                     --qemu-bios="$bios" \
                     --qemu-image="/mnt/host/source/tmp/coreos_production_image.bin" \
                     --tapfile="/mnt/host/source/tmp/${JOB_NAME##*/}.tap"

if [[ "${COREOS_BUILD_ID#jenkins2-}" == master-* ]]; then
  enter gsutil cp "${root}/boards/${BOARD}/${COREOS_VERSION}/version.txt" \
                  "${root}/boards/${BOARD}/current-master/version.txt"
fi
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
              testResults: 'tmp/*.tap',
              todoIsFailure: false,
              validateNumberOfTests: true,
              verbose: true])

        fingerprint 'tmp/*,chroot/var/lib/portage/pkgs/*/*.tbz2'
    }
}
