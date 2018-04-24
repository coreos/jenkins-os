#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        choice(name: 'BOARD',
               choices: "amd64-usr\narm64-usr",
               description: 'Target board to test'),
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
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'VERSION',
               defaultValue: '',
               description: 'OS container version to test'),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

node('amd64 && coreos && sudo') {
    stage('Test') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        withCredentials([
            file(credentialsId: params.DOWNLOAD_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
        ]) {
            withEnv(["BOARD=${params.BOARD}",
                     "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}",
                     "VERSION=${params.VERSION}"]) {
                sh '''#!/bin/bash -ex

sudo rm -f coreos_developer_container.bin*
trap 'sudo rm -f coreos_developer_container.bin*' EXIT

[ -s verify.asc ] && verify_key=--verify-key=verify.asc || verify_key=

bin/gangue get \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --verify=true $verify_key \
    "${DOWNLOAD_ROOT}/boards/${BOARD}/${VERSION}/coreos_developer_container.bin.bz2"
bunzip2 coreos_developer_container.bin.bz2

sudo systemd-nspawn \
    --bind=/lib/modules \
    --image=coreos_developer_container.bin \
    /bin/bash -eux << 'EOF'
emerge-gitclone
. /usr/share/coreos/release
if [[ $COREOS_RELEASE_VERSION =~ master ]]
then
        git -C /var/lib/portage/portage-stable checkout master
        git -C /var/lib/portage/coreos-overlay checkout master
fi
emerge -gv coreos-sources
PKGDIR=/tmp PORTAGE_TMPDIR=/tmp ROOT=/tmp emerge -gOv coreos-modules
cp -f /tmp/usr/boot/config /usr/src/linux/.config
exec make -C /usr/src/linux -j"$(nproc)" modules_prepare V=1
EOF
'''  /* Editor quote safety: ' */
            }
        }
    }
}
