#!groovy

properties([
    buildDiscarder(logRotator(numToKeepStr: '100')),
    pipelineTriggers([pollSCM('H H/6 * * *')])
])

def overlayPR = ''
def versionNew = ''
def versionOld = ''

node('coreos') {
    /*
     * Pull Linux 4.19 stable tags and pick the latest one.
     */
    stage('SCM') {
        checkout([
            $class: 'GitSCM',
            branches: [[name: '**']],
            extensions: [
                [$class: 'CleanBeforeCheckout'],
                [$class: 'CloneOption', honorRefspec: true, noTags: true],
                [$class: 'RelativeTargetDirectory', relativeTargetDir: 'linux'],
            ],
            userRemoteConfigs: [
                [name: 'stable',
                 refspec: '+refs/tags/v4.19.*:refs/remotes/stable/tags/v4.19.*',
                 url: 'https://git.kernel.org/pub/scm/linux/kernel/git/stable/linux-stable.git'],
            ]
        ])
        sh '''
# Pretend stable tags are local due to the weird refspec/noTags interaction.
rmdir linux/.git/refs/tags && ln -fns remotes/stable/tags linux/.git/refs/tags
# Set Git author information.
git -C linux config user.name 'Jenkins OS'
git -C linux config user.email 'ct-automation-os@redhat.com'
# Allow SSH Git remotes.
mkdir -pm 0700 "${HOME}/.ssh"
grep -qs '^github.com ' "${HOME}/.ssh/known_hosts" ||
ssh-keyscan -t rsa github.com >> "${HOME}/.ssh/known_hosts"
'''
        versionNew = sh(returnStdout: true, script: 'git -C linux tag | sed -n "/^v4.19.[0-9]*$/s/^v//p" | sort -ruV | head -1').trim()
    }

    /*
     * Create an SDK from master and read its current kernel version.
     */
    stage('SDK') {
        copyArtifacts filter: 'bin/cork', projectName: '/mantle/master-builder', selector: lastSuccessful()
        sh '''
# Create an SDK from master.
bin/cork update --create --downgrade-replace --force-sync --verbose
git -C src/third_party/coreos-overlay reset --hard github/master
# Set Git author information.
git -C src/third_party/coreos-overlay config user.name 'Jenkins OS'
git -C src/third_party/coreos-overlay config user.email 'ct-automation-os@redhat.com'
# Allow SSH Git remotes.
mkdir -pm 0700 "chroot/home/${USER}/.ssh"
grep -qs '^github.com ' "chroot/home/${USER}/.ssh/known_hosts" ||
ssh-keyscan -t rsa github.com >> "chroot/home/${USER}/.ssh/known_hosts"
'''
        def manifest = readFile 'src/third_party/coreos-overlay/sys-kernel/coreos-sources/Manifest'
        versionOld = manifest.split().findAll({ it.startsWith('patch-4.19.') })[0][6..-4]
    }

    if (versionNew == versionOld) {
        currentBuild.result = 'ABORTED'
        error "The new kernel ${versionNew} is the same as the current kernel ${versionOld}."
    }

    sshagent(['f40af2c1-0f07-41c4-9aad-5014dd213a3e']) {
        /*
         * Rebase CoreOS patches onto the new tag and push to coreosbot.
         */
        stage('Branch Linux') {
            sh """#!/bin/bash -ex
declare -r current=${versionOld} latest=${versionNew}
cd linux
""" + '''
# Verify that the tag is signed with a known key.
gpg2 --keyserver pool.sks-keyservers.net --recv-keys 647F28654894E3BD457199BE38DBBDC86092693E || :
git tag --verify "v${latest}"

# Push the unchanged tag to the CoreOS fork.
git remote add coreos 'ssh://git@github.com/coreos/linux.git'
trap 'git remote remove coreos' EXIT
git push --force coreos "v${latest}"

# Start a branch for the PR base.  Ignore failures if it exists.
git checkout "v${latest}"
git checkout -B "v${latest}-coreos"
git push --set-upstream coreos "v${latest}-coreos" || :

# Rebase from the current kernel release branch.
git fetch coreos "v${current}-coreos"
git rebase --onto="v${latest}-coreos" "v${current}" "coreos/v${current}-coreos"
git checkout -B "v${latest}-coreos"

# Create patches for the overlay.
rm -f [0-9][0-9][0-9][0-9]-*.patch
git format-patch "v${latest}"

# Send the new branch to CoreOS Bot to create a pull request.
git push --force 'ssh://git@github.com/coreosbot/linux.git' "v${latest}-coreos"
'''
        }

        /*
         * Update the overlay's kernel ebuilds in master and push to coreosbot.
         */
        stage('Branch Overlay') {
            sh """#!/bin/bash -ex
declare -r current=${versionOld} latest=${versionNew} branch=linux-${versionNew}
cd src/third_party/coreos-overlay
""" + '''
function enter() ( cd ../../.. ; exec bin/cork enter -- "$@" )

# Start a working branch for the update (with current metadata).
git checkout -B "${branch}"
git rm -fr metadata/md5-cache
enter /mnt/host/source/src/scripts/update_metadata --commit

# Rename ebuilds.
for pkg in sources modules kernel
do
        pushd "sys-kernel/coreos-${pkg}" >/dev/null
        git mv "coreos-${pkg}"-*.ebuild "coreos-${pkg}-${latest}.ebuild"
        sed -i -e '/^COREOS_SOURCE_REVISION=/s/=.*/=""/' "coreos-${pkg}-${latest}.ebuild"
        popd >/dev/null
done

# Download the new stable kernel patch.
enter ebuild "/mnt/host/source/src/third_party/coreos-overlay/sys-kernel/coreos-sources/coreos-sources-${latest}.ebuild" manifest --force
# There are no signatures to verify patches, and regenerating the patch from Git is not reliably reproducible.

# Replace the local patch files.
git rm -fr sys-kernel/coreos-sources/files
mkdir -p sys-kernel/coreos-sources/files/4.19
for patch in ../../../linux/[0-9][0-9][0-9][0-9]-*.patch
do
        cp "${patch}" "sys-kernel/coreos-sources/files/4.19/z${patch##*/}"
        echo -e '\\t${PATCH_DIR}/z'"${patch##*/}"' \\'
done |
sed -i -e '/^UNIPATCH_LIST="$/,/^"$/{/^\\t/d;};/^UNIPATCH_LIST=/r/dev/stdin' "sys-kernel/coreos-sources/coreos-sources-${latest}.ebuild"

# Sync metadata.
git rm -fr metadata/md5-cache
enter /mnt/host/source/src/scripts/update_metadata

# Send the new branch to GitHub.
git add sys-kernel/coreos-*
git commit -am "sys-kernel/coreos-sources: Bump ${current} to ${latest}"
git push --force 'ssh://git@github.com/coreosbot/coreos-overlay.git' "${branch}"
'''
        }
    }
}

/*
 * Create pull requests with the new branches and alert Slack.
 */
stage('PR') {
    withCredentials([string(credentialsId: '2b710187-52cc-4e5b-a020-9cae59519baa', variable: 'token')]) {
        def linuxURL = createPullRequest token: token,
                                         upstreamBranch: "v${versionNew}-coreos",
                                         upstreamProject: 'coreos/linux',
                                         sourceOwner: 'coreosbot',
                                         sourceBranch: "v${versionNew}-coreos",
                                         title: "Rebase ${versionOld} patches onto ${versionNew}",
                                         message: "${env.BUILD_URL}"
        def overlayURL = createPullRequest token: token,
                                           upstreamBranch: 'master',
                                           upstreamProject: 'coreos/coreos-overlay',
                                           sourceOwner: 'coreosbot',
                                           sourceBranch: "linux-${versionNew}",
                                           title: "Upgrade Linux ${versionOld} to ${versionNew}",
                                           message: "coreos/linux#${"${linuxURL}".split('/')[-1]}"
        slackSend color: 'good', message: """\
The Linux ${versionNew} update is building on master!
${env.BUILD_URL}cldsv
${linuxURL}
${overlayURL}"""
        overlayPR = "${overlayURL}".split('/')[-1]
    }
}

/*
 * Run a test build of master with the new kernel.
 */
stage('Test') {
    build job: 'manifest', propagate: false, parameters: [
        string(name: 'MANIFEST_REF', value: 'master'),
        string(name: 'RELEASE_BASE', value: 'master'),
        text(name: 'LOCAL_MANIFEST', value: """\
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
 <remove-project name="coreos/coreos-overlay"/>
 <project \
name="coreos/coreos-overlay" \
path="src/third_party/coreos-overlay" \
revision="refs/pull/${overlayPR}/head"/>
</manifest>
"""),
    ]
}
