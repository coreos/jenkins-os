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
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

node('amd64 && coreos && sudo') {
    stage('Test') {
        copyArtifacts fingerprintArtifacts: true,
                      projectName: '/mantle/master-builder',
                      selector: lastSuccessful()

        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        withCredentials([
            file(credentialsId: params.DOWNLOAD_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
        ]) {
            withEnv(['BOARD=amd64-usr',
                     "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}",
                     "MANIFEST_NAME=${params.MANIFEST_NAME}",
                     "MANIFEST_TAG=${params.MANIFEST_TAG}",
                     "MANIFEST_URL=${params.MANIFEST_URL}"]) {
                sh '''#!/bin/bash -ex

sudo rm -f coreos_developer_container.bin* gnupg manifest portage
trap 'sudo rm -f coreos_developer_container.bin* gnupg manifest portage' EXIT

verify_key=
if [ -s verify.asc ]
then
        verify_key=--verify-key=verify.asc
        export GNUPGHOME="${PWD}/gnupg"
        gpg2 --import verify.asc
fi

git clone --branch="${MANIFEST_TAG}" --depth=1 "${MANIFEST_URL}" manifest
git tag --verify "${MANIFEST_TAG}"
VERSION=$(sed -n 's/^COREOS_VERSION=//p' manifest/version.txt)

for repo in coreos/portage-stable coreos/coreos-overlay
do
        commit=$(sed -n "\\, name=\\"${repo}\\","'s/.* revision="\\([^"]*\\)".*/\\1/p' "manifest/${MANIFEST_NAME}")
        upstream=$(sed -n "\\, name=\\"${repo}\\","'s/.* upstream="\\([^"]*\\)".*/\\1/p' "manifest/${MANIFEST_NAME}")
        git clone "${MANIFEST_URL%/*/*}/${repo}.git" "portage/${repo##*/}"  # Assume default remote is "..".
        git -C "portage/${repo##*/}" fetch origin "${upstream}"
        git -C "portage/${repo##*/}" checkout "${commit}"
done

bin/gangue get \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --verify=true $verify_key \
    "${DOWNLOAD_ROOT}/boards/${BOARD}/${VERSION}/coreos_production_image_kernel_config.txt"

bin/gangue get \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --verify=true $verify_key \
    "${DOWNLOAD_ROOT}/boards/${BOARD}/${VERSION}/coreos_developer_container.bin.bz2"
bunzip2 coreos_developer_container.bin.bz2

sudo systemd-nspawn \
    --bind="$PWD/portage:/var/lib/portage" \
    --bind-ro=/lib/modules \
    --bind-ro="$PWD/coreos_production_image_kernel_config.txt:/boot/config" \
    --image=coreos_developer_container.bin \
    --machine=coreos-developer-container-$(uuidgen) \
    --tmpfs=/usr/src \
    --tmpfs=/var/tmp \
    /bin/bash -eux << 'EOF'
emerge -gv coreos-sources
ln -fns /boot/config /usr/src/linux/.config
exec make -C /usr/src/linux -j"$(nproc)" modules_prepare V=1
EOF
'''  /* Editor quote safety: ' */
            }
        }
    }
}
