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

    stage('Config') {
        configFileProvider([configFile(fileId: 'JOB_CONFIG', variable: 'JOB_CONFIG')]) {
            sh "cat ${env.JOB_CONFIG}"
            config = load("${env.JOB_CONFIG}")
        }
    }

    ws("${env.WORKSPACE}/${params.BOARD}") {
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
                withEnv(["COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                         "MANIFEST_NAME=${params.MANIFEST_NAME}",
                         "MANIFEST_REF=${params.MANIFEST_REF}",
                         "MANIFEST_URL=${params.MANIFEST_URL}",
                         "BOARD=${params.BOARD}",
                         "GPG_USER_ID=${config.GPG_USER_ID()}",
                         "DEV_BUILDS_ROOT=${config.DEV_BUILDS_ROOT()}"]) {
                    sh '''#!/bin/bash -ex

# build may not be started without a ref value
[[ -n "${MANIFEST_REF#refs/tags/}" ]]

./bin/cork update --create --downgrade-replace --verify --verbose \
                  --manifest-url "${MANIFEST_URL}" \
                  --manifest-branch "${MANIFEST_REF}" \
                  --manifest-name "${MANIFEST_NAME}" \
                  -- --toolchain_boards=${BOARD}

# use a ccache dir that persists across sdk recreations
# XXX: alternatively use a ccache dir that is usable by all jobs on a given node.
mkdir -p .cache/ccache

enter() {
  ./bin/cork enter --experimental -- env \
    COREOS_DEV_BUILDS="http://storage.googleapis.com/${DEV_BUILDS_ROOT}" \
    CCACHE_DIR="/mnt/host/source/.cache/ccache" \
    CCACHE_MAXSIZE="5G" \
    "$@"
}

script() {
  local script="/mnt/host/source/src/scripts/${1}"; shift
  enter "${script}" "$@"
}

source .repo/manifests/version.txt
export COREOS_BUILD_ID

# figure out if ccache is doing us any good in this scheme
enter ccache --zero-stats

#if [[ "${COREOS_OFFICIAL:-0}" -eq 1 ]]; then
  script setup_board --board=${BOARD} \
                     --skip_chroot_upgrade \
                     --getbinpkgver=${COREOS_VERSION} \
                     --toolchainpkgonly \
                     --force
#fi
script build_packages --board=${BOARD} \
                      --skip_chroot_upgrade \
                      --getbinpkgver=${COREOS_VERSION} \
                      --toolchainpkgonly \
                      --upload --upload_root gs://${DEV_BUILDS_ROOT}

enter ccache --show-stats
'''  /* Editor quote safety: ' */
                }
            }
        }

        stage('Post-build') {
            fingerprint "chroot/build/${params.BOARD}/var/lib/portage/pkgs/*/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2"
        }
    }
}

stage('Downstream') {
    build job: 'image-matrix', parameters: [
        string(name: 'BOARD', value: params.BOARD),
        string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
        string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
        string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
        string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
        string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
    ]
}
