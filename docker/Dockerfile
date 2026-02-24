FROM jenkins/jenkins:2.452.3-lts-jdk17

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

# ── Jenkins plugins ───────────────────────────────────────────────────────────
USER jenkins

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# ── Runtime ───────────────────────────────────────────────────────────────────
# Skip setup wizard — JCasC handles all configuration at startup.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

# JCasC config files are mounted here at runtime.
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc
