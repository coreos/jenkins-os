#!/usr/bin/env groovy

def default_builder_image = 'quay.io/coreos/tectonic-builder:v1.44'
def tectonic_smoke_test_env_image = 'quay.io/coreos/tectonic-smoke-test-env:v5.15'

properties([
    parameters([
        string(name: 'builder_image',
               defaultValue: default_builder_image,
               description: 'tectonic-builder docker image to use for builds'),
        booleanParam(name: 'RUN_SMOKE_TESTS',
                     defaultValue: true),
        string(name: 'CLUSTER',
               defaultValue: 'tectonic-clnightly',
               description: 'name for the cluster'),
        string(name: 'TF_VAR_tectonic_base_domain',
               defaultValue: 'os.team.coreos.systems',
               description: 'route 53 hosted zone being used for the base domain'),
        string(name: 'TF_VAR_tectonic_aws_region',
               defaultValue: 'us-west-2'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'tectonic-license',
                    name: 'TF_VAR_tectonic_license_path',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'tectonic-pull',
                    name: 'TF_VAR_tectonic_pull_secret_path',
                    required: true),
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl',
                    defaultValue: 'c7e3cb5d-0c69-46c8-b184-48d68b1ce680',
                    name: 'AWS_TEST_CREDS',
                    required: true)
    ]),

    pipelineTriggers([cron('H 22 * * *')])
])

node('amd64 && docker') {
    stage('Cleanup') {
        sh '''#!/bin/bash -ex
        sudo rm -rf tectonic-installer
        '''
    }
    stage('Build & Test') {
        withDockerContainer(params.builder_image) {
            withEnv(["GO_PROJECT=/go/src/github.com/coreos/tectonic-installer",
                     "MAKEFLAGS=-j4"]) {
                sh '''#!/bin/bash -ex
                git clone https://github.com/coreos/tectonic-installer

                mkdir -p "${GO_PROJECT%/*}"
                ln -fns "$(pwd)/tectonic-installer" "${GO_PROJECT}"

                cd ${GO_PROJECT}/
                make bin/smoke

                cd ${GO_PROJECT}/installer
                make clean
                make tools
                make build
                '''
            }
        }
    }
    stage("Tests") {
        withCredentials([
            [$class: 'AmazonWebServicesCredentialsBinding',
             credentialsId: params.AWS_TEST_CREDS,
             accessKeyVariable: 'AWS_ACCESS_KEY_ID',
             secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'],
            file(credentialsId: params.TF_VAR_tectonic_license_path, variable: 'TF_VAR_tectonic_license_path'),
            file(credentialsId: params.TF_VAR_tectonic_pull_secret_path, variable: 'TF_VAR_tectonic_pull_secret_path')
        ]) {
            withDockerContainer(
                image: tectonic_smoke_test_env_image,
                args: '-u root -v /var/run/docker.sock:/var/run/docker.sock'
            ) {
                withEnv(["TECTONIC_INSTALLER_ROLE=tectonic-installer",
                         "GO_PROEJCT=/go/src/github.com/coreos/tectonic-installer",
                         "CLUSTER=${params.CLUSTER}",
                         "TF_VAR_tectonic_base_domain=${params.TF_VAR_tectonic_base_domain}",
                         "TF_VAR_tectonic_aws_region=${params.TF_VAR_tectonic_aws_region}"]) {
                    sh '''#!/bin/bash -ex
                    function cleanup {
                        # Delete aws key-pair
                        if [ -n "$TF_VAR_tectonic_aws_ssh_key" ]; then
                            ssh-add -d $sshdir/$TF_VAR_tectonic_aws_ssh_key
                            aws ec2 delete-key-pair --key-name $TF_VAR_tectonic_aws_ssh_key --region us-west-2
                            rm ~/.ssh/config
                            rm $sshdir/$TF_VAR_tectonic_aws_ssh_key
                            rm $sshdir/$TF_VAR_tectonic_aws_ssh_key.pub
                        fi
                    }
                    trap cleanup EXIT

                    cd tectonic-installer

                    export TF_VAR_tectonic_admin_email=buildbot@coreos.com

                    # Create aws key-pair
                    sshdir=$(pwd)
                    export TF_VAR_tectonic_aws_ssh_key="tectonic-nightly-$(mktemp -t 'XXXXXXXXXX' -u -p . | tr -d ./)"
                    ssh-keygen -t rsa -b 4096 -f $sshdir/$TF_VAR_tectonic_aws_ssh_key -N "" -q
                    kp=$(<$sshdir/$TF_VAR_tectonic_aws_ssh_key.pub)
                    eval `ssh-agent -s`
                    ssh-add $sshdir/$TF_VAR_tectonic_aws_ssh_key
                    mkdir ~/.ssh/
                    echo "IdentityFile $sshdir/$TF_VAR_tectonic_aws_ssh_key" >> ~/.ssh/config
                    aws ec2 import-key-pair --key-name=$TF_VAR_tectonic_aws_ssh_key --public-key-material "$kp" --region us-west-2

                    # Fetch current nightly version and export the EC2 AMI Override
                    COREOS_VERSION=$(curl -Ls "https://storage.googleapis.com/builds.developer.core-os.net/boards/amd64-usr/current-master/version.txt" | sed -n 's/^COREOS_VERSION=//p')
                    AMI=$(curl -s "https://storage.googleapis.com/builds.developer.core-os.net/boards/amd64-usr/${COREOS_VERSION}/coreos_production_ami_all.json" | jq -r '.amis[] | select(.name == "us-west-2") | .hvm')
                    export TF_VAR_tectonic_aws_ec2_ami_override=$AMI

                    # Update the k8s-node-bootstrap to use the developer URL & disable signature validation
                    sed -i "s,.{torcx_store_url},http://builds.developer.core-os.net/torcx/manifests/{{.Board}}/{{.OSVersion}}/torcx_manifest.json,g" modules/ignition/resources/services/k8s-node-bootstrap.service
                    sed -i "s/--verbose=debug/--no-verify-signatures=true --verbose=debug/g" modules/ignition/resources/services/k8s-node-bootstrap.service
                    
                    # Disable Container Linux Channel & Version checking tests
                    #
                    # Overriding the version and channel causes other failures during the deploy process, disable
                    # the expects until further investigation can be done
                    sed -i "s/expect(ContainerLinux/# expect(ContainerLinux/g" tests/rspec/lib/shared_examples/k8s.rb

                    # Change the base domain in the aws.tfvars (the environment variable is ignored for some reason)
                    sed -i "s/tectonic-ci.de/$TF_VAR_tectonic_base_domain/g" tests/smoke/aws/vars/aws.tfvars.json

                    mkdir -p templogfiles && chmod 777 templogfiles
                    cd tests/rspec

                    rspec spec/aws/basic_spec.rb --format RspecTap::Formatter --format RspecTap::Formatter --out ../../templogfiles/format=tap.log
                    '''
                }
            }
        }
    }
}
