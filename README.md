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
| Git PAT | Personal access token with `repo` read scope (or `unused` for public repos) |

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

### Step 3 — Prepare the base var file content

The base var file contains infrastructure-wide Terraform settings that are the same across all cluster builds.
You will paste its content directly into the `BASE_VARS_CONTENT` job parameter when triggering a build — no encoding required.

**Start from `ocp4-upi-powervm/var.tfvars` and keep these keys (populate with real values):**

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
> The pipeline writes the pull secret there at runtime from the `OCP_PULL_SECRET` job parameter.

The following keys are injected as `-var` flags (highest Terraform precedence) and safely override
anything in this file — you may leave them out to keep the file clean:

```
# auth_url, user_name, password, openstack_availability_zone, domain_name
# tenant_name, cluster_id, cluster_id_prefix, cluster_domain (override)
# public_key_file, private_key_file
# rhel_subscription_username, rhel_subscription_password
# bastion, bootstrap, master, worker
```

---

### Step 4 — Encode credentials

SSH keys must be base64-encoded so they can be injected as Jenkins file credentials.
The Terraform backend config is **pre-encoded** in `.jenkins.env.local` for the default path
(`/opt/terraform_states/terraform.tfstate`) — re-encode only if you use a different path.

```bash
# SSH keys (run on the CentOS host where the keys live)
export SSH_PRIVATE_KEY_B64=$(base64 -w0 ~/.ssh/id_rsa)
export SSH_PUBLIC_KEY_B64=$(base64 -w0 ~/.ssh/id_rsa.pub)

# Terraform backend config — only needed if you change the state path from the default:
# printf 'path = "/your/custom/path/terraform.tfstate"\n' | base64
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

GIT_USERNAME=<your-git-username>
GIT_TOKEN=<your-git-pat>

TF_BACKEND_CONFIG_B64=<value from step 4>
SSH_PRIVATE_KEY_B64=<value from step 4>
SSH_PUBLIC_KEY_B64=<value from step 4>
```

> `POWERVC_AUTH_URL`, `POWERVC_USERNAME`, `POWERVC_PASSWORD`, `OPENSTACK_AVAILABILITY_ZONE`,
> `OCP_PULL_SECRET`, and `BASE_VARS_CONTENT` are **not** set here — they are supplied as job parameters at build time.

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
  -v jenkins_ocp4_home:/var/jenkins_home \
  -v $(pwd)/casc:/var/jenkins_home/casc:ro \
  -v /opt/terraform_states:/opt/terraform_states \
  jenkins-ocp4:local
```

> SSH keys are injected via `SSH_PRIVATE_KEY_B64` / `SSH_PUBLIC_KEY_B64` in `.jenkins.env` and stored
> as Jenkins file credentials — no SSH volume mount needed.

To restart an existing container (without recreating):

```bash
docker start jenkins-ocp4
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

## Variable split

| Source | Mechanism | Terraform variables |
|--------|-----------|---------------------|
| Job parameter `BASE_VARS_CONTENT` (multi-line text) | `-var-file` | `network_name`, `domain_name`, OCP tarball URLs, `cluster_domain` (default), `rhel_username`, features… |
| Job parameters | `-var` flags | `auth_url`, `user_name`, `password`, `openstack_availability_zone`, `tenant_name`, `cluster_id`, `cluster_id_prefix`, `cluster_domain` (override), node sizing |
| Job parameters | `-var` flags (optional) | `rhel_subscription_username`, `rhel_subscription_password` |
| Jenkins file credential (`jenkins-ssh-private-key` / `jenkins-ssh-public-key`) | `-var` flags | `public_key_file`, `private_key_file` |

**Terraform precedence (lowest → highest):** `TF_VAR_*` env vars → `-var-file` → `-var` flags.

All connection and identity variables are injected as `-var` flags (highest precedence) and safely override any value in `BASE_VARS_CONTENT`.
No `TF_VAR_*` env vars are used. `password` is masked in logs via `MaskPasswordsBuildWrapper`.
`OCP_PULL_SECRET` is written to `data/pull-secret.txt` and never printed to the build log.

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
| `BASE_VARS_CONTENT` | — | Multi-line tfvars content (network, OCP URLs, cluster_domain default, etc.). Required. |
| `OCP_PULL_SECRET` | — | OCP pull secret JSON from console.redhat.com. Required. Masked in logs. |
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
| `WORKER_INSTANCE_TYPE` | — | PowerVC compute template for workers. Required when `WORKER_COUNT > 0`. |
| `WORKER_IMAGE_ID` | — | PowerVC image UUID for workers (RHCOS). Required when `WORKER_COUNT > 0`. |
| `WORKER_COUNT` | `2` | Number of worker (compute) nodes. Set to `0` for SNO. |
| `RETRY_COUNT` | `2` | Total apply attempts (1 = no retry). On final failure, a cleanup destroy runs automatically. |

Workspace name: `<TENANT_NAME>-<CLUSTER_ID_PREFIX>-<CLUSTER_ID>`
State file: `/opt/terraform_states/terraform.tfstate.d/<workspace>/terraform.tfstate`

> Bastion and bootstrap counts are always `1` and are not exposed as parameters.

### cluster-destroy

| Parameter | Default | Notes |
|-----------|---------|-------|
| `BASE_VARS_CONTENT` | — | Same content as used for create (required by Terraform variable validation). |
| `OCP_PULL_SECRET` | — | OCP pull secret JSON (required). |
| `TENANT_NAME` | — | OpenStack project name. Required. |
| `CLUSTER_ID_PREFIX` | — | Your initials + cluster type (e.g. `vn-sno`). Required. |
| `CLUSTER_ID` | — | Cluster identifier (e.g. `cluster01`). Required. |
| `CLUSTER_DOMAIN` | — | OCP base domain override. Optional. |
| `POWERVC_AUTH_URL` | — | Keystone auth URL. Required. |
| `OPENSTACK_AVAILABILITY_ZONE` | — | Availability zone for all nodes. Required. |
| `POWERVC_USERNAME` | — | OpenStack username. Required. |
| `POWERVC_PASSWORD` | — | OpenStack password (masked). Required. |
| `RHEL_SUBSCRIPTION_ENABLED` | `false` | Attach RHEL subscription on nodes. |
| `RHEL_SUB_USER` | — | Red Hat subscription username (required when enabled). |
| `RHEL_SUB_PASS` | — | Red Hat subscription password (required when enabled, masked). |
| `DELETE_WORKSPACE` | `true` | Remove Terraform workspace from state dir after destroy. |
| `RETRY_COUNT` | `2` | Total destroy attempts (1 = no retry). |

> Node sizing parameters are **not** required for destroy — the pipeline writes placeholder values
> that satisfy Terraform's type requirements. Resources are identified from state, not from vars.

Operator must **type** the exact workspace name in the confirmation dialog to proceed.
