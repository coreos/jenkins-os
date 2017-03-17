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

/* The group list mapping is keyed on ${COREOS_OFFICIAL}.  */
def group_map = ['0': ['developer'],
                 '1': ['alpha', 'beta', 'stable']]

/* Construct a matrix of build variation closures.  */
def matrix_map = [:]

/* Force this as an ArrayList for serializability, or Jenkins explodes.  */
ArrayList<String> format_list = format_map[params.BOARD].trim().split('\n')

for (group in group_map[params.COREOS_OFFICIAL]) {
    def GROUP = group  /* This MUST use fresh variables per iteration.  */

    for (format in format_list) {
        def FORMAT = format  /* This MUST use fresh variables per iteration.  */

        matrix_map["${GROUP}-${FORMAT}"] = {
            node('coreos && amd64 && sudo') {
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
                             "BOARD=${params.BOARD}",
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

                fingerprint "chroot/build/${params.BOARD}/var/lib/portage/pkgs/*/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2,tmp/*"
                dir('tmp') {
                    deleteDir()
                }
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
            string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
            string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
            string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
            string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
            string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
        ]
}
