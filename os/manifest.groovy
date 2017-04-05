#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'MANIFEST_REF',
               defaultValue: 'master',
               description: 'Manifest branch or tag to build'),
        string(name: 'PROFILE',
               defaultValue: 'default:developer',
               description: '''Which JSON build profile to load from a Groovy \
library resource named com/coreos/profiles/developer.json, for example.\n
This value takes a colon-separated list of names to try, using the first one \
that exists and can be parsed successfully.'''),
        text(name: 'LOCAL_MANIFEST',
             defaultValue: '',
             description: """Amend the checked in manifest\n
https://zifnab.net/~zifnab/wiki_dump/Doc%3A_Using_manifests%2Cen.html#The_local_manifest"""),
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

node('coreos && amd64 && sudo') {
    stage('SCM') {
        checkout scm: [
            $class: 'GitSCM',
            branches: [[name: params.MANIFEST_REF]],
            extensions: [[$class: 'RelativeTargetDirectory',
                          relativeTargetDir: 'manifest'],
                         [$class: 'CleanBeforeCheckout']],
            userRemoteConfigs: [[url: profile.MANIFEST_URL, name: 'origin']]
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

        sshagent([profile.BUILDS_PUSH_CREDS]) {
            withCredentials([
                [$class: 'FileBinding',
                 credentialsId: profile.SIGNING_CREDS,
                 variable: 'GPG_SECRET_KEY_FILE']
            ]) {
                /* Work around JENKINS-35230 (broken GIT_* variables).  */
                withEnv(["BUILD_ID_PREFIX=${profile.BUILD_ID_PREFIX}",
                         "BUILDS_CLONE_URL=${profile.BUILDS_CLONE_URL}",
                         "BUILDS_PUSH_URL=${profile.BUILDS_PUSH_URL}",
                         "GIT_AUTHOR_EMAIL=${profile.GIT_AUTHOR_EMAIL}",
                         "GIT_AUTHOR_NAME=${profile.GIT_AUTHOR_NAME}",
                         'GIT_BRANCH=' + (sh(returnStdout: true, script: "git -C manifest tag -l ${params.MANIFEST_REF}").trim() ? params.MANIFEST_REF : "origin/${params.MANIFEST_REF}"),
                         'GIT_COMMIT=' + sh(returnStdout: true, script: 'git -C manifest rev-parse HEAD').trim(),
                         'GIT_URL=' + sh(returnStdout: true, script: 'git -C manifest remote get-url origin').trim(),
                         "LOCAL_MANIFEST=${params.LOCAL_MANIFEST}",
                         "SIGNING_USER=${profile.SIGNING_USER}"]) {
                    sh '''#!/bin/bash -ex

export GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=no"

COREOS_OFFICIAL=0

finish() {
  local tag="$1"
  git -C "${WORKSPACE}/manifest" push \
    "${BUILDS_PUSH_URL}" \
    "refs/tags/${tag}:refs/tags/${tag}"
  tee "${WORKSPACE}/manifest.properties" <<EOF
MANIFEST_URL = ${BUILDS_CLONE_URL}
MANIFEST_REF = refs/tags/${tag}
MANIFEST_NAME = release.xml
COREOS_OFFICIAL = ${COREOS_OFFICIAL:-0}
EOF
}

# Branches are of the form remote-name/branch-name. Tags are just tag-name.
# If we have a release tag use it, for branches we need to make a tag.
if [[ "${GIT_BRANCH}" != */* ]]; then
  COREOS_OFFICIAL=1
  finish "${GIT_BRANCH}"
  exit
fi

MANIFEST_BRANCH="${GIT_BRANCH##*/}"
MANIFEST_NAME="${MANIFEST_BRANCH}.xml"
[[ -f "manifest/${MANIFEST_NAME}" ]]

source manifest/version.txt
export COREOS_BUILD_ID="${BUILD_ID_PREFIX}${MANIFEST_BRANCH}-${BUILD_NUMBER}"

# hack to get repo to set things up using the manifest repo we already have
# (amazing that it tolerates this considering it usually is so intolerant)
mkdir -p .repo
ln -sfT ../manifest .repo/manifests
ln -sfT ../manifest/.git .repo/manifests.git

# Cleanup/setup local manifests
rm -rf .repo/local_manifests
if [[ -n "${LOCAL_MANIFEST}" ]]; then
  mkdir -p .repo/local_manifests
  cat >.repo/local_manifests/local.xml <<<"${LOCAL_MANIFEST}"
fi

./bin/cork update --create --downgrade-replace --verbose \
                  --manifest-url "${GIT_URL}" \
                  --manifest-branch "${GIT_COMMIT}" \
                  --manifest-name "${MANIFEST_NAME}" \
                  --new-version "${COREOS_VERSION}" \
                  --sdk-version "${COREOS_SDK_VERSION}"

./bin/cork enter --experimental -- sh -c \
  "cd /mnt/host/source; repo manifest -r > '/mnt/host/source/manifest/${COREOS_BUILD_ID}.xml'"

cd manifest
git add "${COREOS_BUILD_ID}.xml"

ln -sf "${COREOS_BUILD_ID}.xml" default.xml
ln -sf "${COREOS_BUILD_ID}.xml" release.xml
git add default.xml release.xml

tee version.txt <<EOF
COREOS_VERSION=${COREOS_VERSION_ID}+${COREOS_BUILD_ID}
COREOS_VERSION_ID=${COREOS_VERSION_ID}
COREOS_BUILD_ID=${COREOS_BUILD_ID}
COREOS_SDK_VERSION=${COREOS_SDK_VERSION}
EOF
git add version.txt

# Set up GPG for signing tags
export GNUPGHOME="${PWD}/.gnupg"
sudo rm -rf "${GNUPGHOME}"
trap "sudo rm -rf '${GNUPGHOME}'" EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import "${GPG_SECRET_KEY_FILE}"

GIT_COMMITTER_EMAIL="${GIT_AUTHOR_EMAIL}"
GIT_COMMITTER_NAME="${GIT_AUTHOR_NAME}"
export GIT_COMMITTER_EMAIL GIT_COMMITTER_NAME
git commit \
  -m "${COREOS_BUILD_ID}: add build manifest" \
  -m "Based on ${GIT_URL} branch ${MANIFEST_BRANCH}" \
  -m "${BUILD_URL}"
git tag -u "${SIGNING_USER}" -m "${COREOS_BUILD_ID}" "${COREOS_BUILD_ID}" HEAD

# assert that what we just did will work, update symlink because verify doesn't have a --manifest-name option yet
cd "${WORKSPACE}"
ln -sf "manifests/${COREOS_BUILD_ID}.xml" .repo/manifest.xml
./bin/cork verify

finish "${COREOS_BUILD_ID}"
'''  /* Editor quote safety: ' */
                }
            }
        }
    }

    stage('Post-build') {
        archiveArtifacts 'manifest.properties'

        for (line in readFile('manifest.properties').trim().split("\n")) {
            def tokens = line.tokenize(" =")
            dprops[tokens[0]] = tokens[1]
        }
    }
}

stage('Downstream') {
    parallel failFast: false,
        sdk: {
            build job: 'sdk', parameters: [
                string(name: 'BUILDS_CLONE_CREDS', value: profile.BUILDS_CLONE_CREDS ?: ''),
                string(name: 'COREOS_OFFICIAL', value: dprops.COREOS_OFFICIAL),
                string(name: 'MANIFEST_NAME', value: dprops.MANIFEST_NAME),
                string(name: 'MANIFEST_REF', value: dprops.MANIFEST_REF),
                string(name: 'MANIFEST_URL', value: dprops.MANIFEST_URL),
                string(name: 'GS_DEVEL_CREDS', value: profile.GS_DEVEL_CREDS),
                string(name: 'GS_DEVEL_ROOT', value: profile.GS_DEVEL_ROOT),
                string(name: 'SIGNING_CREDS', value: profile.SIGNING_CREDS),
                string(name: 'SIGNING_USER', value: profile.SIGNING_USER),
                string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
            ]
        },
        toolchains: {
            build job: 'toolchains', parameters: [
                string(name: 'BUILDS_CLONE_CREDS', value: profile.BUILDS_CLONE_CREDS ?: ''),
                string(name: 'COREOS_OFFICIAL', value: dprops.COREOS_OFFICIAL),
                string(name: 'GROUP', value: profile.GROUP),
                string(name: 'MANIFEST_NAME', value: dprops.MANIFEST_NAME),
                string(name: 'MANIFEST_REF', value: dprops.MANIFEST_REF),
                string(name: 'MANIFEST_URL', value: dprops.MANIFEST_URL),
                string(name: 'GS_DEVEL_CREDS', value: profile.GS_DEVEL_CREDS),
                string(name: 'GS_DEVEL_ROOT', value: profile.GS_DEVEL_ROOT),
                string(name: 'GS_RELEASE_CREDS', value: profile.GS_RELEASE_CREDS),
                string(name: 'GS_RELEASE_DOWNLOAD_ROOT', value: profile.GS_RELEASE_DOWNLOAD_ROOT),
                string(name: 'GS_RELEASE_ROOT', value: profile.GS_RELEASE_ROOT),
                string(name: 'SIGNING_CREDS', value: profile.SIGNING_CREDS),
                string(name: 'SIGNING_USER', value: profile.SIGNING_USER),
                string(name: 'VERIFY_KEYRING', value: keyring),
                string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
            ]
        }
}
