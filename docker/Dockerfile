# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build Terraform for linux/ppc64le from source
#
# HashiCorp does not publish official ppc64le binaries. This stage clones
# the official Terraform repo at the latest release tag and cross-compiles it
# for linux/ppc64le using the Go toolchain installed by rpsene/goconfig.
# Method mirrors docs/automation_host_prereqs.md from ocp4-upi-powervm.
# ─────────────────────────────────────────────────────────────────────────────
FROM debian:bookworm-slim AS terraform-builder

RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends \
        git curl ca-certificates && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Go using rpsene/goconfig (same helper used in automation host prereqs)
RUN git clone --depth=1 https://github.com/rpsene/goconfig.git /tmp/goconfig

# Build the latest Terraform release for linux/ppc64le.
# All steps run inside a single bash invocation so that the PATH changes made
# by sourcing go.sh are visible to the 'go build' call that follows.
RUN bash -c '\
    source /tmp/goconfig/go.sh install 1.18 && \
    TAG=$(curl -s "https://api.github.com/repos/hashicorp/terraform/releases/latest" \
          | grep tag_name | sed "s/.*\"tag_name\": \"\(.*\)\".*/\1/") && \
    echo "=== Building Terraform ${TAG} for linux/ppc64le ===" && \
    git clone --depth=1 --branch "${TAG}" \
        https://github.com/hashicorp/terraform.git /tmp/terraform && \
    cd /tmp/terraform && \
    env GOOS=linux GOARCH=ppc64le go build -o /tmp/terraform-ppc64le . && \
    echo "Binary: $(file /tmp/terraform-ppc64le)"'

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Jenkins runtime image
# ─────────────────────────────────────────────────────────────────────────────
FROM jenkins/jenkins:2.541.2-jdk21

USER root

# ── System deps ───────────────────────────────────────────────────────────────
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends \
        wget unzip curl git openssh-client && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# ── Terraform binary (linux/ppc64le, built from source in Stage 1) ────────────
COPY --from=terraform-builder /tmp/terraform-ppc64le /usr/local/bin/terraform
RUN chmod +x /usr/local/bin/terraform && terraform version

# ── Terraform providers (linux/ppc64le) ───────────────────────────────────────
# Always downloads the LATEST release from ocp-power-automation/terraform-providers-power.
# The archive contains all providers compiled for linux/ppc64le and extracts as:
#   registry.terraform.io/<namespace>/<type>/<version>/linux_ppc64le/
# The pipeline passes:  terraform init -plugin-dir /opt/terraform-providers/
RUN mkdir -p /opt/terraform-providers && \
    curl -fsSL \
        "https://github.com/ocp-power-automation/terraform-providers-power/releases/latest/download/archive.zip" \
        -o /tmp/providers.zip && \
    unzip -o /tmp/providers.zip -d /opt/terraform-providers && \
    rm /tmp/providers.zip && \
    chown -R jenkins:jenkins /opt/terraform-providers && \
    echo "=== providers installed ===" && \
    find /opt/terraform-providers -name "terraform-provider-*" -type f

# ── Jenkins plugins ───────────────────────────────────────────────────────────
USER jenkins

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# ── Runtime ───────────────────────────────────────────────────────────────────
# Skip setup wizard — JCasC handles all configuration at startup.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

# JCasC config files are mounted here at runtime.
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc
