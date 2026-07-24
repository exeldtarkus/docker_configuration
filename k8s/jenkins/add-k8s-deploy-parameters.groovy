import jenkins.model.Jenkins
import hudson.model.*
import org.biouno.unochoice.ChoiceParameter
import org.biouno.unochoice.CascadeChoiceParameter
import org.biouno.unochoice.model.GroovyScript
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval

// ------------------------------------------------------------------ konfigurasi
def JOB_NAME    = 'k8s-deploy'
def SCRIPT_PATH = 'jenkins/ci/k8s/Jenkinsfile'   // path Jenkinsfile di dalam repo
def GIT_CRED_ID = 'jenkins-secret-gitea'          // credentialsId untuk clone git (kosong = anonim)

def job = Jenkins.instance.getItemByFullName(JOB_NAME)

// Kalau job belum ada -> buat WorkflowJob (Pipeline) baru.
if (job == null) {
    println "[i] Job '${JOB_NAME}' belum ada — membuat WorkflowJob baru."
    job = Jenkins.instance.createProject(org.jenkinsci.plugins.workflow.job.WorkflowJob, JOB_NAME)

    // Definisi 'Pipeline script from SCM', branch/url dari parameter (${REPO_URL} / ${BRANCH}).
    // FQN dipakai supaya tidak gagal compile kalau salah satu plugin tidak ada.
    try {
        def remote = new hudson.plugins.git.UserRemoteConfig('${REPO_URL}', null, null, (GIT_CRED_ID ?: null))
        def scm = new hudson.plugins.git.GitSCM(
            [remote],
            [new hudson.plugins.git.BranchSpec('${BRANCH}')],
            null, null, []   // (userRemoteConfigs, branches, browser, gitTool, extensions) — git-plugin 4.x/5.x
        )
        def flow = new org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition(scm, SCRIPT_PATH)
        // WAJIB false: lightweight checkout TIDAK mengekspansi parameter build,
        // jadi ${REPO_URL}/${BRANCH} akan dikirim mentah ke git dan gagal.
        // Heavyweight = checkout di konteks build -> parameter diekspansi.
        flow.setLightweight(false)
        job.setDefinition(flow)
        println "[OK] Definisi pipeline: from SCM (\${REPO_URL} @ \${BRANCH}, scriptPath=${SCRIPT_PATH})."
    } catch (t) {
        println "[!] Gagal set definisi pipeline from-SCM otomatis — job tetap dibuat, " +
                "set manual di Configure -> Pipeline. Detail: ${t.message}"
    }
}

assert job != null : "Job '${JOB_NAME}' tidak bisa dibuat/ditemukan."

def prop = job.getProperty(ParametersDefinitionProperty)
def byName = [:]
prop?.parameterDefinitions?.each { byName[it.name] = it }

// ------------------------------------------------------------ script Active Choices
// Triple-single-quote => '${...}' tetap literal (dievaluasi oleh Active Choices saat render).
def repoScript = '''import com.cloudbees.plugins.credentials.CredentialsProvider
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import jenkins.model.Jenkins
import groovy.json.JsonSlurper

def creds = CredentialsProvider.lookupCredentials(
    StringCredentials.class,
    Jenkins.instance,
    null,
    null
).find { it.id == 'gitea-api-token' }
def token = creds.secret.plainText

def giteaUrl = "http://10.3.1.67:8080"
def repos = []
def page = 1
def limit = 50

while (true) {
    def url = new URL("${giteaUrl}/api/v1/repos/search?limit=${limit}&page=${page}&token=${token}")
    def json = new JsonSlurper().parse(url)

    if (json.data.isEmpty()) {
        break
    }

    json.data.each { repo ->
        repos.add(repo.clone_url)
    }

    if (json.data.size() < limit) {
        break
    }

    page++
}

return repos.sort()'''

def branchScript = '''import com.cloudbees.plugins.credentials.CredentialsProvider
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import jenkins.model.Jenkins
import groovy.json.JsonSlurper

def creds = CredentialsProvider.lookupCredentials(
    StringCredentials.class,
    Jenkins.instance,
    null,
    null
).find { it.id == 'gitea-api-token' }
def token = creds.secret.plainText

if (REPO_URL == null || REPO_URL.toString().trim() == "") {
    return ["-- pilih repo dulu --"]
}

def parts = REPO_URL.toString().replace(".git", "").split("/")
def repoName = parts[-1]
def owner = parts[-2]

def branches = []
def page = 1
def limit = 50

while (true) {
    def url = new URL("http://10.3.1.67:8080/api/v1/repos/${owner}/${repoName}/branches?limit=${limit}&page=${page}&token=${token}")
    def json = new JsonSlurper().parse(url)

    if (json.isEmpty()) {
        break
    }

    json.each { b -> branches.add(b.name) }

    if (json.size() < limit) {
        break
    }

    page++
}

return branches.sort()'''

def fallbackScript = 'return [e.getClass().getName() + ": " + e.getMessage()]'

// ------------------------------------------------------------ helper
// Pre-approve script non-sandbox supaya tidak "pending approval" saat pertama render.
def preapprove = { String text ->
    try {
        // API baru: preapprove(script, Language) — approve whole-script langsung.
        ScriptApproval.get().preapprove(text,
            org.jenkinsci.plugins.scriptsecurity.scripts.languages.GroovyLanguage.get())
    } catch (t1) {
        try {
            // API lama: hash(script, language) + approveScript(hash).
            def sa = ScriptApproval.get()
            sa.approveScript(sa.hash(text, 'groovy'))
        } catch (t2) {
            println "[i] Auto-approve dilewati — approve manual di Manage Jenkins -> In-process Script Approval " +
                    "(buka Build with Parameters dulu supaya script masuk daftar pending)."
        }
    }
}

def mkGroovy = { String main, String fb ->
    preapprove(main)
    preapprove(fb)
    new GroovyScript(
        new SecureGroovyScript(main, false, null),   // sandbox=false: butuh privileged (creds, HTTP)
        new SecureGroovyScript(fb, false, null)
    )
}

// ------------------------------------------------------------ bangun / pakai-ulang param
// REPO_URL — Active Choices Parameter
def repoUrlParam = byName['REPO_URL'] ?: new ChoiceParameter(
    'REPO_URL',
    'Select Repositories',
    'choice-param-repo-url-' + UUID.randomUUID().toString(),
    mkGroovy(repoScript, fallbackScript),
    'PT_SINGLE_SELECT',
    true,   // filterable
    1       // filterLength
)

// BRANCH — Active Choices Reactive Parameter (referenced: REPO_URL)
def branchParam = byName['BRANCH'] ?: new CascadeChoiceParameter(
    'BRANCH',
    '',
    'choice-param-branch-' + UUID.randomUUID().toString(),
    mkGroovy(branchScript, fallbackScript),
    'PT_SINGLE_SELECT',
    'REPO_URL',   // referencedParameters
    true,
    1
)

// ENV_FILE — File Parameter (base64File, plugin File Parameters)
def envFileParam = byName['ENV_FILE']
if (envFileParam == null) {
    def fileClasses = [
        'io.jenkins.plugins.file_parameters.Base64FileParameterDefinition',
        'io.jenkins.plugins.file_parameters.StashedFileParameterDefinition',
    ]
    for (cn in fileClasses) {
        try {
            envFileParam = Class.forName(cn).getConstructor(String, String).newInstance('ENV_FILE', 'Upload .env project anda')
            println "[OK] ENV_FILE dibuat sebagai ${cn.tokenize('.').last()}."
            break
        } catch (ignored) { }
    }
    if (envFileParam == null) {
        println "[!] ENV_FILE tidak bisa dibuat otomatis (cek plugin File Parameters). " +
                "Tambah manual: Configure -> This project is parameterized -> File Parameter, name=ENV_FILE."
    }
}

// String / Boolean params (dipakai hanya kalau belum ada)
def neu = [
    APP_ID:             new StringParameterDefinition('APP_ID', '', 'Context path & release name (/<APP_ID>)'),
    NAMESPACE:          new StringParameterDefinition('NAMESPACE', 'default', 'Namespace kubernetes target'),
    DOMAIN:             new StringParameterDefinition('DOMAIN', '10.1.40.51', 'Host/domain ingress'),
    REPLICA_COUNT:      new StringParameterDefinition('REPLICA_COUNT', '1', 'Jumlah replica'),
    CHART_VERSION:      new StringParameterDefinition('CHART_VERSION', '', 'Versi chart universal-platform (kosong = latest)'),
    ENABLE_AUTOSCALING: new BooleanParameterDefinition('ENABLE_AUTOSCALING', false, 'Aktifkan HPA'),
    STORAGE_ENABLED:    new BooleanParameterDefinition('STORAGE_ENABLED', false, 'Aktifkan mount share folder (SMB)'),
    STORAGE_UNC_PATH:   new StringParameterDefinition('STORAGE_UNC_PATH', '', 'UNC path share, mis. \\\\server\\share\\folder'),
    STORAGE_MOUNT_PATH: new StringParameterDefinition('STORAGE_MOUNT_PATH', '/mnt/share', 'Path mount di dalam container'),
    STORAGE_READ_ONLY:  new BooleanParameterDefinition('STORAGE_READ_ONLY', true, 'Mount read-only'),
    STORAGE_SMB_DOMAIN: new StringParameterDefinition('STORAGE_SMB_DOMAIN', '', 'Domain SMB (kosongkan kalau tidak perlu)'),
]

// ------------------------------------------------------------ gabung + urutkan
def all = [:]
all.putAll(neu)                        // default string/bool
all['REPO_URL'] = repoUrlParam         // existing-or-built
all['BRANCH']   = branchParam
if (envFileParam != null) all['ENV_FILE'] = envFileParam
byName.each { k, v -> all[k] = v }     // param yang sudah ada SELALU menang (pertahankan)

def order = ['REPO_URL','BRANCH','APP_ID','NAMESPACE','DOMAIN','REPLICA_COUNT','CHART_VERSION',
             'ENABLE_AUTOSCALING','STORAGE_ENABLED','STORAGE_UNC_PATH','STORAGE_MOUNT_PATH',
             'STORAGE_READ_ONLY','STORAGE_SMB_DOMAIN','ENV_FILE']

def finalList = order.collect { all[it] }.findAll { it != null }
def added = finalList.collect { it.name }.findAll { !byName.containsKey(it) }

job.removeProperty(ParametersDefinitionProperty)
job.addProperty(new ParametersDefinitionProperty(finalList))
job.save()

println "Job          : ${JOB_NAME}"
println "Dibangun baru : ${added.isEmpty() ? '(tidak ada — semua sudah ada)' : added}"
println "Urutan final : ${finalList.collect { it.name }}"
