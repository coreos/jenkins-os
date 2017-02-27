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
    ws("${env.WORKSPACE}/${params.BOARD}") {
        stage('Build') {
            step([$class: 'CopyArtifact',
                  fingerprintArtifacts: true,
                  projectName: '/mantle/master-builder',
                  selector: [$class: 'StatusBuildSelector',
                             stable: false]])

            withCredentials([
                [$class: 'FileBinding',
                 credentialsId: 'GPG_SECRET_KEY_FILE',
                 variable: 'GPG_SECRET_KEY_FILE'],
                [$class: 'FileBinding',
                 credentialsId: 'GOOGLE_APPLICATION_CREDENTIALS',
                 variable: 'GOOGLE_APPLICATION_CREDENTIALS']
            ]) {
                withEnv(["COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                         "MANIFEST_NAME=${params.MANIFEST_NAME}",
                         "MANIFEST_REF=${params.MANIFEST_REF}",
                         "MANIFEST_URL=${params.MANIFEST_URL}",
                         "BOARD=${params.BOARD}"]) {
                    sh '''#!/bin/bash -ex

# build may not be started without a ref value
[[ -n "${MANIFEST_REF#refs/tags/}" ]]

./bin/cork update --create --downgrade-replace --verify --verbose \
                  --manifest-url "${MANIFEST_URL}" \
                  --manifest-branch "${MANIFEST_REF}" \
                  --manifest-name "${MANIFEST_NAME}"

# first thing, clear out old images
sudo rm -rf src/build

script() {
  local script="/mnt/host/source/src/scripts/${1}"; shift
  ./bin/cork enter --experimental -- "${script}" "$@"
}

source .repo/manifests/version.txt
export COREOS_BUILD_ID

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
        }

        stage('Post-build') {
            fingerprint "chroot/build/${params.BOARD}/var/lib/portage/pkgs/*/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2,src/build/images/${params.BOARD}/latest/*"
            dir('src/build') {
                deleteDir()
            }
        }
    }
}

stage('Downstream') {
    parallel failFast: false,
        'board-vm-matrix': {
            if (false && params.COREOS_OFFICIAL == '1')
                build job: 'sign-image', parameters: [
                    string(name: 'BOARD', value: params.BOARD),
                    string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                    string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
                    string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
            else
                build job: 'vm-matrix', parameters: [
                    string(name: 'BOARD', value: params.BOARD),
                    string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
                    string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                    string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
                    string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
        },
        'kola-qemu': {
            build job: '../kola/qemu', propagate: false, parameters: [
                string(name: 'BOARD', value: params.BOARD),
                string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
                string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
                string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
            ]
        }
}
