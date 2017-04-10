#!groovy

String fingerprints = params?.FINGERPRINTS ?: ''
String import_keyring = params?.IMPORT_KEYRING ?: ''

/* On the first initializing run, fetch the buildbot key by default.  */
if (params?.FINGERPRINTS == null)
    fingerprints = '04127D0BFABEC8871FFB2CCE50E0885593D2DCB4 # CoreOS buildbot'

node('coreos') {
    stage('Build') {
        writeFile file: 'fingerprints.txt', text: fingerprints.trim()
        writeFile file: 'import_keyring.txt', text: import_keyring.trim()

        sh '''#!/bin/bash -ex
export GNUPGHOME="${WORKSPACE:-${PWD}}/new_gpg_homedir"
rm -rf "${GNUPGHOME}" keyring.asc *.log

# Create a new temporary GPG home directory.
trap "rm -rf '${GNUPGHOME}'" EXIT
mkdir -pm 0700 "${GNUPGHOME}"
cat << EOF > "${GNUPGHOME}/dirmngr.conf"
keyserver hkp://keys.gnupg.net
log-file ${PWD}/dirmngr.log
EOF

# Run the GPG commands under an agent session for easier process cleaning.
gpg-agent --batch --homedir "${GNUPGHOME}" --daemon /bin/bash -ex << 'EOF'
trap "gpgconf --kill dirmngr" EXIT

# Import an initial keyring, if given.
if [ -s import_keyring.txt ]
then
        gpg2 --dearmor import_keyring.txt
        mv -f import_keyring.txt.gpg import_keyring.gpg
        gpg2 --import import_keyring.gpg
fi

# Receive public keys from any given key IDs.
mapfile -t < fingerprints.txt
if (IFS= ; [ -n "${MAPFILE[*]%%#*}" ])
then
        gpgconf --launch dirmngr
        gpg2 --recv-keys ${MAPFILE[*]%%#*}
fi

# Export the complete ASCII-armored keyring.
gpg2 --armor --export --output keyring.asc
EOF

# Ensure there is something in the artifact before returning successfully.
[ -s keyring.asc ]
'''  /* Editor quote safety: ' */
    }

    stage('Post-build') {
        archiveArtifacts artifacts: 'fingerprints.txt,import_keyring.txt,keyring.asc',
                         fingerprint: true,
                         onlyIfSuccessful: true
    }
}

/* Redefine parameters last, so the previous values are the next defaults.  */
properties([
    buildDiscarder(logRotator(numToKeepStr: '100')),

    parameters([
        text(name: 'FINGERPRINTS',
             defaultValue: fingerprints,
             description: '''Fingerprints of the public keys to receive, \
separated by white space.  Empty lines and characters after a # will be \
ignored.  The default value is the previously used value.'''),
        text(name: 'IMPORT_KEYRING',
             defaultValue: import_keyring,
             description: '''ASCII-armored keyring to be imported into the \
created keyring.  Use this to provide public keys that should not be fetched \
remotely.  The default value is the previously used value.'''),
    ]),

    pipelineTriggers([cron('H H * * 1')])  /* Run this on Mondays.  */
])
