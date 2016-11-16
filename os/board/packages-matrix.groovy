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
               choices: "0\n1")
    ])
])

/* Construct a matrix of build variation closures.  */
def matrix_map = [:]
for (board in ['amd64-usr', 'arm64-usr']) {
    def BOARD = board  /* This MUST use fresh variables per iteration.  */
    matrix_map[BOARD] = {
        node('coreos && sudo') {
            ws("${env.WORKSPACE}/${BOARD}") {
                step([$class: 'CopyArtifact',
                      fingerprintArtifacts: true,
                      projectName: '/mantle/master-builder',
                      selector: [$class: 'StatusBuildSelector',
                                 stable: false]])

                withCredentials([
                    [$class: 'FileBinding',
                     credentialsId: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                     variable: 'GOOGLE_APPLICATION_CREDENTIALS']
                ]) {
                    withEnv(["COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                             "MANIFEST_NAME=${params.MANIFEST_NAME}",
                             "MANIFEST_REF=${params.MANIFEST_REF}",
                             "MANIFEST_URL=${params.MANIFEST_URL}",
                             "BOARD=${BOARD}"]) {
                        sh '''#!/bin/bash -ex

# build may not be started without a ref value
[[ -n "${MANIFEST_REF#refs/tags/}" ]]

./bin/cork update --create --downgrade-replace --verify --verbose \
                  --manifest-url "${MANIFEST_URL}" \
                  --manifest-branch "${MANIFEST_REF}" \
                  --manifest-name "${MANIFEST_NAME}" \
                  -- --toolchain_boards=${BOARD}

if [[ -x ./src/scripts/build_jobs/03_packages.sh ]]; then
  exec ./src/scripts/build_jobs/03_packages.sh
fi

# use a ccache dir that persists across sdk recreations
# XXX: alternatively use a ccache dir that is usable by all jobs on a given node.
mkdir -p .cache/ccache

enter() {
  ./bin/cork enter --experimental -- env \
    CCACHE_DIR="/mnt/host/source/.cache/ccache" \
    CCACHE_MAXSIZE="5G" "$@"
}

script() {
  local script="/mnt/host/source/src/scripts/${1}"; shift
  enter "${script}" "$@"
}

source .repo/manifests/version.txt
export COREOS_BUILD_ID

if [[ "${COREOS_VERSION}" == 1010.* && "${BOARD}" == arm64-usr ]]; then
  echo "SKIPPING ARM"
  exit 0
fi

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
                      --upload --upload_root gs://builds.developer.core-os.net

enter ccache --show-stats
'''  /* Editor quote safety: ' */
                    }
                }

                fingerprint "chroot/build/${BOARD}/var/lib/portage/pkgs/*/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2"
            }
        }
    }
}

stage('Build') {
    matrix_map.failFast = true
    parallel matrix_map
}

stage('Downstream') {
    build job: 'image-matrix', parameters: [
        string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
        string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
        string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
        string(name: 'MANIFEST_URL', value: params.MANIFEST_URL)
    ]
}
