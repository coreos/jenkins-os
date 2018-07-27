#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'MANIFEST_REF',
               defaultValue: 'master',
               description: 'Manifest branch or tag to build'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'default',
               description: 'Manifest file name (less .xml extension)'),
        string(name: 'PROFILE',
               defaultValue: 'default:developer',
               description: '''Which JSON build profile to load from a Groovy \
library resource named com/coreos/profiles/developer.json, for example.\n
This value takes a colon-separated list of names to try, using the first one \
that exists and can be parsed successfully.'''),
        string(name: 'RELEASE_BASE',
               defaultValue: '',
               description: '''When non-empty, the release version number \
given here will be used as a source of binary packages for this build.  The \
special value "master" can also be given to use the latest successful build \
of the manifest master branch.  This completely skips building the toolchains \
and SDK, and the package build job downloads binary packages from this \
version so only modified packages are built from source.  Be aware that no \
SDK will be produced by this build for future releases to use.  This option \
should not be used for release builds unless a critical security fix needs to \
be released quickly.'''),
        text(name: 'LOCAL_MANIFEST',
             defaultValue: '',
             description: """Amend the checked in manifest\n
https://undocumented.software/wiki_dump/Doc%3A_Using_manifests%2Cen.html#The_local_manifest"""),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

/* Try to work on instances whether they have trusted libraries or not.  */
Map loadProfileTrusted(String name) {
    def map = parseJson libraryResource("com/coreos/profiles/${name}.json")
    return map?.PARENT ? loadProfileTrusted(map.PARENT) + map : map
}
Map loadProfileDirect(String name) {
    def map = [:] + new groovy.json.JsonSlurper(
        type: groovy.json.JsonParserType.LAX
    ).parseText(libraryResource("com/coreos/profiles/${name}.json"))
    return map?.PARENT ? loadProfileDirect(map.PARENT) + map : map
}

/* Parse the first working build profile values from library resources.  */
def profile = [:]
ArrayList<String> search_list = params.PROFILE.trim().split(':')
for (profile_name in search_list) {
    try {
        try {
            profile = loadProfileTrusted(profile_name)
            break
        } catch (NoSuchMethodError err) {
            echo 'Failed to use a trusted library to parse JSON...'
            echo "Attempting direct parsing and hoping the sandbox won't quit."
            profile = loadProfileDirect(profile_name)
            break
        }
    } catch (hudson.AbortException err) {
        echo "Could not load the ${profile_name} profile..."
    }
}

/* Sanity check that profile values were loaded.  */
if (!profile.BUILDS_PUSH_URL)
    throw new Exception('A build profile was not loaded')

def dprops = [:]  /* Store properties read from an artifact later.  */
def keyring = ''
def releaseBase = params.RELEASE_BASE

node('coreos && amd64 && sudo') {
    stage('SCM') {
        checkout scm: [
            $class: 'GitSCM',
            branches: [[name: params.MANIFEST_REF]],
            extensions: [[$class: 'RelativeTargetDirectory',
                          relativeTargetDir: 'manifest'],
                         [$class: 'CleanBeforeCheckout']],
            userRemoteConfigs: [[url: profile.MANIFEST_URL, name: 'origin', credentialsId: profile.BUILDS_CLONE_CREDS]]
        ]
    }

    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'TriggeredBuildSelector',
                         allowUpstreamDependencies: true,
                         fallbackToLastSuccessful: true,
                         upstreamFilterStrategy: 'UseGlobalSetting']])

        if (profile.VERIFY_KEYRING.startsWith("artifact:")) {
            ArrayList<String> keyringSpec = profile.VERIFY_KEYRING.split(':')
            step([$class: 'CopyArtifact',
                  fingerprintArtifacts: true,
                  projectName: keyringSpec[1],
                  selector: [$class: 'TriggeredBuildSelector',
                             allowUpstreamDependencies: true,
                             fallbackToLastSuccessful: true,
                             upstreamFilterStrategy: 'UseGlobalSetting']])
            keyring = readFile(keyringSpec[2] ?: 'keyring.asc')
        } else {
            keyring = profile.VERIFY_KEYRING
        }

        writeFile file: 'verify.asc', text: keyring

        sshagent([profile.BUILDS_PUSH_CREDS]) {
            withCredentials([
                file(credentialsId: profile.SIGNING_CREDS, variable: 'GPG_SECRET_KEY_FILE'),
            ]) {
                /* Work around JENKINS-35230 (broken GIT_* variables).  */
                withEnv(["BUILD_ID_PREFIX=${profile.BUILD_ID_PREFIX}",
                         "BUILDS_CLONE_URL=${profile.BUILDS_CLONE_URL}",
                         "BUILDS_PUSH_URL=${profile.BUILDS_PUSH_URL}",
                         "GIT_AUTHOR_EMAIL=${profile.GIT_AUTHOR_EMAIL}",
                         "GIT_AUTHOR_NAME=${profile.GIT_AUTHOR_NAME}",
                         "GIT_BRANCH=${sh(returnStdout: true, script: "git -C manifest tag -l ${params.MANIFEST_REF}").trim() ? params.MANIFEST_REF : "origin/${params.MANIFEST_REF}"}",
                         "GIT_COMMIT=${sh(returnStdout: true, script: 'git -C manifest rev-parse HEAD').trim()}",
                         "GIT_URL=${sh(returnStdout: true, script: 'git -C manifest remote get-url origin').trim()}",
                         "LOCAL_MANIFEST=${params.LOCAL_MANIFEST}",
                         "SIGNING_USER=${profile.SIGNING_USER}"]) {
                    sh '''#!/bin/bash -ex

export GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=no"
git -C manifest config user.name "${GIT_AUTHOR_NAME}"
git -C manifest config user.email "${GIT_AUTHOR_EMAIL}"

COREOS_OFFICIAL=0

finish() {
        local tag="$1"
        git -C manifest tag -v "${tag}"
        git -C manifest push "${BUILDS_PUSH_URL}" "refs/tags/${tag}:refs/tags/${tag}"
        tee manifest.properties << EOF
MANIFEST_URL = ${BUILDS_CLONE_URL}
MANIFEST_REF = refs/tags/${tag}
MANIFEST_NAME = release.xml
COREOS_OFFICIAL = ${COREOS_OFFICIAL:-0}
EOF
}

# Set up GPG for verifying tags.
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap 'rm -rf "${GNUPGHOME}"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

# Branches are of the form remote-name/branch-name.  Tags are just tag-name.
# If we have a release tag use it, for branches we need to make a tag.
if [[ "${GIT_BRANCH}" != */* ]]
then
        COREOS_OFFICIAL=1
        finish "${GIT_BRANCH}"
        exit
fi

MANIFEST_BRANCH="${GIT_BRANCH##*/}"
MANIFEST_NAME="${MANIFEST_NAME}.xml"
[[ -f "manifest/${MANIFEST_NAME}" ]]

source manifest/version.txt
export COREOS_BUILD_ID="${BUILD_ID_PREFIX}${MANIFEST_BRANCH}-${BUILD_NUMBER}"

# Get repo to set things up using the manifest repository we already have.
mkdir -p .repo
ln -sfT ../manifest .repo/manifests
ln -sfT ../manifest/.git .repo/manifests.git

# Cleanup/setup local manifests.
rm -rf .repo/local_manifests
if [[ -n "${LOCAL_MANIFEST}" ]]
then
        mkdir -p .repo/local_manifests
        echo "${LOCAL_MANIFEST}" > .repo/local_manifests/local.xml
        COREOS_BUILD_ID="${BUILD_ID_PREFIX}${MANIFEST_BRANCH}+local-${BUILD_NUMBER}"
fi

# Initialize an SDK without verifying a manifest tag, since this uses a branch.
bin/cork update \
    --create --downgrade-replace --verbose --force-sync \
    --manifest-branch "${GIT_COMMIT}" \
    --manifest-name "${MANIFEST_NAME}" \
    --manifest-url "${GIT_URL}" \
    --new-version "${COREOS_VERSION}" \
    --sdk-version "${COREOS_SDK_VERSION}"

bin/cork enter --bind-gpg-agent=false -- sh -exc \
    "cd /mnt/host/source && repo manifest -r > 'manifest/${COREOS_BUILD_ID}.xml'"

ln -fns "${COREOS_BUILD_ID}.xml" manifest/default.xml
ln -fns "${COREOS_BUILD_ID}.xml" manifest/release.xml

tee manifest/version.txt << EOF
COREOS_VERSION=${COREOS_VERSION_ID}+${COREOS_BUILD_ID}
COREOS_VERSION_ID=${COREOS_VERSION_ID}
COREOS_BUILD_ID=${COREOS_BUILD_ID}
COREOS_SDK_VERSION=${COREOS_SDK_VERSION}
EOF

# Set up GPG for signing tags.
gpg --import "${GPG_SECRET_KEY_FILE}"

# Tag a development build manifest.
git -C manifest add "${COREOS_BUILD_ID}.xml" default.xml release.xml version.txt
git -C manifest commit \
    -m "${COREOS_BUILD_ID}: add build manifest" \
    -m "Based on ${GIT_URL} branch ${MANIFEST_BRANCH}" \
    -m "${BUILD_URL}"
git -C manifest tag -u "${SIGNING_USER}" -m "${COREOS_BUILD_ID}" "${COREOS_BUILD_ID}"

# Assert that what we just did will work by updating the symlink because verify
# doesn't have a --manifest-name option yet.
ln -fns "manifests/${COREOS_BUILD_ID}.xml" .repo/manifest.xml
bin/cork verify

finish "${COREOS_BUILD_ID}"
'''  /* Editor quote safety: ' */
                }
            }
        }

        /* Dereference a magic word since GCS can't handle symlinks.  */
        if (releaseBase == 'master') {
            withCredentials([
                file(credentialsId: profile.GS_DEVEL_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
            ]) {
                withEnv(["DEVEL_ROOT=${profile.GS_DEVEL_ROOT}"]) {
                    sh '''#!/bin/bash -ex
bin/cork enter --bind-gpg-agent=false -- \
    gsutil cat "${DEVEL_ROOT}/boards/amd64-usr/current-master/version.txt" |
tee /dev/stderr |
grep -m1 ^COREOS_VERSION= > current.txt
'''  /* Editor quote safety: ' */
                }
            }
            releaseBase = readFile('current.txt').trim()[15..-1]
        }
    }

    stage('Post-build') {
        archiveArtifacts 'manifest.properties,manifest/version.txt'

        for (line in readFile('manifest.properties').trim().split("\n")) {
            def tokens = line.tokenize(" =")
            dprops[tokens[0]] = tokens[1]
        }

        /* Show a summary report for official releases.  */
        if (dprops.COREOS_OFFICIAL == '1') {
            withEnv(["MANIFEST_NAME=${dprops.MANIFEST_NAME}",
                     "MANIFEST_REF=${dprops.MANIFEST_REF.split('/')[-1]}",
                     "MANIFEST_URL=${profile.MANIFEST_URL}"]) {
                sh '''#!/bin/bash -ex
rm -f message.txt
repos=( coreos/coreos-overlay coreos/portage-stable coreos/scripts )

declare -A new=()
git -C manifest checkout "${MANIFEST_REF}"
for repo in "${repos[@]}"
do
        new[${repo}]=$(sed -n 's,.* name="'${repo}'".* revision="\\([^"]*\\)".*,\\1,p' "manifest/${MANIFEST_NAME}")
done

declare -A old=()
git -C manifest checkout HEAD^
for repo in "${repos[@]}"
do
        old[${repo}]=$(sed -n 's,.* name="'${repo}'".* revision="\\([^"]*\\)".*,\\1,p' "manifest/${MANIFEST_NAME}")
done

echo "${MANIFEST_REF#v} - ${BUILD_URL}cldsv" > message.txt
echo "${MANIFEST_URL%.git}/commit/$(git -C manifest rev-list --max-count=1 "${MANIFEST_REF}" | head -c 10)" >> message.txt
for repo in "${repos[@]}"
do
        [ -z "${new[${repo}]}" -o "x${new[${repo}]}" == "x${old[${repo}]}" ] ||
        echo "https://github.com/${repo}/compare/${old[${repo}]:0:10}...${new[${repo}]:0:10}" >> message.txt
done
'''  /* Editor quote safety: ' */
            }

            String summary = readFile('message.txt').trim()
            try {
                slackSend color: '#2020C0', message: summary
            } catch (NoSuchMethodError err) {
                echo summary
            }
        }
    }
}

stage('Downstream') {
    if (releaseBase) {
        def genBuildPackages = { boardToBuild, minutesToWait ->
            def board = boardToBuild    /* Create a closure with new variables.  */
            def minutes = minutesToWait /* Cute curried closures have bad refs.  */
            if (dprops.COREOS_OFFICIAL == '1' && board == 'arm64-usr')
                return {
                    echo 'The arm64 artifacts are no longer built for releases.'
                }
            return {
                sleep time: minutes, unit: 'MINUTES'
                build job: 'board/packages-matrix', parameters: [
                    string(name: 'AWS_REGION', value: profile.AWS_REGION),
                    credentials(name: 'AWS_RELEASE_CREDS', value: profile.AWS_RELEASE_CREDS),
                    credentials(name: 'AWS_TEST_CREDS', value: profile.AWS_TEST_CREDS),
                    credentials(name: 'AZURE_CREDS', value: profile.AZURE_CREDS),
                    string(name: 'BOARD', value: board),
                    credentials(name: 'BUILDS_CLONE_CREDS', value: profile.BUILDS_CLONE_CREDS ?: ''),
                    string(name: 'COREOS_OFFICIAL', value: dprops.COREOS_OFFICIAL),
                    credentials(name: 'DIGITALOCEAN_CREDS', value: profile.DIGITALOCEAN_CREDS),
                    string(name: 'GROUP', value: profile.GROUP),
                    credentials(name: 'GS_DEVEL_CREDS', value: profile.GS_DEVEL_CREDS),
                    string(name: 'GS_DEVEL_ROOT', value: profile.GS_DEVEL_ROOT),
                    credentials(name: 'GS_RELEASE_CREDS', value: profile.GS_RELEASE_CREDS),
                    string(name: 'GS_RELEASE_DOWNLOAD_ROOT', value: profile.GS_RELEASE_DOWNLOAD_ROOT),
                    string(name: 'GS_RELEASE_ROOT', value: profile.GS_RELEASE_ROOT),
                    string(name: 'MANIFEST_NAME', value: dprops.MANIFEST_NAME),
                    string(name: 'MANIFEST_TAG', value: dprops.MANIFEST_REF.substring(10)),
                    string(name: 'MANIFEST_URL', value: dprops.MANIFEST_URL),
                    credentials(name: 'PACKET_CREDS', value: profile.PACKET_CREDS),
                    string(name: 'PACKET_PROJECT', value: profile.PACKET_PROJECT),
                    string(name: 'RELEASE_BASE', value: releaseBase),
                    credentials(name: 'SIGNING_CREDS', value: profile.SIGNING_CREDS),
                    string(name: 'SIGNING_USER', value: profile.SIGNING_USER),
                    string(name: 'TORCX_PUBLIC_DOWNLOAD_ROOT', value: profile.TORCX_PUBLIC_DOWNLOAD_ROOT),
                    string(name: 'TORCX_ROOT', value: profile.TORCX_ROOT),
                    text(name: 'VERIFY_KEYRING', value: keyring),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
            }
        }
        parallel failFast: false,
            'board-packages-matrix-amd64-usr': genBuildPackages('amd64-usr', 0),
            'board-packages-matrix-arm64-usr': genBuildPackages('arm64-usr', 1)
    } else
        parallel failFast: false,
            sdk: {
                build job: 'sdk', parameters: [
                    credentials(name: 'BUILDS_CLONE_CREDS', value: profile.BUILDS_CLONE_CREDS ?: ''),
                    string(name: 'COREOS_OFFICIAL', value: dprops.COREOS_OFFICIAL),
                    credentials(name: 'GS_DEVEL_CREDS', value: profile.GS_DEVEL_CREDS),
                    string(name: 'GS_DEVEL_ROOT', value: profile.GS_DEVEL_ROOT),
                    string(name: 'MANIFEST_NAME', value: dprops.MANIFEST_NAME),
                    string(name: 'MANIFEST_TAG', value: dprops.MANIFEST_REF.substring(10)),
                    string(name: 'MANIFEST_URL', value: dprops.MANIFEST_URL),
                    credentials(name: 'SIGNING_CREDS', value: profile.SIGNING_CREDS),
                    string(name: 'SIGNING_USER', value: profile.SIGNING_USER),
                    text(name: 'VERIFY_KEYRING', value: keyring),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
            },
            toolchains: {
                build job: 'toolchains', parameters: [
                    string(name: 'AWS_REGION', value: profile.AWS_REGION),
                    credentials(name: 'AWS_RELEASE_CREDS', value: profile.AWS_RELEASE_CREDS),
                    credentials(name: 'AWS_TEST_CREDS', value: profile.AWS_TEST_CREDS),
                    credentials(name: 'AZURE_CREDS', value: profile.AZURE_CREDS),
                    credentials(name: 'BUILDS_CLONE_CREDS', value: profile.BUILDS_CLONE_CREDS ?: ''),
                    string(name: 'COREOS_OFFICIAL', value: dprops.COREOS_OFFICIAL),
                    credentials(name: 'DIGITALOCEAN_CREDS', value: profile.DIGITALOCEAN_CREDS),
                    string(name: 'GROUP', value: profile.GROUP),
                    credentials(name: 'GS_DEVEL_CREDS', value: profile.GS_DEVEL_CREDS),
                    string(name: 'GS_DEVEL_ROOT', value: profile.GS_DEVEL_ROOT),
                    credentials(name: 'GS_RELEASE_CREDS', value: profile.GS_RELEASE_CREDS),
                    string(name: 'GS_RELEASE_DOWNLOAD_ROOT', value: profile.GS_RELEASE_DOWNLOAD_ROOT),
                    string(name: 'GS_RELEASE_ROOT', value: profile.GS_RELEASE_ROOT),
                    string(name: 'MANIFEST_NAME', value: dprops.MANIFEST_NAME),
                    string(name: 'MANIFEST_TAG', value: dprops.MANIFEST_REF.substring(10)),
                    string(name: 'MANIFEST_URL', value: dprops.MANIFEST_URL),
                    credentials(name: 'PACKET_CREDS', value: profile.PACKET_CREDS),
                    string(name: 'PACKET_PROJECT', value: profile.PACKET_PROJECT),
                    credentials(name: 'SIGNING_CREDS', value: profile.SIGNING_CREDS),
                    string(name: 'SIGNING_USER', value: profile.SIGNING_USER),
                    string(name: 'TORCX_PUBLIC_DOWNLOAD_ROOT', value: profile.TORCX_PUBLIC_DOWNLOAD_ROOT),
                    string(name: 'TORCX_ROOT', value: profile.TORCX_ROOT),
                    text(name: 'VERIFY_KEYRING', value: keyring),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
            }
}
