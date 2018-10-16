#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        choice(name: 'BOARD',
               choices: "amd64-usr\narm64-usr",
               description: 'Target board to build'),
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
                    defaultValue: '',
                    description: 'Credential ID for SSH Git clone URLs',
                    name: 'BUILDS_CLONE_CREDS',
                    required: false),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: '''Credentials ID for a JSON file passed as \
the GOOGLE_APPLICATION_CREDENTIALS value for downloading release files from \
the Google Storage URL, requires read permission''',
                    name: 'DOWNLOAD_CREDS',
                    required: true),
        string(name: 'DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where image files are downloaded'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        string(name: 'MANIFEST_TAG',
               defaultValue: ''),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs'),
        booleanParam(name: 'KOLA_DEBUG',
               defaultValue: false,
               description: 'Pass --debug flag to kola'),
    ])
])

/* The kola step doesn't fail the job, so save the return code separately.  */
def rc = 0
def chroot = "chroot"

node('arm64 && kvm && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        sshagent(credentials: [params.BUILDS_CLONE_CREDS], ignoreMissing: true) {
            withCredentials([
                file(credentialsId: params.DOWNLOAD_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
            ]) {
                withEnv(["BOARD=${params.BOARD}",
                         "chroot=${chroot}",
                         "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}",
                         "MANIFEST_NAME=${params.MANIFEST_NAME}",
                         "MANIFEST_TAG=${params.MANIFEST_TAG}",
                         "MANIFEST_URL=${params.MANIFEST_URL}",
                         "KOLA_DEBUG=${params.KOLA_DEBUG}",]) {
                    rc = sh returnStatus: true, script: '''#!/bin/bash -ex

# Only arm64 currently supported.
[[ "${BOARD}" == "arm64-usr" ]] || exit 1

tapfile="${JOB_NAME##*/}.tap"
GNUPGHOME="${PWD}/.gnupg"

create_chroot () {
    if [[ -f "${chroot}/etc/os-release" && -f "${chroot}/update" ]]; then
        echo "Using existing chroot"
        return 0
    fi

    echo "Creating chroot"
    local docker_image="aarch64/ubuntu@sha256:63b997e0d64339408504f3d986e074dbba966e9b091562e11d61aef77337cad5" # aarch64/ubuntu:16.10
    sudo rm -rf ${chroot}
    riid=$(sudo --preserve-env rkt --insecure-options=image fetch "docker://${docker_image}")
    sudo --preserve-env rkt image extract --overwrite --rootfs-only "${riid}" ${chroot}
    sudo --preserve-env rkt image rm "${riid}"

    sudo chown ${USER}: ${chroot}
    mkdir -p ${chroot}/downloads

    cat << EOF > ${chroot}/update
#!bin/bash
apt-get update
apt-get -y install qemu-system-arm dnsmasq
rm -rf /var/lib/apt/lists/*
EOF
    chmod +x ${chroot}/update
}

update_chroot() {
    create_chroot
    sudo cp /etc/resolv.conf ${chroot}/etc/resolv.conf
    sudo chroot ${chroot}  bash -x /update
}

cleanup_chroot () {
    sudo umount -l ${chroot}/{proc,dev,sys,run}
}

enter_chroot() {
  sudo mount -t proc /proc ${chroot}/proc
  sudo mount --rbind /dev ${chroot}/dev
  sudo mount --make-rslave ${chroot}/dev
  sudo mount --rbind /sys ${chroot}/sys
  sudo mount --make-rslave ${chroot}/sys
  sudo mount --rbind /run ${chroot}/run
  sudo mount --make-rslave ${chroot}/run

  sudo chroot ${chroot} "$@"
  cleanup_chroot
}

download_manifest() {
    rm -rf ${1}
    mkdir -p ${1}
    curl --silent --show-error --location "${MANIFEST_URL%.git}/archive/${MANIFEST_TAG}.tar.gz" | \
        tar -C ${1} --strip-components=1 -xzf -
}

cleanup () {
    rm -rf "${GNUPGHOME}"
    cleanup_chroot || true
}

trap cleanup EXIT

sudo rm -rf *.tap src/scripts/_kola_temp tmp _kola_temp* ${chroot}/downloads/*

# Set up GPG for verifying tags.
rm -rf "${GNUPGHOME}"
mkdir --mode=0700 "${GNUPGHOME}"
export GNUPGHOME
gpg --import verify.asc

update_chroot
download_manifest manifest
source manifest/version.txt

[ -s verify.asc ] && verify_key=--verify-key=verify.asc || verify_key=

bin/arm64/cork download-image \
    --cache-dir=${chroot}/downloads \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --platform=qemu_uefi \
    --root="${DOWNLOAD_ROOT}/boards/${BOARD}/${COREOS_VERSION}" \
    --verify=true $verify_key

bunzip2 --force --keep ${chroot}/downloads/coreos_production_image.bin.bz2

# copy all of the latest mantle binaries into the chroot
sudo mkdir -p ${chroot}/usr/lib/kola/{amd64,arm64}
sudo cp -v -t ${chroot}/usr/lib/kola/arm64 bin/arm64/*
sudo cp -v -t ${chroot}/usr/lib/kola/amd64 bin/amd64/*
sudo ln -sf /usr/lib/kola/arm64/kola ${chroot}/bin/kola

if [[ "${KOLA_DEBUG}" == "true" ]]; then
    kola_debug="--debug"
fi

enter_chroot timeout --signal=SIGQUIT 60m kola run \
    ${kola_debug} \
    --board="${BOARD}" \
    --parallel=2 \
    --platform=qemu \
    --qemu-bios=/downloads/coreos_production_qemu_uefi_efi_code.fd \
    --qemu-image=/downloads/coreos_production_image.bin \
    --tapfile=/${tapfile}

    cp ${chroot}/${tapfile} .

if [[ "${KOLA_DEBUG}" == "true" ]]; then
    cat ${tapfile}
fi
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
              testResults: '*.tap',
              todoIsFailure: false,
              validateNumberOfTests: true,
              verbose: true])

        sh "tar -C ${chroot} -cJf _kola_temp.tar.xz _kola_temp"
        archiveArtifacts '_kola_temp.tar.xz'
    }
}

/* Propagate the job status after publishing TAP results.  */
currentBuild.result = rc == 0 ? 'SUCCESS' : 'FAILURE'
