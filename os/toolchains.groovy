#!groovy

properties([
    buildDiscarder(logRotator(artifactDaysToKeepStr: '3',
                              artifactNumToKeepStr: '3',
                              daysToKeepStr: '30',
                              numToKeepStr: '50')),

    parameters([
        booleanParam(name: 'USE_CACHE',
                     defaultValue: false,
                     description: 'Enable use of any binary packages cached locally from previous builds.'),
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

    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'TriggeredBuildSelector',
                         allowUpstreamDependencies: true,
                         fallbackToLastSuccessful: true,
                         upstreamFilterStrategy: 'UseGlobalSetting']])

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
                     'USE_CACHE=' + (params.USE_CACHE ? 'true' : 'false'),
                     "DEV_BUILDS_ROOT=${config.DEV_BUILDS_ROOT()}",
                     "GPG_USER_ID=${config.GPG_USER_ID()}"]) {
                sh '''#!/bin/bash -ex

# build may not be started without a ref value
[[ -n "${MANIFEST_REF#refs/tags/}" ]]

# hack because catalyst leaves things chowned as root
[[ -d .cache/sdks ]] && sudo chown -R $USER .cache/sdks

./bin/cork update --create --downgrade-replace --verify --verbose \
                  --manifest-url "${MANIFEST_URL}" \
                  --manifest-branch "${MANIFEST_REF}" \
                  --manifest-name "${MANIFEST_NAME}"

enter() {
  ./bin/cork enter --experimental -- env \
    COREOS_DEV_BUILDS="http://storage.googleapis.com/${DEV_BUILDS_ROOT}" \
    "$@"
}

script() {
  local script="/mnt/host/source/src/scripts/${1}"; shift
  enter "${script}" "$@"
}

source .repo/manifests/version.txt
export COREOS_BUILD_ID

# Set up GPG for signing images
export GNUPGHOME="${PWD}/.gnupg"
sudo rm -rf "${GNUPGHOME}"
trap "sudo rm -rf '${GNUPGHOME}'" EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import "${GPG_SECRET_KEY_FILE}"

# Wipe all of catalyst or just clear out old tarballs taking up space
sudo rm -rf src/build/catalyst/builds
if [[ "${COREOS_OFFICIAL:-0}" -eq 1 || "$USE_CACHE" == false ]]; then
  sudo rm -rf src/build
fi

S=/mnt/host/source/src/scripts
enter sudo emerge -uv --jobs=2 catalyst
enter sudo ${S}/build_toolchains \
  --sign ${GPG_USER_ID} --sign_digests ${GPG_USER_ID} \
  --upload --upload_root gs://${DEV_BUILDS_ROOT}

# Free some disk space only on success, for debugging failures
sudo rm -rf src/build/catalyst/builds
'''  /* Editor quote safety: ' */
            }
        }
    }

    stage('Post-build') {
        fingerprint 'src/build/catalyst/packages/coreos-toolchains/**/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2'
    }
}

stage('Downstream') {
    parallel failFast: false,
        'board-packages-matrix-amd64-usr': {
            build job: 'board/packages-matrix', parameters: [
                string(name: 'BOARD', value: 'amd64-usr'),
                string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
                string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
                string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
            ]
        },
        'board-packages-matrix-arm64-usr': {
            sleep time: 1, unit: 'MINUTES'
            build job: 'board/packages-matrix', parameters: [
                string(name: 'BOARD', value: 'arm64-usr'),
                string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
                string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
                string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
            ]
        }
}
