#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        choice(name: 'CHANNEL',
               choices: "alpha",
               description: 'Which release channel to use'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '9b77e4af-a9cb-47c8-8952-f375f0b48596',
                    description: '''Credentials ID for a JSON file passed as \
the GOOGLE_APPLICATION_CREDENTIALS value for downloading release files from \
the Google Storage URL, requires read permission''',
                    name: 'DOWNLOAD_CREDS',
                    required: true),
        string(name: 'DOWNLOAD_ROOT',
                defaultValue: 'gs://builds.release.core-os.net',
                description: 'URL prefix where image files are downloaded'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '2103e2c7-4847-4686-981f-44520878cdd1',
                    description: 'Configuration file with credentials to perform releases',
                    name: 'OCI_RELEASE_CONFIG',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'fcf6674f-612c-4daf-adc6-b4944757a18e',
                    description: 'RSA Key in PEM format referenced by config',
                    name: 'OCI_RELEASE_KEY',
                    required: true),
        string(name: 'VERSION',
               defaultValue: '',
               description: 'OS image version to use'),
    ])
])

node('coreos && amd64 && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        copyArtifacts filter: 'keyring.asc', fingerprintArtifacts: true, projectName: '/os/keyring', selector: lastSuccessful()

        String keyring = readFile 'keyring.asc'
        writeFile file: 'verify.asc', text: keyring ?: ''

        withCredentials([
            file(credentialsId: params.OCI_RELEASE_CONFIG, variable: 'OCI_RELEASE_CONFIG'),
            file(credentialsId: params.OCI_RELEASE_KEY, variable: 'OCI_RELEASE_KEY'),
            file(credentialsId: params.DOWNLOAD_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
            withEnv(["BOARD=amd64-usr",
                     "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}/${params.CHANNEL}",
                     "CHANNEL=${params.CHANNEL}",
                     "VERSION=${params.VERSION}"]) {
                sh '''#!/bin/bash -ex
# Set up GPG for verifying tags.
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap 'rm -rf "${GNUPGHOME}"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

bin/cork update \
    --create --downgrade-replace --verify --verify-signature --verbose \
    --force-sync \
    --manifest-branch "refs/tags/v${VERSION}" \
    --manifest-name "release.xml" \
    --manifest-url "https://github.com/coreos/manifest-builds.git"

[ -s verify.asc ] && verify_key=--verify-key=verify.asc || verify_key=

enter() {
    bin/cork enter --experimental -- "$@"
}

rm -rf ~/.oci
mkdir  --mode=0700 ~/.oci
mv ${OCI_RELEASE_CONFIG} ~/.oci/config
mv ${OCI_RELEASE_KEY} ~/.oci/oci_api_key.pem
chmod 0600 ~/.oci/*
trap 'rm -rf ~/.oci/' EXIT

mkdir -p src tmp
bin/cork download-image \
    --root="${DOWNLOAD_ROOT}/boards/${BOARD}/${VERSION}" \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --cache-dir=./src \
    --platform=oci \
    --verify=true $verify_key
img=src/coreos_production_oracle_oci_qcow_image.img
[[ "${img}.bz2" -nt "${img}" ]] && enter lbunzip2 -k -f "/mnt/host/source/${img}.bz2"

bucket=CoreOS_Drop
region=us-phoenix-1
object="Container-Linux-${CHANNEL}-${VERSION}.qcow"

rm -rf src/.oci
cp -r ~/.oci src/
trap 'rm -rf src/.oci ~/.oci/' EXIT;
bin/cork enter --experimental -- region=$region bucket=$bucket object=$object sh -ex << 'EOF'
rm -rf ocienv/;
pyvenv ocienv && ocienv/bin/pip install oci-cli;
cp -r /mnt/host/source/src/.oci ~/;
export LC_ALL=C.UTF-8;
export LANG=C.UTF-8;
trap 'rm -rf ocienv/ ~/.oci/' EXIT;
namespace=$(ocienv/bin/oci os ns get | jq .data -r);
ocienv/bin/oci os object put \
     --namespace "${namespace}" \
     --bucket-name "${bucket}" \
     --file "/mnt/host/source/src/coreos_production_oracle_oci_qcow_image.img" \
     --name "${object}";
EOF
'''  /* Editor quote safety: ' */
            }
        }
    }
}
