#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
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
        string(name: 'GROUP',
               defaultValue: 'developer',
               description: 'Which release group owns this build'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        string(name: 'MANIFEST_TAG',
               defaultValue: ''),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
	    choice(name: 'OCI_SHAPE',
               choices: "VM.Standard1.1\nVM.Standard1.4\nVM.Standard1.8\nVM.Standard1.16",
               description: 'OCI shape to test'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '17942f6a-969b-466d-ac46-cd15925c8953',
                    description: 'Config required by "kola run --platform=oci"',
                    name: 'OCI_TEST_CONFIG',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'f7121197-c5b2-4e67-8529-ff59224bef91',
                    description: 'RSA Key in PEM format referenced by config',
                    name: 'OCI_TEST_KEY',
                    required: true),
        text(name: 'TORCX_MANIFEST',
             defaultValue: '',
             description: 'Contents of the torcx manifest for kola tests'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'VERSION',
               defaultValue: '',
               description: 'OS image version to use'),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

/* The kola step doesn't fail the job, so save the return code separately.  */
def rc = 0

node('coreos && amd64 && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'torcx_manifest.json', text: params.TORCX_MANIFEST ?: ''
        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        withCredentials([
            file(credentialsId: params.OCI_TEST_CONFIG, variable: 'OCI_TEST_CONFIG'),
            file(credentialsId: params.OCI_TEST_KEY, variable: 'OCI_TEST_KEY'),
            file(credentialsId: params.DOWNLOAD_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
            withEnv(["BOARD=amd64-usr",
                     "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}",
                     "GROUP=${params.GROUP}",
                     "MANIFEST_NAME=${params.MANIFEST_NAME}",
                     "MANIFEST_TAG=${params.MANIFEST_TAG}",
                     "MANIFEST_URL=${params.MANIFEST_URL}",
                     "OCI_SHAPE=${params.OCI_SHAPE}",
                     "VERSION=${params.VERSION}"]) {
                rc = sh returnStatus: true, script: '''#!/bin/bash -ex

# The build may not be started without a tag value.
[ -n "${MANIFEST_TAG}" ]

rm -rf *.tap src/scripts/_kola_temp _kola_temp*

# Set up GPG for verifying tags.
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap 'rm -rf "${GNUPGHOME}"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

bin/cork update \
    --create --downgrade-replace --verify --verify-signature --verbose \
    --force-sync \
    --manifest-branch "refs/tags/${MANIFEST_TAG}" \
    --manifest-name "${MANIFEST_NAME}" \
    --manifest-url "${MANIFEST_URL}"

[ -s verify.asc ] && verify_key=--verify-key=verify.asc || verify_key=

enter() {
    bin/cork enter --bind-gpg-agent=false -- "$@"
}

mkdir -p src
bin/cork download-image \
    --root="${DOWNLOAD_ROOT}/boards/${BOARD}/${VERSION}" \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --cache-dir=./src \
    --platform=oci \
    --verify=true $verify_key
img=src/coreos_production_oracle_oci_qcow_image.img
[[ "${img}.bz2" -nt "${img}" ]] && enter lbunzip2 -k -f "/mnt/host/source/${img}.bz2"

rm -rf .oci
mkdir --mode=0700 .oci
mv ${OCI_TEST_CONFIG} .oci/config
mv ${OCI_TEST_KEY} .oci/oci_api_key.pem
touch .oci/config.mantle
chmod 0600 .oci/*

trap 'rm -rf .oci' EXIT;
compartment=$(awk -F "=" '/compartment/ {print $2}' .oci/config)
bucket=image-upload
region=us-phoenix-1
object="Container-Linux-${GROUP}-${VERSION}.qcow"
NAME="jenkins-${JOB_NAME##*/}-${BUILD_NUMBER}"

sudo cp -t chroot/usr/lib/kola/amd64 bin/amd64/*
sudo cp -t chroot/usr/bin bin/[b-z]*

# Run the script through the SDK to give access to python for the
# oci-cli which is used to import the image and to provide a stable
# location for the configuration files (the .oci/config must point
# to the .oci/oci_api_key.pem files location and cannot be configured
# via parameters).
bin/cork enter --bind-gpg-agent=false -- region=$region bucket=$bucket object=$object compartment=$compartment NAME=$NAME OCI_SHAPE=$OCI_SHAPE sh -ex << 'EOF'
pyvenv ocienv && ocienv/bin/pip install oci-cli --upgrade;
ln -fns /mnt/host/source/.oci ~/.oci
export LC_ALL=C.UTF-8;
export LANG=C.UTF-8;
namespace=$(ocienv/bin/oci os ns get | jq .data -r);
uri="https://objectstorage.${region}.oraclecloud.com/n/${namespace}/b/${bucket}/o/${object}";
ocienv/bin/oci os object put \
     --namespace "${namespace}" \
     --bucket-name "${bucket}" \
     --file "/mnt/host/source/src/coreos_production_oracle_oci_qcow_image.img" \
     --name "${object}";
trap 'ocienv/bin/oci os object delete \
     --namespace "${namespace}" \
     --bucket-name "${bucket}" \
     --name "${object}" \
     --force' EXIT;
ocienv/bin/oci compute image import from-object-uri \
     --uri "${uri}" \
     --compartment-id "${compartment}" | jq -r .data.id > /mnt/host/source/src/image_id;
trap 'ocienv/bin/oci compute image delete --image-id "${image_id}" --force' EXIT
image_id=$(cat /mnt/host/source/src/image_id);
for ((i=0; i<60; i++ ))
do
    sleep 1m
    state=$(ocienv/bin/oci compute image get --image-id "${image_id}" | jq -r '.data[ "lifecycle-state" ]');
    if [ "$state" == "AVAILABLE" ]; then
        break;
    fi
done

ocienv/bin/oci os object delete \
     --namespace "${namespace}" \
     --bucket-name "${bucket}" \
     --name "${object}" \
     --force

if [ "$state" != "AVAILABLE" ]; then
    # The image isn't available after 1 hour, exit
    exit 1
fi

timeout --signal=SIGQUIT 300m kola run \
    --parallel=1 \
    --basename="${NAME}" \
    --oci-image="${image_id}" \
    --oci-shape="${OCI_SHAPE}" \
    --platform=oci \
    --tapfile="/mnt/host/source/${JOB_NAME##*/}.tap" \
    --torcx-manifest=/mnt/host/source/torcx_manifest.json
EOF
'''  /* Editor quote safety: ' */

                message = sh returnStdout: true, script: '''jq '.tests[] | select(.result == "FAIL") | .name' -r < src/scripts/_kola_temp/oci-latest/reports/report.json | sed -e :a -e '$!N; s/\\n/, /; ta' '''
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

        sh 'tar -C src/scripts -cJf _kola_temp.tar.xz _kola_temp'
        archiveArtifacts '_kola_temp.tar.xz'
    }
}

/* Propagate the job status after publishing TAP results.  */
currentBuild.result = rc == 0 ? 'SUCCESS' : 'FAILURE'

if (currentBuild.result == 'FAILURE')
    slackSend color: 'danger',
              message: "```Kola: OCI-amd64 Failure: <${BUILD_URL}console|Console> - <${BUILD_URL}artifact/_kola_temp.tar.xz|_kola_temp>\n$message```"
