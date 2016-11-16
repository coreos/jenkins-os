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
                     credentialsId: 'buildbot-official.2E16137F.subkey.gpg',
                     variable: 'GPG_SECRET_KEY_FILE'],
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
                  --manifest-name "${MANIFEST_NAME}"

if [[ -x ./src/scripts/build_jobs/04_images.sh ]]; then
  exec ./src/scripts/build_jobs/04_images.sh
fi

# first thing, clear out old images
sudo rm -rf src/build

script() {
  local script="/mnt/host/source/src/scripts/${1}"; shift
  ./bin/cork enter --experimental -- "${script}" "$@"
}

source .repo/manifests/version.txt
export COREOS_BUILD_ID

if [[ "${COREOS_VERSION}" == 1010.* && "${BOARD}" == arm64-usr ]]; then
  echo "SKIPPING ARM"
  exit 0
fi

# Set up GPG for signing images
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap "rm -rf '${GNUPGHOME}'" EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import "${GPG_SECRET_KEY_FILE}"

sudo rm -rf chroot/build
script setup_board --board=${BOARD} \
                   --getbinpkgver="${COREOS_VERSION}" \
                   --regen_configs_only

if [[ "${COREOS_OFFICIAL}" -eq 1 ]]; then
  GROUP=stable
  UPLOAD=gs://builds.release.core-os.net/stable
  script set_official --board=${BOARD} --official
else
  GROUP=developer
  UPLOAD=gs://builds.developer.core-os.net
  script set_official --board=${BOARD} --noofficial
fi

script build_image --board=${BOARD} \
                   --group=${GROUP} \
                   --getbinpkg \
                   --getbinpkgver="${COREOS_VERSION}" \
                   --sign=buildbot@coreos.com \
                   --sign_digests=buildbot@coreos.com \
                   --upload_root=${UPLOAD} \
                   --upload prod container

if [[ "${COREOS_OFFICIAL}" -eq 1 ]]; then
  script image_set_group --board=${BOARD} \
                         --group=alpha \
                         --sign=buildbot@coreos.com \
                         --sign_digests=buildbot@coreos.com \
                         --upload_root=gs://builds.release.core-os.net/alpha \
                         --upload
  script image_set_group --board=${BOARD} \
                         --group=beta \
                         --sign=buildbot@coreos.com \
                         --sign_digests=buildbot@coreos.com \
                         --upload_root=gs://builds.release.core-os.net/beta \
                         --upload
fi
'''  /* Editor quote safety: ' */
                    }
                }

                fingerprint "chroot/build/${BOARD}/var/lib/portage/pkgs/*/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2,src/build/images/${BOARD}/latest/*"
            }
        }
    }
}

stage('Build') {
    matrix_map.failFast = true
    parallel matrix_map
}

stage('Downstream') {
    if (false)  /* Disable downstream jobs for now.  */
    parallel failFast: false,
        'board-vm-matrix': {
            build job: 'vm-matrix', parameters: [
                string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
                string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
                string(name: 'MANIFEST_URL', value: params.MANIFEST_URL)
            ]
        },
        'kola-qemu-amd64': {
            build job: '../kola/qemu', parameters: [
                string(name: 'BOARD', value: 'amd64-usr'),
                string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
                string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
                string(name: 'MANIFEST_URL', value: params.MANIFEST_URL)
            ]
        }
}
