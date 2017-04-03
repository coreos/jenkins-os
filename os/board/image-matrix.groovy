#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        choice(name: 'BOARD',
               choices: "amd64-usr\narm64-usr",
               description: 'Target board to build'),
        string(name: 'GROUP',
               defaultValue: 'developer',
               description: 'Which release group owns this build'),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        string(name: 'MANIFEST_REF',
               defaultValue: 'refs/tags/'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        choice(name: 'COREOS_OFFICIAL',
               choices: "0\n1"),
        string(name: 'BUILDS_CLONE_CREDS',
               defaultValue: '',
               description: 'Credential ID for SSH Git clone URLs'),
        string(name: 'GS_DEVEL_CREDS',
               defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
               description: '''Credentials ID for a JSON file passed as the \
GOOGLE_APPLICATION_CREDENTIALS value for uploading development files to the \
Google Storage URL, requires write permission'''),
        string(name: 'GS_DEVEL_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where development files are uploaded'),
        string(name: 'GS_RELEASE_CREDS',
               defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
               description: '''Credentials ID for a JSON file passed as the \
GOOGLE_APPLICATION_CREDENTIALS value for uploading release files to the \
Google Storage URL, requires write permission'''),
        string(name: 'GS_RELEASE_DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are downloaded'),
        string(name: 'GS_RELEASE_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are uploaded'),
        string(name: 'SIGNING_CREDS',
               defaultValue: 'buildbot-official.2E16137F.subkey.gpg',
               description: 'Credential ID for a GPG private key file'),
        string(name: 'SIGNING_USER',
               defaultValue: 'buildbot@coreos.com',
               description: 'E-mail address to identify the GPG key'),
        text(name: 'SIGNING_VERIFY',
             defaultValue: '',
             description: '''Public key to verify signed files, or blank to \
use the built-in buildbot public key'''),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

/* The unsigned image generated here is still a dev file with Secure Boot.  */
def UPLOAD_CREDS = params.GS_RELEASE_CREDS
def UPLOAD_ROOT = params.GS_RELEASE_ROOT
if (false && params.COREOS_OFFICIAL == '1') {
    UPLOAD_CREDS = params.GS_DEVEL_CREDS
    UPLOAD_ROOT = params.GS_DEVEL_ROOT
}

node('coreos && amd64 && sudo') {
    ws("${env.WORKSPACE}/${params.BOARD}") {
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
                     credentialsId: params.SIGNING_CREDS,
                     variable: 'GPG_SECRET_KEY_FILE'],
                    [$class: 'FileBinding',
                     credentialsId: params.GS_DEVEL_CREDS,
                     variable: 'GS_DEVEL_CREDS'],
                    [$class: 'FileBinding',
                     credentialsId: UPLOAD_CREDS,
                     variable: 'GOOGLE_APPLICATION_CREDENTIALS']
                ]) {
                    withEnv(["COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                             "GROUP=${params.GROUP}",
                             "MANIFEST_NAME=${params.MANIFEST_NAME}",
                             "MANIFEST_REF=${params.MANIFEST_REF}",
                             "MANIFEST_URL=${params.MANIFEST_URL}",
                             "BOARD=${params.BOARD}",
                             "DOWNLOAD_ROOT=${params.GS_DEVEL_ROOT}",
                             "SIGNING_USER=${params.SIGNING_USER}",
                             "UPLOAD_ROOT=${UPLOAD_ROOT}"]) {
                        sh '''#!/bin/bash -ex

# build may not be started without a ref value
[[ -n "${MANIFEST_REF#refs/tags/}" ]]

./bin/cork update --create --downgrade-replace --verify --verbose \
                  --manifest-url "${MANIFEST_URL}" \
                  --manifest-branch "${MANIFEST_REF}" \
                  --manifest-name "${MANIFEST_NAME}"

# first thing, clear out old images
sudo rm -rf src/build

enter() {
  sudo ln -f "${GS_DEVEL_CREDS}" chroot/etc/portage/gangue.json
  [ -s verify.gpg.pub ] &&
  sudo ln -f verify.gpg.pub chroot/etc/portage/gangue.gpg.pub &&
  verify_key=--verify-key=/etc/portage/gangue.gpg.pub
  trap 'sudo rm -f chroot/etc/portage/gangue.*' RETURN
  ./bin/cork enter --experimental -- env \
    COREOS_DEV_BUILDS="${DOWNLOAD_ROOT}" \
    PORTAGE_SSH_OPTS= \
    {FETCH,RESUME}COMMAND_GS="/usr/bin/gangue get \
--json-key=/etc/portage/gangue.json $verify_key \
"'"${URI}" "${DISTDIR}/${FILE}"' \
    "$@"
}

script() {
  local script="/mnt/host/source/src/scripts/${1}"; shift
  enter "${script}" "$@"
}

sudo cp bin/gangue chroot/usr/bin/gangue  # XXX: until SDK mantle has it

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
  script set_official --board=${BOARD} --official
else
  script set_official --board=${BOARD} --noofficial
fi

script build_image --board=${BOARD} \
                   --group=${GROUP} \
                   --getbinpkg \
                   --getbinpkgver="${COREOS_VERSION}" \
                   --sign="${SIGNING_USER}" \
                   --sign_digests="${SIGNING_USER}" \
                   --upload_root="${UPLOAD_ROOT}" \
                   --upload prod container
'''  /* Editor quote safety: ' */
                    }
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
                    string(name: 'GROUP', value: params.GROUP),
                    string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                    string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
                    string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                    string(name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS),
                    string(name: 'GS_DEVEL_CREDS', value: params.GS_DEVEL_CREDS),
                    string(name: 'GS_DEVEL_ROOT', value: params.GS_DEVEL_ROOT),
                    string(name: 'GS_RELEASE_CREDS', value: params.GS_RELEASE_CREDS),
                    string(name: 'GS_RELEASE_DOWNLOAD_ROOT', value: params.GS_RELEASE_DOWNLOAD_ROOT),
                    string(name: 'GS_RELEASE_ROOT', value: params.GS_RELEASE_ROOT),
                    string(name: 'SIGNING_CREDS', value: params.SIGNING_CREDS),
                    string(name: 'SIGNING_USER', value: params.SIGNING_USER),
                    text(name: 'SIGNING_VERIFY', value: params.SIGNING_VERIFY),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
            else
                build job: 'vm-matrix', parameters: [
                    string(name: 'BOARD', value: params.BOARD),
                    string(name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS),
                    string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
                    string(name: 'GS_DEVEL_CREDS', value: params.GS_DEVEL_CREDS),
                    string(name: 'GS_DEVEL_ROOT', value: params.GS_DEVEL_ROOT),
                    string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                    string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
                    string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                    string(name: 'GS_RELEASE_CREDS', value: params.GS_RELEASE_CREDS),
                    string(name: 'GS_RELEASE_DOWNLOAD_ROOT', value: params.GS_RELEASE_DOWNLOAD_ROOT),
                    string(name: 'GS_RELEASE_ROOT', value: params.GS_RELEASE_ROOT),
                    string(name: 'SIGNING_CREDS', value: params.SIGNING_CREDS),
                    string(name: 'SIGNING_USER', value: params.SIGNING_USER),
                    text(name: 'SIGNING_VERIFY', value: params.SIGNING_VERIFY),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
        },
        'kola-qemu': {
            if (params.BOARD == 'amd64-usr')
                build job: '../kola/qemu', parameters: [
                    string(name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS),
                    string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                    string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
                    string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                    string(name: 'DOWNLOAD_CREDS', value: UPLOAD_CREDS),
                    string(name: 'DOWNLOAD_ROOT', value: UPLOAD_ROOT),
                    text(name: 'SIGNING_VERIFY', value: params.SIGNING_VERIFY),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
        }
}
