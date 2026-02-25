FROM jenkins/jenkins:2.541.2-jdk21

USER root

# ── System deps ───────────────────────────────────────────────────────────────
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends \
        wget unzip curl git openssh-client && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# ── Terraform ─────────────────────────────────────────────────────────────────
# Minimum version required by ocp4-upi-powervm: 1.2.0
ARG TERRAFORM_VERSION=1.7.5
RUN wget -q "https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_linux_amd64.zip" \
        -O /tmp/terraform.zip && \
    unzip -q /tmp/terraform.zip -d /usr/local/bin && \
    rm /tmp/terraform.zip && \
    chmod +x /usr/local/bin/terraform && \
    terraform version

# ── Provider cache directory ───────────────────────────────────────────────────
# Outside /var/jenkins_home so it is NOT hidden by the volume mount at runtime.
# Pre-populated at build time; pipeline builds find providers without downloading.
RUN mkdir -p /opt/terraform-plugin-cache && \
    chown jenkins:jenkins /opt/terraform-plugin-cache

# ── Jenkins plugins ───────────────────────────────────────────────────────────
USER jenkins

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# ── Pre-warm Terraform provider cache ─────────────────────────────────────────
# Downloads the two providers required by ocp4-upi-powervm at image build time
# so that pipeline "terraform init" runs without internet access on every build.
# Mirrors versions.tf in the terraform repo:
#   terraform-provider-openstack/openstack ~> 1.32
#   hashicorp/random                       ~> 3.4
ENV TF_PLUGIN_CACHE_DIR=/opt/terraform-plugin-cache

RUN mkdir -p /tmp/tf-warmup && \
    printf '%s\n' \
        'terraform {' \
        '  required_providers {' \
        '    openstack = { source = "terraform-provider-openstack/openstack", version = "~> 1.32" }' \
        '    random    = { source = "hashicorp/random", version = "~> 3.4" }' \
        '  }' \
        '}' > /tmp/tf-warmup/versions.tf && \
    terraform -chdir=/tmp/tf-warmup init -no-color && \
    rm -rf /tmp/tf-warmup

# ── Runtime ───────────────────────────────────────────────────────────────────
# Skip setup wizard — JCasC handles all configuration at startup.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

# JCasC config files are mounted here at runtime.
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc
