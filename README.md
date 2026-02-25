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
│   └── casc.yaml                    ← JCasC: full Jenkins system config
├── backend/
│   └── local-persistent.hcl.example ← Local backend config for /opt/terraform_states
├── docker/
│   ├── Dockerfile                   ← Jenkins 2.541.2-jdk21 + Terraform (latest, built from source)
│   └── plugins.txt                  ← Required plugins
├── jobs/
│   └── seed.groovy                  ← Job DSL: creates cluster-create & cluster-destroy
├── pipelines/
│   ├── Jenkinsfile.seed             ← Pipeline wrapper that runs seed.groovy via Job DSL
│   ├── Jenkinsfile.create           ← Provision pipeline
│   └── Jenkinsfile.destroy          ← Teardown pipeline
└── README.md
```

---

## Two-repo model

```
jenkins-ocp4 repo
  ├── casc/casc.yaml          → configures Jenkins at startup
  ├── jobs/seed.groovy        → creates pipeline job definitions
  └── pipelines/
       ├── Jenkinsfile.seed    → bootstrap: runs seed.groovy via Job DSL step
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
| Jenkins credentials | `TF_VAR_*` env vars | `domain_name` |
| `tf-base-vars` credential | `-var-file` | `network_name`, image UUIDs, OCP tarball URLs, features… |
| Job parameters | `TF_VAR_*` env vars | `auth_url`, `user_name`, `password`, `availability_zone` |
| Job parameters | `-var` flags | `tenant_name`, `cluster_id`, `cluster_id_prefix`, `cluster_domain`, node sizing |
| Job parameters | `-var` flags (optional) | `rhel_subscription_username`, `rhel_subscription_password` |
| Host `~/.ssh/` | `-var` flags | `public_key_file`, `private_key_file` |

### Base var file (`tf-base-vars`)

The real, populated `var.tfvars` minus the keys that are handled elsewhere.
**Remove or leave empty these keys before encoding:**

```
user_name                   ← injected as TF_VAR_user_name (job param)
password                    ← injected as TF_VAR_password (job param)
auth_url                    ← injected as TF_VAR_auth_url (job param)
availability_zone           ← injected as TF_VAR_availability_zone (job param)
tenant_name                 ← from TENANT_NAME parameter
domain_name                 ← injected as TF_VAR_domain_name (Jenkins credential)
cluster_id                  ← from CLUSTER_ID parameter
cluster_id_prefix           ← from CLUSTER_ID_PREFIX parameter
cluster_domain              ← from CLUSTER_DOMAIN parameter (optional)
public_key_file             ← from $HOME/.ssh/id_rsa.pub
private_key_file            ← from $HOME/.ssh/id_rsa
rhel_subscription_username  ← from job parameter (optional)
rhel_subscription_password  ← from job parameter (optional)
```

### RHEL subscription (optional)

Set `RHEL_SUBSCRIPTION_ENABLED=true` when triggering the job and fill in
`RHEL_SUB_USER` / `RHEL_SUB_PASS`. If those fields are empty or the boolean
is false the pipeline skips injection — appropriate for CentOS bastion builds.

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
    ├── tenant-a-vn-sno-cluster01/terraform.tfstate
    ├── tenant-a-vn-reg-cluster02/terraform.tfstate
    └── tenant-b-jd-sno-cluster01/terraform.tfstate
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

## Environment variables (startup)

Set as Docker `-e` flags or `--env-file`. **Never commit this file.**
Copy `.jenkins.env.local` as a template (it is gitignored).

| Variable | Required | Description |
|----------|----------|-------------|
| `JENKINS_ADMIN_ID` | ✅ | Admin username |
| `JENKINS_ADMIN_PASSWORD` | ✅ | Admin password |
| `JENKINS_REPO_URL` | ✅ | Git URL of this (jenkins-ocp4) repo |
| `JENKINS_REPO_BRANCH` | ✅ | Branch (e.g. `main`) |
| `TF_REPO_URL` | ✅ | Git URL of `ocp4-upi-powervm` |
| `TF_REPO_BRANCH` | ✅ | Branch (e.g. `main`) |
| `POWERVC_DOMAIN_NAME` | ✅ | OpenStack domain name (usually `Default`) |
| `OCP_PULL_SECRET_BASE64` | ✅ | `base64 -w0 ~/pull-secret.txt` |
| `TF_BASE_VARS_B64` | ✅ | `base64 -w0 base.tfvars` (secrets removed) |
| `TF_BACKEND_CONFIG_B64` | ✅ | `base64 -w0 backend.hcl` |
| `GIT_USERNAME` | ✅ | Git username |
| `GIT_TOKEN` | ✅ | Git personal access token (or `unused` for public repos) |

> **Note:** `POWERVC_AUTH_URL`, `POWERVC_USERNAME`, `POWERVC_PASSWORD`,
> `OPENSTACK_AVAILABILITY_ZONE`, and RHEL subscription credentials are **job
> parameters** — users supply them at trigger time, not at container startup.

---

## Quick start

> **macOS note:** replace `base64 -w0` with `base64 -b 0` throughout.

```bash
# 1. Build the image (downloads Terraform providers at build time — needs internet once)
docker build --no-cache -t jenkins-ocp4:local docker/

# 2. Prepare credentials (run once per environment)
#    a. Strip secret keys from var.tfvars → base.tfvars (see Variable split above)
#    b. Encode everything
export OCP_PULL_SECRET_BASE64=$(base64 -w0 ~/pull-secret.txt)   # macOS: base64 -b 0
export TF_BASE_VARS_B64=$(base64 -w0 base.tfvars)               # macOS: base64 -b 0

cp backend/local-persistent.hcl.example backend.hcl
export TF_BACKEND_CONFIG_B64=$(base64 -w0 backend.hcl)          # macOS: base64 -b 0
rm backend.hcl

# 3. Create host state directory
sudo mkdir -p /opt/terraform_states
sudo chown 1000:1000 /opt/terraform_states   # uid 1000 = jenkins user inside container

# 4. Create env file (never commit)
cp .jenkins.env.local .jenkins.env   # edit with real values

# 5. Run Jenkins
docker run -d \
  --name jenkins-ocp4 \
  -p 8080:8080 \
  --env-file .jenkins.env \
  -v jenkins_home:/var/jenkins_home \
  -v $(pwd)/casc:/var/jenkins_home/casc:ro \
  -v /opt/terraform_states:/opt/terraform_states \
  -v ~/.ssh/id_rsa:/var/jenkins_home/.ssh/id_rsa:ro \
  -v ~/.ssh/id_rsa.pub:/var/jenkins_home/.ssh/id_rsa.pub:ro \
  jenkins-ocp4:local

# 6. Wait for Jenkins to be ready
docker logs -f jenkins-ocp4 2>&1 | grep "fully up"

# 7. Open http://localhost:8080
```

### Terraform providers

All providers are downloaded at image build time from the
[`ocp-power-automation/terraform-providers-power`](https://github.com/ocp-power-automation/terraform-providers-power/releases)
archive (always the latest release). They are stored at `/opt/terraform-providers/` inside the image.

Every `terraform init` in the pipeline uses `-plugin-dir /opt/terraform-providers/` — the Terraform
registry is never contacted at runtime. No internet access is required on the Jenkins host.

Providers currently included in the archive (all `linux/ppc64le`):

| Provider | Notes |
|---|---|
| `terraform-provider-openstack/openstack` | Required by `ocp4-upi-powervm` |
| `hashicorp/random` | Required by `ocp4-upi-powervm` |
| `hashicorp/null` | Bundled in archive |
| `hashicorp/time` | Bundled in archive |
| `IBM-Cloud/ibm` | Bundled in archive |
| `community-terraform-providers/ignition` | Bundled in archive |
| `terraform-providers/ignition` | Bundled in archive |

If the upstream archive adds or removes providers, rebuild the image (`docker build --no-cache`).

### One-time post-start configuration (authorize-project)

Required for the Job DSL sandbox to run as the triggering user:

1. **Manage Jenkins → Security → Build Authorization**
2. Set strategy to **"Run as User who Triggered Build"** → **Save**

This setting persists in the Jenkins home volume across restarts.

### Bootstrap

Trigger `_seed-jobs` → **Build Now**.
This runs `jobs/seed.groovy` via the Job DSL step, which creates:
- `cluster-create`
- `cluster-destroy`

---

## Concurrent builds on the same host

Jenkins isolates concurrent builds of the same job into separate workspace dirs:

```
/var/jenkins_home/workspace/cluster-create/      ← build A  (tenant-a-vn-sno-cluster01)
/var/jenkins_home/workspace/cluster-create@2/    ← build B  (tenant-b-jd-reg-cluster02)
/var/jenkins_home/workspace/cluster-create@3/    ← build C  (tenant-a-vn-reg-cluster03)
```

Each has its own `tf/`, `tf/data/`, `tf/.terraform/`, and plan files.
No shared mutable state on disk between concurrent builds.

`numExecutors: 10` in `casc.yaml` — increase for higher concurrency.

---

## Job parameters

### cluster-create

| Parameter | Default | Notes |
|-----------|---------|-------|
| `TENANT_NAME` | — | OpenStack project (tenant) name. Required. |
| `CLUSTER_ID_PREFIX` | — | Your initials + cluster type, e.g. `vn-sno` (Vaibhav Nazare, SNO) or `vn-reg` (regular). Max 6 chars. Required. |
| `CLUSTER_ID` | — | Unique cluster identifier, e.g. `cluster01`. Max 8 chars. Required. |
| `CLUSTER_DOMAIN` | — | Optional OCP base domain override. |
| `AUTO_APPROVE` | `false` | Skip the manual approval gate before apply. |
| `POWERVC_AUTH_URL` | — | Keystone auth URL, e.g. `https://powervc.example.com:5000/v3/`. Required. |
| `OPENSTACK_AVAILABILITY_ZONE` | — | Availability zone for all nodes, e.g. `Default`. Required. |
| `POWERVC_USERNAME` | — | OpenStack username. Required. |
| `POWERVC_PASSWORD` | — | OpenStack password (masked). Required. |
| `RHEL_SUBSCRIPTION_ENABLED` | `false` | Attach RHEL subscription on nodes. |
| `RHEL_SUB_USER` | — | Red Hat subscription username (required when enabled). |
| `RHEL_SUB_PASS` | — | Red Hat subscription password (required when enabled, masked). |
| `BASTION_INSTANCE_TYPE` | — | PowerVC compute template for bastion. Required. |
| `BASTION_IMAGE_ID` | — | PowerVC image UUID for bastion (RHEL). Required. |
| `BOOTSTRAP_INSTANCE_TYPE` | — | PowerVC compute template for bootstrap. Required. |
| `BOOTSTRAP_IMAGE_ID` | — | PowerVC image UUID for bootstrap (RHCOS). Required. |
| `MASTER_INSTANCE_TYPE` | — | PowerVC compute template for masters. Required. |
| `MASTER_IMAGE_ID` | — | PowerVC image UUID for masters (RHCOS). Required. |
| `MASTER_COUNT` | `3` | Number of master (control-plane) nodes. |
| `WORKER_INSTANCE_TYPE` | — | PowerVC compute template for workers. Required. |
| `WORKER_IMAGE_ID` | — | PowerVC image UUID for workers (RHCOS). Required. |
| `WORKER_COUNT` | `2` | Number of worker (compute) nodes. |

Workspace: `<TENANT_NAME>-<CLUSTER_ID_PREFIX>-<CLUSTER_ID>`
State: `/opt/terraform_states/terraform.tfstate.d/<workspace>/terraform.tfstate`

> Bastion and bootstrap counts are always `1` and are not exposed as parameters.

### cluster-destroy

Same identity + PowerVC connection + RHEL parameters as create, plus:

| Parameter | Default | Notes |
|-----------|---------|-------|
| `DELETE_WORKSPACE` | `true` | Remove Terraform workspace from state dir after destroy. |

Operator must **type** the exact workspace name in the confirmation dialog to proceed.
