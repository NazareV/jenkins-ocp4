# jenkins-ocp4

Jenkins Configuration as Code (JCasC) + pipeline repo for OCP4 cluster automation on PowerVM (ppc64le).
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

## How it works

Each pipeline build performs two Git checkouts:

1. **jenkins-ocp4** (this repo) — checked out automatically by Jenkins SCM
2. **ocp4-upi-powervm** — checked out explicitly into `tf/` inside the pipeline

A `backend.tf` is generated dynamically into `tf/` on every build and never committed to the Terraform repo.

Terraform runs inside the Jenkins container on the controller node. All providers are pre-installed in the image at `/opt/terraform-providers/` (ppc64le binaries). `terraform init` uses `-plugin-dir` — no internet access is needed at pipeline runtime.

---

## Setup — step by step

### Prerequisites

| What | Notes |
|------|-------|
| Docker | Running on the Jenkins host |
| SSH key pair | `~/.ssh/id_rsa` + `~/.ssh/id_rsa.pub` on the Jenkins host |
| OCP pull secret | Downloaded from Red Hat — `~/pull-secret.txt` |
| `ocp4-upi-powervm` `var.tfvars` | Your populated copy of the Terraform var file |
| Git PAT | Personal access token with `repo` read scope (or `unused` for public repos) |

> **macOS note:** replace `base64 -w0` with `base64 -b 0` in all commands below.

---

### Step 1 — Clone this repo

```bash
git clone <this-repo-url> jenkins-ocp4
cd jenkins-ocp4
```

---

### Step 2 — Build the Docker image

Builds Terraform (latest) from source for `linux/ppc64le` and downloads all providers.
**Internet access is required once at build time — not at runtime.**

```bash
docker build --no-cache -t jenkins-ocp4:local docker/
```

This takes several minutes (Terraform compilation is ~5–10 min).

---

### Step 3 — Prepare `base.tfvars`

`base.tfvars` is your populated `ocp4-upi-powervm/var.tfvars` with the keys that Jenkins
injects via other mechanisms removed. If those keys stay in the file, the file value
silently overrides the Jenkins-injected value (Terraform precedence: `-var-file` beats `TF_VAR_*` env vars).

**Start from your `var.tfvars` and remove these keys entirely:**

```
# Injected as TF_VAR_* env vars — file value would override them
auth_url
user_name
password
domain_name
openstack_availability_zone

# Injected as -var flags — safe to keep OR remove (your choice)
# tenant_name, cluster_id, cluster_id_prefix, public_key_file, private_key_file
# rhel_subscription_username, rhel_subscription_password
# bastion, bootstrap, master, worker
```

**Keys to keep and populate with real values:**

```hcl
### Network
network_name = "your-network-name"

### OCP Installation
openshift_install_tarball = "https://mirror.openshift.com/pub/openshift-v4/ppc64le/clients/ocp/stable/openshift-install-linux.tar.gz"
openshift_client_tarball  = "https://mirror.openshift.com/pub/openshift-v4/ppc64le/clients/ocp/stable/openshift-client-linux.tar.gz"
pull_secret_file          = "data/pull-secret.txt"   # keep exactly as-is

### Bastion
rhel_username      = "root"
connection_timeout = 45
jump_host          = ""

### OCP cluster base domain (used when CLUSTER_DOMAIN job param is left empty)
cluster_domain = "yourdomain.com"

### Any optional settings your environment needs (uncomment from var.tfvars):
# fips_compliant     = false
# dns_forwarders     = "1.1.1.1; 9.9.9.9"
# storage_type       = "nfs"
# ...
```

> `pull_secret_file = "data/pull-secret.txt"` must stay exactly as-is.
> The pipeline writes the pull secret there at runtime from the `ocp-pull-secret` Jenkins credential.

---

### Step 4 — Encode credentials

```bash
# macOS
export OCP_PULL_SECRET_BASE64=$(base64 -b 0 ~/pull-secret.txt)
export TF_BASE_VARS_B64=$(base64 -b 0 base.tfvars)
cp backend/local-persistent.hcl.example backend.hcl
export TF_BACKEND_CONFIG_B64=$(base64 -b 0 backend.hcl)
rm backend.hcl

# Linux
export OCP_PULL_SECRET_BASE64=$(base64 -w0 ~/pull-secret.txt)
export TF_BASE_VARS_B64=$(base64 -w0 base.tfvars)
cp backend/local-persistent.hcl.example backend.hcl
export TF_BACKEND_CONFIG_B64=$(base64 -w0 backend.hcl)
rm backend.hcl
```

---

### Step 5 — Create the env file

```bash
cp .jenkins.env.local .jenkins.env
```

Edit `.jenkins.env` — **never commit this file**:

```
JENKINS_ADMIN_ID=admin
JENKINS_ADMIN_PASSWORD=<your-jenkins-password>

JENKINS_REPO_URL=<git-url-of-this-repo>
JENKINS_REPO_BRANCH=main

TF_REPO_URL=<git-url-of-ocp4-upi-powervm>
TF_REPO_BRANCH=main

POWERVC_DOMAIN_NAME=Default

GIT_USERNAME=<your-git-username>
GIT_TOKEN=<your-git-pat>

OCP_PULL_SECRET_BASE64=<value from step 4>
TF_BASE_VARS_B64=<value from step 4>
TF_BACKEND_CONFIG_B64=<value from step 4>
```

> `POWERVC_AUTH_URL`, `POWERVC_USERNAME`, `POWERVC_PASSWORD`, and `OPENSTACK_AVAILABILITY_ZONE`
> are **not** set here — they are job parameters supplied by the user at trigger time.

---

### Step 6 — Create the state directory

Terraform state is stored on the host at `/opt/terraform_states/`. Create it once:

```bash
sudo mkdir -p /opt/terraform_states
sudo chown 1000:1000 /opt/terraform_states   # uid 1000 = jenkins user inside container
```

---

### Step 7 — Start Jenkins

Run from the root of this repo:

```bash
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
```

---

### Step 8 — Wait for Jenkins to be ready

```bash
docker logs -f jenkins-ocp4 2>&1 | grep "fully up"
```

Press `Ctrl+C` once you see the message, then open [http://localhost:8080](http://localhost:8080)
and log in with `JENKINS_ADMIN_ID` / `JENKINS_ADMIN_PASSWORD`.

---

### Step 9 — One-time UI config *(first start only)*

Required for the Job DSL sandbox to run as the triggering user:

1. **Manage Jenkins → Security → Build Authorization**
2. Set strategy to **"Run as User who Triggered Build"** → **Save**

This setting persists in the `jenkins_home` volume across restarts — only needed once.

---

### Step 10 — Bootstrap

Open **`_seed-jobs`** → **Build Now**.

This runs `jobs/seed.groovy` via the Job DSL step and creates:
- `cluster-create`
- `cluster-destroy`

Jenkins is now fully operational. Trigger either job with parameters to provision or tear down a cluster.

---

### Subsequent restarts

No rebuild needed. The `jenkins_home` volume retains all configuration, credentials, and job history:

```bash
docker start jenkins-ocp4
```

---

## Variable split

| Source | Mechanism | Terraform variables |
|--------|-----------|---------------------|
| Jenkins credential `powervc-domain-name` | `TF_VAR_domain_name` | `domain_name` |
| Jenkins credential `tf-base-vars` | `-var-file` | `network_name`, OCP tarball URLs, `cluster_domain` (default), features… |
| Job parameters | `TF_VAR_*` env vars | `auth_url`, `user_name`, `password`, `openstack_availability_zone` |
| Job parameters | `-var` flags | `tenant_name`, `cluster_id`, `cluster_id_prefix`, `cluster_domain` (override), node sizing |
| Job parameters | `-var` flags (optional) | `rhel_subscription_username`, `rhel_subscription_password` |
| Host `~/.ssh/` | `-var` flags | `public_key_file`, `private_key_file` |

**Terraform precedence (lowest → highest):** `TF_VAR_*` env vars → `-var-file` → `-var` flags.

This means variables injected as `TF_VAR_*` (auth_url, user_name, password, domain_name,
openstack_availability_zone) **must not appear in `base.tfvars`** — the file would win over the env var.
Variables injected as `-var` flags can safely remain in `base.tfvars` as defaults.

---

## Terraform providers

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

---

## State files

```
/opt/terraform_states/
└── terraform.tfstate.d/
    ├── tenant-a-vn-sno-cluster01/terraform.tfstate
    ├── tenant-a-vn-reg-cluster02/terraform.tfstate
    └── tenant-b-jd-sno-cluster01/terraform.tfstate
```

Each cluster → one Terraform workspace → one state file. No shared state, no collisions.

---

## Concurrent builds

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
| `CLUSTER_ID_PREFIX` | — | Your initials + cluster type, e.g. `vn-sno` (SNO) or `vn-reg` (regular). Max 6 chars. Required. |
| `CLUSTER_ID` | — | Unique cluster identifier, e.g. `cluster01`. Max 8 chars. Required. |
| `CLUSTER_DOMAIN` | — | OCP base domain override. If empty, value from `base.tfvars` is used. |
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

Workspace name: `<TENANT_NAME>-<CLUSTER_ID_PREFIX>-<CLUSTER_ID>`
State file: `/opt/terraform_states/terraform.tfstate.d/<workspace>/terraform.tfstate`

> Bastion and bootstrap counts are always `1` and are not exposed as parameters.

### cluster-destroy

Same identity + PowerVC connection + RHEL parameters as create, plus:

| Parameter | Default | Notes |
|-----------|---------|-------|
| `DELETE_WORKSPACE` | `true` | Remove Terraform workspace from state dir after destroy. |

Operator must **type** the exact workspace name in the confirmation dialog to proceed.
