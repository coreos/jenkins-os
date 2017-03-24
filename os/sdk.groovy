#!groovy

properties([
    buildDiscarder(logRotator(artifactDaysToKeepStr: '3',
                              artifactNumToKeepStr: '3',
                              daysToKeepStr: '30',
                              numToKeepStr: '50')),

    parameters([
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        string(name: 'MANIFEST_REF',
               defaultValue: 'refs/tags/'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        choice(name: 'COREOS_OFFICIAL',
               choices: "0\n1"),
        string(name: 'GS_DEVEL_CREDS',
               defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
               description: '''Credentials ID for a JSON file passed as the \
GOOGLE_APPLICATION_CREDENTIALS value for uploading development files to the \
Google Storage URL, requires write permission'''),
        string(name: 'GS_DEVEL_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where development files are uploaded'),
        string(name: 'SIGNING_CREDS',
               defaultValue: 'buildbot-official.2E16137F.subkey.gpg',
               description: 'Credential ID for a GPG private key file'),
        string(name: 'SIGNING_USER',
               defaultValue: 'buildbot@coreos.com',
               description: 'E-mail address to identify the GPG key'),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

node('coreos && amd64 && sudo') {
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
             credentialsId: params.SIGNING_CREDS,
             variable: 'GPG_SECRET_KEY_FILE'],
            [$class: 'FileBinding',
             credentialsId: params.GS_DEVEL_CREDS,
             variable: 'GOOGLE_APPLICATION_CREDENTIALS']
        ]) {
            withEnv(["COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                     "MANIFEST_NAME=${params.MANIFEST_NAME}",
                     "MANIFEST_REF=${params.MANIFEST_REF}",
                     "MANIFEST_URL=${params.MANIFEST_URL}",
                     "SIGNING_USER=${params.SIGNING_USER}",
                     "UPLOAD_ROOT=${params.GS_DEVEL_ROOT}"]) {
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
    ./bin/cork enter --experimental -- "$@"
}

source .repo/manifests/version.txt
export COREOS_BUILD_ID

# Set up GPG for signing images
export GNUPGHOME="${PWD}/.gnupg"
sudo rm -rf "${GNUPGHOME}"
trap "sudo rm -rf '${GNUPGHOME}'" EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import "${GPG_SECRET_KEY_FILE}"

# Wipe all of catalyst
sudo rm -rf src/build

S=/mnt/host/source/src/scripts
enter ${S}/update_chroot
enter sudo emerge -uv --jobs=2 catalyst
enter sudo ${S}/bootstrap_sdk \
    --sign="${SIGNING_USER}" \
    --sign_digests="${SIGNING_USER}" \
    --upload_root="${UPLOAD_ROOT}" \
    --upload

# Free some disk space only on success, for debugging failures
sudo rm -rf src/build/catalyst/builds
'''  /* Editor quote safety: ' */
            }
        }
    }

    stage('Post-build') {
        fingerprint 'src/build/catalyst/packages/coreos-sdk/**/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2'
    }
}
