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
    for (group in group_list(params.COREOS_OFFICIAL)) {
        for (format in format_list(board)) {
            def BOARD = board  /* This MUST use fresh variables per iteration.  */
            def FORMAT = format
            def GROUP = group
            matrix_map["${GROUP}-${BOARD}-${FORMAT}"] = {
                node('coreos && sudo') {
                    ws("${env.WORKSPACE}/executor${env.EXECUTOR_NUMBER}") {
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
                                     "BOARD=${BOARD}",
                                     "FORMAT=${FORMAT}",
                                     "GROUP=${GROUP}"]) {
                                sh '''#!/bin/bash -ex

rm -f gce.properties
sudo rm -rf tmp

# build may not be started without a ref value
[[ -n "${MANIFEST_REF#refs/tags/}" ]]

./bin/cork update --create --downgrade-replace --verify --verbose \
                  --manifest-url "${MANIFEST_URL}" \
                  --manifest-branch "${MANIFEST_REF}" \
                  --manifest-name "${MANIFEST_NAME}"

if [[ -x ./src/scripts/build_jobs/05_vm.sh ]]; then
  exec ./src/scripts/build_jobs/05_vm.sh
fi

# check that the matrix didn't go bananas
if [[ "${COREOS_OFFICIAL}" -eq 1 ]]; then
  [[ "${GROUP}" != developer ]]
else
  [[ "${GROUP}" == developer ]]
fi

script() {
  local script="/mnt/host/source/src/scripts/${1}"; shift
  ./bin/cork enter --experimental -- "${script}" "$@"
}

enter() {
  ./bin/cork enter --experimental -- "$@"
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

if [[ "${GROUP}" == developer ]]; then
  root="gs://builds.developer.core-os.net"
  dlroot=""
else
  root="gs://builds.release.core-os.net/${GROUP}"
  dlroot="--download_root https://${GROUP}.release.core-os.net"
fi

mkdir -p src tmp
./bin/cork download-image --root="${root}/boards/${BOARD}/${COREOS_VERSION}" \
                          --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
                          --cache-dir=./src \
                          --platform=qemu
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
                      --sign=buildbot@coreos.com \
                      --sign_digests=buildbot@coreos.com \
                      --upload_root="${root}" \
                      --upload ${dlroot}
'''  /* Editor quote safety: ' */
                            }
                        }

                        fingerprint "chroot/build/${BOARD}/var/lib/portage/pkgs/*/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2,tmp/*"
                    }
                }
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
    build job: '../kola/gce', parameters: [
        string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
        string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
        string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
        string(name: 'MANIFEST_URL', value: params.MANIFEST_URL)
    ]
}

/* The VM image format map is keyed on ${BOARD}.  */
def format_list(board) {
    ['amd64-usr': '''
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
'''][board].trim().split('\n')
}

/* The group list map is keyed on ${COREOS_OFFICIAL}.  */
def group_list(official) {
    ['0': ['developer'],
     '1': ['alpha', 'beta', 'stable']][official]
}
