// ─────────────────────────────────────────────────────────────────────────────
// Job DSL Seed Script  —  jenkins-ocp4 repo
// Creates: cluster-create, cluster-destroy
//
// Both jobs check out the JENKINS repo (for the Jenkinsfile), then separately
// check out the TERRAFORM repo (ocp4-upi-powervm) at runtime for actual TF code.
// ─────────────────────────────────────────────────────────────────────────────

def jenkinsRepoUrl    = System.getenv('JENKINS_REPO_URL')    ?: 'https://github.com/your-org/jenkins-ocp4.git'
def jenkinsRepoBranch = System.getenv('JENKINS_REPO_BRANCH') ?: 'main'

// ── Shared helpers ────────────────────────────────────────────────────────────

def commonParameters = { ->
    stringParam(
        'TENANT_NAME', '',
        'OpenStack/PowerVC project (tenant) name. First segment of the Terraform workspace: <TENANT_NAME>-<CLUSTER_ID_PREFIX>-<CLUSTER_ID>.'
    )
    stringParam(
        'CLUSTER_ID_PREFIX', 'ocp',
        'Short prefix for cluster role or environment (e.g. ocp, dev, prod). Max 6 chars.'
    )
    stringParam(
        'CLUSTER_ID', '',
        'Unique cluster identifier within the tenant+prefix namespace (e.g. cluster01). Max 8 chars.'
    )
    stringParam(
        'CLUSTER_DOMAIN', '',
        '(Optional) OCP base domain override, e.g. example.com. Leave blank to use the default from the base var file.'
    )
}

def commonLogRotator = { ->
    numToKeep(30)
    artifactNumToKeep(10)
}

// SCM points to the JENKINS repo — this is where the Jenkinsfiles live.
def jenkinsScm = { ->
    cpsScm {
        scm {
            git {
                remote {
                    url(jenkinsRepoUrl)
                    credentials('git-credentials')
                }
                branch(jenkinsRepoBranch)
                extensions {
                    cloneOption { shallow(false); depth(0); timeout(15) }
                    cleanBeforeCheckout()
                }
            }
        }
        lightweight(false)
    }
}

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

    logRotator commonLogRotator

    parameters {
        commonParameters.delegate = delegate
        commonParameters()
        booleanParam(
            'AUTO_APPROVE', false,
            'Skip the manual approval gate before terraform apply.'
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
        stringParam('BASTION_INSTANCE_TYPE',   '', 'PowerVC compute template for the bastion node.')
        stringParam('BASTION_IMAGE_ID',        '', 'PowerVC image UUID for the bastion node (RHEL image).')
        stringParam('BASTION_COUNT',          '1', 'Number of bastion nodes.')
        stringParam('BOOTSTRAP_INSTANCE_TYPE', '', 'PowerVC compute template for the bootstrap node.')
        stringParam('BOOTSTRAP_IMAGE_ID',      '', 'PowerVC image UUID for the bootstrap node (RHCOS image).')
        stringParam('BOOTSTRAP_COUNT',        '1', 'Number of bootstrap nodes (always 1 for OCP UPI).')
        stringParam('MASTER_INSTANCE_TYPE',    '', 'PowerVC compute template for master nodes.')
        stringParam('MASTER_IMAGE_ID',         '', 'PowerVC image UUID for master nodes (RHCOS image).')
        stringParam('MASTER_COUNT',           '3', 'Number of master (control-plane) nodes.')
        stringParam('WORKER_INSTANCE_TYPE',    '', 'PowerVC compute template for worker nodes.')
        stringParam('WORKER_IMAGE_ID',         '', 'PowerVC image UUID for worker nodes (RHCOS image).')
        stringParam('WORKER_COUNT',           '2', 'Number of worker (compute) nodes.')
    }

    definition {
        def scm = jenkinsScm()
        scm.delegate = delegate
        scm()
        delegate.scriptPath('pipelines/Jenkinsfile.create')
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

    logRotator commonLogRotator

    parameters {
        commonParameters.delegate = delegate
        commonParameters()
        booleanParam(
            'DELETE_WORKSPACE', true,
            'Delete the Terraform workspace from the state directory after successful destroy.'
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
        def scm = jenkinsScm()
        scm.delegate = delegate
        scm()
        delegate.scriptPath('pipelines/Jenkinsfile.destroy')
    }
}
