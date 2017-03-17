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

node('coreos && amd64 && sudo') {
    def config
    String verify_key = "./verify_key"

    stage('Config') {
        configFileProvider([configFile(fileId: 'JOB_CONFIG', variable: 'JOB_CONFIG')]) {
            sh "cat ${env.JOB_CONFIG}"
            config = load("${env.JOB_CONFIG}")
        }
        try {
            configFileProvider([configFile(fileId: 'GPG_VERIFY_KEY', targetLocation: "${verify_key}")]) {
            }
        } catch (err) {
            echo "Using build-in GPG verify key."
            verify_key = ""
        }
    }

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
                     "MANIFEST_URL=${params.MANIFEST_URL}",
                     "DEV_BUILDS_ROOT=${config.DEV_BUILDS_ROOT()}",
                     "REL_BUILDS_ROOT=${config.REL_BUILDS_ROOT()}",
                     "GPG_VERIFY_KEY=${verify_key}"]) {
                sh '''#!/bin/bash -ex

# clean up old test results
rm -f tmp/*.tap

# build may not be started without a ref value
[[ -n "${MANIFEST_REF#refs/tags/}" ]]

enter() {
  ./bin/cork enter --experimental -- env \
    COREOS_DEV_BUILDS="http://storage.googleapis.com/${DEV_BUILDS_ROOT}" \
    "$@"
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
  root="gs://${REL_BUILDS_ROOT}/stable"
else
  root="gs://${DEV_BUILDS_ROOT}"
fi

mkdir -p tmp
./bin/cork download-image --root="${root}/boards/${BOARD}/${COREOS_VERSION}" \
                          --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
                          --verify-key="${GPG_VERIFY_KEY}" \
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
