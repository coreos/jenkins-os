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
        string(name: 'BUILDS_CLONE_CREDS',
               defaultValue: '',
               description: 'Credential ID for SSH Git clone URLs'),
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

/* The VM format list mapping is keyed on ${BOARD}.  */
def format_map = ['amd64-usr': '''
qemu
qemu_uefi
ami
ami_vmdk
pxe
iso
openstack
openstack_mini
parallels
rackspace
rackspace_onmetal
rackspace_vhd
vagrant
vagrant_parallels
vagrant_vmware_fusion
virtualbox
vmware
vmware_ova
vmware_raw
vmware_insecure
xen
gce
brightbox
cloudstack
cloudstack_vhd
digitalocean
exoscale
azure
hyperv
niftycloud
cloudsigma
packet
''',
                  'arm64-usr': '''
qemu_uefi
pxe
openstack
openstack_mini
packet
''']

/* Construct a matrix of build variation closures.  */
def matrix_map = [:]

/* Force this as an ArrayList for serializability, or Jenkins explodes.  */
ArrayList<String> format_list = format_map[params.BOARD].trim().split('\n')

for (format in format_list) {
    def FORMAT = format  /* This MUST use fresh variables per iteration.  */

    matrix_map[FORMAT] = {
        node('coreos && amd64 && sudo') {
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
                     credentialsId: params.GS_RELEASE_CREDS,
                     variable: 'GOOGLE_APPLICATION_CREDENTIALS']
                ]) {
                    withEnv(["COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                             "MANIFEST_NAME=${params.MANIFEST_NAME}",
                             "MANIFEST_REF=${params.MANIFEST_REF}",
                             "MANIFEST_URL=${params.MANIFEST_URL}",
                             "BOARD=${params.BOARD}",
                             "FORMAT=${FORMAT}",
                             "DOWNLOAD_ROOT=${params.GS_RELEASE_DOWNLOAD_ROOT}",
                             "GS_DEVEL_ROOT=${params.GS_DEVEL_ROOT}",
                             "SIGNING_USER=${params.SIGNING_USER}",
                             "UPLOAD_ROOT=${params.GS_RELEASE_ROOT}"]) {
                        sh '''#!/bin/bash -ex

rm -f gce.properties
sudo rm -rf tmp

# build may not be started without a ref value
[[ -n "${MANIFEST_REF#refs/tags/}" ]]

./bin/cork update --create --downgrade-replace --verify --verbose \
                  --manifest-url "${MANIFEST_URL}" \
                  --manifest-branch "${MANIFEST_REF}" \
                  --manifest-name "${MANIFEST_NAME}"

enter() {
  sudo ln -f "${GS_DEVEL_CREDS}" chroot/etc/portage/gangue.json
  [ -s verify.gpg.pub ] &&
  sudo ln -f verify.gpg.pub chroot/etc/portage/gangue.gpg.pub &&
  verify_key=--verify-key=/etc/portage/gangue.gpg.pub
  trap 'sudo rm -f chroot/etc/portage/gangue.*' RETURN
  ./bin/cork enter --experimental -- env \
    COREOS_DEV_BUILDS="${GS_DEVEL_ROOT}" \
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

source .repo/manifests/version.txt
export COREOS_BUILD_ID

# Set up GPG for signing images
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap "rm -rf '${GNUPGHOME}'" EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import "${GPG_SECRET_KEY_FILE}"

[ -s verify.gpg.pub ] && verify_key=--verify-key=verify.gpg.pub || verify_key=

mkdir -p src tmp
./bin/cork download-image --root="${UPLOAD_ROOT}/boards/${BOARD}/${COREOS_VERSION}" \
                          --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
                          --cache-dir=./src \
                          --platform=qemu \
                          --verify=true $verify_key
img=src/coreos_production_image.bin
if [[ "${img}.bz2" -nt "${img}" ]]; then
  enter lbunzip2 -k -f "/mnt/host/source/${img}.bz2"
fi

sudo rm -rf chroot/build
script image_to_vm.sh --board=${BOARD} \
                      --format=${FORMAT} \
                      --prod_image \
                      --getbinpkg \
                      --getbinpkgver=${COREOS_VERSION} \
                      --from=/mnt/host/source/src/ \
                      --to=/mnt/host/source/tmp/ \
                      --sign="${SIGNING_USER}" \
                      --sign_digests="${SIGNING_USER}" \
                      --download_root="${DOWNLOAD_ROOT}" \
                      --upload_root="${UPLOAD_ROOT}" \
                      --upload
'''  /* Editor quote safety: ' */
                    }
                }
            }

            fingerprint "chroot/build/${params.BOARD}/var/lib/portage/pkgs/*/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2,tmp/*"
            dir('tmp') {
                deleteDir()
            }
        }
    }
}

stage('Build') {
    if (true) {  /* Limit the parallel builds to avoid scheduling failures.  */
        def parallel_max = 2
        /* Make this ugly for serializability again.  */
        ArrayList<Closure> vm_builds = matrix_map.values()
        matrix_map = [:]
        for (int j = 0; j < parallel_max; j++) {
            def MOD = j  /* This MUST use fresh variables per iteration.  */
            matrix_map["vm_${MOD}"] = {
                for (int i = MOD; i < vm_builds.size(); i += parallel_max) {
                    vm_builds[i]()
                }
            }
        }
    }

    matrix_map.failFast = true
    parallel matrix_map
}

stage('Downstream') {
    if (params.BOARD == 'amd64-usr')
        build job: '../kola/gce', propagate: false, parameters: [
            string(name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS),
            string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
            string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
            string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
            string(name: 'GS_RELEASE_CREDS', value: params.GS_RELEASE_CREDS),
            string(name: 'GS_RELEASE_ROOT', value: params.GS_RELEASE_ROOT),
            string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
        ]
}
