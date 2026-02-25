// ─────────────────────────────────────────────────────────────────────────────
// Job DSL Seed Script  —  jenkins-ocp4 repo
// Creates: cluster-create, cluster-destroy
//
// Both jobs check out the JENKINS repo (for the Jenkinsfile), then separately
// check out the TERRAFORM repo (ocp4-upi-powervm) at runtime for actual TF code.
// ─────────────────────────────────────────────────────────────────────────────

def jenkinsRepoUrl    = System.getenv('JENKINS_REPO_URL')    ?: 'https://github.com/your-org/jenkins-ocp4.git'
def jenkinsRepoBranch = System.getenv('JENKINS_REPO_BRANCH') ?: 'main'

// ── cluster-create ────────────────────────────────────────────────────────────

pipelineJob('cluster-create') {
    description('''\
        Provision a new OCP4 cluster on IBM PowerVM via Terraform.

        Workspace : <TENANT_NAME>-<CLUSTER_ID_PREFIX>-<CLUSTER_ID>
        State     : /opt/terraform_states/terraform.tfstate.d/<workspace>/
        SSH keys  : $HOME/.ssh/id_rsa[.pub] from the Jenkins host
        Parallel  : concurrent operations on different clusters are fully parallel;
                    same-cluster operations are serialized by a Jenkins lock.
    '''.stripIndent())

    logRotator {
        numToKeep(30)
        artifactNumToKeep(10)
    }

    parameters {
        // ── Cluster identity ─────────────────────────────────────────────────
        stringParam(
            'TENANT_NAME', '',
            'OpenStack/PowerVC project (tenant) name. First segment of the Terraform workspace: <TENANT_NAME>-<CLUSTER_ID_PREFIX>-<CLUSTER_ID>.'
        )
        stringParam(
            'CLUSTER_ID_PREFIX', '',
            'Your initials + cluster type, e.g. Vaibhav Nazare doing an SNO cluster → "vn-sno"; regular cluster → "vn-reg". Max 6 chars.'
        )
        stringParam(
            'CLUSTER_ID', '',
            'Unique cluster identifier within the tenant+prefix namespace (e.g. cluster01). Max 8 chars.'
        )
        stringParam(
            'CLUSTER_DOMAIN', '',
            '(Optional) OCP base domain override, e.g. example.com. Leave blank to use the default from the base var file.'
        )
        booleanParam(
            'AUTO_APPROVE', false,
            'Skip the manual approval gate before terraform apply.'
        )
        // ── Base var file content ────────────────────────────────────────────
        textParam(
            'BASE_VARS_CONTENT', '',
            'Contents of base.tfvars — Terraform variables for network, OCP URLs, cluster domain, rhel_username, connection_timeout, and any optional settings. See var.tfvars in ocp4-upi-powervm for reference.'
        )
        // ── OCP pull secret ──────────────────────────────────────────────────
        nonStoredPasswordParam(
            'OCP_PULL_SECRET',
            'OCP pull secret JSON from console.redhat.com/openshift/install/pull-secret. Required.'
        )
        // ── PowerVC connection ───────────────────────────────────────────────
        stringParam(
            'POWERVC_AUTH_URL', '',
            'PowerVC / OpenStack Keystone auth endpoint, e.g. https://powervc.example.com:5000/v3/. Required.'
        )
        stringParam(
            'OPENSTACK_AVAILABILITY_ZONE', '',
            'OpenStack availability zone for all provisioned nodes, e.g. "Default". Required.'
        )
        // ── PowerVC credentials ──────────────────────────────────────────────
        stringParam(
            'POWERVC_USERNAME', '',
            'PowerVC / OpenStack username.'
        )
        nonStoredPasswordParam(
            'POWERVC_PASSWORD',
            'PowerVC / OpenStack password.'
        )
        // ── RHEL subscription (optional) ─────────────────────────────────────
        booleanParam(
            'RHEL_SUBSCRIPTION_ENABLED', false,
            'Attach RHEL subscription on provisioned nodes.'
        )
        stringParam(
            'RHEL_SUB_USER', '',
            'Red Hat subscription username (required when RHEL_SUBSCRIPTION_ENABLED).'
        )
        nonStoredPasswordParam(
            'RHEL_SUB_PASS',
            'Red Hat subscription password (required when RHEL_SUBSCRIPTION_ENABLED).'
        )
        // ── Node sizing ───────────────────────────────────────────────────────
        // Bastion and bootstrap counts are always 1; only instance type and image need user input.
        stringParam('BASTION_INSTANCE_TYPE',   '', 'PowerVC compute template for the bastion node.')
        stringParam('BASTION_IMAGE_ID',        '', 'PowerVC image UUID for the bastion node (RHEL image).')
        stringParam('BOOTSTRAP_INSTANCE_TYPE', '', 'PowerVC compute template for the bootstrap node.')
        stringParam('BOOTSTRAP_IMAGE_ID',      '', 'PowerVC image UUID for the bootstrap node (RHCOS image).')
        stringParam('MASTER_INSTANCE_TYPE',    '', 'PowerVC compute template for master nodes.')
        stringParam('MASTER_IMAGE_ID',         '', 'PowerVC image UUID for master nodes (RHCOS image).')
        stringParam('MASTER_COUNT',           '3', 'Number of master (control-plane) nodes.')
        stringParam('WORKER_INSTANCE_TYPE',    '', 'PowerVC compute template for worker nodes. Optional when WORKER_COUNT=0 (SNO).')
        stringParam('WORKER_IMAGE_ID',         '', 'PowerVC image UUID for worker nodes (RHCOS image). Optional when WORKER_COUNT=0 (SNO).')
        stringParam('WORKER_COUNT',           '2', 'Number of worker (compute) nodes. Set to 0 for SNO.')
    }

    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url(jenkinsRepoUrl)
                        credentials('git-credentials')
                    }
                    branch(jenkinsRepoBranch)
                    extensions {
                        cloneOption { shallow(false); depth(0); timeout(15); noTags(false); reference('') }
                        cleanBeforeCheckout()
                    }
                }
            }
            scriptPath('pipelines/Jenkinsfile.create')
            lightweight(false)
        }
    }
}

// ── cluster-destroy ───────────────────────────────────────────────────────────

pipelineJob('cluster-destroy') {
    description('''\
        Destroy an existing OCP4 cluster on IBM PowerVM via Terraform.

        The operator must TYPE the full workspace name in the confirmation dialog.
        When DELETE_WORKSPACE=true, the Terraform workspace is removed from
        /opt/terraform_states after a successful destroy.
    '''.stripIndent())

    logRotator {
        numToKeep(30)
        artifactNumToKeep(10)
    }

    parameters {
        // ── Cluster identity ─────────────────────────────────────────────────
        stringParam(
            'TENANT_NAME', '',
            'OpenStack/PowerVC project (tenant) name. First segment of the Terraform workspace: <TENANT_NAME>-<CLUSTER_ID_PREFIX>-<CLUSTER_ID>.'
        )
        stringParam(
            'CLUSTER_ID_PREFIX', '',
            'Your initials + cluster type, e.g. Vaibhav Nazare doing an SNO cluster → "vn-sno"; regular cluster → "vn-reg". Max 6 chars.'
        )
        stringParam(
            'CLUSTER_ID', '',
            'Unique cluster identifier within the tenant+prefix namespace (e.g. cluster01). Max 8 chars.'
        )
        stringParam(
            'CLUSTER_DOMAIN', '',
            '(Optional) OCP base domain override, e.g. example.com. Leave blank to use the default from the base var file.'
        )
        booleanParam(
            'DELETE_WORKSPACE', true,
            'Delete the Terraform workspace from the state directory after successful destroy.'
        )
        // ── Base var file content ────────────────────────────────────────────
        textParam(
            'BASE_VARS_CONTENT', '',
            'Contents of base.tfvars — Terraform variables for network, OCP URLs, cluster domain, rhel_username, connection_timeout, and any optional settings. See var.tfvars in ocp4-upi-powervm for reference.'
        )
        // ── OCP pull secret ──────────────────────────────────────────────────
        nonStoredPasswordParam(
            'OCP_PULL_SECRET',
            'OCP pull secret JSON from console.redhat.com/openshift/install/pull-secret. Required.'
        )
        // ── PowerVC connection ───────────────────────────────────────────────
        stringParam(
            'POWERVC_AUTH_URL', '',
            'PowerVC / OpenStack Keystone auth endpoint, e.g. https://powervc.example.com:5000/v3/. Required.'
        )
        stringParam(
            'OPENSTACK_AVAILABILITY_ZONE', '',
            'OpenStack availability zone for all provisioned nodes, e.g. "Default". Required.'
        )
        // ── PowerVC credentials ──────────────────────────────────────────────
        stringParam(
            'POWERVC_USERNAME', '',
            'PowerVC / OpenStack username.'
        )
        nonStoredPasswordParam(
            'POWERVC_PASSWORD',
            'PowerVC / OpenStack password.'
        )
        // ── RHEL subscription (optional) ─────────────────────────────────────
        booleanParam(
            'RHEL_SUBSCRIPTION_ENABLED', false,
            'Attach RHEL subscription on provisioned nodes.'
        )
        stringParam(
            'RHEL_SUB_USER', '',
            'Red Hat subscription username (required when RHEL_SUBSCRIPTION_ENABLED).'
        )
        nonStoredPasswordParam(
            'RHEL_SUB_PASS',
            'Red Hat subscription password (required when RHEL_SUBSCRIPTION_ENABLED).'
        )
    }

    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url(jenkinsRepoUrl)
                        credentials('git-credentials')
                    }
                    branch(jenkinsRepoBranch)
                    extensions {
                        cloneOption { shallow(false); depth(0); timeout(15); noTags(false); reference('') }
                        cleanBeforeCheckout()
                    }
                }
            }
            scriptPath('pipelines/Jenkinsfile.destroy')
            lightweight(false)
        }
    }
}
