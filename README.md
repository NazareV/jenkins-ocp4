# jenkins-ocp4

Jenkins Configuration as Code (JCasC) + pipeline repo for OCP4 cluster automation.
This is a **separate project** from the Terraform repo (`ocp4-upi-powervm`).
No file in the Terraform repo is modified by this setup.

```
Projects/
├── ocp4-upi-powervm/     ← Terraform repo (untouched)
└── jenkins-ocp4/         ← This repo
```

---

## Directory layout

```
jenkins-ocp4/
├── casc/
│   └── casc.yaml                   ← JCasC: full Jenkins system config
├── backend/
│   └── local-persistent.hcl.example← Local backend config for /opt/terraform_states
├── docker/
│   ├── Dockerfile                  ← Jenkins LTS + Terraform 1.7.5
│   └── plugins.txt                 ← Required plugins
├── jobs/
│   └── seed.groovy                 ← Job DSL: creates cluster-create & cluster-destroy
├── pipelines/
│   ├── Jenkinsfile.create          ← Provision pipeline
│   └── Jenkinsfile.destroy         ← Teardown pipeline
└── README.md
```

---

## Two-repo model

```
jenkins-ocp4 repo
  ├── casc/casc.yaml         → configures Jenkins at startup
  ├── jobs/seed.groovy       → creates pipeline job definitions
  └── pipelines/
       ├── Jenkinsfile.create  → at runtime, checks out ocp4-upi-powervm into tf/
       └── Jenkinsfile.destroy → same
```

Each pipeline build performs two checkouts:
1. **jenkins-ocp4** — automatic (contains the Jenkinsfile itself)
2. **ocp4-upi-powervm** — explicit `dir('tf') { checkout ... }` inside the pipeline

A `backend.tf` file is generated dynamically into `tf/` on every build.
It declares `backend "local" {}` but is **never committed** to the Terraform repo.

---

## Variable split

| Source | Mechanism | Terraform variables |
|--------|-----------|---------------------|
| Jenkins credentials | `TF_VAR_*` env vars | `user_name`, `password`, `auth_url`, `domain_name` |
| `tf-base-vars` credential | `-var-file` | `network_name`, image UUIDs, instance types, OCP tarball URLs, features… |
| Job parameters | `-var` flags (highest precedence) | `tenant_name`, `cluster_id`, `cluster_id_prefix`, `cluster_domain` |
| Host `~/.ssh/` | `-var` flags | `public_key_file`, `private_key_file` |
| `rhel-subscription` credential | `TF_VAR_*` (only when enabled) | `rhel_subscription_username`, `rhel_subscription_password` |

### Base var file (`tf-base-vars`)

The real, populated `var.tfvars` minus the keys that are handled elsewhere.
**Remove or leave empty these keys before encoding:**

```
user_name                   ← injected as TF_VAR_user_name
password                    ← injected as TF_VAR_password
tenant_name                 ← from TENANT_NAME parameter
domain_name                 ← injected as TF_VAR_domain_name
auth_url                    ← injected as TF_VAR_auth_url
cluster_id                  ← from CLUSTER_ID parameter
cluster_id_prefix           ← from CLUSTER_ID_PREFIX parameter
cluster_domain              ← from CLUSTER_DOMAIN parameter (optional)
public_key_file             ← from $HOME/.ssh/id_rsa.pub
private_key_file            ← from $HOME/.ssh/id_rsa
rhel_subscription_username  ← from rhel-subscription credential (optional)
rhel_subscription_password  ← from rhel-subscription credential (optional)
```

### RHEL subscription (optional)

Set `RHEL_SUBSCRIPTION_ENABLED=true` in the startup env and populate
`RHEL_SUBSCRIPTION_USERNAME` / `RHEL_SUBSCRIPTION_PASSWORD`.
If those variables are empty or `RHEL_SUBSCRIPTION_ENABLED` is not `true`,
the pipeline skips RHEL credential injection — appropriate for CentOS bastion.

### SSH keys

The pipelines pass:
```
-var "public_key_file=${HOME}/.ssh/id_rsa.pub"
-var "private_key_file=${HOME}/.ssh/id_rsa"
```
`$HOME` inside the container is `/var/jenkins_home`.
Mount the host keys read-only:
```bash
-v ~/.ssh/id_rsa:/var/jenkins_home/.ssh/id_rsa:ro
-v ~/.ssh/id_rsa.pub:/var/jenkins_home/.ssh/id_rsa.pub:ro
```
Or mount the whole directory:
```bash
-v ~/.ssh:/var/jenkins_home/.ssh:ro
```

---

## State files

State is stored on the host at `/opt/terraform_states/`:

```
/opt/terraform_states/
└── terraform.tfstate.d/
    ├── tenant-a-ocp-cluster01/terraform.tfstate
    ├── tenant-a-ocp-cluster02/terraform.tfstate
    └── tenant-b-dev-cluster01/terraform.tfstate
```

Each cluster → one workspace → one state file. No shared state. No collisions.

### Setup

```bash
# On the host
sudo mkdir -p /opt/terraform_states
sudo chown 1000:1000 /opt/terraform_states   # uid 1000 = jenkins user inside container

# Encode the backend config for the Jenkins credential
cp backend/local-persistent.hcl.example backend.hcl
export TF_BACKEND_CONFIG_B64=$(base64 -w0 backend.hcl)
rm backend.hcl
```

---

## Concurrent builds on the same host

Jenkins isolates concurrent builds of the same job into separate workspace dirs:

```
/var/jenkins_home/workspace/cluster-create/      ← build A  (tenant-a-ocp-cluster01)
/var/jenkins_home/workspace/cluster-create@2/    ← build B  (tenant-b-dev-cluster02)
/var/jenkins_home/workspace/cluster-create@3/    ← build C  (tenant-a-ocp-cluster03)
```

Each has its own `tf/`, `tf/data/`, `tf/.terraform/`, and plan files.
No shared mutable state on disk between concurrent builds.

`TF_PLUGIN_CACHE_DIR=/var/jenkins_home/.terraform-plugin-cache` is a shared
provider binary cache. Terraform uses OS file locking inside it — safe for
concurrent access. Avoids re-downloading providers on every build.

`numExecutors: 10` in `casc.yaml` — increase for higher concurrency.

---

## Environment variables (startup)

Set as Docker `-e` flags or `--env-file`. **Never commit this file.**

| Variable | Required | Description |
|----------|----------|-------------|
| `JENKINS_ADMIN_ID` | ✅ | Admin username |
| `JENKINS_ADMIN_PASSWORD` | ✅ | Admin password |
| `JENKINS_REPO_URL` | ✅ | Git URL of this (jenkins-ocp4) repo |
| `JENKINS_REPO_BRANCH` | ✅ | Branch (e.g. `main`) |
| `TF_REPO_URL` | ✅ | Git URL of `ocp4-upi-powervm` |
| `TF_REPO_BRANCH` | ✅ | Branch (e.g. `main`) |
| `POWERVC_USERNAME` | ✅ | PowerVC username |
| `POWERVC_PASSWORD` | ✅ | PowerVC password |
| `POWERVC_AUTH_URL` | ✅ | PowerVC auth endpoint |
| `POWERVC_DOMAIN_NAME` | ✅ | OpenStack domain (usually `Default`) |
| `OCP_PULL_SECRET_BASE64` | ✅ | `base64 -w0 ~/pull-secret.txt` |
| `TF_BASE_VARS_B64` | ✅ | `base64 -w0 base.tfvars` (secrets removed) |
| `TF_BACKEND_CONFIG_B64` | ✅ | `base64 -w0 backend.hcl` |
| `GIT_USERNAME` | ✅ | Git username |
| `GIT_TOKEN` | ✅ | Git personal access token |
| `RHEL_SUBSCRIPTION_ENABLED` | ☐ | `true` to enable RHEL subscription injection |
| `RHEL_SUBSCRIPTION_USERNAME` | ☐ | RHSM username (only if RHEL_SUBSCRIPTION_ENABLED=true) |
| `RHEL_SUBSCRIPTION_PASSWORD` | ☐ | RHSM password (only if RHEL_SUBSCRIPTION_ENABLED=true) |

---

## Quick start

```bash
# 1. Build the image
docker build -t jenkins-ocp4 docker/

# 2. Prepare credentials (run once)
#    a. Strip secret keys from var.tfvars → base.tfvars
#    b. Encode everything
export OCP_PULL_SECRET_BASE64=$(base64 -w0 ~/pull-secret.txt)
export TF_BASE_VARS_B64=$(base64 -w0 base.tfvars)

cp backend/local-persistent.hcl.example backend.hcl
export TF_BACKEND_CONFIG_B64=$(base64 -w0 backend.hcl)
rm backend.hcl

# 3. Create host state directory
sudo mkdir -p /opt/terraform_states
sudo chown 1000:1000 /opt/terraform_states

# 4. Run Jenkins
docker run -d \
  --name jenkins-ocp4 \
  -p 8080:8080 \
  --env-file .jenkins.env \
  -v jenkins_home:/var/jenkins_home \
  -v $(pwd)/casc:/var/jenkins_home/casc:ro \
  -v /opt/terraform_states:/opt/terraform_states \
  -v ~/.ssh/id_rsa:/var/jenkins_home/.ssh/id_rsa:ro \
  -v ~/.ssh/id_rsa.pub:/var/jenkins_home/.ssh/id_rsa.pub:ro \
  jenkins-ocp4

# 5. Open http://localhost:8080
#    Trigger _seed-jobs to create cluster-create and cluster-destroy
```

---

## Job parameters

### cluster-create

| Parameter | Example | Notes |
|-----------|---------|-------|
| `TENANT_NAME` | `tenant-a` | OpenStack project name |
| `CLUSTER_ID_PREFIX` | `ocp` | Max 6 chars |
| `CLUSTER_ID` | `cluster01` | Max 8 chars |
| `CLUSTER_DOMAIN` | `example.com` | Optional; overrides var file default |
| `AUTO_APPROVE` | `false` | Skip manual gate |

Workspace: `tenant-a-ocp-cluster01`
State: `/opt/terraform_states/terraform.tfstate.d/tenant-a-ocp-cluster01/terraform.tfstate`

### cluster-destroy

Same parameters, plus:

| Parameter | Default | Notes |
|-----------|---------|-------|
| `DELETE_WORKSPACE` | `true` | Remove workspace from state dir after destroy |

Operator must type the exact workspace name to confirm destroy.
